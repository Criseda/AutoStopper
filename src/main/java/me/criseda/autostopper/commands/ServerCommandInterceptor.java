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

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerCommandInterceptor {
    private final ProxyServer server;
    private final AutoStopperPlugin plugin;
    private final ServerManager serverManager;
    private final ActivityTracker activityTracker;

    public ServerCommandInterceptor(ProxyServer server, AutoStopperPlugin plugin,
            ServerManager serverManager, ActivityTracker activityTracker) {
        this.server = server;
        this.plugin = plugin;
        this.serverManager = serverManager;
        this.activityTracker = activityTracker;

        registerCommand();
    }

    private void registerCommand() {
        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("server")
                        .aliases("join", "s")
                        .plugin(plugin)
                        .build(),
                new ServerCommand());
    }

    private class ServerCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();

            if (!(source instanceof Player)) {
                // Only players can use this command
                source.sendMessage(Component.text("§cOnly players can use this command!"));
                return;
            }

            Player player = (Player) source;

            if (args.length != 1) {
                // Forward to original handler
                server.getCommandManager().executeAsync(source, "server " + String.join(" ", args));
                return;
            }

            String targetServer = args[0];
            Optional<RegisteredServer> registeredServer = server.getServer(targetServer);

            if (!registeredServer.isPresent()) {
                // Unknown server, let the original handler show the error
                server.getCommandManager().executeAsync(source, "server " + targetServer);
                return;
            }

            // Now we know the server exists
            if (serverManager.isMonitoredServer(targetServer) && !serverManager.isServerRunning(targetServer)) {
                player.sendMessage(Component.text("§eServer is currently offline. Starting it up for you..."));

                AtomicBoolean isStarting = serverManager.getServerStartingStatus(targetServer);

                if (isStarting.compareAndSet(false, true)) {
                    // Start server in a separate thread
                    server.getScheduler().buildTask(plugin, () -> {
                        try {
                            serverManager.startServer(targetServer);

                            // Wait up to 120 seconds for server to be ready
                            boolean serverReady = serverManager.waitForServerReady(targetServer, 120);

                            if (serverReady) {
                                player.sendMessage(Component.text("§aServer is now online! Connecting..."));
                                player.createConnectionRequest(registeredServer.get()).fireAndForget();

                                // Update activity tracker
                                activityTracker.updateActivity(targetServer);
                            } else {
                                player.sendMessage(Component
                                        .text("§cServer startup timed out. Please try again later."));
                            }
                        } catch (Exception e) {
                            plugin.getLogger().error("Error while starting server", e);
                            player.sendMessage(Component.text(
                                    "§cFailed to start the server. Please contact an administrator."));
                        } finally {
                            isStarting.set(false);
                        }
                    }).schedule();
                } else {
                    player.sendMessage(Component.text("§eServer is already being started, please wait..."));
                }
            } else {
                // Server is running or not monitored, use original command
                server.getCommandManager().executeAsync(source, "server " + targetServer);
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            // Forward suggestions to original handler
            return Collections.emptyList();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return true;
        }
    }
}