package me.criseda.autostopper.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;

import net.kyori.adventure.text.Component;

import java.time.Instant;
import java.util.List;
import java.util.Collections;

public class AutoStopperCommand implements SimpleCommand {
    private final AutoStopperConfig config;
    private final ServerManager serverManager;
    private final ActivityTracker activityTracker;

    public AutoStopperCommand(AutoStopperConfig config,
            ServerManager serverManager, ActivityTracker activityTracker) {
        this.config = config;
        this.serverManager = serverManager;
        this.activityTracker = activityTracker;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        // Check if the user has admin permission before executing any command
        if (!source.hasPermission("autostopper.admin")) {
            source.sendMessage(Component.text("§cYou don't have permission to use this command."));
            return;
        }

        if (args.length == 0) {
            source.sendMessage(Component.text("§6AutoStopper v1.0 §7- §eServer Auto-Stop Plugin"));
            source.sendMessage(Component.text("§7Use §e/autostopper help §7for more information"));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "help":
                showHelp(source);
                break;
            case "status":
                showStatus(source);
                break;
            case "reload":
                reloadConfig(source);
                break;
            default:
                source.sendMessage(Component.text("§cUnknown command. Use §e/autostopper help §cfor help."));
        }
    }

    private void showHelp(CommandSource source) {
        source.sendMessage(Component.text("§6AutoStopper Help:"));
        source.sendMessage(Component.text("§e/autostopper §7- Shows plugin information"));
        source.sendMessage(Component.text("§e/autostopper help §7- Shows this help menu"));
        source.sendMessage(Component.text("§e/autostopper status §7- Shows server status"));
        source.sendMessage(Component.text("§e/autostopper reload §7- Reload configuration"));
    }

    private void showStatus(CommandSource source) {
        source.sendMessage(Component.text("§6AutoStopper Server Status:"));

        for (String serverName : config.getServerNames()) {
            boolean running = serverManager.isServerRunning(serverName);
            Instant lastActive = activityTracker.getLastActivity(serverName);

            if (running) {
                // Server is running - show activity time
                if (lastActive != null) {
                    long minutes = activityTracker.getMinutesSinceActivity(serverName);
                    source.sendMessage(Component.text(
                            String.format("§7%s: §a§lRunning §7- %d minutes since last activity",
                                    serverName, minutes)));
                } else {
                    source.sendMessage(Component.text(
                            String.format("§7%s: §a§lRunning §7- No activity recorded", serverName)));
                }
            } else {
                // Server is stopped - don't show activity time
                source.sendMessage(Component.text(
                        String.format("§7%s: §c§lStopped", serverName)));
            }
        }
    }

    private void reloadConfig(CommandSource source) {
        // Permission check is now handled at the execute method level
        source.sendMessage(Component.text("§6Reloading AutoStopper configuration..."));
        config.loadConfig();
        source.sendMessage(Component.text("§aConfiguration reloaded successfully!"));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // Only suggest commands if user has permission
        if (!invocation.source().hasPermission("autostopper.admin")) {
            return Collections.emptyList();
        }
        
        String[] args = invocation.arguments();
        if (args.length == 1) {
            return List.of("help", "status", "reload");
        }
        return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Change permission check to require autostopper.admin for all commands
        return invocation.source().hasPermission("autostopper.admin");
    }
}