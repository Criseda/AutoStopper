package me.criseda.autostopper.lifecycle;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.messages.AutoStopperMessages;
import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.readiness.ReadinessResult;
import me.criseda.autostopper.server.ServerManager;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;

/**
 * Owns lifecycle state, the single in-flight startup operation, and connection waiters for every
 * managed server. Map updates serialize admission and reconciliation per server; asynchronous
 * callbacks synchronize on the same entry before changing its state or waiter collection.
 */
public final class ServerLifecycleCoordinator {
    private static final Map<ServerLifecycleState, Set<ServerLifecycleState>> LEGAL_TRANSITIONS =
            legalTransitions();

    private final Logger logger;
    private final ServerManager serverManager;
    private final Map<String, LifecycleEntry> lifecycles = new ConcurrentHashMap<>();
    private final Set<ReconnectPermit> reconnectPermits = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final Object shutdownLock = new Object();

    public ServerLifecycleCoordinator(Logger logger, ServerManager serverManager) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.serverManager = Objects.requireNonNull(serverManager, "serverManager");
    }

    public boolean consumeReconnectPermit(Player player, String serverName) {
        if (shutdown.get()) {
            return false;
        }
        return reconnectPermits.remove(new ReconnectPermit(player.getUniqueId(), serverName));
    }

    public CompletableFuture<ConnectionOutcome> requestConnection(Player player, RegisteredServer targetServer,
            ServerMapping mapping) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(targetServer, "targetServer");
        Objects.requireNonNull(mapping, "mapping");
        if (shutdown.get()) {
            return CompletableFuture.completedFuture(ConnectionOutcome.PROXY_SHUTDOWN);
        }

        AtomicReference<Admission> admitted = new AtomicReference<>();
        synchronized (shutdownLock) {
            lifecycles.compute(mapping.serverName(), (serverName, current) -> {
                if (shutdown.get()) {
                    admitted.set(Admission.rejected(ConnectionOutcome.PROXY_SHUTDOWN));
                    return current;
                }
                LifecycleEntry entry = current;
                if (entry == null) {
                    entry = new LifecycleEntry(mapping);
                }

                synchronized (entry) {
                    if (!entry.mapping.equals(mapping)) {
                        if (entry.isBusy()) {
                            admitted.set(Admission.rejected(ConnectionOutcome.MAPPING_CHANGED));
                            return entry;
                        }
                        entry = new LifecycleEntry(mapping);
                    } else if (entry.retired && !entry.isBusy()) {
                        entry.retired = false;
                    }

                    UUID playerId = player.getUniqueId();
                    ConnectionWaiter existing = entry.waiters.get(playerId);
                    if (existing != null) {
                        admitted.set(Admission.queued(entry, existing));
                        return entry;
                    }

                    if (entry.retired) {
                        admitted.set(Admission.rejected(ConnectionOutcome.MAPPING_CHANGED));
                        return entry;
                    }
                    if (entry.state == ServerLifecycleState.STOPPING) {
                        admitted.set(Admission.rejected(ConnectionOutcome.SERVER_STOPPING));
                        return entry;
                    }

                    ConnectionWaiter waiter = new ConnectionWaiter(playerId, player, targetServer);
                    entry.waiters.put(playerId, waiter);
                    if (entry.state == ServerLifecycleState.STARTING) {
                        admitted.set(Admission.queued(entry, waiter));
                        return entry;
                    }
                    if (entry.state == ServerLifecycleState.READY) {
                        admitted.set(Admission.connect(entry, waiter));
                        return entry;
                    }

                    transition(entry, ServerLifecycleState.STARTING);
                    CompletableFuture<StartupOutcome> operation = new CompletableFuture<>();
                    entry.startupFuture = operation;
                    admitted.set(Admission.start(entry, waiter, operation));
                    return entry;
                }
            });
        }

        Admission admission = admitted.get();
        if (admission == null) {
            return CompletableFuture.completedFuture(ConnectionOutcome.START_FAILED);
        }
        if (admission.rejectedOutcome != null) {
            if (admission.rejectedOutcome != ConnectionOutcome.PROXY_SHUTDOWN) {
                notifyRejected(player, mapping.serverName(), admission.rejectedOutcome);
            }
            return CompletableFuture.completedFuture(admission.rejectedOutcome);
        }
        if (admission.queued) {
            safeSend(player, AutoStopperMessages.serverAlreadyStarting());
        }
        if (admission.connectNow) {
            connectWaiter(admission.entry, admission.waiter);
        } else if (admission.launchStartup) {
            launchStatusCheck(admission.entry, mapping, admission.startupFuture);
        }
        return admission.waiter.future;
    }

    public void discardPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        List<ConnectionWaiter> discarded = new ArrayList<>();
        for (String serverName : List.copyOf(lifecycles.keySet())) {
            lifecycles.computeIfPresent(serverName, (ignored, entry) -> {
                synchronized (entry) {
                    ConnectionWaiter waiter = entry.waiters.remove(playerId);
                    if (waiter != null) {
                        waiter.discarded = true;
                        discarded.add(waiter);
                    }
                    return entry.retired && !entry.isBusy() ? null : entry;
                }
            });
        }
        reconnectPermits.removeIf(permit -> permit.playerId.equals(playerId));
        for (ConnectionWaiter waiter : discarded) {
            waiter.future.complete(ConnectionOutcome.PLAYER_DISCONNECTED);
        }
    }

    public boolean tryBeginStop(ServerMapping mapping) {
        if (shutdown.get()) {
            return false;
        }
        AtomicBoolean admitted = new AtomicBoolean(false);
        synchronized (shutdownLock) {
            lifecycles.compute(mapping.serverName(), (ignored, current) -> {
                if (shutdown.get()) {
                    return current;
                }
                LifecycleEntry entry = current;
                if (entry == null) {
                    entry = new LifecycleEntry(mapping);
                }
                synchronized (entry) {
                    if (!entry.mapping.equals(mapping)) {
                        if (entry.isBusy()) {
                            return entry;
                        }
                        entry = new LifecycleEntry(mapping);
                    }
                    if (entry.state == ServerLifecycleState.STARTING
                            || entry.state == ServerLifecycleState.STOPPING
                            || !entry.waiters.isEmpty()) {
                        return entry;
                    }
                    transition(entry, ServerLifecycleState.STOPPING);
                    admitted.set(true);
                    return entry;
                }
            });
        }
        return admitted.get();
    }

    public void completeStop(ServerMapping mapping, ContainerStatus result) {
        if (shutdown.get()) {
            return;
        }
        lifecycles.computeIfPresent(mapping.serverName(), (ignored, entry) -> {
            synchronized (entry) {
                if (!entry.mapping.equals(mapping) || entry.state != ServerLifecycleState.STOPPING) {
                    return entry;
                }
                transition(entry, result == ContainerStatus.STOPPED
                        ? ServerLifecycleState.STOPPED
                        : ServerLifecycleState.FAILED);
                if (result == ContainerStatus.STOPPED) {
                    entry.lastFailure = null;
                } else {
                    entry.lastFailure = failure("container stop",
                            "container stop failed with " + result,
                            "Check Docker access and container state, then allow the bounded retry or retry manually.");
                }
                return entry.retired && !entry.isBusy() ? null : entry;
            }
        });
    }

    public void cancelStop(ServerMapping mapping) {
        if (shutdown.get()) {
            return;
        }
        lifecycles.computeIfPresent(mapping.serverName(), (ignored, entry) -> {
            synchronized (entry) {
                if (entry.mapping.equals(mapping) && entry.state == ServerLifecycleState.STOPPING) {
                    transition(entry, ServerLifecycleState.READY);
                }
                return entry.retired && !entry.isBusy() ? null : entry;
            }
        });
    }

    public void markReady(String serverName) {
        if (shutdown.get()) {
            return;
        }
        lifecycles.computeIfPresent(serverName, (ignored, entry) -> {
            synchronized (entry) {
                if (entry.state == ServerLifecycleState.STOPPED
                        || entry.state == ServerLifecycleState.FAILED) {
                    transition(entry, ServerLifecycleState.READY);
                }
                entry.lastFailure = null;
                entry.readyConnectionSucceeded = true;
                return entry;
            }
        });
    }

    public void reconcileConfig(ConfigSnapshot previous, ConfigSnapshot current) {
        if (shutdown.get()) {
            return;
        }
        for (String serverName : previous.serverNames()) {
            Optional<ServerMapping> currentMapping = current.server(serverName);
            lifecycles.computeIfPresent(serverName, (ignored, entry) -> {
                synchronized (entry) {
                    if (currentMapping.isPresent() && entry.mapping.equals(currentMapping.get())) {
                        entry.retired = false;
                        return entry;
                    }
                    if (entry.isBusy()) {
                        entry.retired = true;
                        return entry;
                    }
                    return null;
                }
            });
        }
    }

    public Optional<ServerLifecycleState> state(String serverName) {
        LifecycleEntry entry = lifecycles.get(serverName);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            return Optional.of(entry.state);
        }
    }

    public int waitingCount(String serverName) {
        LifecycleEntry entry = lifecycles.get(serverName);
        if (entry == null) {
            return 0;
        }
        synchronized (entry) {
            return entry.waiters.size();
        }
    }

    public Optional<ConnectionOutcome> lastConnectionOutcome(String serverName) {
        LifecycleEntry entry = lifecycles.get(serverName);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            return Optional.ofNullable(entry.lastConnectionOutcome);
        }
    }

    public Optional<OperationalFailure> lastFailure(String serverName) {
        LifecycleEntry entry = lifecycles.get(serverName);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            return Optional.ofNullable(entry.lastFailure);
        }
    }

    public void shutdown() {
        List<CompletableFuture<?>> operations = new ArrayList<>();
        List<ConnectionWaiter> waiters = new ArrayList<>();
        synchronized (shutdownLock) {
            if (!shutdown.compareAndSet(false, true)) {
                return;
            }
            for (LifecycleEntry entry : lifecycles.values()) {
                synchronized (entry) {
                    if (entry.activeOperation != null) {
                        operations.add(entry.activeOperation);
                        entry.activeOperation = null;
                    }
                    if (entry.startupFuture != null) {
                        entry.startupFuture.cancel(false);
                        entry.startupFuture = null;
                    }
                    for (ConnectionWaiter waiter : entry.waiters.values()) {
                        waiter.discarded = true;
                        if (waiter.connectionFuture != null) {
                            operations.add(waiter.connectionFuture);
                            waiter.connectionFuture = null;
                        }
                        waiters.add(waiter);
                    }
                    entry.waiters.clear();
                    entry.lastConnectionOutcome = ConnectionOutcome.PROXY_SHUTDOWN;
                }
            }
            lifecycles.clear();
            reconnectPermits.clear();
        }

        for (ConnectionWaiter waiter : waiters) {
            waiter.future.complete(ConnectionOutcome.PROXY_SHUTDOWN);
        }
        for (CompletableFuture<?> operation : operations) {
            operation.cancel(true);
        }
    }

    private void launchStatusCheck(LifecycleEntry entry, ServerMapping mapping,
            CompletableFuture<StartupOutcome> operation) {
        CompletableFuture<Optional<ContainerStatus>> statusFuture;
        try {
            statusFuture = serverManager.getServerStatusAsync(mapping);
        } catch (RuntimeException error) {
            completeStartup(entry, mapping, operation,
                    exceptionalOutcome(error, StartupStage.STATUS, mapping.serverName()));
            return;
        }
        if (statusFuture == null) {
            completeStartup(entry, mapping, operation, StartupOutcome.STATUS_ERROR);
            return;
        }
        if (!ownOperation(entry, operation, statusFuture)) {
            return;
        }
        statusFuture.whenComplete((status, error) -> {
            if (shutdown.get()) {
                return;
            }
            if (error != null) {
                completeStartup(entry, mapping, operation,
                        exceptionalOutcome(error, StartupStage.STATUS, mapping.serverName()));
                return;
            }
            if (status == null) {
                completeStartup(entry, mapping, operation, StartupOutcome.STATUS_ERROR);
                return;
            }
            if (status.isEmpty()) {
                completeStartup(entry, mapping, operation, StartupOutcome.STATUS_NO_MAPPING);
                return;
            }
            switch (status.get()) {
                case RUNNING -> launchReadiness(entry, mapping, operation, false);
                case STOPPED -> launchStart(entry, mapping, operation);
                case MISSING -> completeStartup(entry, mapping, operation, StartupOutcome.STATUS_MISSING);
                case INACCESSIBLE -> completeStartup(entry, mapping, operation, StartupOutcome.STATUS_INACCESSIBLE);
                case TIMED_OUT -> completeStartup(entry, mapping, operation, StartupOutcome.STATUS_TIMED_OUT);
                case FAILED -> completeStartup(entry, mapping, operation, StartupOutcome.STATUS_FAILED);
            }
        });
    }

    private void launchStart(LifecycleEntry entry, ServerMapping mapping,
            CompletableFuture<StartupOutcome> operation) {
        notifyStarting(entry);
        CompletableFuture<ContainerStatus> startFuture;
        try {
            startFuture = serverManager.startServerAsync(mapping);
        } catch (RuntimeException error) {
            completeStartup(entry, mapping, operation,
                    exceptionalOutcome(error, StartupStage.START, mapping.serverName()));
            return;
        }
        if (startFuture == null) {
            completeStartup(entry, mapping, operation, StartupOutcome.START_ERROR);
            return;
        }
        if (!ownOperation(entry, operation, startFuture)) {
            return;
        }
        startFuture.whenComplete((result, error) -> {
            if (shutdown.get()) {
                return;
            }
            if (error != null) {
                completeStartup(entry, mapping, operation,
                        exceptionalOutcome(error, StartupStage.START, mapping.serverName()));
                return;
            }
            if (result == null) {
                completeStartup(entry, mapping, operation, StartupOutcome.START_ERROR);
                return;
            }
            switch (result) {
                case RUNNING -> launchReadiness(entry, mapping, operation, true);
                case MISSING -> completeStartup(entry, mapping, operation, StartupOutcome.START_MISSING);
                case INACCESSIBLE -> completeStartup(entry, mapping, operation, StartupOutcome.START_INACCESSIBLE);
                case TIMED_OUT -> completeStartup(entry, mapping, operation, StartupOutcome.START_TIMED_OUT);
                case STOPPED, FAILED -> completeStartup(entry, mapping, operation, StartupOutcome.START_FAILED);
            }
        });
    }

    private void launchReadiness(LifecycleEntry entry, ServerMapping mapping,
            CompletableFuture<StartupOutcome> operation, boolean startedContainer) {
        CompletableFuture<ReadinessResult> readinessFuture;
        try {
            readinessFuture = serverManager.waitForServerReadyAsync(mapping);
        } catch (RuntimeException error) {
            completeStartup(entry, mapping, operation,
                    exceptionalOutcome(error, StartupStage.READINESS, mapping.serverName()));
            return;
        }
        if (readinessFuture == null) {
            completeStartup(entry, mapping, operation, StartupOutcome.READINESS_ERROR);
            return;
        }
        if (!ownOperation(entry, operation, readinessFuture)) {
            return;
        }
        readinessFuture.whenComplete((ready, error) -> {
            if (shutdown.get()) {
                return;
            }
            if (error != null) {
                completeStartup(entry, mapping, operation,
                        exceptionalOutcome(error, StartupStage.READINESS, mapping.serverName()));
            } else if (ready != null && ready.ready()) {
                completeStartup(entry, mapping, operation,
                        startedContainer ? StartupOutcome.READY_AFTER_START : StartupOutcome.READY_RUNNING);
            } else {
                completeStartup(entry, mapping, operation, StartupOutcome.NOT_READY, ready);
            }
        });
    }

    private void completeStartup(LifecycleEntry entry, ServerMapping mapping,
            CompletableFuture<StartupOutcome> operation, StartupOutcome outcome) {
        completeStartup(entry, mapping, operation, outcome, null);
    }

    private void completeStartup(LifecycleEntry entry, ServerMapping mapping,
            CompletableFuture<StartupOutcome> operation, StartupOutcome outcome,
            ReadinessResult readinessFailure) {
        List<ConnectionWaiter> waiters;
        boolean accepted;
        synchronized (entry) {
            if (shutdown.get()) {
                return;
            }
            accepted = entry.state == ServerLifecycleState.STARTING && entry.startupFuture == operation;
            if (!accepted) {
                return;
            }
            entry.startupFuture = null;
            entry.activeOperation = null;
            transition(entry, outcome.ready ? ServerLifecycleState.READY : ServerLifecycleState.FAILED);
            if (outcome.ready) {
                entry.readyConnectionSucceeded = false;
                entry.lastFailure = null;
            } else {
                String detail = readinessFailure == null
                        ? startupFailureDetail(outcome)
                        : readinessFailure.playerDetail();
                entry.lastFailure = failure("server startup", detail,
                        startupRemediation(outcome));
            }
            waiters = new ArrayList<>(entry.waiters.values());
            if (!outcome.ready) {
                entry.waiters.clear();
                entry.lastConnectionOutcome = outcome.connectionOutcome;
            }
        }

        operation.complete(outcome);
        if (outcome.ready) {
            for (ConnectionWaiter waiter : waiters) {
                if (outcome == StartupOutcome.READY_AFTER_START) {
                    safeSend(waiter.player, AutoStopperMessages.serverReady(mapping.serverName()));
                }
                connectWaiter(entry, waiter);
            }
        } else {
            for (ConnectionWaiter waiter : waiters) {
                boolean active = isPlayerActive(waiter.player);
                if (active) {
                    notifyStartupFailure(waiter.player, mapping.serverName(), outcome, readinessFailure);
                }
                waiter.future.complete(active
                        ? outcome.connectionOutcome
                        : ConnectionOutcome.PLAYER_DISCONNECTED);
            }
            cleanupRetired(mapping.serverName(), entry);
        }
    }

    private void connectWaiter(LifecycleEntry entry, ConnectionWaiter waiter) {
        if (shutdown.get() || waiter.discarded || waiter.future.isDone()) {
            return;
        }
        if (!isPlayerActive(waiter.player)) {
            finishWaiter(entry, waiter, ConnectionOutcome.PLAYER_DISCONNECTED);
            return;
        }

        String serverName = entry.mapping.serverName();
        ReconnectPermit permit = new ReconnectPermit(waiter.playerId, serverName);
        reconnectPermits.add(permit);
        CompletableFuture<ConnectionRequestBuilder.Result> connection;
        try {
            connection = waiter.player.createConnectionRequest(waiter.targetServer).connect();
        } catch (RuntimeException error) {
            reconnectPermits.remove(permit);
            logger.error("Error creating connection request for server {}", serverName, error);
            notifyConnectionFailure(waiter.player, serverName, ConnectionOutcome.CONNECTION_FAILED);
            finishWaiter(entry, waiter, ConnectionOutcome.CONNECTION_FAILED);
            return;
        }

        if (connection == null) {
            reconnectPermits.remove(permit);
            logger.error("Connection request for server {} returned no future", serverName);
            notifyConnectionFailure(waiter.player, serverName, ConnectionOutcome.CONNECTION_FAILED);
            finishWaiter(entry, waiter, ConnectionOutcome.CONNECTION_FAILED);
            return;
        }
        synchronized (entry) {
            if (shutdown.get() || waiter.discarded || waiter.future.isDone()) {
                reconnectPermits.remove(permit);
                connection.cancel(true);
                return;
            }
            waiter.connectionFuture = connection;
        }

        connection.whenComplete((result, error) -> {
            reconnectPermits.remove(permit);
            if (shutdown.get()) {
                return;
            }
            ConnectionOutcome outcome;
            try {
                if (error != null) {
                    logger.error("Error connecting player to server {}", serverName, unwrap(error));
                    outcome = ConnectionOutcome.CONNECTION_FAILED;
                } else if (result == null) {
                    logger.warn("Connection request for server {} completed without a result", serverName);
                    outcome = ConnectionOutcome.CONNECTION_FAILED;
                } else {
                    ConnectionRequestBuilder.Status status = result.getStatus();
                    outcome = switch (status) {
                        case SUCCESS -> ConnectionOutcome.CONNECTED;
                        case ALREADY_CONNECTED -> ConnectionOutcome.ALREADY_CONNECTED;
                        case CONNECTION_IN_PROGRESS -> ConnectionOutcome.CONNECTION_IN_PROGRESS;
                        case CONNECTION_CANCELLED -> ConnectionOutcome.CONNECTION_CANCELLED;
                        case SERVER_DISCONNECTED -> ConnectionOutcome.SERVER_DISCONNECTED;
                    };
                    if (!outcome.isSuccessful()) {
                        logger.warn("Connection to server {} completed with status {}", serverName, status);
                    }
                }
            } catch (RuntimeException classificationError) {
                logger.error("Could not classify connection result for server {}",
                        serverName, classificationError);
                outcome = ConnectionOutcome.CONNECTION_FAILED;
            }
            if (!outcome.isSuccessful() && isPlayerActive(waiter.player)) {
                notifyConnectionFailure(waiter.player, serverName, outcome);
            }
            finishWaiter(entry, waiter, outcome);
        });
    }

    private void finishWaiter(LifecycleEntry entry, ConnectionWaiter waiter, ConnectionOutcome outcome) {
        boolean owned;
        synchronized (entry) {
            owned = entry.waiters.remove(waiter.playerId, waiter);
            if (!owned) {
                return;
            }
            entry.lastConnectionOutcome = outcome;
            waiter.connectionFuture = null;
            if (outcome.isSuccessful()) {
                entry.lastFailure = null;
                entry.readyConnectionSucceeded = true;
                if (entry.state == ServerLifecycleState.FAILED) {
                    transition(entry, ServerLifecycleState.READY);
                }
            } else if ((outcome == ConnectionOutcome.SERVER_DISCONNECTED
                    || outcome == ConnectionOutcome.CONNECTION_FAILED)
                    && entry.state == ServerLifecycleState.READY
                    && entry.waiters.isEmpty()
                    && !entry.readyConnectionSucceeded) {
                transition(entry, ServerLifecycleState.FAILED);
                entry.lastFailure = failure("player connection",
                        "Velocity could not complete the backend connection: " + outcome,
                        "Check the backend listener and Velocity server address, then retry.");
            }
        }
        waiter.future.complete(outcome);
        cleanupRetired(entry.mapping.serverName(), entry);
    }

    private void cleanupRetired(String serverName, LifecycleEntry expected) {
        lifecycles.computeIfPresent(serverName, (ignored, current) -> {
            if (current != expected) {
                return current;
            }
            synchronized (current) {
                return current.retired && !current.isBusy() ? null : current;
            }
        });
    }

    private void notifyStarting(LifecycleEntry entry) {
        List<ConnectionWaiter> waiters;
        synchronized (entry) {
            waiters = new ArrayList<>(entry.waiters.values());
        }
        for (ConnectionWaiter waiter : waiters) {
            safeSend(waiter.player, AutoStopperMessages.serverOfflineStarting());
        }
    }

    private void notifyRejected(Player player, String serverName, ConnectionOutcome outcome) {
        if (outcome == ConnectionOutcome.SERVER_STOPPING) {
            safeSend(player, AutoStopperMessages.serverStopping(serverName));
        } else {
            safeSend(player, AutoStopperMessages.mappingChanged(serverName));
        }
    }

    private void notifyStartupFailure(Player player, String serverName, StartupOutcome outcome,
            ReadinessResult readinessFailure) {
        Component message = switch (outcome) {
            case STATUS_NO_MAPPING -> AutoStopperMessages.noContainerMapping(serverName);
            case STATUS_MISSING, START_MISSING -> AutoStopperMessages.containerMissing(serverName);
            case STATUS_INACCESSIBLE -> AutoStopperMessages.dockerUnavailable("manage", serverName);
            case START_INACCESSIBLE -> AutoStopperMessages.dockerUnavailable("start", serverName);
            case STATUS_TIMED_OUT -> AutoStopperMessages.statusCheckTimedOut(serverName);
            case STATUS_FAILED -> AutoStopperMessages.statusCheckFailed(serverName);
            case STATUS_ERROR -> AutoStopperMessages.statusCheckError(serverName);
            case START_TIMED_OUT -> AutoStopperMessages.startTimedOut(serverName);
            case START_FAILED -> AutoStopperMessages.startFailed(serverName);
            case START_ERROR -> AutoStopperMessages.startError(serverName);
            case NOT_READY, READINESS_ERROR -> readinessFailure == null
                    ? AutoStopperMessages.serverNotReady(serverName)
                    : AutoStopperMessages.serverNotReady(serverName, readinessFailure.playerDetail());
            case CANCELLED -> AutoStopperMessages.startCancelled(serverName);
            case OVERLOADED -> AutoStopperMessages.overloaded();
            case READY_RUNNING, READY_AFTER_START -> throw new IllegalArgumentException("ready outcome is not a failure");
        };
        boolean disconnected = notifyTerminalFailure(player, message);
        if ((outcome == StartupOutcome.NOT_READY || outcome == StartupOutcome.READINESS_ERROR) && !disconnected) {
            safeSend(player, AutoStopperMessages.retryServerCommand(serverName));
        }
    }

    private boolean notifyTerminalFailure(Player player, Component message) {
        if (shutdown.get() || !isPlayerActive(player)) {
            return false;
        }
        try {
            if (player.getCurrentServer().isEmpty()) {
                player.disconnect(message);
                return true;
            }
        } catch (RuntimeException error) {
            logger.debug("Could not inspect or disconnect an initial lifecycle waiter", error);
        }
        safeSend(player, message);
        return false;
    }

    private void notifyConnectionFailure(Player player, String serverName, ConnectionOutcome outcome) {
        switch (outcome) {
            case CONNECTION_IN_PROGRESS -> safeSend(player, AutoStopperMessages.connectionInProgress(serverName));
            case CONNECTION_CANCELLED -> safeSend(player, AutoStopperMessages.connectionCancelled(serverName));
            case SERVER_DISCONNECTED -> safeSend(player, AutoStopperMessages.connectionRefused(serverName));
            default -> safeSend(player, AutoStopperMessages.connectionFailed(serverName));
        }
    }

    private void safeSend(Player player, Component message) {
        if (shutdown.get() || !isPlayerActive(player)) {
            return;
        }
        try {
            player.sendMessage(message);
        } catch (RuntimeException error) {
            logger.debug("Could not send lifecycle message to a player", error);
        }
    }

    private boolean isPlayerActive(Player player) {
        try {
            return player.isActive();
        } catch (RuntimeException error) {
            logger.debug("Could not check whether a lifecycle waiter is active", error);
            return false;
        }
    }

    private StartupOutcome exceptionalOutcome(Throwable error, StartupStage stage, String serverName) {
        Throwable cause = unwrap(error);
        if (cause instanceof AutoStopperExecutor.SaturationException) {
            return StartupOutcome.OVERLOADED;
        }
        if (cause instanceof CancellationException || cause instanceof AutoStopperExecutor.ShutdownException) {
            return StartupOutcome.CANCELLED;
        }
        logger.error("Lifecycle {} operation failed for server {}",
                stage.name().toLowerCase(), serverName, cause);
        return switch (stage) {
            case STATUS -> StartupOutcome.STATUS_ERROR;
            case START -> StartupOutcome.START_ERROR;
            case READINESS -> StartupOutcome.READINESS_ERROR;
        };
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private OperationalFailure failure(String context, String detail, String remediation) {
        return new OperationalFailure(Instant.now(), context, detail, remediation);
    }

    private String startupFailureDetail(StartupOutcome outcome) {
        return switch (outcome) {
            case STATUS_NO_MAPPING -> "no active container mapping";
            case STATUS_MISSING, START_MISSING -> "configured container does not exist";
            case STATUS_INACCESSIBLE, START_INACCESSIBLE -> "Docker is unavailable";
            case STATUS_TIMED_OUT, START_TIMED_OUT -> "Docker operation timed out";
            case OVERLOADED -> "AutoStopper worker queue is saturated";
            case CANCELLED -> "startup was cancelled";
            case STATUS_FAILED, STATUS_ERROR, START_FAILED, START_ERROR -> "Docker operation failed";
            case NOT_READY, READINESS_ERROR -> "server readiness check failed";
            case READY_RUNNING, READY_AFTER_START -> throw new IllegalArgumentException("ready outcome is not a failure");
        };
    }

    private String startupRemediation(StartupOutcome outcome) {
        return switch (outcome) {
            case STATUS_NO_MAPPING -> "Reload a valid monitored server mapping.";
            case STATUS_MISSING, START_MISSING -> "Create the container or correct container_name, then retry.";
            case STATUS_INACCESSIBLE, START_INACCESSIBLE -> "Restore Docker daemon and socket access, then retry.";
            case STATUS_TIMED_OUT, START_TIMED_OUT -> "Check Docker daemon responsiveness and host load, then retry.";
            case OVERLOADED -> "Wait for current AutoStopper operations to finish, then retry.";
            case CANCELLED -> "Retry after the current reload or shutdown completes.";
            case STATUS_FAILED, STATUS_ERROR, START_FAILED, START_ERROR -> "Review proxy logs and Docker state, then retry.";
            case NOT_READY, READINESS_ERROR -> "Verify the configured readiness strategy and backend endpoint, then retry.";
            case READY_RUNNING, READY_AFTER_START -> throw new IllegalArgumentException("ready outcome is not a failure");
        };
    }

    private void transition(LifecycleEntry entry, ServerLifecycleState next) {
        if (entry.state == next) {
            return;
        }
        if (!LEGAL_TRANSITIONS.get(entry.state).contains(next)) {
            throw new IllegalStateException("Illegal lifecycle transition for " + entry.mapping.serverName()
                    + ": " + entry.state + " -> " + next);
        }
        logger.debug("Server {} lifecycle transitioned from {} to {}",
                entry.mapping.serverName(), entry.state, next);
        entry.state = next;
    }

    private boolean ownOperation(LifecycleEntry entry, CompletableFuture<StartupOutcome> startup,
            CompletableFuture<?> operation) {
        synchronized (entry) {
            if (shutdown.get() || entry.startupFuture != startup
                    || entry.state != ServerLifecycleState.STARTING) {
                operation.cancel(true);
                return false;
            }
            entry.activeOperation = operation;
            return true;
        }
    }

    private static Map<ServerLifecycleState, Set<ServerLifecycleState>> legalTransitions() {
        Map<ServerLifecycleState, Set<ServerLifecycleState>> transitions =
                new EnumMap<>(ServerLifecycleState.class);
        transitions.put(ServerLifecycleState.STOPPED,
                EnumSet.of(ServerLifecycleState.STARTING, ServerLifecycleState.READY,
                        ServerLifecycleState.STOPPING));
        transitions.put(ServerLifecycleState.STARTING,
                EnumSet.of(ServerLifecycleState.READY, ServerLifecycleState.FAILED));
        transitions.put(ServerLifecycleState.READY,
                EnumSet.of(ServerLifecycleState.STOPPING, ServerLifecycleState.FAILED,
                        ServerLifecycleState.STOPPED));
        transitions.put(ServerLifecycleState.STOPPING,
                EnumSet.of(ServerLifecycleState.STOPPED, ServerLifecycleState.FAILED,
                        ServerLifecycleState.READY));
        transitions.put(ServerLifecycleState.FAILED,
                EnumSet.of(ServerLifecycleState.STARTING, ServerLifecycleState.READY,
                        ServerLifecycleState.STOPPING, ServerLifecycleState.STOPPED));
        return Map.copyOf(transitions);
    }

    private static final class LifecycleEntry {
        private final ServerMapping mapping;
        private final Map<UUID, ConnectionWaiter> waiters = new LinkedHashMap<>();
        private ServerLifecycleState state = ServerLifecycleState.STOPPED;
        private CompletableFuture<StartupOutcome> startupFuture;
        private CompletableFuture<?> activeOperation;
        private ConnectionOutcome lastConnectionOutcome;
        private OperationalFailure lastFailure;
        private boolean readyConnectionSucceeded;
        private boolean retired;

        private LifecycleEntry(ServerMapping mapping) {
            this.mapping = mapping;
        }

        private boolean isBusy() {
            return state == ServerLifecycleState.STARTING
                    || state == ServerLifecycleState.STOPPING
                    || !waiters.isEmpty();
        }
    }

    private static final class ConnectionWaiter {
        private final UUID playerId;
        private final Player player;
        private final RegisteredServer targetServer;
        private final CompletableFuture<ConnectionOutcome> future = new CompletableFuture<>();
        private volatile boolean discarded;
        private CompletableFuture<ConnectionRequestBuilder.Result> connectionFuture;

        private ConnectionWaiter(UUID playerId, Player player, RegisteredServer targetServer) {
            this.playerId = playerId;
            this.player = player;
            this.targetServer = targetServer;
        }
    }

    private static final class Admission {
        private final LifecycleEntry entry;
        private final ConnectionWaiter waiter;
        private final CompletableFuture<StartupOutcome> startupFuture;
        private final ConnectionOutcome rejectedOutcome;
        private final boolean launchStartup;
        private final boolean connectNow;
        private final boolean queued;

        private Admission(LifecycleEntry entry, ConnectionWaiter waiter,
                CompletableFuture<StartupOutcome> startupFuture, ConnectionOutcome rejectedOutcome,
                boolean launchStartup, boolean connectNow, boolean queued) {
            this.entry = entry;
            this.waiter = waiter;
            this.startupFuture = startupFuture;
            this.rejectedOutcome = rejectedOutcome;
            this.launchStartup = launchStartup;
            this.connectNow = connectNow;
            this.queued = queued;
        }

        private static Admission start(LifecycleEntry entry, ConnectionWaiter waiter,
                CompletableFuture<StartupOutcome> startupFuture) {
            return new Admission(entry, waiter, startupFuture, null, true, false, false);
        }

        private static Admission connect(LifecycleEntry entry, ConnectionWaiter waiter) {
            return new Admission(entry, waiter, null, null, false, true, false);
        }

        private static Admission queued(LifecycleEntry entry, ConnectionWaiter waiter) {
            return new Admission(entry, waiter, null, null, false, false, true);
        }

        private static Admission rejected(ConnectionOutcome outcome) {
            return new Admission(null, null, null, outcome, false, false, false);
        }
    }

    private enum StartupStage {
        STATUS,
        START,
        READINESS
    }

    private enum StartupOutcome {
        READY_RUNNING(true, ConnectionOutcome.CONNECTED),
        READY_AFTER_START(true, ConnectionOutcome.CONNECTED),
        STATUS_NO_MAPPING(false, ConnectionOutcome.STATUS_FAILED),
        STATUS_MISSING(false, ConnectionOutcome.CONTAINER_MISSING),
        STATUS_INACCESSIBLE(false, ConnectionOutcome.DOCKER_INACCESSIBLE),
        STATUS_TIMED_OUT(false, ConnectionOutcome.STATUS_FAILED),
        STATUS_FAILED(false, ConnectionOutcome.STATUS_FAILED),
        STATUS_ERROR(false, ConnectionOutcome.STATUS_FAILED),
        START_MISSING(false, ConnectionOutcome.CONTAINER_MISSING),
        START_INACCESSIBLE(false, ConnectionOutcome.DOCKER_INACCESSIBLE),
        START_TIMED_OUT(false, ConnectionOutcome.START_TIMED_OUT),
        START_FAILED(false, ConnectionOutcome.START_FAILED),
        START_ERROR(false, ConnectionOutcome.START_FAILED),
        NOT_READY(false, ConnectionOutcome.SERVER_NOT_READY),
        READINESS_ERROR(false, ConnectionOutcome.SERVER_NOT_READY),
        CANCELLED(false, ConnectionOutcome.START_CANCELLED),
        OVERLOADED(false, ConnectionOutcome.OVERLOADED);

        private final boolean ready;
        private final ConnectionOutcome connectionOutcome;

        StartupOutcome(boolean ready, ConnectionOutcome connectionOutcome) {
            this.ready = ready;
            this.connectionOutcome = connectionOutcome;
        }
    }

    private record ReconnectPermit(UUID playerId, String serverName) {
    }
}
