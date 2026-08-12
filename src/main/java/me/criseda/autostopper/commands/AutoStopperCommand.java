package me.criseda.autostopper.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.PluginContainer;

import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigLoadResult;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.messages.AutoStopperMessages;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Optional;

public class AutoStopperCommand implements SimpleCommand {
    static final String ADMIN_PERMISSION = "autostopper.admin";
    static final String STATUS_PERMISSION = "autostopper.command.status";
    static final String RELOAD_PERMISSION = "autostopper.command.reload";

    private final AutoStopperConfig config;
    private final ServerManager serverManager;
    private final ActivityTracker activityTracker;
    private final PluginContainer pluginContainer;

    public AutoStopperCommand(AutoStopperConfig config,
            ServerManager serverManager, ActivityTracker activityTracker, PluginContainer pluginContainer) {
        this.config = config;
        this.serverManager = serverManager;
        this.activityTracker = activityTracker;
        this.pluginContainer = pluginContainer;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            String version = pluginContainer.getDescription().getVersion().orElse("Unknown");
            source.sendMessage(AutoStopperMessages.pluginInfo(version));
            source.sendMessage(AutoStopperMessages.helpHint());
            return;
        }

        switch (args[0].toLowerCase()) {
            case "help":
                showHelp(source);
                break;
            case "status":
                if (hasAdministrativePermission(source, STATUS_PERMISSION)) {
                    showStatus(source);
                } else {
                    sendPermissionDenied(source, "view AutoStopper status");
                }
                break;
            case "reload":
                if (hasAdministrativePermission(source, RELOAD_PERMISSION)) {
                    reloadConfig(source);
                } else {
                    sendPermissionDenied(source, "reload AutoStopper configuration");
                }
                break;
            default:
                source.sendMessage(AutoStopperMessages.unknownCommand());
        }
    }

    private void showHelp(CommandSource source) {
        source.sendMessage(AutoStopperMessages.helpHeader());
        source.sendMessage(AutoStopperMessages.helpEntry("/autostopper", "Shows plugin information"));
        source.sendMessage(AutoStopperMessages.helpEntry("/autostopper help", "Shows this help menu"));
        if (hasAdministrativePermission(source, STATUS_PERMISSION)) {
            source.sendMessage(AutoStopperMessages.helpEntry(
                    "/autostopper status", "Shows server status"));
        }
        if (hasAdministrativePermission(source, RELOAD_PERMISSION)) {
            source.sendMessage(AutoStopperMessages.helpEntry(
                    "/autostopper reload", "Reload configuration"));
        }
    }

    private void showStatus(CommandSource source) {
        source.sendMessage(AutoStopperMessages.statusHeader());

        ConfigSnapshot snapshot = config.snapshot();
        List<String> serverNames = snapshot.serverNames();
        // All Docker status checks run on the plugin-owned executor and fan
        // back in here, never blocking the command thread.
        serverManager.getStatusesAsync(snapshot).whenComplete((statuses, error) -> {
            if (error != null) {
                source.sendMessage(AutoStopperMessages.statusCollectionFailed());
                return;
            }
            for (String serverName : serverNames) {
                sendServerStatus(source, serverName, statuses.get(serverName));
            }
        });
    }

    private void sendServerStatus(CommandSource source, String serverName,
            Optional<ContainerStatus> status) {
        if (status.isEmpty()) {
            source.sendMessage(AutoStopperMessages.statusNoMapping(serverName));
            return;
        }

        switch (status.get()) {
            case RUNNING: {
                Instant lastActive = activityTracker.getLastActivity(serverName);
                if (lastActive != null) {
                    long minutes = activityTracker.getMinutesSinceActivity(serverName);
                    source.sendMessage(AutoStopperMessages.statusRunning(serverName, minutes));
                } else {
                    source.sendMessage(AutoStopperMessages.statusRunning(serverName, null));
                }
                break;
            }
            case STOPPED:
                source.sendMessage(AutoStopperMessages.statusStopped(serverName));
                break;
            case MISSING:
                source.sendMessage(AutoStopperMessages.statusMissing(serverName));
                break;
            case INACCESSIBLE:
                source.sendMessage(AutoStopperMessages.statusInaccessible(serverName));
                break;
            case TIMED_OUT:
                source.sendMessage(AutoStopperMessages.statusTimedOut(serverName));
                break;
            case FAILED:
                source.sendMessage(AutoStopperMessages.statusFailed(serverName));
                break;
        }
    }

    private void reloadConfig(CommandSource source) {
        source.sendMessage(AutoStopperMessages.reloadStarted());
        ConfigSnapshot previous = config.snapshot();
        ConfigLoadResult result = config.loadConfig();
        if (!result.successful()) {
            source.sendMessage(AutoStopperMessages.reloadFailed(result.errorSummary()));
            return;
        }

        serverManager.reconcileConfig(previous, result.snapshot());
        activityTracker.reconcileConfig(previous, result.snapshot());
        source.sendMessage(AutoStopperMessages.reloadSucceeded());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        // If user hasn't typed anything yet or is typing the first argument
        if (args.length == 0 || args.length == 1) {
            String input = args.length == 1 ? args[0].toLowerCase() : "";
            List<String> suggestions = new ArrayList<>();
            
            if ("help".startsWith(input)) suggestions.add("help");
            if (hasAdministrativePermission(source, STATUS_PERMISSION)
                    && "status".startsWith(input)) {
                suggestions.add("status");
            }
            if (hasAdministrativePermission(source, RELOAD_PERMISSION)
                    && "reload".startsWith(input)) {
                suggestions.add("reload");
            }
            
            return suggestions;
        }
        return Collections.emptyList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Plugin information and help are public. Restricted subcommands enforce
        // their own permissions in execute() and suggest().
        return true;
    }

    private boolean hasAdministrativePermission(CommandSource source, String permission) {
        return source.getPermissionValue(permission) == Tristate.TRUE
                || source.getPermissionValue(ADMIN_PERMISSION) == Tristate.TRUE;
    }

    private void sendPermissionDenied(CommandSource source, String action) {
        source.sendMessage(AutoStopperMessages.permissionDenied(action));
    }
}
