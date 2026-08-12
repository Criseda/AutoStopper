package me.criseda.autostopper.operational;

import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.docker.ContainerInspection;
import me.criseda.autostopper.docker.ContainerStatus;
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
    private final Set<CompletableFuture<?>> outstandingChecks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private volatile Set<String> configuredServers = Set.of();

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
            applyObservations(snapshot, inspections, context);
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
        CompletableFuture<Map<String, ContainerInspection>> inspectionsFuture;
        try {
            inspectionsFuture = Objects.requireNonNull(serverManager.inspectContainersAsync(snapshot),
                    "inspectContainersAsync returned null");
            track(inspectionsFuture);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
        return inspectionsFuture.thenApply(inspections -> {
            applyObservations(snapshot, inspections, "status check");
            Map<String, OperationalServerStatus> result = new LinkedHashMap<>();
            for (String serverName : snapshot.serverNames()) {
                ContainerInspection inspection = inspections.get(serverName);
                result.put(serverName, compose(serverName, inspection));
            }
            return result;
        });
    }

    public void reconcileConfig(ConfigSnapshot current) {
        Set<String> retained = Set.copyOf(current.serverNames());
        configuredServers = retained;
        inspectionFailures.keySet().removeIf(serverName -> !retained.contains(serverName));
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        preflightGeneration.incrementAndGet();
        configuredServers = Set.of();
        inspectionFailures.clear();
        for (CompletableFuture<?> check : List.copyOf(outstandingChecks)) {
            check.cancel(true);
        }
        outstandingChecks.clear();
    }

    private OperationalServerStatus compose(String serverName, ContainerInspection inspection) {
        Optional<ServerLifecycleState> lifecycle = lifecycleCoordinator.state(serverName);
        OperationalState state = determineState(lifecycle, inspection);
        Optional<OperationalFailure> inspectionFailure =
                Optional.ofNullable(inspectionFailures.get(serverName));
        Optional<OperationalFailure> lifecycleFailure = lifecycleCoordinator.lastFailure(serverName);
        Optional<OperationalFailure> latest = latest(inspectionFailure, lifecycleFailure);
        return new OperationalServerStatus(state, lifecycleCoordinator.waitingCount(serverName), latest);
    }

    private OperationalState determineState(Optional<ServerLifecycleState> lifecycle,
            ContainerInspection inspection) {
        if (lifecycle.filter(state -> state == ServerLifecycleState.STARTING
                || state == ServerLifecycleState.STOPPING).isPresent()) {
            return OperationalState.valueOf(lifecycle.orElseThrow().name());
        }
        if (inspection != null && inspection.diagnostic().dockerUnavailable()) {
            return OperationalState.DOCKER_UNAVAILABLE;
        }
        if (lifecycle.isPresent()) {
            return OperationalState.valueOf(lifecycle.get().name());
        }
        if (inspection == null) {
            return OperationalState.FAILED;
        }
        return switch (inspection.status()) {
            case RUNNING -> OperationalState.READY;
            case STOPPED -> OperationalState.STOPPED;
            case MISSING, INACCESSIBLE, FAILED, TIMED_OUT -> OperationalState.FAILED;
        };
    }

    private void applyObservations(ConfigSnapshot snapshot,
            Map<String, ContainerInspection> inspections, String context) {
        if (!configuredServers.equals(Set.copyOf(snapshot.serverNames()))) {
            return;
        }
        Instant observedAt = clock.instant();
        for (String serverName : snapshot.serverNames()) {
            ContainerInspection inspection = inspections.get(serverName);
            if (inspection == null) {
                inspectionFailures.put(serverName, new OperationalFailure(observedAt, context,
                        "container check produced no result", "Retry the check and review proxy logs."));
            } else if (inspection.healthy()) {
                inspectionFailures.remove(serverName);
            } else {
                inspectionFailures.put(serverName, new OperationalFailure(observedAt, context,
                        inspection.detail(), inspection.remediation()));
            }
        }
    }

    private PreflightSummary summarize(ConfigSnapshot snapshot,
            Map<String, ContainerInspection> inspections) {
        int healthy = (int) inspections.values().stream().filter(ContainerInspection::healthy).count();
        return new PreflightSummary(healthy, snapshot.servers().size() - healthy);
    }

    private void recordCollectionFailure(ConfigSnapshot snapshot, String context) {
        if (!configuredServers.equals(Set.copyOf(snapshot.serverNames()))) {
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
}
