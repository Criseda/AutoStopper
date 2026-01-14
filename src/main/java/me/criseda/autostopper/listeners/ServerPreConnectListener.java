package me.criseda.autostopper.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.AutoStopperPlugin;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;
import net.kyori.adventure.text.Component;

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
        
        if (serverManager.isMonitoredServer(serverName)) {
            // Check if existing start process is already in progress
            AtomicBoolean isStarting = serverManager.getServerStartingStatus(serverName);
            if (isStarting.get()) {
                player.sendMessage(Component.text("§eServer is already being started, please wait..."));
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                return;
            }

            // If not starting, but also not running, then we start it
            if (!serverManager.isServerRunning(serverName)) {
                player.sendMessage(Component.text("§eServer is currently offline. Starting it up for you..."));
                
                if (isStarting.compareAndSet(false, true)) {
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
                     // Race condition hit: someone else started it just now
                     player.sendMessage(Component.text("§eServer is being started by another request, please wait..."));
                     event.setResult(ServerPreConnectEvent.ServerResult.denied());
                }
            }
        }
    }
}