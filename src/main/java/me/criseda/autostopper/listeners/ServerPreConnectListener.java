package me.criseda.autostopper.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.AutoStopperPlugin;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.messages.AutoStopperMessages;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerPreConnectListener {
    private static final int SERVER_READY_TIMEOUT_SECONDS = 120;

    private final AutoStopperPlugin plugin;
    private final ServerManager serverManager;
    private final ActivityTracker activityTracker;
    private final Set<ReconnectPermit> reconnectPermits = ConcurrentHashMap.newKeySet();

    public ServerPreConnectListener(AutoStopperPlugin plugin, ServerManager serverManager,
            ActivityTracker activityTracker) {
        this.plugin = plugin;
        this.serverManager = serverManager;
        this.activityTracker = activityTracker;
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        Optional<RegisteredServer> target = event.getResult().getServer();
        // The connection attempt was denied (by this or another plugin) - never start a container.
        if (target.isEmpty()) {
            return;
        }
        RegisteredServer targetServer = target.get();
        String serverName = targetServer.getServerInfo().getName();

        Optional<ServerMapping> mapping = serverManager.getServerMapping(serverName);
        if (mapping.isEmpty()) {
            return;
        }

        // A connection request created by this listener must pass through once,
        // otherwise it would be denied and recursively re-created forever.
        if (reconnectPermits.remove(new ReconnectPermit(player, serverName))) {
            return;
        }

        AtomicBoolean isStarting = serverManager.getServerStartingStatus(serverName);
        if (isStarting.get()) {
            player.sendMessage(AutoStopperMessages.serverAlreadyStarting());
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        if (!isStarting.compareAndSet(false, true)) {
            // Race condition hit: someone else started it just now
            player.sendMessage(AutoStopperMessages.serverStartingFromAnotherRequest());
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        // Defer all Docker I/O to the plugin-owned executor and resolve the final
        // connection asynchronously, so this event thread is never blocked.
        event.setResult(ServerPreConnectEvent.ServerResult.denied());

        ServerMapping capturedMapping = mapping.get();
        serverManager.getServerStatusAsync(capturedMapping).whenComplete((status, error) ->
                handleStatusResult(player, targetServer, capturedMapping, status, error, isStarting));
    }

    private void handleStatusResult(Player player, RegisteredServer targetServer, ServerMapping mapping,
            Optional<ContainerStatus> status, Throwable error, AtomicBoolean isStarting) {
        String serverName = mapping.serverName();
        if (error != null) {
            if (error instanceof AutoStopperExecutor.SaturationException) {
                player.sendMessage(AutoStopperMessages.overloaded());
            } else {
                plugin.getLogger().error("Error while checking status for server {}", serverName, error);
                player.sendMessage(AutoStopperMessages.statusCheckError(serverName));
            }
            releaseStarting(serverName, isStarting);
            return;
        }

        if (status.isEmpty()) {
            player.sendMessage(AutoStopperMessages.noContainerMapping(serverName));
            releaseStarting(serverName, isStarting);
            return;
        }

        switch (status.get()) {
            case RUNNING: {
                // Server is up - connect the player now.
                reconnectPlayer(player, targetServer, serverName);
                releaseStarting(serverName, isStarting);
                break;
            }
            case STOPPED:
                // The start chain clears the starting flag when it completes.
                startServerForPlayer(player, targetServer, mapping, isStarting);
                break;
            case MISSING:
                player.sendMessage(AutoStopperMessages.containerMissing(serverName));
                releaseStarting(serverName, isStarting);
                break;
            case INACCESSIBLE:
                player.sendMessage(AutoStopperMessages.dockerUnavailable("manage", serverName));
                releaseStarting(serverName, isStarting);
                break;
            case TIMED_OUT:
                player.sendMessage(AutoStopperMessages.statusCheckTimedOut(serverName));
                releaseStarting(serverName, isStarting);
                break;
            case FAILED:
                player.sendMessage(AutoStopperMessages.statusCheckFailed(serverName));
                releaseStarting(serverName, isStarting);
                break;
        }
    }

    private void startServerForPlayer(Player player, RegisteredServer targetServer, ServerMapping mapping,
            AtomicBoolean isStarting) {
        String serverName = mapping.serverName();
        player.sendMessage(AutoStopperMessages.serverOfflineStarting());

        CompletableFuture<ContainerStatus> startFuture = serverManager.startServerAsync(mapping);
        startFuture.whenComplete((startResult, error) -> {
            if (error != null) {
                if (error instanceof AutoStopperExecutor.SaturationException) {
                    player.sendMessage(AutoStopperMessages.overloaded());
                } else {
                    plugin.getLogger().error("Error while starting server {}", serverName, error);
                    player.sendMessage(AutoStopperMessages.startError(serverName));
                }
                releaseStarting(serverName, isStarting);
                return;
            }

            if (startResult == ContainerStatus.RUNNING) {
                // The readiness chain clears the starting flag when it completes.
                waitForServerReady(player, targetServer, mapping, isStarting);
                return;
            }

            switch (startResult) {
                case MISSING:
                    player.sendMessage(AutoStopperMessages.containerMissing(serverName));
                    break;
                case INACCESSIBLE:
                    player.sendMessage(AutoStopperMessages.dockerUnavailable("start", serverName));
                    break;
                case TIMED_OUT:
                    player.sendMessage(AutoStopperMessages.startTimedOut(serverName));
                    break;
                default:
                    player.sendMessage(AutoStopperMessages.startFailed(serverName));
                    break;
            }
            releaseStarting(serverName, isStarting);
        });
    }

    private void waitForServerReady(Player player, RegisteredServer targetServer, ServerMapping mapping,
            AtomicBoolean isStarting) {
        String serverName = mapping.serverName();
        CompletableFuture<Boolean> readyFuture =
                serverManager.waitForServerReadyAsync(mapping, SERVER_READY_TIMEOUT_SECONDS);
        readyFuture.whenComplete((ready, error) -> {
            try {
                if (error != null) {
                    if (!(error instanceof AutoStopperExecutor.SaturationException)) {
                        plugin.getLogger().error("Error while waiting for server {} to become ready",
                                serverName, error);
                    }
                    player.sendMessage(AutoStopperMessages.serverNotReady(serverName));
                    return;
                }

                if (ready) {
                    player.sendMessage(AutoStopperMessages.serverReady(serverName));
                    activityTracker.updateActivity(serverName);
                    reconnectPlayer(player, targetServer, serverName);
                } else {
                    player.sendMessage(AutoStopperMessages.serverNotReady(serverName));
                    player.sendMessage(AutoStopperMessages.retryServerCommand(serverName));
                }
            } finally {
                releaseStarting(serverName, isStarting);
            }
        });
    }

    private void releaseStarting(String serverName, AtomicBoolean isStarting) {
        isStarting.set(false);
        serverManager.releaseServerStartingStatus(serverName, isStarting);
    }

    private void reconnectPlayer(Player player, RegisteredServer targetServer, String serverName) {
        ReconnectPermit permit = new ReconnectPermit(player, serverName);
        reconnectPermits.add(permit);
        try {
            player.createConnectionRequest(targetServer).connect().whenComplete((result, error) -> {
                reconnectPermits.remove(permit);
                if (error != null) {
                    plugin.getLogger().error("Error reconnecting player to server {}", serverName, error);
                } else if (!result.isSuccessful()) {
                    plugin.getLogger().warn("Could not reconnect player to server {}: {}",
                            serverName, result.getStatus());
                }
            });
        } catch (RuntimeException error) {
            reconnectPermits.remove(permit);
            plugin.getLogger().error("Error creating reconnect request for server {}", serverName, error);
            player.sendMessage(AutoStopperMessages.connectionFailed(serverName));
        }
    }

    private record ReconnectPermit(Player player, String serverName) {
    }
}
