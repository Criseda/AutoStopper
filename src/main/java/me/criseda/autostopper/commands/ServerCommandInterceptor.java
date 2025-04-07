package me.criseda.autostopper.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import me.criseda.autostopper.AutoStopperPlugin;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerCommandInterceptor {
    // Make the ServerCommand public static so it can be accessed directly
    public static class ServerCommand implements SimpleCommand {
        private final ProxyServer server;
        private final AutoStopperPlugin plugin;
        private final ServerManager serverManager;
        private final ActivityTracker activityTracker;

        public ServerCommand(ProxyServer server, AutoStopperPlugin plugin,
                ServerManager serverManager, ActivityTracker activityTracker) {
            this.server = server;
            this.plugin = plugin;
            this.serverManager = serverManager;
            this.activityTracker = activityTracker;
        }

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();

            // Handle case with no arguments
            if (args.length == 0) {
                source.sendMessage(Component.text("§eAvailable servers:"));
                for (RegisteredServer rs : server.getAllServers()) {
                    String name = rs.getServerInfo().getName();
                    boolean running = serverManager.isServerRunning(name);
                    String status = running ? "§a§lONLINE" : "§c§lOFFLINE";
                    source.sendMessage(Component.text("§7- §e" + name + " §7(" + status + "§7)"));
                }
                return;
            }

            // Usage message if more than 1 argument
            if (args.length > 1) {
                source.sendMessage(Component.text("§cUsage: /server <name>"));
                return;
            }

            String targetServer = args[0];
            Optional<RegisteredServer> registeredServer = server.getServer(targetServer);

            if (!registeredServer.isPresent()) {
                source.sendMessage(Component.text("§cServer §e" + targetServer + " §cdoes not exist."));
                return;
            }

            // Handle player source directly
            if (source instanceof Player) {
                handlePlayerConnect((Player) source, targetServer, registeredServer.get());
            }
            // Allow command execution from other sources (like Advanced Portals)
            else if (args.length == 1) {
                // Log the command
                source.sendMessage(Component.text("§7Non-player command source requesting connection to §e" + targetServer));
                
                // Check if server is offline and needs starting
                if (serverManager.isMonitoredServer(targetServer) && !serverManager.isServerRunning(targetServer)) {
                    source.sendMessage(Component.text("§eServer is currently offline. Starting it up..."));
                    
                    AtomicBoolean isStarting = serverManager.getServerStartingStatus(targetServer);
                    
                    if (isStarting.compareAndSet(false, true)) {
                        // Start server in a separate thread
                        server.getScheduler().buildTask(plugin, () -> {
                            try {
                                if (serverManager.startServer(targetServer)) {               
                                    // Wait for server to be ready
                                    if (serverManager.waitForServerReady(targetServer, 120)) {
                                        source.sendMessage(Component.text("§aServer §e" + targetServer + "§a is now ready!"));
                                    } else {
                                        source.sendMessage(Component.text("§cServer §e" + targetServer + "§c may not be fully ready yet."));
                                    }
                                } else {
                                    source.sendMessage(Component.text("§cFailed to start server §e" + targetServer));
                                }
                            } catch (Exception e) {
                                plugin.getLogger().error("Error while starting server", e);
                                source.sendMessage(Component.text("§cError starting server: " + e.getMessage()));
                            } finally {
                                isStarting.set(false);
                            }
                        }).schedule();
                    } else {
                        source.sendMessage(Component.text("§eServer is already being started, please wait..."));
                    }
                }
                // Let the original command go through to connect the player
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            // Make sure arguments aren't null
            if (invocation.arguments() == null) {
                return List.of();
            }

            // Get server names for autocompletion
            List<String> serverNames = server.getAllServers().stream()
                    .map(s -> s.getServerInfo().getName())
                    .sorted()
                    .filter(s -> {
                        // If there's a partial input, filter by it
                        if (invocation.arguments().length > 0 && !invocation.arguments()[0].isEmpty()) {
                            return s.toLowerCase().startsWith(invocation.arguments()[0].toLowerCase());
                        }
                        return true;
                    })
                    .toList();

            // Debug log suggestion count
            if (serverNames.size() > 0) {
                plugin.getLogger().debug("Suggesting " + serverNames.size() + " server names for tab completion");
            }

            return serverNames;
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return true;
        }

        private void handlePlayerConnect(Player player, String targetServer, RegisteredServer registeredServer) {
            if (serverManager.isMonitoredServer(targetServer) && !serverManager.isServerRunning(targetServer)) {
                player.sendMessage(Component.text("§eServer is currently offline. Starting it up for you..."));
        
                AtomicBoolean isStarting = serverManager.getServerStartingStatus(targetServer);
        
                if (isStarting.compareAndSet(false, true)) {
                    // Start server in a separate thread
                    server.getScheduler().buildTask(plugin, () -> {
                        try {
                            if (serverManager.startServer(targetServer)) {
                                // Wait for server to be ready
                                if (serverManager.waitForServerReady(targetServer, 120)) {
                                    player.sendMessage(Component.text("§aServer §e" + targetServer + "§a is now ready!"));
                                    player.createConnectionRequest(registeredServer).fireAndForget();
                                    activityTracker.updateActivity(targetServer);
                                } else {
                                    player.sendMessage(Component.text("§cServer §e" + targetServer + "§c may not be fully ready yet."));
                                }
                            } else {
                                player.sendMessage(Component.text("§cFailed to start server §e" + targetServer));
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
                }
            } else {
                // Server is running or not monitored, connect directly
                player.sendMessage(Component.text("§aConnecting to server §e" + targetServer + "§a..."));
                player.createConnectionRequest(registeredServer).fireAndForget();
            }
        }
    }
}