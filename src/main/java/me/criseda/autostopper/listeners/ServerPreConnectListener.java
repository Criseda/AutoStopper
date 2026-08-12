package me.criseda.autostopper.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.AutoStopperPlugin;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;
import net.kyori.adventure.text.Component;

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
            player.sendMessage(Component.text("§eServer is already being started, please wait..."));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        if (!isStarting.compareAndSet(false, true)) {
            // Race condition hit: someone else started it just now
            player.sendMessage(Component.text("§eServer is being started by another request, please wait..."));
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
                player.sendMessage(Component
                        .text("§cAutoStopper is overloaded right now; please try again in a moment."));
            } else {
                plugin.getLogger().error("Error while checking status for server {}", serverName, error);
                player.sendMessage(Component
                        .text("§cError checking status of server §e" + serverName + "§c."));
            }
            releaseStarting(serverName, isStarting);
            return;
        }

        if (status.isEmpty()) {
            player.sendMessage(Component.text("§cServer §e" + serverName + "§c has no container mapping."));
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
                player.sendMessage(Component
                        .text("§cThe container for server §e" + serverName + "§c does not exist."));
                releaseStarting(serverName, isStarting);
                break;
            case INACCESSIBLE:
                player.sendMessage(Component
                        .text("§cCannot reach the Docker daemon to manage server §e" + serverName + "§c."));
                releaseStarting(serverName, isStarting);
                break;
            case TIMED_OUT:
                player.sendMessage(Component
                        .text("§cCould not check the status of server §e" + serverName
                                + "§c in time. Try again."));
                releaseStarting(serverName, isStarting);
                break;
            case FAILED:
                player.sendMessage(Component
                        .text("§cCould not check the status of server §e" + serverName + "§c."));
                releaseStarting(serverName, isStarting);
                break;
        }
    }

    private void startServerForPlayer(Player player, RegisteredServer targetServer, ServerMapping mapping,
            AtomicBoolean isStarting) {
        String serverName = mapping.serverName();
        player.sendMessage(Component.text("§eServer is currently offline. Starting it up for you..."));

        CompletableFuture<ContainerStatus> startFuture = serverManager.startServerAsync(mapping);
        startFuture.whenComplete((startResult, error) -> {
            if (error != null) {
                if (error instanceof AutoStopperExecutor.SaturationException) {
                    player.sendMessage(Component
                            .text("§cAutoStopper is overloaded right now; please try again in a moment."));
                } else {
                    plugin.getLogger().error("Error while starting server {}", serverName, error);
                    player.sendMessage(Component.text("§cError starting server §e" + serverName + "§c."));
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
                    player.sendMessage(Component
                            .text("§cThe container for server §e" + serverName + "§c does not exist."));
                    break;
                case INACCESSIBLE:
                    player.sendMessage(Component
                            .text("§cCannot reach the Docker daemon to start server §e" + serverName + "§c."));
                    break;
                case TIMED_OUT:
                    player.sendMessage(Component
                            .text("§cTimed out starting server §e" + serverName + "§c. Try again."));
                    break;
                default:
                    player.sendMessage(Component.text("§cFailed to start server §e" + serverName));
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
                    player.sendMessage(Component
                            .text("§cServer §e" + serverName + "§c may not be fully ready yet."));
                    return;
                }

                if (ready) {
                    player.sendMessage(Component.text("§aServer §e" + serverName + "§a is now ready!"));
                    activityTracker.updateActivity(serverName);
                    reconnectPlayer(player, targetServer, serverName);
                } else {
                    player.sendMessage(Component
                            .text("§cServer §e" + serverName + "§c may not be fully ready yet."));
                    player.sendMessage(
                            Component.text("§eTry again in a moment with §b/server " + serverName));
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
            player.sendMessage(Component.text("§cCould not connect you to server §e" + serverName + "§c."));
        }
    }

    private record ReconnectPermit(Player player, String serverName) {
    }
}
