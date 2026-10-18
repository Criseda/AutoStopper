package me.criseda.autostopper.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigLoadResult;
import me.criseda.autostopper.config.ConfigProvider;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.messages.AutoStopperMessages;
import me.criseda.autostopper.lifecycle.ManualRestartOutcome;
import me.criseda.autostopper.lifecycle.ManualStartOutcome;
import me.criseda.autostopper.lifecycle.ManualStopOutcome;
import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;
import me.criseda.autostopper.messages.OperatorFailureGuide;
import me.criseda.autostopper.operational.OperationalServerStatus;
import me.criseda.autostopper.operational.OperationalState;
import me.criseda.autostopper.operational.OperationalStatusService;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ActivityTrackerService;
import net.kyori.adventure.text.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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

    private final ConfigProvider config;
    private final ActivityTrackerService activityTracker;
    private final ServerLifecycleCoordinator lifecycleCoordinator;
    private final OperationalStatusService operationalStatus;
    private final PluginContainer pluginContainer;
    private final Clock clock;

    public AutoStopperCommand(ConfigProvider config,
            ActivityTrackerService activityTracker,
            ServerLifecycleCoordinator lifecycleCoordinator, OperationalStatusService operationalStatus,
            PluginContainer pluginContainer) {
        this(config, activityTracker, lifecycleCoordinator, operationalStatus, pluginContainer, Clock.systemUTC());
    }

    AutoStopperCommand(ConfigProvider config,
            ActivityTrackerService activityTracker,
            ServerLifecycleCoordinator lifecycleCoordinator, OperationalStatusService operationalStatus,
            PluginContainer pluginContainer, Clock clock) {
        this.config = config;
        this.activityTracker = activityTracker;
        this.lifecycleCoordinator = lifecycleCoordinator;
        this.operationalStatus = operationalStatus;
        this.pluginContainer = pluginContainer;
        this.clock = clock;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        boolean isPlayer = source instanceof Player;

        if (args.length == 0) {
            String version = pluginVersion();
            source.sendMessage(AutoStopperMessages.pluginHeader(version, isPlayer));
            source.sendMessage(AutoStopperMessages.pluginTagline());
            source.sendMessage(AutoStopperMessages.projectLinks(version, isPlayer));
            source.sendMessage(AutoStopperMessages.helpHint(isPlayer));
            return;
        }

        Optional<AutoStopperSubcommand> match = AutoStopperSubcommand.byLabel(args[0]);
        if (match.isEmpty()) {
            List<String> permitted = getPermittedSubcommands(source);
            Optional<String> closest = findClosestSubcommand(args[0], permitted);
            if (closest.isPresent()) {
                source.sendMessage(AutoStopperMessages.unknownSubcommand(args[0], closest.get(), isPlayer));
            } else {
                source.sendMessage(AutoStopperMessages.unknownSubcommandNoMatch(args[0], isPlayer));
            }
            return;
        }

        AutoStopperSubcommand subcommand = match.get();
        if (!isPermitted(source, subcommand)) {
            sendPermissionDenied(source, subcommand.deniedAction());
            return;
        }
        if (subcommand.argument() == AutoStopperSubcommand.Argument.NONE
                || (subcommand.argument() == AutoStopperSubcommand.Argument.OPTIONAL && args.length < 2)) {
            switch (subcommand) {
                case HELP -> showHelp(source, isPlayer);
                case STATUS -> showStatus(source);
                case RELOAD -> reloadConfig(source);
                default -> throw new IllegalStateException("Unhandled subcommand " + subcommand);
            }
            return;
        }

        if (args.length < 2) {
            source.sendMessage(AutoStopperMessages.commandUsage(
                    subcommand.usage(), subcommand.description(), isPlayer));
            return;
        }
        String serverName = args[1];
        Optional<ServerMapping> mapping = config.snapshot().server(serverName);
        if (mapping.isEmpty()) {
            source.sendMessage(AutoStopperMessages.unmappedServer(serverName));
            return;
        }
        switch (subcommand) {
            case STATUS -> showServerStatus(source, mapping.get());
            case START -> startServer(source, mapping.get());
            case STOP -> stopServer(source, mapping.get());
            case RESTART -> restartServer(source, mapping.get());
            case HOLD -> holdServer(source, mapping.get());
            case RELEASE -> releaseServer(source, mapping.get());
            default -> throw new IllegalStateException("Unhandled subcommand " + subcommand);
        }
    }

    private void showHelp(CommandSource source, boolean isPlayer) {
        source.sendMessage(AutoStopperMessages.helpHeader());
        List<AutoStopperSubcommand> listed = Arrays.stream(AutoStopperSubcommand.values())
                .filter(subcommand -> subcommand.group() != null && isPermitted(source, subcommand))
                .toList();
        if (listed.isEmpty()) {
            source.sendMessage(AutoStopperMessages.helpNoOperatorCommands());
            source.sendMessage(AutoStopperMessages.projectLinks(pluginVersion(), isPlayer));
            return;
        }
        AutoStopperSubcommand.Group currentGroup = null;
        for (AutoStopperSubcommand subcommand : listed) {
            if (subcommand.group() != currentGroup) {
                currentGroup = subcommand.group();
                source.sendMessage(AutoStopperMessages.helpGroup(currentGroup.label()));
            }
            source.sendMessage(AutoStopperMessages.helpEntry(subcommand.usage(), subcommand.description(),
                    subcommand.permission(), ADMIN_PERMISSION, isPlayer));
        }
        source.sendMessage(AutoStopperMessages.operatorHelpFooter(pluginVersion(), isPlayer));
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
            boolean anyFailure = false;
            for (String serverName : serverNames) {
                OperationalServerStatus status = statuses.get(serverName);
                sendServerStatus(source, serverName, status);
                anyFailure |= hasFailure(status);
            }
            if (anyFailure) {
                source.sendMessage(AutoStopperMessages.operatorHelpFooter(pluginVersion(), source instanceof Player));
            }
        });
    }

    private void showServerStatus(CommandSource source, ServerMapping mapping) {
        String serverName = mapping.serverName();
        source.sendMessage(AutoStopperMessages.statusDetailHeader(serverName));
        operationalStatus.collectStatus(config.snapshot(), serverName).whenComplete((status, error) -> {
            if (error != null || status == null) {
                source.sendMessage(AutoStopperMessages.statusDetailCollectionFailed(serverName));
                return;
            }
            Instant now = clock.instant();
            boolean isPlayer = source instanceof Player;
            for (Component line : AutoStopperMessages.statusDetail(status, minutesSinceActivity(serverName),
                    now, isPlayer)) {
                source.sendMessage(line);
            }
            for (Component line : AutoStopperMessages.statusDetailHistory(
                    lifecycleCoordinator.recentOperations(serverName), now, isPlayer)) {
                source.sendMessage(line);
            }
            if (hasFailure(status)) {
                source.sendMessage(AutoStopperMessages.operatorHelpFooter(pluginVersion(), source instanceof Player));
            }
        });
    }

    private static boolean hasFailure(OperationalServerStatus status) {
        return status != null && (status.lastFailure().isPresent()
                || status.state() == OperationalState.FAILED
                || status.state() == OperationalState.DOCKER_UNAVAILABLE);
    }

    private void sendServerStatus(CommandSource source, String serverName,
            OperationalServerStatus status) {
        source.sendMessage(AutoStopperMessages.operationalStatus(serverName, status,
                minutesSinceActivity(serverName), clock.instant(), source instanceof Player));
    }

    private Long minutesSinceActivity(String serverName) {
        return activityTracker.getLastActivity(serverName) == null
                ? null : activityTracker.getMinutesSinceActivity(serverName);
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
        String retryCommand = "/autostopper start " + serverName;
        source.sendMessage(AutoStopperMessages.manualStartStarting(serverName));
        long startNanos = System.nanoTime();
        lifecycleCoordinator.requestManualStart(mapping).whenComplete((outcome, error) -> {
            Duration elapsed = Duration.ofNanos(Math.max(0, System.nanoTime() - startNanos));
            if (error != null) {
                sendFailure(source, AutoStopperMessages.startError(serverName),
                        OperatorFailureGuide.START_FAILED, retryCommand, elapsed);
                return;
            }
            switch (outcome) {
                case ALREADY_READY -> source.sendMessage(AutoStopperMessages.manualStartAlreadyReady(serverName));
                case READY -> source.sendMessage(AutoStopperMessages.manualStartSucceeded(serverName, elapsed));
                case PROXY_SHUTDOWN -> {}
                default -> sendFailure(source, startFailureReason(serverName, outcome),
                        OperatorFailureGuide.of(outcome), retryCommand, elapsed);
            }
        });
    }

    private static Component startFailureReason(String serverName, ManualStartOutcome outcome) {
        return switch (outcome) {
            case SERVER_STOPPING -> AutoStopperMessages.serverStopping(serverName);
            case MAPPING_CHANGED -> AutoStopperMessages.mappingChanged(serverName);
            case CONTAINER_MISSING -> AutoStopperMessages.containerMissing(serverName);
            case DOCKER_INACCESSIBLE -> AutoStopperMessages.dockerUnavailable("start", serverName);
            case STATUS_TIMED_OUT -> AutoStopperMessages.statusCheckTimedOut(serverName);
            case STATUS_FAILED -> AutoStopperMessages.statusCheckFailed(serverName);
            case START_TIMED_OUT -> AutoStopperMessages.startTimedOut(serverName);
            case SERVER_NOT_READY -> AutoStopperMessages.serverNotReady(serverName);
            case OVERLOADED -> AutoStopperMessages.overloaded();
            case CANCELLED -> AutoStopperMessages.startCancelled(serverName);
            case START_FAILED, ALREADY_READY, READY, PROXY_SHUTDOWN -> AutoStopperMessages.startFailed(serverName);
        };
    }

    private void stopServer(CommandSource source, ServerMapping mapping) {
        String serverName = mapping.serverName();
        String retryCommand = "/autostopper stop " + serverName;
        source.sendMessage(AutoStopperMessages.manualStopStopping(serverName));
        long startNanos = System.nanoTime();
        lifecycleCoordinator.requestManualStop(mapping).whenComplete((outcome, error) -> {
            Duration elapsed = Duration.ofNanos(Math.max(0, System.nanoTime() - startNanos));
            if (error != null) {
                sendFailure(source, AutoStopperMessages.stopFailed(serverName),
                        OperatorFailureGuide.STOP_FAILED, retryCommand, null);
                return;
            }
            switch (outcome) {
                case STOPPED -> {
                    activityTracker.removeActivity(serverName);
                    source.sendMessage(AutoStopperMessages.manualStopSucceeded(serverName, elapsed));
                }
                case ALREADY_STOPPED -> source.sendMessage(AutoStopperMessages.manualStopAlreadyStopped(serverName));
                case PROXY_SHUTDOWN -> {}
                default -> sendFailure(source, stopFailureReason(serverName, outcome),
                        OperatorFailureGuide.of(outcome), retryCommand, null);
            }
        });
    }

    private Component stopFailureReason(String serverName, ManualStopOutcome outcome) {
        return switch (outcome) {
            case PLAYERS_CONNECTED -> AutoStopperMessages.manualStopRefusedPlayers(serverName,
                    Math.max(1, lifecycleCoordinator.connectedPlayerCount(serverName)));
            case WAITERS_PRESENT -> AutoStopperMessages.manualStopRefusedWaiters(serverName);
            case SERVER_STARTING -> AutoStopperMessages.serverAlreadyStarting();
            case SERVER_STOPPING -> AutoStopperMessages.serverStopping(serverName);
            case MAPPING_CHANGED -> AutoStopperMessages.mappingChanged(serverName);
            case CONTAINER_MISSING -> AutoStopperMessages.containerMissing(serverName);
            case DOCKER_INACCESSIBLE -> AutoStopperMessages.dockerUnavailable("stop", serverName);
            case STOP_TIMED_OUT -> AutoStopperMessages.stopTimedOut(serverName);
            case OVERLOADED -> AutoStopperMessages.overloaded();
            case CANCELLED -> AutoStopperMessages.stopCancelled(serverName);
            case STOP_FAILED, STOPPED, ALREADY_STOPPED, PROXY_SHUTDOWN -> AutoStopperMessages.stopFailed(serverName);
        };
    }

    private void restartServer(CommandSource source, ServerMapping mapping) {
        String serverName = mapping.serverName();
        String retryCommand = "/autostopper restart " + serverName;
        source.sendMessage(AutoStopperMessages.manualRestartRestarting(serverName));
        long startNanos = System.nanoTime();
        lifecycleCoordinator.requestManualRestart(mapping).whenComplete((outcome, error) -> {
            Duration elapsed = Duration.ofNanos(Math.max(0, System.nanoTime() - startNanos));
            if (error != null) {
                sendFailure(source, AutoStopperMessages.startError(serverName),
                        OperatorFailureGuide.START_FAILED, retryCommand, elapsed);
                return;
            }
            switch (outcome) {
                case RESTARTED_AND_READY -> source.sendMessage(AutoStopperMessages.manualRestartSucceeded(serverName, elapsed));
                case PROXY_SHUTDOWN -> {}
                default -> sendFailure(source, restartFailureReason(serverName, outcome),
                        OperatorFailureGuide.of(outcome), retryCommand, elapsed);
            }
        });
    }

    private Component restartFailureReason(String serverName, ManualRestartOutcome outcome) {
        return switch (outcome) {
            case PLAYERS_CONNECTED -> AutoStopperMessages.manualRestartRefusedPlayers(serverName,
                    Math.max(1, lifecycleCoordinator.connectedPlayerCount(serverName)));
            case WAITERS_PRESENT -> AutoStopperMessages.manualRestartRefusedWaiters(serverName);
            case SERVER_STARTING -> AutoStopperMessages.serverAlreadyStarting();
            case SERVER_STOPPING -> AutoStopperMessages.serverStopping(serverName);
            case MAPPING_CHANGED -> AutoStopperMessages.mappingChanged(serverName);
            case CONTAINER_MISSING -> AutoStopperMessages.containerMissing(serverName);
            case DOCKER_INACCESSIBLE -> AutoStopperMessages.dockerUnavailable("restart", serverName);
            case STOP_TIMED_OUT -> AutoStopperMessages.stopTimedOut(serverName);
            case STOP_FAILED -> AutoStopperMessages.stopFailed(serverName);
            case START_TIMED_OUT -> AutoStopperMessages.startTimedOut(serverName);
            case SERVER_NOT_READY -> AutoStopperMessages.serverNotReady(serverName);
            case OVERLOADED -> AutoStopperMessages.overloaded();
            case CANCELLED -> AutoStopperMessages.startCancelled(serverName);
            case START_FAILED, RESTARTED_AND_READY, PROXY_SHUTDOWN -> AutoStopperMessages.startFailed(serverName);
        };
    }

    private void sendFailure(CommandSource source, Component reason, OperatorFailureGuide guide,
            String retryCommand, Duration elapsed) {
        source.sendMessage(AutoStopperMessages.operatorFailure(reason, guide, retryCommand, elapsed,
                pluginVersion(), source instanceof Player));
    }

    private String pluginVersion() {
        return pluginContainer.getDescription().getVersion().orElse("Unknown");
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
            String input = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            return Arrays.stream(AutoStopperSubcommand.values())
                    .filter(subcommand -> subcommand.label().startsWith(input) && isPermitted(source, subcommand))
                    .map(AutoStopperSubcommand::label)
                    .toList();
        }

        // If user is typing the second argument (server name)
        if (args.length == 2) {
            Optional<AutoStopperSubcommand> subcommand = AutoStopperSubcommand.byLabel(args[0]);
            String input = args[1].toLowerCase(Locale.ROOT);
            if (subcommand.isPresent() && subcommand.get().acceptsServer() && isPermitted(source, subcommand.get())) {
                return config.snapshot().serverNames().stream()
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input))
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
        return Arrays.stream(AutoStopperSubcommand.values())
                .filter(subcommand -> isPermitted(source, subcommand))
                .map(AutoStopperSubcommand::label)
                .toList();
    }

    private boolean isPermitted(CommandSource source, AutoStopperSubcommand subcommand) {
        return subcommand.isPublic() || hasAdministrativePermission(source, subcommand.permission());
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
