package me.criseda.autostopper.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigLoadResult;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.messages.AutoStopperMessages;
import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;
import me.criseda.autostopper.operational.OperationalServerStatus;
import me.criseda.autostopper.operational.OperationalStatusService;
import me.criseda.autostopper.server.ActivityTracker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class AutoStopperCommand implements SimpleCommand {
    static final String ADMIN_PERMISSION = "autostopper.admin";
    static final String STATUS_PERMISSION = "autostopper.command.status";
    static final String RELOAD_PERMISSION = "autostopper.command.reload";
    static final String START_PERMISSION = "autostopper.command.start";
    static final String STOP_PERMISSION = "autostopper.command.stop";
    static final String RESTART_PERMISSION = "autostopper.command.restart";
    static final String HOLD_PERMISSION = "autostopper.command.hold";
    static final String RELEASE_PERMISSION = "autostopper.command.release";

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
            case "help" -> showHelp(source, isPlayer);
            case "status" -> {
                if (hasAdministrativePermission(source, STATUS_PERMISSION)) {
                    showStatus(source);
                } else {
                    sendPermissionDenied(source, "view AutoStopper status");
                }
            }
            case "reload" -> {
                if (hasAdministrativePermission(source, RELOAD_PERMISSION)) {
                    reloadConfig(source);
                } else {
                    sendPermissionDenied(source, "reload AutoStopper configuration");
                }
            }
            case "start" -> {
                if (!hasAdministrativePermission(source, START_PERMISSION)) {
                    sendPermissionDenied(source, "start a server");
                    return;
                }
                if (args.length < 2) {
                    source.sendMessage(AutoStopperMessages.commandUsage(
                            "/autostopper start <server>", "Starts and readies a mapped backend server", isPlayer));
                    return;
                }
                String serverName = args[1];
                Optional<ServerMapping> mapping = config.snapshot().server(serverName);
                if (mapping.isEmpty()) {
                    source.sendMessage(AutoStopperMessages.unmappedServer(serverName));
                    return;
                }
                startServer(source, mapping.get());
            }
            case "stop" -> {
                if (!hasAdministrativePermission(source, STOP_PERMISSION)) {
                    sendPermissionDenied(source, "stop a server");
                    return;
                }
                if (args.length < 2) {
                    source.sendMessage(AutoStopperMessages.commandUsage(
                            "/autostopper stop <server>", "Stops an empty mapped backend server", isPlayer));
                    return;
                }
                String serverName = args[1];
                Optional<ServerMapping> mapping = config.snapshot().server(serverName);
                if (mapping.isEmpty()) {
                    source.sendMessage(AutoStopperMessages.unmappedServer(serverName));
                    return;
                }
                stopServer(source, mapping.get());
            }
            case "restart" -> {
                if (!hasAdministrativePermission(source, RESTART_PERMISSION)) {
                    sendPermissionDenied(source, "restart a server");
                    return;
                }
                if (args.length < 2) {
                    source.sendMessage(AutoStopperMessages.commandUsage(
                            "/autostopper restart <server>", "Restarts and readies an empty mapped backend server", isPlayer));
                    return;
                }
                String serverName = args[1];
                Optional<ServerMapping> mapping = config.snapshot().server(serverName);
                if (mapping.isEmpty()) {
                    source.sendMessage(AutoStopperMessages.unmappedServer(serverName));
                    return;
                }
                restartServer(source, mapping.get());
            }
            case "hold" -> {
                if (!hasAdministrativePermission(source, HOLD_PERMISSION)) {
                    sendPermissionDenied(source, "hold a server");
                    return;
                }
                if (args.length < 2) {
                    source.sendMessage(AutoStopperMessages.commandUsage(
                            "/autostopper hold <server>", "Suppresses automatic shutdown for a server", isPlayer));
                    return;
                }
                String serverName = args[1];
                Optional<ServerMapping> mapping = config.snapshot().server(serverName);
                if (mapping.isEmpty()) {
                    source.sendMessage(AutoStopperMessages.unmappedServer(serverName));
                    return;
                }
                holdServer(source, mapping.get());
            }
            case "release" -> {
                if (!hasAdministrativePermission(source, RELEASE_PERMISSION)) {
                    sendPermissionDenied(source, "release a server hold");
                    return;
                }
                if (args.length < 2) {
                    source.sendMessage(AutoStopperMessages.commandUsage(
                            "/autostopper release <server>", "Removes automatic shutdown suppression for a server", isPlayer));
                    return;
                }
                String serverName = args[1];
                Optional<ServerMapping> mapping = config.snapshot().server(serverName);
                if (mapping.isEmpty()) {
                    source.sendMessage(AutoStopperMessages.unmappedServer(serverName));
                    return;
                }
                releaseServer(source, mapping.get());
            }
            default -> {
                List<String> permitted = getPermittedSubcommands(source);
                Optional<String> closest = findClosestSubcommand(args[0], permitted);
                if (closest.isPresent()) {
                    source.sendMessage(AutoStopperMessages.unknownSubcommand(args[0], closest.get(), isPlayer));
                } else {
                    source.sendMessage(AutoStopperMessages.unknownSubcommandNoMatch(args[0], isPlayer));
                }
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
        if (hasAdministrativePermission(source, START_PERMISSION)) {
            source.sendMessage(AutoStopperMessages.helpEntry(
                    "/autostopper start <server>", "Starts and readies a mapped backend server", isPlayer));
        }
        if (hasAdministrativePermission(source, STOP_PERMISSION)) {
            source.sendMessage(AutoStopperMessages.helpEntry(
                    "/autostopper stop <server>", "Stops an empty mapped backend server", isPlayer));
        }
        if (hasAdministrativePermission(source, RESTART_PERMISSION)) {
            source.sendMessage(AutoStopperMessages.helpEntry(
                    "/autostopper restart <server>", "Restarts and readies an empty mapped backend server", isPlayer));
        }
        if (hasAdministrativePermission(source, HOLD_PERMISSION)) {
            source.sendMessage(AutoStopperMessages.helpEntry(
                    "/autostopper hold <server>", "Suppresses automatic shutdown for a server", isPlayer));
        }
        if (hasAdministrativePermission(source, RELEASE_PERMISSION)) {
            source.sendMessage(AutoStopperMessages.helpEntry(
                    "/autostopper release <server>", "Removes automatic shutdown suppression for a server", isPlayer));
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

    private void startServer(CommandSource source, ServerMapping mapping) {
        String serverName = mapping.serverName();
        source.sendMessage(AutoStopperMessages.manualStartStarting(serverName));
        long startNanos = System.nanoTime();
        lifecycleCoordinator.requestManualStart(mapping).whenComplete((outcome, error) -> {
            Duration elapsed = Duration.ofNanos(Math.max(0, System.nanoTime() - startNanos));
            if (error != null) {
                source.sendMessage(AutoStopperMessages.manualStartFailed(serverName,
                        AutoStopperMessages.startError(serverName), elapsed));
                return;
            }
            switch (outcome) {
                case ALREADY_READY -> source.sendMessage(AutoStopperMessages.manualStartAlreadyReady(serverName));
                case READY -> source.sendMessage(AutoStopperMessages.manualStartSucceeded(serverName, elapsed));
                case SERVER_STOPPING -> source.sendMessage(AutoStopperMessages.manualStartFailed(serverName,
                        AutoStopperMessages.serverStopping(serverName), elapsed));
                case MAPPING_CHANGED -> source.sendMessage(AutoStopperMessages.manualStartFailed(serverName,
                        AutoStopperMessages.mappingChanged(serverName), elapsed));
                case CONTAINER_MISSING -> source.sendMessage(AutoStopperMessages.manualStartFailed(serverName,
                        AutoStopperMessages.containerMissing(serverName), elapsed));
                case DOCKER_INACCESSIBLE -> source.sendMessage(AutoStopperMessages.manualStartFailed(serverName,
                        AutoStopperMessages.dockerUnavailable("start", serverName), elapsed));
                case STATUS_TIMED_OUT -> source.sendMessage(AutoStopperMessages.manualStartFailed(serverName,
                        AutoStopperMessages.statusCheckTimedOut(serverName), elapsed));
                case STATUS_FAILED -> source.sendMessage(AutoStopperMessages.manualStartFailed(serverName,
                        AutoStopperMessages.statusCheckFailed(serverName), elapsed));
                case START_TIMED_OUT -> source.sendMessage(AutoStopperMessages.manualStartFailed(serverName,
                        AutoStopperMessages.startTimedOut(serverName), elapsed));
                case START_FAILED -> source.sendMessage(AutoStopperMessages.manualStartFailed(serverName,
                        AutoStopperMessages.startFailed(serverName), elapsed));
                case SERVER_NOT_READY -> source.sendMessage(AutoStopperMessages.manualStartFailed(serverName,
                        AutoStopperMessages.serverNotReady(serverName), elapsed));
                case OVERLOADED -> source.sendMessage(AutoStopperMessages.manualStartFailed(serverName,
                        AutoStopperMessages.overloaded(), elapsed));
                case CANCELLED -> source.sendMessage(AutoStopperMessages.manualStartFailed(serverName,
                        AutoStopperMessages.startCancelled(serverName), elapsed));
                case PROXY_SHUTDOWN -> {}
            }
        });
    }

    private void stopServer(CommandSource source, ServerMapping mapping) {
        String serverName = mapping.serverName();
        source.sendMessage(AutoStopperMessages.manualStopStopping(serverName));
        long startNanos = System.nanoTime();
        lifecycleCoordinator.requestManualStop(mapping).whenComplete((outcome, error) -> {
            Duration elapsed = Duration.ofNanos(Math.max(0, System.nanoTime() - startNanos));
            if (error != null) {
                source.sendMessage(AutoStopperMessages.manualStopFailed(serverName,
                        AutoStopperMessages.stopFailed(serverName)));
                return;
            }
            switch (outcome) {
                case STOPPED -> {
                    activityTracker.removeActivity(serverName);
                    source.sendMessage(AutoStopperMessages.manualStopSucceeded(serverName, elapsed));
                }
                case ALREADY_STOPPED -> source.sendMessage(AutoStopperMessages.manualStopAlreadyStopped(serverName));
                case PLAYERS_CONNECTED -> {
                    int count = Math.max(1, lifecycleCoordinator.connectedPlayerCount(serverName));
                    source.sendMessage(AutoStopperMessages.manualStopRefusedPlayers(serverName, count));
                }
                case WAITERS_PRESENT -> source.sendMessage(AutoStopperMessages.manualStopRefusedWaiters(serverName));
                case SERVER_STARTING -> source.sendMessage(AutoStopperMessages.manualStopFailed(serverName,
                        AutoStopperMessages.serverAlreadyStarting()));
                case SERVER_STOPPING -> source.sendMessage(AutoStopperMessages.manualStopFailed(serverName,
                        AutoStopperMessages.serverStopping(serverName)));
                case MAPPING_CHANGED -> source.sendMessage(AutoStopperMessages.manualStopFailed(serverName,
                        AutoStopperMessages.mappingChanged(serverName)));
                case CONTAINER_MISSING -> source.sendMessage(AutoStopperMessages.manualStopFailed(serverName,
                        AutoStopperMessages.containerMissing(serverName)));
                case DOCKER_INACCESSIBLE -> source.sendMessage(AutoStopperMessages.manualStopFailed(serverName,
                        AutoStopperMessages.dockerUnavailable("stop", serverName)));
                case STOP_TIMED_OUT -> source.sendMessage(AutoStopperMessages.manualStopFailed(serverName,
                        AutoStopperMessages.stopTimedOut(serverName)));
                case STOP_FAILED -> source.sendMessage(AutoStopperMessages.manualStopFailed(serverName,
                        AutoStopperMessages.stopFailed(serverName)));
                case OVERLOADED -> source.sendMessage(AutoStopperMessages.manualStopFailed(serverName,
                        AutoStopperMessages.overloaded()));
                case CANCELLED -> source.sendMessage(AutoStopperMessages.manualStopFailed(serverName,
                        AutoStopperMessages.startCancelled(serverName)));
                case PROXY_SHUTDOWN -> {}
            }
        });
    }

    private void restartServer(CommandSource source, ServerMapping mapping) {
        String serverName = mapping.serverName();
        source.sendMessage(AutoStopperMessages.manualRestartRestarting(serverName));
        long startNanos = System.nanoTime();
        lifecycleCoordinator.requestManualRestart(mapping).whenComplete((outcome, error) -> {
            Duration elapsed = Duration.ofNanos(Math.max(0, System.nanoTime() - startNanos));
            if (error != null) {
                source.sendMessage(AutoStopperMessages.manualRestartFailed(serverName,
                        AutoStopperMessages.startError(serverName), elapsed));
                return;
            }
            switch (outcome) {
                case RESTARTED_AND_READY -> source.sendMessage(AutoStopperMessages.manualRestartSucceeded(serverName, elapsed));
                case PLAYERS_CONNECTED -> {
                    int count = Math.max(1, lifecycleCoordinator.connectedPlayerCount(serverName));
                    source.sendMessage(AutoStopperMessages.manualRestartRefusedPlayers(serverName, count));
                }
                case WAITERS_PRESENT -> source.sendMessage(AutoStopperMessages.manualRestartRefusedWaiters(serverName));
                case SERVER_STARTING -> source.sendMessage(AutoStopperMessages.manualRestartFailed(serverName,
                        AutoStopperMessages.serverAlreadyStarting(), elapsed));
                case SERVER_STOPPING -> source.sendMessage(AutoStopperMessages.manualRestartFailed(serverName,
                        AutoStopperMessages.serverStopping(serverName), elapsed));
                case MAPPING_CHANGED -> source.sendMessage(AutoStopperMessages.manualRestartFailed(serverName,
                        AutoStopperMessages.mappingChanged(serverName), elapsed));
                case CONTAINER_MISSING -> source.sendMessage(AutoStopperMessages.manualRestartFailed(serverName,
                        AutoStopperMessages.containerMissing(serverName), elapsed));
                case DOCKER_INACCESSIBLE -> source.sendMessage(AutoStopperMessages.manualRestartFailed(serverName,
                        AutoStopperMessages.dockerUnavailable("restart", serverName), elapsed));
                case STOP_TIMED_OUT -> source.sendMessage(AutoStopperMessages.manualRestartFailed(serverName,
                        AutoStopperMessages.stopTimedOut(serverName), elapsed));
                case STOP_FAILED -> source.sendMessage(AutoStopperMessages.manualRestartFailed(serverName,
                        AutoStopperMessages.stopFailed(serverName), elapsed));
                case START_TIMED_OUT -> source.sendMessage(AutoStopperMessages.manualRestartFailed(serverName,
                        AutoStopperMessages.startTimedOut(serverName), elapsed));
                case START_FAILED -> source.sendMessage(AutoStopperMessages.manualRestartFailed(serverName,
                        AutoStopperMessages.startFailed(serverName), elapsed));
                case SERVER_NOT_READY -> source.sendMessage(AutoStopperMessages.manualRestartFailed(serverName,
                        AutoStopperMessages.serverNotReady(serverName), elapsed));
                case OVERLOADED -> source.sendMessage(AutoStopperMessages.manualRestartFailed(serverName,
                        AutoStopperMessages.overloaded(), elapsed));
                case CANCELLED -> source.sendMessage(AutoStopperMessages.manualRestartFailed(serverName,
                        AutoStopperMessages.startCancelled(serverName), elapsed));
                case PROXY_SHUTDOWN -> {}
            }
        });
    }

    private void holdServer(CommandSource source, ServerMapping mapping) {
        String serverName = mapping.serverName();
        if (lifecycleCoordinator.hold(mapping)) {
            source.sendMessage(AutoStopperMessages.holdApplied(serverName));
        } else {
            source.sendMessage(AutoStopperMessages.holdAlreadyActive(serverName));
        }
    }

    private void releaseServer(CommandSource source, ServerMapping mapping) {
        String serverName = mapping.serverName();
        if (lifecycleCoordinator.release(serverName)) {
            activityTracker.updateActivity(serverName);
            source.sendMessage(AutoStopperMessages.holdReleased(serverName));
        } else {
            source.sendMessage(AutoStopperMessages.holdNotActive(serverName));
        }
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
            if (hasAdministrativePermission(source, STATUS_PERMISSION) && "status".startsWith(input)) {
                suggestions.add("status");
            }
            if (hasAdministrativePermission(source, RELOAD_PERMISSION) && "reload".startsWith(input)) {
                suggestions.add("reload");
            }
            if (hasAdministrativePermission(source, START_PERMISSION) && "start".startsWith(input)) {
                suggestions.add("start");
            }
            if (hasAdministrativePermission(source, STOP_PERMISSION) && "stop".startsWith(input)) {
                suggestions.add("stop");
            }
            if (hasAdministrativePermission(source, RESTART_PERMISSION) && "restart".startsWith(input)) {
                suggestions.add("restart");
            }
            if (hasAdministrativePermission(source, HOLD_PERMISSION) && "hold".startsWith(input)) {
                suggestions.add("hold");
            }
            if (hasAdministrativePermission(source, RELEASE_PERMISSION) && "release".startsWith(input)) {
                suggestions.add("release");
            }

            return suggestions;
        }

        // If user is typing the second argument (server name)
        if (args.length == 2) {
            String subcommand = args[0].toLowerCase();
            String input = args[1].toLowerCase();

            boolean isStart = subcommand.equals("start") && hasAdministrativePermission(source, START_PERMISSION);
            boolean isStop = subcommand.equals("stop") && hasAdministrativePermission(source, STOP_PERMISSION);
            boolean isRestart = subcommand.equals("restart") && hasAdministrativePermission(source, RESTART_PERMISSION);
            boolean isHold = subcommand.equals("hold") && hasAdministrativePermission(source, HOLD_PERMISSION);
            boolean isRelease = subcommand.equals("release") && hasAdministrativePermission(source, RELEASE_PERMISSION);

            if (isStart || isStop || isRestart || isHold || isRelease) {
                return config.snapshot().serverNames().stream()
                        .filter(name -> name.toLowerCase().startsWith(input))
                        .sorted()
                        .toList();
            }
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
        if (hasAdministrativePermission(source, START_PERMISSION)) {
            permitted.add("start");
        }
        if (hasAdministrativePermission(source, STOP_PERMISSION)) {
            permitted.add("stop");
        }
        if (hasAdministrativePermission(source, RESTART_PERMISSION)) {
            permitted.add("restart");
        }
        if (hasAdministrativePermission(source, HOLD_PERMISSION)) {
            permitted.add("hold");
        }
        if (hasAdministrativePermission(source, RELEASE_PERMISSION)) {
            permitted.add("release");
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
