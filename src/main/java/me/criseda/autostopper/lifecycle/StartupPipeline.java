package me.criseda.autostopper.lifecycle;

import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.readiness.ReadinessResult;
import me.criseda.autostopper.server.ServerManager;
import me.criseda.autostopper.telemetry.LifecycleTelemetry;
import me.criseda.autostopper.telemetry.TelemetryOperationType;
import me.criseda.autostopper.telemetry.TelemetryOrigin;
import me.criseda.autostopper.telemetry.TelemetryOutcome;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Drives the single shared startup operation for a server: container status, container start
 * when stopped, then readiness. Each stage runs only while its startup future still owns the
 * entry, so a superseded or cancelled operation never mutates lifecycle state.
 */
final class StartupPipeline {
    private final LifecycleRuntime runtime;
    private final WaiterConnector connector;
    private final ServerManager serverManager;
    private final LifecycleTelemetry telemetry;
    private final Logger logger;

    StartupPipeline(LifecycleRuntime runtime, WaiterConnector connector, ServerManager serverManager,
            LifecycleTelemetry telemetry, Logger logger) {
        this.runtime = runtime;
        this.connector = connector;
        this.serverManager = serverManager;
        this.telemetry = telemetry;
        this.logger = logger;
    }

    /** Starts the pipeline at the status check. */
    void launch(LifecycleEntry entry, ServerMapping mapping, CompletableFuture<StartupOutcome> operation) {
        runStage(entry, mapping, operation, Stage.STATUS,
                () -> serverManager.getServerStatusAsync(mapping),
                (status, elapsed) -> onStatus(entry, mapping, operation, status, elapsed));
    }

    /** Starts the pipeline at the container start, skipping the status check. */
    void launchStart(LifecycleEntry entry, ServerMapping mapping, CompletableFuture<StartupOutcome> operation) {
        runStage(entry, mapping, operation, Stage.START,
                () -> serverManager.startServerAsync(mapping),
                (result, elapsed) -> onStarted(entry, mapping, operation, result, elapsed));
    }

    private void launchReadiness(LifecycleEntry entry, ServerMapping mapping,
            CompletableFuture<StartupOutcome> operation, boolean startedContainer) {
        runStage(entry, mapping, operation, Stage.READINESS,
                () -> serverManager.waitForServerReadyAsync(mapping),
                (ready, elapsed) -> onReadiness(entry, mapping, operation, startedContainer, ready, elapsed));
    }

    /**
     * Runs one asynchronous stage. Waiters hear about the stage first when it is announced, and
     * the stage is skipped if the operation no longer owns the entry. A thrown or {@code null}
     * future fails startup immediately; otherwise {@code onResult} receives the completed value
     * (possibly {@code null}) unless the proxy shut down meanwhile.
     */
    private <T> void runStage(LifecycleEntry entry, ServerMapping mapping, CompletableFuture<StartupOutcome> operation,
            Stage stage, Supplier<CompletableFuture<T>> action, BiConsumer<T, Duration> onResult) {
        long stageStart = runtime.now();
        List<ConnectionWaiter> stageWaiters = List.of();
        if (stage.announced != null) {
            stageWaiters = announceStage(entry, operation, stage.announced);
            if (stageWaiters == null) {
                return;
            }
        }
        CompletableFuture<T> future;
        try {
            future = action.get();
        } catch (RuntimeException error) {
            failStage(entry, mapping, operation, stage, error, runtime.elapsedSince(stageStart));
            return;
        }
        if (future == null) {
            failStage(entry, mapping, operation, stage.telemetryType, stage.missingResultTelemetry,
                    stage.errorOutcome, runtime.elapsedSince(stageStart));
            return;
        }
        if (!ownOperation(entry, operation, future)) {
            return;
        }
        future.whenComplete((result, error) -> {
            if (runtime.isShutdown()) {
                return;
            }
            Duration stageElapsed = runtime.elapsedSince(stageStart);
            if (error != null) {
                failStage(entry, mapping, operation, stage, error, stageElapsed);
            } else {
                onResult.accept(result, stageElapsed);
            }
        });
        connector.drainNotifications(stageWaiters);
    }

    private void onStatus(LifecycleEntry entry, ServerMapping mapping, CompletableFuture<StartupOutcome> operation,
            Optional<ContainerStatus> status, Duration elapsed) {
        TelemetryOperationType type = Stage.STATUS.telemetryType;
        if (status == null) {
            failStage(entry, mapping, operation, type, TelemetryOutcome.STATUS_FAILED,
                    StartupOutcome.STATUS_ERROR, elapsed);
            return;
        }
        if (status.isEmpty()) {
            failStage(entry, mapping, operation, type, TelemetryOutcome.NO_MAPPING,
                    StartupOutcome.STATUS_NO_MAPPING, elapsed);
            return;
        }
        ContainerStatus containerStatus = status.get();
        telemetry.recordStage(type, mapping.serverName(), TelemetryOutcome.from(containerStatus), elapsed);
        switch (containerStatus) {
            case RUNNING -> launchReadiness(entry, mapping, operation, false);
            case STOPPED -> launchStart(entry, mapping, operation);
            case MISSING -> completeStartup(entry, mapping, operation, StartupOutcome.STATUS_MISSING, null);
            case INACCESSIBLE -> completeStartup(entry, mapping, operation, StartupOutcome.STATUS_INACCESSIBLE, null);
            case TIMED_OUT -> completeStartup(entry, mapping, operation, StartupOutcome.STATUS_TIMED_OUT, null);
            case FAILED -> completeStartup(entry, mapping, operation, StartupOutcome.STATUS_FAILED, null);
        }
    }

    private void onStarted(LifecycleEntry entry, ServerMapping mapping, CompletableFuture<StartupOutcome> operation,
            ContainerStatus result, Duration elapsed) {
        TelemetryOperationType type = Stage.START.telemetryType;
        if (result == null) {
            failStage(entry, mapping, operation, type, TelemetryOutcome.START_FAILED,
                    StartupOutcome.START_ERROR, elapsed);
            return;
        }
        telemetry.recordStage(type, mapping.serverName(), TelemetryOutcome.from(result), elapsed);
        switch (result) {
            case RUNNING -> launchReadiness(entry, mapping, operation, true);
            case MISSING -> completeStartup(entry, mapping, operation, StartupOutcome.START_MISSING, null);
            case INACCESSIBLE -> completeStartup(entry, mapping, operation, StartupOutcome.START_INACCESSIBLE, null);
            case TIMED_OUT -> completeStartup(entry, mapping, operation, StartupOutcome.START_TIMED_OUT, null);
            case STOPPED, FAILED -> completeStartup(entry, mapping, operation, StartupOutcome.START_FAILED, null);
        }
    }

    private void onReadiness(LifecycleEntry entry, ServerMapping mapping, CompletableFuture<StartupOutcome> operation,
            boolean startedContainer, ReadinessResult ready, Duration elapsed) {
        TelemetryOperationType type = Stage.READINESS.telemetryType;
        if (ready != null && ready.ready()) {
            telemetry.recordStage(type, mapping.serverName(), TelemetryOutcome.READY, elapsed);
            completeStartup(entry, mapping, operation,
                    startedContainer ? StartupOutcome.READY_AFTER_START : StartupOutcome.READY_RUNNING, null);
        } else {
            TelemetryOutcome stageOutcome = ready == null
                    ? TelemetryOutcome.SERVER_NOT_READY
                    : TelemetryOutcome.from(ready.outcome());
            telemetry.recordStage(type, mapping.serverName(), stageOutcome, elapsed);
            completeStartup(entry, mapping, operation, StartupOutcome.NOT_READY, ready);
        }
    }

    private void failStage(LifecycleEntry entry, ServerMapping mapping, CompletableFuture<StartupOutcome> operation,
            Stage stage, Throwable error, Duration elapsed) {
        StartupOutcome outcome = AutoStopperExecutor.classify(error,
                StartupOutcome.OVERLOADED, StartupOutcome.CANCELLED, stage.errorOutcome);
        if (outcome == stage.errorOutcome) {
            logger.error("Lifecycle {} operation failed for server {}",
                    stage.name().toLowerCase(), mapping.serverName(), AutoStopperExecutor.rootCause(error));
        }
        failStage(entry, mapping, operation, stage.telemetryType, outcome.toTelemetryOutcome(), outcome, elapsed);
    }

    private void failStage(LifecycleEntry entry, ServerMapping mapping, CompletableFuture<StartupOutcome> operation,
            TelemetryOperationType type, TelemetryOutcome stageOutcome, StartupOutcome outcome, Duration elapsed) {
        telemetry.recordStage(type, mapping.serverName(), stageOutcome, elapsed);
        completeStartup(entry, mapping, operation, outcome, null);
    }

    private void completeStartup(LifecycleEntry entry, ServerMapping mapping,
            CompletableFuture<StartupOutcome> operation, StartupOutcome outcome,
            ReadinessResult readinessFailure) {
        List<ConnectionWaiter> waiters;
        synchronized (entry) {
            if (runtime.isShutdown() || !entry.ownsStartup(operation)) {
                return;
            }
            if (entry.claimStartupTelemetry()) {
                TelemetryOutcome teleOutcome = outcome.isReady() ? TelemetryOutcome.READY : outcome.toTelemetryOutcome();
                telemetry.recordOperation(TelemetryOperationType.STARTUP, mapping.serverName(),
                        TelemetryOrigin.PLAYER_CONNECTION, teleOutcome,
                        runtime.elapsedSince(entry.startupStartNanos()), entry.startupWaiterCount());
            }
            waiters = entry.finishStartup(outcome, outcome.isReady() ? null : new OperationalFailure(Instant.now(),
                    "server startup",
                    readinessFailure == null ? outcome.failureDetail() : readinessFailure.playerDetail(),
                    outcome.remediation()));
            if (outcome.isReady()) {
                Component connecting = LifecycleMessages.stage(ConnectionLifecycleStage.CONNECTING, mapping.serverName());
                for (ConnectionWaiter waiter : waiters) {
                    waiter.queueStage(ConnectionLifecycleStage.CONNECTING, connecting, false);
                }
            }
        }

        operation.complete(outcome);
        if (outcome.isReady()) {
            connector.connectAll(entry, waiters);
        } else {
            for (ConnectionWaiter waiter : waiters) {
                connector.failStartupWaiter(waiter, mapping.serverName(), outcome, readinessFailure);
            }
            runtime.cleanupRetired(mapping.serverName(), entry);
        }
    }

    private List<ConnectionWaiter> announceStage(LifecycleEntry entry,
            CompletableFuture<StartupOutcome> operation, ConnectionLifecycleStage stage) {
        synchronized (entry) {
            if (runtime.isShutdown() || !entry.ownsStartup(operation)) {
                return null;
            }
            entry.advanceStartup(stage);
            List<ConnectionWaiter> waiters = entry.waiters();
            Component message = LifecycleMessages.stage(stage, entry.mapping().serverName());
            for (ConnectionWaiter waiter : waiters) {
                waiter.queueStage(stage, message, false);
            }
            return waiters;
        }
    }

    private boolean ownOperation(LifecycleEntry entry, CompletableFuture<StartupOutcome> startup,
            CompletableFuture<?> operation) {
        synchronized (entry) {
            if (runtime.isShutdown() || !entry.ownsStartup(startup)) {
                operation.cancel(true);
                return false;
            }
            entry.attachOperation(operation);
            return true;
        }
    }

    /** Docker-facing step of the pipeline and how its failures are reported. */
    private enum Stage {
        STATUS(null, TelemetryOperationType.STATUS_CHECK,
                TelemetryOutcome.STATUS_FAILED, StartupOutcome.STATUS_ERROR),
        START(ConnectionLifecycleStage.STARTING, TelemetryOperationType.CONTAINER_START,
                TelemetryOutcome.START_FAILED, StartupOutcome.START_ERROR),
        READINESS(ConnectionLifecycleStage.WAITING_FOR_READINESS, TelemetryOperationType.READINESS_CHECK,
                TelemetryOutcome.SERVER_NOT_READY, StartupOutcome.READINESS_ERROR);

        /** Stage shown to waiting players, or {@code null} when the stage is silent. */
        private final ConnectionLifecycleStage announced;
        private final TelemetryOperationType telemetryType;
        /** Stage telemetry when Docker hands back no future at all. */
        private final TelemetryOutcome missingResultTelemetry;
        /** Startup outcome for an unexpected exception (or missing future) in this stage. */
        private final StartupOutcome errorOutcome;

        Stage(ConnectionLifecycleStage announced, TelemetryOperationType telemetryType,
                TelemetryOutcome missingResultTelemetry, StartupOutcome errorOutcome) {
            this.announced = announced;
            this.telemetryType = telemetryType;
            this.missingResultTelemetry = missingResultTelemetry;
            this.errorOutcome = errorOutcome;
        }
    }
}
