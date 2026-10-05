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
 * when several threads queue stages concurrently. {@link #connectionFuture} is guarded by the
 * owning {@link LifecycleEntry}.
 */
final class ConnectionWaiter {
    final UUID playerId;
    final Player player;
    final RegisteredServer targetServer;
    final String serverName;
    final CompletableFuture<ConnectionOutcome> future = new CompletableFuture<>();
    final long startNanos;
    private final ArrayDeque<WaiterNotification> notifications = new ArrayDeque<>();
    private final Set<ConnectionLifecycleStage> queuedStages =
            EnumSet.noneOf(ConnectionLifecycleStage.class);
    private final Set<ConnectionLifecycleStage> deliveredStages =
            EnumSet.noneOf(ConnectionLifecycleStage.class);
    volatile boolean discarded;
    private boolean notificationsSuppressed;
    private boolean deliveringNotifications;
    private int lastWaitingCountReported;
    CompletableFuture<ConnectionRequestBuilder.Result> connectionFuture;

    ConnectionWaiter(UUID playerId, Player player, RegisteredServer targetServer,
            String serverName, long startNanos) {
        this.playerId = playerId;
        this.player = player;
        this.targetServer = targetServer;
        this.serverName = serverName;
        this.startNanos = startNanos;
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
