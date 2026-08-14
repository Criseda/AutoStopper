package me.criseda.autostopper.operational;

import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerInspection;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.lifecycle.LifecycleStatusSnapshot;
import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;
import me.criseda.autostopper.lifecycle.ServerLifecycleState;
import me.criseda.autostopper.server.ServerManager;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs preflight and composes Docker observations with the lifecycle state machine. */
public final class OperationalStatusService {
    private final Logger logger;
    private final ServerManager serverManager;
    private final ServerLifecycleCoordinator lifecycleCoordinator;
    private final Clock clock;
    private final Map<String, OperationalFailure> inspectionFailures = new ConcurrentHashMap<>();
    private final AtomicLong preflightGeneration = new AtomicLong();
    private final AtomicLong configurationSequence = new AtomicLong();
    private final Set<CompletableFuture<?>> outstandingChecks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final Object configurationLock = new Object();
    private volatile ConfigurationView configuration = ConfigurationView.empty();

    public OperationalStatusService(Logger logger, ServerManager serverManager,
            ServerLifecycleCoordinator lifecycleCoordinator) {
        this(logger, serverManager, lifecycleCoordinator, Clock.systemUTC());
    }

    OperationalStatusService(Logger logger, ServerManager serverManager,
            ServerLifecycleCoordinator lifecycleCoordinator, Clock clock) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.serverManager = Objects.requireNonNull(serverManager, "serverManager");
        this.lifecycleCoordinator = Objects.requireNonNull(lifecycleCoordinator, "lifecycleCoordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletableFuture<PreflightSummary> runPreflight(ConfigSnapshot snapshot, String context) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(context, "context");
        if (shutdown.get()) {
            return CompletableFuture.completedFuture(
                    new PreflightSummary(0, snapshot.servers().size()));
        }
        reconcileConfig(snapshot);
        ConfigurationView expectedConfiguration = configuration;
        Map<String, LifecycleStatusSnapshot> lifecycleAtStart = captureLifecycle(snapshot);
        long generation = preflightGeneration.incrementAndGet();
        CompletableFuture<Map<String, ContainerInspection>> future;
        try {
            future = Objects.requireNonNull(serverManager.inspectContainersAsync(snapshot),
                    "inspectContainersAsync returned null");
            track(future);
        } catch (RuntimeException error) {
            recordCollectionFailure(snapshot, context);
            logCollectionFailure(context, error);
            return CompletableFuture.completedFuture(
                    new PreflightSummary(0, snapshot.servers().size()));
        }
        return future.thenApply(inspections -> {
            if (preflightGeneration.get() != generation) {
                return summarize(snapshot, inspections);
            }
            applyObservations(snapshot, inspections, context,
                    expectedConfiguration, lifecycleAtStart);
            PreflightSummary summary = summarize(snapshot, inspections);
            logSummary(context, snapshot, inspections, summary);
            return summary;
        }).exceptionally(error -> {
            if (shutdown.get()) {
                return new PreflightSummary(0, snapshot.servers().size());
            }
            recordCollectionFailure(snapshot, context);
            logCollectionFailure(context, error);
            return new PreflightSummary(0, snapshot.servers().size());
        });
    }

    public CompletableFuture<Map<String, OperationalServerStatus>> collectStatuses(ConfigSnapshot snapshot) {
        if (shutdown.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("operational status service is shut down"));
        }
        ConfigurationView expectedConfiguration = configuration;
        if (!expectedConfiguration.matches(snapshot)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("configuration changed before status collection"));
        }
        Map<String, LifecycleStatusSnapshot> lifecycleAtStart = captureLifecycle(snapshot);
        CompletableFuture<Map<String, ContainerInspection>> inspectionsFuture;
        try {
            inspectionsFuture = Objects.requireNonNull(serverManager.inspectContainersAsync(snapshot),
                    "inspectContainersAsync returned null");
            track(inspectionsFuture);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
        return inspectionsFuture.thenApply(inspections -> {
            if (configuration != expectedConfiguration) {
                throw new IllegalStateException("configuration changed during status collection");
            }
            Map<String, LifecycleStatusSnapshot> acceptedObservations =
                    applyObservations(snapshot, inspections, "status check",
                            expectedConfiguration, lifecycleAtStart);
            Map<String, OperationalServerStatus> result = new LinkedHashMap<>();
            for (ServerMapping mapping : snapshot.servers()) {
                ContainerInspection inspection = inspections.get(mapping.serverName());
                result.put(mapping.serverName(), compose(mapping, inspection,
                        acceptedObservations.get(mapping.serverName())));
            }
            return result;
        });
    }

    public void reconcileConfig(ConfigSnapshot current) {
        Map<String, ServerMapping> mappings = mappings(current);
        synchronized (configurationLock) {
            ConfigurationView previous = configuration;
            if (!previous.mappings().equals(mappings)) {
                configuration = new ConfigurationView(
                        configurationSequence.incrementAndGet(), mappings);
            }
            inspectionFailures.keySet().removeIf(serverName ->
                    !Objects.equals(previous.mappings().get(serverName), mappings.get(serverName)));
        }
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        preflightGeneration.incrementAndGet();
        configuration = ConfigurationView.empty();
        inspectionFailures.clear();
        for (CompletableFuture<?> check : List.copyOf(outstandingChecks)) {
            check.cancel(true);
        }
        outstandingChecks.clear();
    }

    private OperationalServerStatus compose(ServerMapping mapping, ContainerInspection inspection,
            LifecycleStatusSnapshot acceptedObservation) {
        String serverName = mapping.serverName();
        LifecycleStatusSnapshot lifecycle = lifecycleCoordinator.statusSnapshot(mapping);
        boolean observationCurrent = acceptedObservation != null
                && acceptedObservation.revision() == lifecycle.revision();
        OperationalState state = determineState(lifecycle.state(), inspection, observationCurrent);
        Optional<OperationalFailure> latest;
        if (observationCurrent) {
            Optional<OperationalFailure> inspectionFailure =
                    Optional.ofNullable(inspectionFailures.get(serverName));
            latest = latest(inspectionFailure, lifecycle.lastFailure());
        } else if (lifecycle.state().isPresent()) {
            latest = lifecycle.lastFailure();
        } else {
            latest = Optional.of(new OperationalFailure(clock.instant(), "status check",
                    "lifecycle changed while Docker status was collected",
                    "Retry the status command to collect a current observation."));
        }
        return new OperationalServerStatus(state, lifecycle.waitingPlayers(), latest);
    }

    private OperationalState determineState(Optional<ServerLifecycleState> lifecycle,
            ContainerInspection inspection, boolean observationCurrent) {
        if (lifecycle.filter(state -> state == ServerLifecycleState.STARTING
                || state == ServerLifecycleState.STOPPING).isPresent()) {
            return OperationalState.valueOf(lifecycle.orElseThrow().name());
        }
        if (!observationCurrent) {
            return lifecycle.map(state -> OperationalState.valueOf(state.name()))
                    .orElse(OperationalState.FAILED);
        }
        if (inspection != null && inspection.diagnostic().dockerUnavailable()) {
            return OperationalState.DOCKER_UNAVAILABLE;
        }
        if (inspection == null) {
            return OperationalState.FAILED;
        }
        if (!inspection.healthy()) {
            return OperationalState.FAILED;
        }
        if (inspection.status() == ContainerStatus.STOPPED) {
            return OperationalState.STOPPED;
        }
        if (inspection.status() == ContainerStatus.RUNNING) {
            if (lifecycle.isPresent()) {
                ServerLifecycleState lcState = lifecycle.get();
                if (lcState == ServerLifecycleState.READY) {
                    return OperationalState.READY;
                }
                if (lcState == ServerLifecycleState.FAILED) {
                    return OperationalState.FAILED;
                }
            }
            return OperationalState.RUNNING_UNVERIFIED;
        }
        return OperationalState.FAILED;
    }

    private Map<String, LifecycleStatusSnapshot> applyObservations(ConfigSnapshot snapshot,
            Map<String, ContainerInspection> inspections, String context,
            ConfigurationView expectedConfiguration,
            Map<String, LifecycleStatusSnapshot> lifecycleAtStart) {
        if (configuration != expectedConfiguration || !expectedConfiguration.matches(snapshot)) {
            return Map.of();
        }
        Instant observedAt = clock.instant();
        Map<String, LifecycleStatusSnapshot> accepted = new LinkedHashMap<>();
        for (ServerMapping mapping : snapshot.servers()) {
            if (configuration != expectedConfiguration) {
                break;
            }
            String serverName = mapping.serverName();
            LifecycleStatusSnapshot baseline = lifecycleAtStart.get(serverName);
            LifecycleStatusSnapshot current = lifecycleCoordinator.statusSnapshot(mapping);
            if (baseline == null || current.revision() != baseline.revision()) {
                continue;
            }
            ContainerInspection inspection = inspections.get(serverName);
            if (inspection == null) {
                inspectionFailures.put(serverName, new OperationalFailure(observedAt, context,
                        "container check produced no result", "Retry the check and review proxy logs."));
            } else if (inspection.healthy()) {
                inspectionFailures.remove(serverName);
                if (inspection.status() == ContainerStatus.STOPPED) {
                    lifecycleCoordinator.markStoppedIfUnchanged(mapping, baseline.revision())
                            .ifPresent(result -> accepted.put(serverName, result));
                    continue;
                }
            } else {
                inspectionFailures.put(serverName, new OperationalFailure(observedAt, context,
                        inspection.detail(), inspection.remediation()));
            }
            LifecycleStatusSnapshot after = lifecycleCoordinator.statusSnapshot(mapping);
            if (after.revision() == baseline.revision()) {
                accepted.put(serverName, after);
            }
        }
        return Map.copyOf(accepted);
    }

    private PreflightSummary summarize(ConfigSnapshot snapshot,
            Map<String, ContainerInspection> inspections) {
        int healthy = (int) inspections.values().stream().filter(ContainerInspection::healthy).count();
        return new PreflightSummary(healthy, snapshot.servers().size() - healthy);
    }

    private void recordCollectionFailure(ConfigSnapshot snapshot, String context) {
        if (!configuration.matches(snapshot)) {
            return;
        }
        Instant observedAt = clock.instant();
        for (String serverName : snapshot.serverNames()) {
            inspectionFailures.put(serverName, new OperationalFailure(observedAt, context,
                    "container checks could not be scheduled or completed",
                    "Wait for active operations to finish, retry, and review proxy logs."));
        }
    }

    private void logSummary(String context, ConfigSnapshot snapshot,
            Map<String, ContainerInspection> inspections, PreflightSummary summary) {
        if (summary.healthy()) {
            logger.info("AutoStopper {} preflight healthy: {} mapping(s) checked.",
                    context, summary.healthyMappings());
            return;
        }
        logger.warn("AutoStopper {} preflight degraded: {} healthy, {} degraded mapping(s).",
                context, summary.healthyMappings(), summary.degradedMappings());
        for (String serverName : snapshot.serverNames()) {
            ContainerInspection inspection = inspections.get(serverName);
            if (inspection != null && !inspection.healthy()) {
                logger.warn("Preflight {} (container {}): {} Remediation: {}",
                        serverName, snapshot.server(serverName).orElseThrow().containerName(),
                        inspection.detail(), inspection.remediation());
            }
        }
    }

    private Optional<OperationalFailure> latest(Optional<OperationalFailure> left,
            Optional<OperationalFailure> right) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left.get().timestamp().isAfter(right.get().timestamp()) ? left : right;
    }

    private String safeException(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private void logCollectionFailure(String context, Throwable error) {
        logger.warn("AutoStopper {} preflight could not complete: {}. Remediation: wait for active "
                        + "operations, verify Docker access, and retry.",
                context, safeException(error));
    }

    private void track(CompletableFuture<?> check) {
        outstandingChecks.add(check);
        check.whenComplete((ignored, error) -> outstandingChecks.remove(check));
        if (shutdown.get() && outstandingChecks.remove(check)) {
            check.cancel(true);
        }
    }

    private Map<String, LifecycleStatusSnapshot> captureLifecycle(ConfigSnapshot snapshot) {
        Map<String, LifecycleStatusSnapshot> result = new LinkedHashMap<>();
        for (ServerMapping mapping : snapshot.servers()) {
            result.put(mapping.serverName(), lifecycleCoordinator.statusSnapshot(mapping));
        }
        return Map.copyOf(result);
    }

    private static Map<String, ServerMapping> mappings(ConfigSnapshot snapshot) {
        Map<String, ServerMapping> result = new LinkedHashMap<>();
        for (ServerMapping mapping : snapshot.servers()) {
            result.put(mapping.serverName(), mapping);
        }
        return Map.copyOf(result);
    }

    private record ConfigurationView(long generation, Map<String, ServerMapping> mappings) {
        private static ConfigurationView empty() {
            return new ConfigurationView(0, Map.of());
        }

        private boolean matches(ConfigSnapshot snapshot) {
            return mappings.equals(OperationalStatusService.mappings(snapshot));
        }
    }
}
