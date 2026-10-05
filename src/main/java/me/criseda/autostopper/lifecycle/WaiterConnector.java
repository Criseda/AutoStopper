package me.criseda.autostopper.lifecycle;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.messages.AutoStopperMessages;
import me.criseda.autostopper.readiness.ReadinessResult;
import me.criseda.autostopper.telemetry.TelemetryOperationType;
import me.criseda.autostopper.telemetry.TelemetryOrigin;
import me.criseda.autostopper.telemetry.TelemetryOutcome;
import net.kyori.adventure.text.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Owns how every connection waiter ends: connected (or refused) by Velocity, failed with its
 * startup, discarded with its player, or abandoned at shutdown. Each path completes the waiter's
 * future exactly once, records its telemetry, and delivers its queued notifications.
 */
final class WaiterConnector {
    private final LifecycleRuntime runtime;

    WaiterConnector(LifecycleRuntime runtime) {
        this.runtime = runtime;
    }

    /** Connects every waiter of a server that just became ready. */
    void connectAll(LifecycleEntry entry, List<ConnectionWaiter> waiters) {
        for (ConnectionWaiter waiter : waiters) {
            connectWaiter(entry, waiter);
            drainNotifications(waiter);
        }
    }

    void connectWaiter(LifecycleEntry entry, ConnectionWaiter waiter) {
        if (runtime.isShutdown() || waiter.discarded || waiter.future.isDone()) {
            return;
        }
        if (!runtime.isPlayerActive(waiter.player)) {
            finishWaiter(entry, waiter, ConnectionOutcome.PLAYER_DISCONNECTED);
            return;
        }

        String serverName = entry.mapping().serverName();
        LifecycleRuntime.ReconnectPermit permit = runtime.grantReconnect(waiter.playerId, serverName);
        CompletableFuture<ConnectionRequestBuilder.Result> connection;
        try {
            connection = waiter.player.createConnectionRequest(waiter.targetServer).connect();
        } catch (RuntimeException error) {
            runtime.revokeReconnect(permit);
            runtime.logger.error("Error creating connection request for server {}", serverName, error);
            finishWaiter(entry, waiter, ConnectionOutcome.CONNECTION_FAILED);
            return;
        }

        if (connection == null) {
            runtime.revokeReconnect(permit);
            runtime.logger.error("Connection request for server {} returned no future", serverName);
            finishWaiter(entry, waiter, ConnectionOutcome.CONNECTION_FAILED);
            return;
        }
        synchronized (entry) {
            if (runtime.isShutdown() || waiter.discarded || waiter.future.isDone()) {
                runtime.revokeReconnect(permit);
                connection.cancel(true);
                return;
            }
            waiter.connectionFuture = connection;
        }

        connection.whenComplete((result, error) -> {
            runtime.revokeReconnect(permit);
            if (runtime.isShutdown()) {
                return;
            }
            finishWaiter(entry, waiter, classify(serverName, result, error));
        });
    }

    private ConnectionOutcome classify(String serverName, ConnectionRequestBuilder.Result result, Throwable error) {
        try {
            if (error != null) {
                runtime.logger.error("Error connecting player to server {}", serverName,
                        LifecycleRuntime.unwrap(error));
                return ConnectionOutcome.CONNECTION_FAILED;
            }
            if (result == null) {
                runtime.logger.warn("Connection request for server {} completed without a result", serverName);
                return ConnectionOutcome.CONNECTION_FAILED;
            }
            ConnectionRequestBuilder.Status status = result.getStatus();
            ConnectionOutcome outcome = switch (status) {
                case SUCCESS -> ConnectionOutcome.CONNECTED;
                case ALREADY_CONNECTED -> ConnectionOutcome.ALREADY_CONNECTED;
                case CONNECTION_IN_PROGRESS -> ConnectionOutcome.CONNECTION_IN_PROGRESS;
                case CONNECTION_CANCELLED -> ConnectionOutcome.CONNECTION_CANCELLED;
                case SERVER_DISCONNECTED -> ConnectionOutcome.SERVER_DISCONNECTED;
            };
            if (!outcome.isSuccessful()) {
                runtime.logger.warn("Connection to server {} completed with status {}", serverName, status);
            }
            return outcome;
        } catch (RuntimeException classificationError) {
            runtime.logger.error("Could not classify connection result for server {}",
                    serverName, classificationError);
            return ConnectionOutcome.CONNECTION_FAILED;
        }
    }

    private void finishWaiter(LifecycleEntry entry, ConnectionWaiter waiter, ConnectionOutcome outcome) {
        Duration elapsed = runtime.elapsed(waiter);
        int remainingWaiters;
        boolean verifyContainer;
        long verifiedRevision;
        synchronized (entry) {
            if (!entry.removeWaiter(waiter)) {
                return;
            }
            remainingWaiters = entry.waiterCount();
            waiter.connectionFuture = null;
            verifyContainer = entry.recordConnectionOutcome(outcome, () -> runtime.failure("player connection",
                    "Velocity could not complete the backend connection: " + outcome,
                    "Check the backend listener and Velocity server address, then retry."));
            verifiedRevision = entry.revision();
        }
        String serverName = entry.mapping().serverName();
        runtime.telemetry.recordOperation(TelemetryOperationType.CONNECTION_WAIT, serverName,
                TelemetryOrigin.PLAYER_CONNECTION, TelemetryOutcome.from(outcome), elapsed, remainingWaiters);
        if (outcome.isSuccessful()) {
            waiter.queueStage(ConnectionLifecycleStage.SUCCEEDED,
                    AutoStopperMessages.lifecycleSucceeded(serverName, runtime.elapsed(waiter)), false);
        } else if (outcome != ConnectionOutcome.PLAYER_DISCONNECTED
                && outcome != ConnectionOutcome.PROXY_SHUTDOWN) {
            waiter.queueStage(ConnectionLifecycleStage.FAILED,
                    AutoStopperMessages.lifecycleFailed(
                            LifecycleMessages.connectionFailure(serverName, outcome), runtime.elapsed(waiter)), false);
        }
        waiter.future.complete(outcome);
        drainNotifications(waiter);
        runtime.cleanupRetired(serverName, entry);
        if (verifyContainer) {
            reconcileExternalStop(entry.mapping(), verifiedRevision);
        }
    }

    /**
     * Completes a waiter whose shared startup failed. The waiter has already been detached from
     * its entry.
     */
    void failStartupWaiter(ConnectionWaiter waiter, String serverName, StartupOutcome outcome,
            ReadinessResult readinessFailure) {
        boolean active = runtime.isPlayerActive(waiter.player);
        boolean initialConnection = active && isInitialConnection(waiter.player);
        if (active) {
            Component failureMessage = AutoStopperMessages.lifecycleFailed(
                    LifecycleMessages.startupFailure(serverName, outcome, readinessFailure),
                    runtime.elapsed(waiter));
            waiter.queueStage(ConnectionLifecycleStage.FAILED, failureMessage, initialConnection);
        }
        ConnectionOutcome waiterOutcome = active
                ? outcome.connectionOutcome()
                : ConnectionOutcome.PLAYER_DISCONNECTED;
        runtime.telemetry.recordOperation(TelemetryOperationType.CONNECTION_WAIT, serverName,
                TelemetryOrigin.PLAYER_CONNECTION, TelemetryOutcome.from(waiterOutcome),
                runtime.elapsed(waiter), 0);
        waiter.future.complete(waiterOutcome);
        drainNotifications(waiter);
        if (active && !initialConnection && (outcome == StartupOutcome.NOT_READY
                || outcome == StartupOutcome.READINESS_ERROR)) {
            runtime.safeSend(waiter.player, AutoStopperMessages.retryServerCommand(serverName));
        }
    }

    /**
     * Completes a waiter that was detached without a connection attempt, because its player left
     * or the proxy is shutting down. No notifications are delivered.
     */
    void abandon(ConnectionWaiter waiter, ConnectionOutcome outcome) {
        runtime.telemetry.recordOperation(TelemetryOperationType.CONNECTION_WAIT, waiter.serverName,
                TelemetryOrigin.PLAYER_CONNECTION, TelemetryOutcome.from(outcome), runtime.elapsed(waiter), 0);
        waiter.future.complete(outcome);
    }

    private void reconcileExternalStop(ServerMapping mapping, long expectedRevision) {
        if (runtime.isShutdown()) {
            return;
        }
        CompletableFuture<Optional<ContainerStatus>> statusFuture;
        try {
            statusFuture = runtime.serverManager.getServerStatusAsync(mapping);
        } catch (RuntimeException error) {
            runtime.logger.debug("Could not schedule a container check for server {}", mapping.serverName(), error);
            return;
        }
        if (statusFuture == null) {
            return;
        }
        statusFuture.whenComplete((status, error) -> {
            if (error != null || status == null || status.orElse(null) != ContainerStatus.STOPPED) {
                return;
            }
            runtime.markStoppedIfUnchanged(mapping, expectedRevision).ifPresent(ignored -> runtime.logger.info(
                    "Server {} was stopped outside AutoStopper; the next connection will start it",
                    mapping.serverName()));
        });
    }

    void drainNotifications(List<ConnectionWaiter> waiters) {
        for (ConnectionWaiter waiter : waiters) {
            drainNotifications(waiter);
        }
    }

    void drainNotifications(ConnectionWaiter waiter) {
        waiter.drainNotifications(notification -> deliverNotification(waiter, notification));
    }

    private void deliverNotification(ConnectionWaiter waiter, ConnectionWaiter.WaiterNotification notification) {
        if (runtime.isShutdown() || waiter.discarded || !runtime.isPlayerActive(waiter.player)) {
            return;
        }
        if (notification.disconnectInitial()) {
            try {
                if (waiter.player.getCurrentServer().isEmpty()) {
                    waiter.player.disconnect(notification.message());
                    return;
                }
            } catch (RuntimeException error) {
                runtime.logger.debug("Could not inspect or disconnect an initial lifecycle waiter", error);
            }
        }
        runtime.safeSend(waiter.player, notification.message());
    }

    private boolean isInitialConnection(Player player) {
        try {
            return player.getCurrentServer().isEmpty();
        } catch (RuntimeException error) {
            runtime.logger.debug("Could not inspect whether a lifecycle waiter has a current server", error);
            return false;
        }
    }
}
