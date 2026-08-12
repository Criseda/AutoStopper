package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import me.criseda.autostopper.AutoStopperPlugin;
import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.config.StopRetrySettings;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;

import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ActivityTracker {
    private final ProxyServer server;
    private final Logger logger;
    private final AutoStopperConfig config;
    private final ServerManager serverManager;
    private final AutoStopperExecutor executor;
    private final ServerLifecycleCoordinator lifecycleCoordinator;
    private final Map<String, ActivityState> activity = new ConcurrentHashMap<>();
    private final AutoStopperPlugin plugin;
    private final AtomicBoolean inactivityScanActive = new AtomicBoolean(false);
    private final Clock clock;

    public ActivityTracker(ProxyServer server, Logger logger, AutoStopperConfig config, ServerManager serverManager,
            AutoStopperExecutor executor, AutoStopperPlugin plugin,
            ServerLifecycleCoordinator lifecycleCoordinator) {
        this(server, logger, config, serverManager, executor, plugin, lifecycleCoordinator, Clock.systemUTC());
    }

    ActivityTracker(ProxyServer server, Logger logger, AutoStopperConfig config, ServerManager serverManager,
            AutoStopperExecutor executor, AutoStopperPlugin plugin,
            ServerLifecycleCoordinator lifecycleCoordinator, Clock clock) {
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.serverManager = serverManager;
        this.executor = executor;
        this.plugin = plugin;
        this.lifecycleCoordinator = lifecycleCoordinator;
        this.clock = clock;
        initializeActivityTracking();
    }

    private void initializeActivityTracking() {
        // Initialize all monitored servers with current time
        for (String serverName : config.snapshot().serverNames()) {
            activity.put(serverName, ActivityState.activeAt(clock.instant()));
            logger.info("Initialized activity tracking for server: " + serverName);
        }

        // Log the initial state
        logger.info("Initial server activity state:");
        for (Map.Entry<String, ActivityState> entry : activity.entrySet()) {
            logger.info("- " + entry.getKey() + ": " + entry.getValue().lastActivity());
        }
    }

    public void startInactivityCheck() {
        server.getScheduler().buildTask(plugin, this::requestInactivityCheck)
                .repeat(1, TimeUnit.MINUTES)
                .schedule();
    }

    CompletableFuture<Void> requestInactivityCheck() {
        if (!inactivityScanActive.compareAndSet(false, true)) {
            logger.debug("Skipping inactivity check because the previous scan is still running");
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> scan = executor.supply(() -> {
            runInactivityCheck();
            return null;
        });
        scan.whenComplete((ignored, error) -> {
            inactivityScanActive.set(false);
            if (error != null) {
                logger.warn("Inactivity check could not run: {}", error.toString());
            }
        });
        return scan;
    }

    private void runInactivityCheck() {
        logger.debug("Running inactivity check...");
        ConfigSnapshot snapshot = config.snapshot();
        for (ServerMapping mapping : snapshot.servers()) {
            server.getServer(mapping.serverName())
                    .ifPresent(registeredServer -> evaluateServer(snapshot, mapping, registeredServer));
        }
    }

    private void evaluateServer(ConfigSnapshot snapshot, ServerMapping mapping, RegisteredServer registeredServer) {
        String serverName = mapping.serverName();
        ActivityState activityAtScanStart = activity.get(serverName);
        // If players are connected, update the timestamp
        if (!registeredServer.getPlayersConnected().isEmpty()) {
            updateActivity(serverName);
            logger.debug("Players active on " + serverName + ", refreshing timestamp");
            return;
        }

        // If no players are connected, check if the server is actually running
        Optional<ContainerStatus> status = serverManager.getServerStatus(mapping);
        if (status.isEmpty()) {
            removeActivityIfUnchanged(serverName, activityAtScanStart);
            return;
        }

        switch (status.get()) {
            case STOPPED, MISSING:
                removeActivityIfUnchanged(serverName, activityAtScanStart);
                return;
            case INACCESSIBLE, TIMED_OUT, FAILED:
                logger.warn("Server {} status is {}; retaining activity and skipping inactivity shutdown",
                        serverName, status.get());
                return;
            case RUNNING:
                break;
        }

        // If it is running but not being tracked, start tracking it now
        Instant now = clock.instant();
        ActivityState observed = activity.computeIfAbsent(serverName, ignored -> ActivityState.activeAt(now));
        if (observed.nextStopAttemptAt() != null && now.isBefore(observed.nextStopAttemptAt())) {
            return;
        }

        Duration inactiveDuration = Duration.between(observed.lastActivity(), now);
        long minutesInactive = inactiveDuration.toMinutes();

        logger.debug(serverName + " has been inactive for " + minutesInactive + " minutes");

        boolean retryDue = observed.nextStopAttemptAt() != null;
        if (retryDue || inactiveDuration.getSeconds() > snapshot.inactivityTimeoutSeconds()) {
            if (!lifecycleCoordinator.tryBeginStop(mapping)) {
                logger.debug("Skipping inactivity shutdown for {} because lifecycle work is active", serverName);
                return;
            }
            if (!registeredServer.getPlayersConnected().isEmpty() || activity.get(serverName) != observed) {
                updateActivity(serverName);
                lifecycleCoordinator.cancelStop(mapping);
                logger.debug("Cancelled inactivity shutdown for {} because activity changed", serverName);
                return;
            }
            logger.info("Server {} has been inactive for {} minutes; stop attempt {} of {}",
                    serverName, minutesInactive, observed.failedStopAttempts() + 1,
                    snapshot.stopRetry().maxAttempts());
            ContainerStatus stopResult = ContainerStatus.FAILED;
            try {
                stopResult = serverManager.stopServer(mapping);
            } finally {
                lifecycleCoordinator.completeStop(mapping, stopResult);
            }
            if (stopResult == ContainerStatus.STOPPED) {
                activity.remove(serverName, observed);
            } else {
                retainForRetry(serverName, observed, stopResult, snapshot.stopRetry(), clock.instant());
            }
        }
    }

    private void retainForRetry(String serverName, ActivityState observed, ContainerStatus result,
            StopRetrySettings settings, Instant now) {
        int failedAttempt = observed.failedStopAttempts() + 1;
        if (failedAttempt >= settings.maxAttempts()) {
            activity.computeIfPresent(serverName, (ignored, current) -> current == observed
                    ? ActivityState.activeAt(now)
                    : current);
            logger.warn("Stop retries exhausted for server {} after {} attempts (last result: {}); "
                    + "a new retry cycle requires another inactivity period",
                    serverName, failedAttempt, result);
            return;
        }
        Duration backoff = settings.backoffAfterFailure(failedAttempt);
        Instant retryAt = now.plus(backoff);
        activity.computeIfPresent(serverName, (ignored, current) -> current == observed
                ? new ActivityState(current.lastActivity(), failedAttempt, retryAt)
                : current);
        logger.warn("Stop attempt {} of {} failed for server {} with {}; retrying in {} seconds",
                failedAttempt, settings.maxAttempts(), serverName, result, backoff.toSeconds());
    }

    public void updateActivity(String serverName) {
        if (config.snapshot().containsServer(serverName)) {
            activity.put(serverName, ActivityState.activeAt(clock.instant()));
        }
    }

    public void reconcileConfig(ConfigSnapshot previous, ConfigSnapshot current) {
        Set<String> currentNames = new HashSet<>(current.serverNames());
        activity.keySet().removeIf(serverName -> !currentNames.contains(serverName));

        Instant now = clock.instant();
        for (ServerMapping mapping : current.servers()) {
            if (previous.server(mapping.serverName()).filter(mapping::equals).isEmpty()) {
                activity.put(mapping.serverName(), ActivityState.activeAt(now));
            }
        }
    }

    public void removeActivity(String serverName) {
        activity.remove(serverName);
    }

    private void removeActivityIfUnchanged(String serverName, ActivityState observed) {
        if (observed != null) {
            activity.remove(serverName, observed);
        }
    }

    public Instant getLastActivity(String serverName) {
        ActivityState state = activity.get(serverName);
        return state == null ? null : state.lastActivity();
    }

    public long getMinutesSinceActivity(String serverName) {
        ActivityState state = activity.get(serverName);
        if (state == null) {
            return 0;
        }

        return Duration.between(state.lastActivity(), clock.instant()).toMinutes();
    }

    public Map<String, Instant> getAllActivity() {
        Map<String, Instant> snapshot = new HashMap<>();
        activity.forEach((serverName, state) -> snapshot.put(serverName, state.lastActivity()));
        return snapshot;
    }

    void setLastActivityForTest(String serverName, Instant lastActivity) {
        activity.put(serverName, ActivityState.activeAt(lastActivity));
    }

    int getFailedStopAttemptsForTest(String serverName) {
        ActivityState state = activity.get(serverName);
        return state == null ? 0 : state.failedStopAttempts();
    }

    private record ActivityState(Instant lastActivity, int failedStopAttempts, Instant nextStopAttemptAt) {
        private static ActivityState activeAt(Instant instant) {
            return new ActivityState(instant, 0, null);
        }
    }
}
