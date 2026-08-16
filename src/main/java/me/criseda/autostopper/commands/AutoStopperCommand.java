package me.criseda.autostopper.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;

import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigLoadResult;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.messages.AutoStopperMessages;
import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;
import me.criseda.autostopper.operational.OperationalServerStatus;
import me.criseda.autostopper.operational.OperationalStatusService;
import me.criseda.autostopper.server.ActivityTracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class AutoStopperCommand implements SimpleCommand {
    static final String ADMIN_PERMISSION = "autostopper.admin";
    static final String STATUS_PERMISSION = "autostopper.command.status";
    static final String RELOAD_PERMISSION = "autostopper.command.reload";

    private final AutoStopperConfig config;
    private final ActivityTracker activityTracker;
    private final ServerLifecycleCoordinator lifecycleCoordinator;
    private final OperationalStatusService operationalStatus;
    private final PluginContainer pluginContainer;

    public AutoStopperCommand(AutoStopperConfig config,
            ActivityTracker activityTracker,
            ServerLifecycleCoordinator lifecycleCoordinator, OperationalStatusService operationalStatus,
            PluginContainer pluginContainer) {
        this.config = config;
        this.activityTracker = activityTracker;
        this.lifecycleCoordinator = lifecycleCoordinator;
        this.operationalStatus = operationalStatus;
        this.pluginContainer = pluginContainer;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        boolean isPlayer = source instanceof Player;

        if (args.length == 0) {
            String version = pluginContainer.getDescription().getVersion().orElse("Unknown");
            source.sendMessage(AutoStopperMessages.pluginHeader(version));
            source.sendMessage(AutoStopperMessages.pluginTagline());
            source.sendMessage(AutoStopperMessages.helpHint(isPlayer));
            return;
        }

        String subcommand = args[0].toLowerCase();
        switch (subcommand) {
            case "help":
                showHelp(source, isPlayer);
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
                List<String> permitted = getPermittedSubcommands(source);
                Optional<String> closest = findClosestSubcommand(args[0], permitted);
                if (closest.isPresent()) {
                    source.sendMessage(AutoStopperMessages.unknownSubcommand(args[0], closest.get(), isPlayer));
                } else {
                    source.sendMessage(AutoStopperMessages.unknownSubcommandNoMatch(args[0], isPlayer));
                }
        }
    }

    private void showHelp(CommandSource source, boolean isPlayer) {
        source.sendMessage(AutoStopperMessages.helpHeader());
        source.sendMessage(AutoStopperMessages.helpEntry("/autostopper", "Shows plugin overview", isPlayer));
        source.sendMessage(AutoStopperMessages.helpEntry("/autostopper help", "Shows this help menu", isPlayer));
        if (hasAdministrativePermission(source, STATUS_PERMISSION)) {
            source.sendMessage(AutoStopperMessages.helpEntry(
                    "/autostopper status", "Shows server status", isPlayer));
        }
        if (hasAdministrativePermission(source, RELOAD_PERMISSION)) {
            source.sendMessage(AutoStopperMessages.helpEntry(
                    "/autostopper reload", "Reload configuration", isPlayer));
        }
    }

    private void showStatus(CommandSource source) {
        source.sendMessage(AutoStopperMessages.statusHeader());

        ConfigSnapshot snapshot = config.snapshot();
        List<String> serverNames = snapshot.serverNames().stream().sorted().toList();
        operationalStatus.collectStatuses(snapshot).whenComplete((statuses, error) -> {
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
            OperationalServerStatus status) {
        Long minutes = activityTracker.getLastActivity(serverName) == null
                ? null : activityTracker.getMinutesSinceActivity(serverName);
        source.sendMessage(AutoStopperMessages.operationalStatus(serverName, status, minutes));
    }

    private void reloadConfig(CommandSource source) {
        source.sendMessage(AutoStopperMessages.reloadStarted());
        ConfigSnapshot previous = config.snapshot();
        ConfigLoadResult result = config.loadConfig();
        if (!result.successful()) {
            source.sendMessage(AutoStopperMessages.reloadFailed(result.errorSummary()));
            return;
        }

        lifecycleCoordinator.reconcileConfig(previous, result.snapshot());
        activityTracker.reconcileConfig(previous, result.snapshot());
        operationalStatus.reconcileConfig(result.snapshot());
        source.sendMessage(AutoStopperMessages.reloadSucceeded());
        operationalStatus.runPreflight(result.snapshot(), "reload")
                .thenAccept(summary -> source.sendMessage(AutoStopperMessages.preflightCompleted(
                        summary.healthyMappings(), summary.degradedMappings())));
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

    private List<String> getPermittedSubcommands(CommandSource source) {
        List<String> permitted = new ArrayList<>();
        permitted.add("help");
        if (hasAdministrativePermission(source, STATUS_PERMISSION)) {
            permitted.add("status");
        }
        if (hasAdministrativePermission(source, RELOAD_PERMISSION)) {
            permitted.add("reload");
        }
        return permitted;
    }

    private boolean hasAdministrativePermission(CommandSource source, String permission) {
        return source.getPermissionValue(permission) == Tristate.TRUE
                || source.getPermissionValue(ADMIN_PERMISSION) == Tristate.TRUE;
    }

    private void sendPermissionDenied(CommandSource source, String action) {
        source.sendMessage(AutoStopperMessages.permissionDenied(action));
    }

    static Optional<String> findClosestSubcommand(String input, List<String> candidates) {
        if (input == null || input.isEmpty() || candidates.isEmpty()) {
            return Optional.empty();
        }
        String lower = input.toLowerCase();
        String bestMatch = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = levenshteinDistance(lower, candidate.toLowerCase());
            if (distance < bestDistance) {
                bestDistance = distance;
                bestMatch = candidate;
            }
        }
        if (bestDistance <= 2 && bestMatch != null) {
            return Optional.of(bestMatch);
        }
        return Optional.empty();
    }

    static int levenshteinDistance(String a, String b) {
        int[] costs = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            costs[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= b.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                        a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[b.length()];
    }
}
