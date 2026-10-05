package me.criseda.autostopper.lifecycle;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.telemetry.TelemetryOperationType;
import me.criseda.autostopper.telemetry.TelemetryOrigin;
import me.criseda.autostopper.telemetry.TelemetryOutcome;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Operator-initiated start, stop, and restart. Admission shares the lifecycle locks with player
 * connections, so a manual operation never overlaps a startup, a stop, or pending waiters.
 */
final class ManualOperations {
    private final LifecycleRuntime runtime;
    private final StartupPipeline pipeline;

    ManualOperations(LifecycleRuntime runtime, StartupPipeline pipeline) {
        this.runtime = runtime;
        this.pipeline = pipeline;
    }

    // --- Start ---

    CompletableFuture<ManualStartOutcome> requestStart(ServerMapping mapping) {
        long startNanos = runtime.now();
        if (runtime.isShutdown()) {
            record(TelemetryOperationType.MANUAL_START, mapping, TelemetryOutcome.PROXY_SHUTDOWN, Duration.ZERO);
            return CompletableFuture.completedFuture(ManualStartOutcome.PROXY_SHUTDOWN);
        }

        StartAdmission admission = runtime.admit(mapping,
                StartAdmission.done(ManualStartOutcome.PROXY_SHUTDOWN),
                StartAdmission.done(ManualStartOutcome.MAPPING_CHANGED),
                entry -> {
                    if (entry.isRetired()) {
                        return StartAdmission.done(ManualStartOutcome.MAPPING_CHANGED);
                    }
                    if (entry.is(ServerLifecycleState.STOPPING)) {
                        return StartAdmission.done(ManualStartOutcome.SERVER_STOPPING);
                    }
                    if (entry.is(ServerLifecycleState.READY)) {
                        return StartAdmission.done(ManualStartOutcome.ALREADY_READY);
                    }
                    if (entry.is(ServerLifecycleState.STARTING) && entry.startupFuture().isPresent()) {
                        return new StartAdmission(null, entry.startupFuture().get(), null);
                    }
                    return new StartAdmission(entry, entry.beginStartup(
                            ConnectionLifecycleStage.INSPECTING, runtime.now(), 0), null);
                });

        ManualStartOutcome immediate = admission == null ? ManualStartOutcome.START_FAILED : admission.immediate();
        if (immediate != null) {
            record(TelemetryOperationType.MANUAL_START, mapping, TelemetryOutcome.from(immediate),
                    runtime.elapsedSince(startNanos));
            return CompletableFuture.completedFuture(immediate);
        }
        if (admission.launch() != null) {
            pipeline.launch(admission.launch(), mapping, admission.startup());
        }
        CompletableFuture<ManualStartOutcome> resultFuture = admission.startup()
                .thenApply(outcome -> outcome == null ? ManualStartOutcome.START_FAILED : outcome.toManualStartOutcome())
                .exceptionally(error -> LifecycleRuntime.classifyFailure(error,
                        ManualStartOutcome.OVERLOADED, ManualStartOutcome.CANCELLED, ManualStartOutcome.START_FAILED));
        resultFuture.whenComplete((outcome, error) -> {
            ManualStartOutcome result = outcome != null ? outcome : ManualStartOutcome.START_FAILED;
            record(TelemetryOperationType.MANUAL_START, mapping, TelemetryOutcome.from(result),
                    runtime.elapsedSince(startNanos));
        });
        return resultFuture;
    }

    // --- Stop ---

    CompletableFuture<ManualStopOutcome> requestStop(ServerMapping mapping, RegisteredServer registeredServer) {
        long startNanos = runtime.now();
        if (runtime.isShutdown()) {
            record(TelemetryOperationType.MANUAL_STOP, mapping, TelemetryOutcome.PROXY_SHUTDOWN, Duration.ZERO);
            return CompletableFuture.completedFuture(ManualStopOutcome.PROXY_SHUTDOWN);
        }

        StopAdmission admission = runtime.admit(mapping,
                StopAdmission.done(ManualStopOutcome.PROXY_SHUTDOWN),
                StopAdmission.done(ManualStopOutcome.MAPPING_CHANGED),
                entry -> {
                    if (entry.isRetired()) {
                        return StopAdmission.done(ManualStopOutcome.MAPPING_CHANGED);
                    }
                    if (hasPlayers(registeredServer)) {
                        return StopAdmission.done(ManualStopOutcome.PLAYERS_CONNECTED);
                    }
                    if (entry.hasWaiters()) {
                        return StopAdmission.done(ManualStopOutcome.WAITERS_PRESENT);
                    }
                    if (entry.is(ServerLifecycleState.STARTING)) {
                        return StopAdmission.done(ManualStopOutcome.SERVER_STARTING);
                    }
                    if (entry.is(ServerLifecycleState.STOPPING)) {
                        return StopAdmission.done(ManualStopOutcome.SERVER_STOPPING);
                    }
                    if (entry.is(ServerLifecycleState.STOPPED)) {
                        return StopAdmission.done(ManualStopOutcome.ALREADY_STOPPED);
                    }
                    entry.beginStop();
                    CompletableFuture<ManualStopOutcome> operation = new CompletableFuture<>();
                    entry.attachOperation(operation);
                    return new StopAdmission(entry, operation, null);
                });

        ManualStopOutcome immediate = admission == null ? ManualStopOutcome.STOP_FAILED : admission.immediate();
        if (immediate != null) {
            record(TelemetryOperationType.MANUAL_STOP, mapping, TelemetryOutcome.from(immediate),
                    runtime.elapsedSince(startNanos));
            return CompletableFuture.completedFuture(immediate);
        }

        CompletableFuture<ManualStopOutcome> stopFuture = admission.operation();
        stopFuture.whenComplete((outcome, error) -> {
            ManualStopOutcome result = outcome != null ? outcome : ManualStopOutcome.STOP_FAILED;
            record(TelemetryOperationType.MANUAL_STOP, mapping, TelemetryOutcome.from(result),
                    runtime.elapsedSince(startNanos));
        });
        executeStop(admission.entry(), mapping, registeredServer, stopFuture);
        return stopFuture;
    }

    private void executeStop(LifecycleEntry entry, ServerMapping mapping,
            RegisteredServer registeredServer, CompletableFuture<ManualStopOutcome> stopFuture) {
        try {
            runtime.executor.supply(() -> {
                synchronized (entry) {
                    ManualStopOutcome aborted = stopPrecondition(entry, registeredServer, stopFuture,
                            ManualStopOutcome.PROXY_SHUTDOWN, ManualStopOutcome.CANCELLED,
                            ManualStopOutcome.PLAYERS_CONNECTED, ManualStopOutcome.WAITERS_PRESENT);
                    if (aborted != null) {
                        abortStop(entry, stopFuture, aborted);
                        return aborted;
                    }
                }

                ContainerStatus result = runtime.serverManager.stopServer(mapping);
                return completeStop(entry, stopFuture, result);
            }).exceptionally(error -> {
                synchronized (entry) {
                    abortStop(entry, stopFuture, LifecycleRuntime.classifyFailure(error,
                            ManualStopOutcome.OVERLOADED, ManualStopOutcome.CANCELLED, ManualStopOutcome.STOP_FAILED));
                }
                return null;
            });
        } catch (AutoStopperExecutor.SaturationException e) {
            synchronized (entry) {
                abortStop(entry, stopFuture, ManualStopOutcome.OVERLOADED);
            }
        } catch (RuntimeException e) {
            synchronized (entry) {
                abortStop(entry, stopFuture, ManualStopOutcome.STOP_FAILED);
            }
        }
    }

    private ManualStopOutcome completeStop(LifecycleEntry entry,
            CompletableFuture<ManualStopOutcome> operation, ContainerStatus result) {
        synchronized (entry) {
            if (runtime.isShutdown() || !entry.ownsOperation(operation) || !entry.is(ServerLifecycleState.STOPPING)) {
                entry.settleStop(result);
                operation.complete(ManualStopOutcome.PROXY_SHUTDOWN);
                return ManualStopOutcome.PROXY_SHUTDOWN;
            }
            entry.detachOperation(operation);
            entry.finishStop(result, stopFailure("manual stop", result));
            ManualStopOutcome outcome = toManualStopOutcome(result);
            operation.complete(outcome);
            return outcome;
        }
    }

    // --- Restart ---

    CompletableFuture<ManualRestartOutcome> requestRestart(ServerMapping mapping, RegisteredServer registeredServer) {
        long startNanos = runtime.now();
        if (runtime.isShutdown()) {
            record(TelemetryOperationType.MANUAL_RESTART, mapping, TelemetryOutcome.PROXY_SHUTDOWN, Duration.ZERO);
            return CompletableFuture.completedFuture(ManualRestartOutcome.PROXY_SHUTDOWN);
        }

        RestartAdmission admission = runtime.admit(mapping,
                RestartAdmission.done(ManualRestartOutcome.PROXY_SHUTDOWN),
                RestartAdmission.done(ManualRestartOutcome.MAPPING_CHANGED),
                entry -> {
                    if (entry.isRetired()) {
                        return RestartAdmission.done(ManualRestartOutcome.MAPPING_CHANGED);
                    }
                    if (hasPlayers(registeredServer)) {
                        return RestartAdmission.done(ManualRestartOutcome.PLAYERS_CONNECTED);
                    }
                    if (entry.hasWaiters()) {
                        return RestartAdmission.done(ManualRestartOutcome.WAITERS_PRESENT);
                    }
                    if (entry.is(ServerLifecycleState.STARTING)) {
                        return RestartAdmission.done(ManualRestartOutcome.SERVER_STARTING);
                    }
                    if (entry.is(ServerLifecycleState.STOPPING)) {
                        return RestartAdmission.done(ManualRestartOutcome.SERVER_STOPPING);
                    }

                    CompletableFuture<ManualRestartOutcome> operation = new CompletableFuture<>();
                    entry.attachOperation(operation);
                    if (entry.is(ServerLifecycleState.STOPPED)) {
                        // Nothing to stop: join the shared startup path directly.
                        return new RestartAdmission(entry, operation, entry.beginStartup(
                                ConnectionLifecycleStage.INSPECTING, runtime.now(), 0), null);
                    }
                    entry.beginStop();
                    return new RestartAdmission(entry, operation, null, null);
                });

        ManualRestartOutcome immediate = admission == null ? ManualRestartOutcome.STOP_FAILED : admission.rejected();
        if (immediate != null) {
            record(TelemetryOperationType.MANUAL_RESTART, mapping, TelemetryOutcome.from(immediate),
                    runtime.elapsedSince(startNanos));
            return CompletableFuture.completedFuture(immediate);
        }

        CompletableFuture<ManualRestartOutcome> restartFuture = admission.operation();
        restartFuture.whenComplete((outcome, error) -> {
            ManualRestartOutcome result = outcome != null ? outcome : ManualRestartOutcome.STOP_FAILED;
            record(TelemetryOperationType.MANUAL_RESTART, mapping, TelemetryOutcome.from(result),
                    runtime.elapsedSince(startNanos));
        });

        if (admission.startup() != null) {
            pipeline.launch(admission.entry(), mapping, admission.startup());
            completeRestartAfterStartup(admission.entry(), admission.startup(), restartFuture);
        } else {
            executeRestart(admission.entry(), mapping, registeredServer, restartFuture);
        }
        return restartFuture;
    }

    private void executeRestart(LifecycleEntry entry, ServerMapping mapping,
            RegisteredServer registeredServer, CompletableFuture<ManualRestartOutcome> restartFuture) {
        try {
            runtime.executor.supply(() -> {
                synchronized (entry) {
                    ManualRestartOutcome aborted = stopPrecondition(entry, registeredServer, restartFuture,
                            ManualRestartOutcome.PROXY_SHUTDOWN, ManualRestartOutcome.CANCELLED,
                            ManualRestartOutcome.PLAYERS_CONNECTED, ManualRestartOutcome.WAITERS_PRESENT);
                    if (aborted != null) {
                        abortStop(entry, restartFuture, aborted);
                        return null;
                    }
                }

                ContainerStatus stopResult = runtime.serverManager.stopServer(mapping);
                CompletableFuture<StartupOutcome> startupFuture;
                synchronized (entry) {
                    if (runtime.isShutdown() || !entry.ownsOperation(restartFuture)) {
                        entry.settleStop(stopResult);
                        restartFuture.complete(ManualRestartOutcome.PROXY_SHUTDOWN);
                        return null;
                    }
                    if (stopResult != ContainerStatus.STOPPED) {
                        entry.finishStop(stopResult, stopFailure("container stop during restart", stopResult));
                        entry.detachOperation(restartFuture);
                        restartFuture.complete(toRestartStopFailure(stopResult));
                        return null;
                    }
                    entry.settleStop(stopResult);
                    startupFuture = entry.beginStartup(ConnectionLifecycleStage.STARTING, runtime.now(), 0);
                }

                pipeline.launchStart(entry, mapping, startupFuture);
                completeRestartAfterStartup(entry, startupFuture, restartFuture);
                return null;
            }).exceptionally(error -> {
                synchronized (entry) {
                    abortStop(entry, restartFuture, LifecycleRuntime.classifyFailure(error,
                            ManualRestartOutcome.OVERLOADED, ManualRestartOutcome.CANCELLED,
                            ManualRestartOutcome.STOP_FAILED));
                }
                return null;
            });
        } catch (AutoStopperExecutor.SaturationException e) {
            synchronized (entry) {
                abortStop(entry, restartFuture, ManualRestartOutcome.OVERLOADED);
            }
        } catch (RuntimeException e) {
            synchronized (entry) {
                abortStop(entry, restartFuture, ManualRestartOutcome.STOP_FAILED);
            }
        }
    }

    private void completeRestartAfterStartup(LifecycleEntry entry,
            CompletableFuture<StartupOutcome> startupFuture, CompletableFuture<ManualRestartOutcome> restartFuture) {
        startupFuture.whenComplete((outcome, error) -> {
            synchronized (entry) {
                entry.detachOperation(restartFuture);
            }
            if (error != null) {
                restartFuture.complete(LifecycleRuntime.classifyFailure(error,
                        ManualRestartOutcome.OVERLOADED, ManualRestartOutcome.CANCELLED,
                        ManualRestartOutcome.STOP_FAILED));
            } else if (outcome == null) {
                restartFuture.complete(ManualRestartOutcome.START_FAILED);
            } else {
                restartFuture.complete(outcome.toManualRestartOutcome());
            }
        });
    }

    // --- Shared stop helpers ---

    /**
     * Re-checks, on the worker thread and under the entry lock, that a stop admitted earlier may
     * still proceed. Returns the abort outcome, or {@code null} to continue.
     */
    private <T> T stopPrecondition(LifecycleEntry entry, RegisteredServer registeredServer,
            CompletableFuture<T> operation, T shutdownOutcome, T cancelledOutcome,
            T playersOutcome, T waitersOutcome) {
        if (runtime.isShutdown()) {
            return shutdownOutcome;
        }
        if (!entry.ownsOperation(operation) || !entry.is(ServerLifecycleState.STOPPING)) {
            return cancelledOutcome;
        }
        if (hasPlayers(registeredServer)) {
            return playersOutcome;
        }
        if (entry.hasWaiters()) {
            return waitersOutcome;
        }
        return null;
    }

    /** Caller must hold the entry lock. */
    private <T> void abortStop(LifecycleEntry entry, CompletableFuture<T> operation, T outcome) {
        entry.cancelStop();
        entry.detachOperation(operation);
        operation.complete(outcome);
    }

    private Supplier<OperationalFailure> stopFailure(String context, ContainerStatus result) {
        return () -> runtime.failure(context, "container stop failed with " + result,
                "Check Docker access and container state, then retry.");
    }

    private static boolean hasPlayers(RegisteredServer registeredServer) {
        return registeredServer != null && !registeredServer.getPlayersConnected().isEmpty();
    }

    private static ManualStopOutcome toManualStopOutcome(ContainerStatus status) {
        return switch (status) {
            case STOPPED -> ManualStopOutcome.STOPPED;
            case MISSING -> ManualStopOutcome.CONTAINER_MISSING;
            case INACCESSIBLE -> ManualStopOutcome.DOCKER_INACCESSIBLE;
            case TIMED_OUT -> ManualStopOutcome.STOP_TIMED_OUT;
            case RUNNING, FAILED -> ManualStopOutcome.STOP_FAILED;
        };
    }

    private static ManualRestartOutcome toRestartStopFailure(ContainerStatus status) {
        return switch (status) {
            case STOPPED -> throw new IllegalArgumentException("stopped is not a failure");
            case MISSING -> ManualRestartOutcome.CONTAINER_MISSING;
            case INACCESSIBLE -> ManualRestartOutcome.DOCKER_INACCESSIBLE;
            case TIMED_OUT -> ManualRestartOutcome.STOP_TIMED_OUT;
            case RUNNING, FAILED -> ManualRestartOutcome.STOP_FAILED;
        };
    }

    private void record(TelemetryOperationType type, ServerMapping mapping, TelemetryOutcome outcome,
            Duration elapsed) {
        runtime.telemetry.recordOperation(type, mapping.serverName(), TelemetryOrigin.MANUAL_COMMAND,
                outcome, elapsed, 0);
    }

    // --- Admission decisions, taken under the lifecycle locks and acted on after release ---

    /**
     * Either an immediate outcome, or a startup to follow; {@code launch} is set only when this
     * request opened the startup and must start the pipeline.
     */
    private record StartAdmission(LifecycleEntry launch, CompletableFuture<StartupOutcome> startup,
            ManualStartOutcome immediate) {
        static StartAdmission done(ManualStartOutcome outcome) {
            return new StartAdmission(null, null, outcome);
        }
    }

    private record StopAdmission(LifecycleEntry entry, CompletableFuture<ManualStopOutcome> operation,
            ManualStopOutcome immediate) {
        static StopAdmission done(ManualStopOutcome outcome) {
            return new StopAdmission(null, null, outcome);
        }
    }

    /** {@code startup} is set when the server was already stopped and restart only starts it. */
    private record RestartAdmission(LifecycleEntry entry, CompletableFuture<ManualRestartOutcome> operation,
            CompletableFuture<StartupOutcome> startup, ManualRestartOutcome rejected) {
        static RestartAdmission done(ManualRestartOutcome outcome) {
            return new RestartAdmission(null, null, null, outcome);
        }
    }
}
