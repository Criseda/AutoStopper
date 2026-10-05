package me.criseda.autostopper.lifecycle;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.messages.AutoStopperMessages;
import net.kyori.adventure.text.Component;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * One player's pending connection to a managed server. The notification queue is guarded by the
 * waiter's own monitor so that each lifecycle stage is delivered at most once and in order, even
 * when several threads queue stages concurrently. The in-flight connection request is guarded by
 * the owning {@link LifecycleEntry}'s monitor.
 */
final class ConnectionWaiter {
    private final UUID playerId;
    private final Player player;
    private final RegisteredServer targetServer;
    private final String serverName;
    private final CompletableFuture<ConnectionOutcome> outcome = new CompletableFuture<>();
    private final long startNanos;
    private final ArrayDeque<WaiterNotification> notifications = new ArrayDeque<>();
    private final Set<ConnectionLifecycleStage> queuedStages =
            EnumSet.noneOf(ConnectionLifecycleStage.class);
    private final Set<ConnectionLifecycleStage> deliveredStages =
            EnumSet.noneOf(ConnectionLifecycleStage.class);
    private volatile boolean discarded;
    private boolean notificationsSuppressed;
    private boolean deliveringNotifications;
    private int lastWaitingCountReported;
    private CompletableFuture<ConnectionRequestBuilder.Result> connection;

    ConnectionWaiter(UUID playerId, Player player, RegisteredServer targetServer,
            String serverName, long startNanos) {
        this.playerId = playerId;
        this.player = player;
        this.targetServer = targetServer;
        this.serverName = serverName;
        this.startNanos = startNanos;
    }

    UUID playerId() {
        return playerId;
    }

    Player player() {
        return player;
    }

    RegisteredServer targetServer() {
        return targetServer;
    }

    String serverName() {
        return serverName;
    }

    long startNanos() {
        return startNanos;
    }

    /** The future handed back to whoever asked for this connection. */
    CompletableFuture<ConnectionOutcome> outcome() {
        return outcome;
    }

    /** Ends this waiter's request. Only {@link WaiterConnector} does this; later calls have no effect. */
    void complete(ConnectionOutcome result) {
        outcome.complete(result);
    }

    /** Marks the waiter as no longer wanted (player left or proxy shutting down); nothing more is delivered. */
    void discard() {
        discarded = true;
    }

    boolean isDiscarded() {
        return discarded;
    }

    /** Whether this waiter needs no further work: discarded, or its outcome already decided. */
    boolean isFinished() {
        return discarded || outcome.isDone();
    }

    /** Caller must hold the owning entry's monitor. */
    void attachConnection(CompletableFuture<ConnectionRequestBuilder.Result> request) {
        connection = request;
    }

    /**
     * Forgets the in-flight connection request and returns it, or {@code null} if there was none.
     * Caller must hold the owning entry's monitor.
     */
    CompletableFuture<ConnectionRequestBuilder.Result> detachConnection() {
        CompletableFuture<ConnectionRequestBuilder.Result> request = connection;
        connection = null;
        return request;
    }

    synchronized void queueStage(ConnectionLifecycleStage stage, Component message, boolean disconnectInitial) {
        if (discarded || notificationsSuppressed || queuedStages.contains(stage)
                || deliveredStages.contains(stage)) {
            return;
        }
        queuedStages.add(stage);
        notifications.addLast(new WaiterNotification(Optional.of(stage), message, disconnectInitial));
    }

    synchronized void queueWaitingCount(int count) {
        if (count <= 1 || discarded || notificationsSuppressed || lastWaitingCountReported == count) {
            return;
        }
        lastWaitingCountReported = count;
        notifications.addLast(new WaiterNotification(
                Optional.empty(), AutoStopperMessages.playersWaiting(count), false));
    }

    synchronized void suppressNotifications() {
        notificationsSuppressed = true;
        notifications.clear();
        queuedStages.clear();
    }

    /**
     * Delivers queued notifications outside the waiter monitor. Only one thread delivers at a
     * time; concurrent callers return immediately and leave the active deliverer to drain.
     */
    void drainNotifications(Consumer<WaiterNotification> delivery) {
        synchronized (this) {
            if (deliveringNotifications) {
                return;
            }
            deliveringNotifications = true;
        }
        while (true) {
            WaiterNotification notification;
            synchronized (this) {
                notification = notifications.pollFirst();
                if (notification == null) {
                    deliveringNotifications = false;
                    return;
                }
                notification.stage().ifPresent(queuedStages::remove);
                if (discarded || notificationsSuppressed
                        || notification.stage().map(deliveredStages::contains).orElse(false)) {
                    continue;
                }
                notification.stage().ifPresent(deliveredStages::add);
            }
            delivery.accept(notification);
        }
    }

    record WaiterNotification(Optional<ConnectionLifecycleStage> stage, Component message,
            boolean disconnectInitial) {
    }
}
