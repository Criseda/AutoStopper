package me.criseda.autostopper.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.AutoStopperPlugin;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;
import net.kyori.adventure.text.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerPreConnectListener {
    private final AutoStopperPlugin plugin;
    private final ServerManager serverManager;
    private final ActivityTracker activityTracker;

    public ServerPreConnectListener(AutoStopperPlugin plugin, ServerManager serverManager, ActivityTracker activityTracker) {
        this.plugin = plugin;
        this.serverManager = serverManager;
        this.activityTracker = activityTracker;
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        RegisteredServer targetServer = event.getOriginalServer();
        String serverName = targetServer.getServerInfo().getName();
        
        // Only intercept if this is a monitored server that's not running
        if (serverManager.isMonitoredServer(serverName) && !serverManager.isServerRunning(serverName)) {
            player.sendMessage(Component.text("§eServer is currently offline. Starting it up for you..."));
            
            AtomicBoolean isStarting = serverManager.getServerStartingStatus(serverName);
            
            if (isStarting.compareAndSet(false, true)) {
                // Create a deferred result that will be completed when server is ready
                CompletableFuture<Boolean> serverReady = new CompletableFuture<>();
                
                // Cancel the connection attempt for now - we'll reconnect later
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                
                // Start server in a separate thread
                plugin.getServer().getScheduler().buildTask(plugin, () -> {
                    try {
                        if (serverManager.startServer(serverName)) {
                            // Wait for server to be ready
                            if (serverManager.waitForServerReady(serverName, 120)) {
                                player.sendMessage(Component.text("§aServer §e" + serverName + "§a is now ready!"));
                                activityTracker.updateActivity(serverName);
                                
                                // Connect the player to the server now that it's running
                                player.createConnectionRequest(targetServer).fireAndForget();
                            } else {
                                player.sendMessage(Component.text("§cServer §e" + serverName + "§c may not be fully ready yet."));
                                player.sendMessage(Component.text("§eTry again in a moment with §b/server " + serverName));
                            }
                        } else {
                            player.sendMessage(Component.text("§cFailed to start server §e" + serverName));
                        }
                    } catch (Exception e) {
                        plugin.getLogger().error("Error while starting server", e);
                        player.sendMessage(Component.text("§cError starting server: " + e.getMessage()));
                    } finally {
                        isStarting.set(false);
                    }
                }).schedule();
            } else {
                player.sendMessage(Component.text("§eServer is already being started, please wait..."));
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
            }
        }
    }
}