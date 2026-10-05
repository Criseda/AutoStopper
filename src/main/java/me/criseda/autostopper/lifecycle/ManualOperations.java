package me.criseda.autostopper.lifecycle;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.server.ServerManager;
import me.criseda.autostopper.telemetry.LifecycleTelemetry;
import me.criseda.autostopper.telemetry.TelemetryOperationType;
import me.criseda.autostopper.telemetry.TelemetryOrigin;
import me.criseda.autostopper.telemetry.TelemetryOutcome;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Operator-initiated start, stop, and restart. Admission shares the lifecycle locks with player
 * connections, so a manual operation never overlaps a startup, a stop, or pending waiters.
 *
 * <p>Restart is a manual stop followed by a container start: it runs the same stop code and
 * reports stop failures as their {@link ManualRestartOutcome} equivalents.
 */
final class ManualOperations {
    private final LifecycleRuntime runtime;
    private final StartupPipeline pipeline;
    private final ServerManager serverManager;
    private final AutoStopperExecutor executor;
    private final LifecycleTelemetry telemetry;

    ManualOperations(LifecycleRuntime runtime, StartupPipeline pipeline, ServerManager serverManager,
            AutoStopperExecutor executor, LifecycleTelemetry telemetry) {
        this.runtime = runtime;
        this.pipeline = pipeline;
        this.serverManager = serverManager;
        this.executor = executor;
        this.telemetry = telemetry;
    }

    // --- Start ---

    CompletableFuture<ManualStartOutcome> requestStart(ServerMapping mapping) {
        long startNanos = runtime.now();
        StartAdmission admission = runtime.admit(mapping,
                StartAdmission.rejected(ManualStartOutcome.PROXY_SHUTDOWN),
                StartAdmission.rejected(ManualStartOutcome.MAPPING_CHANGED),
                entry -> {
                    if (entry.isRetired()) {
                        return StartAdmission.rejected(ManualStartOutcome.MAPPING_CHANGED);
                    }
                    if (entry.is(ServerLifecycleState.STOPPING)) {
                        return StartAdmission.rejected(ManualStartOutcome.SERVER_STOPPING);
                    }
                    if (entry.is(ServerLifecycleState.READY)) {
                        return StartAdmission.rejected(ManualStartOutcome.ALREADY_READY);
                    }
                    if (entry.is(ServerLifecycleState.STARTING) && entry.startupFuture().isPresent()) {
                        return new StartAdmission(null, entry.startupFuture().get(), null);
                    }
                    return new StartAdmission(entry, entry.beginStartup(
                            ConnectionLifecycleStage.INSPECTING, runtime.now(), 0), null);
                });

        CompletableFuture<ManualStartOutcome> result;
        if (admission.rejected() != null) {
            result = CompletableFuture.completedFuture(admission.rejected());
        } else {
            if (admission.launch() != null) {
                pipeline.launch(admission.launch(), mapping, admission.startup());
            }
            result = admission.startup()
                    .thenApply(outcome -> outcome == null
                            ? ManualStartOutcome.START_FAILED
                            : outcome.toManualStartOutcome())
                    .exceptionally(error -> AutoStopperExecutor.classify(error,
                            ManualStartOutcome.OVERLOADED, ManualStartOutcome.CANCELLED,
                            ManualStartOutcome.START_FAILED));
        }
        return recordWhenDone(TelemetryOperationType.MANUAL_START, mapping, startNanos, result,
                ManualStartOutcome.START_FAILED, TelemetryOutcome::from);
    }

    // --- Stop ---

    CompletableFuture<ManualStopOutcome> requestStop(ServerMapping mapping, RegisteredServer registeredServer) {
        long startNanos = runtime.now();
        StopAdmission admission = runtime.admit(mapping,
                StopAdmission.rejected(ManualStopOutcome.PROXY_SHUTDOWN),
                StopAdmission.rejected(ManualStopOutcome.MAPPING_CHANGED),
                entry -> {
                    ManualStopOutcome blocked = stopBlocker(entry, registeredServer);
                    if (blocked != null) {
                        return StopAdmission.rejected(blocked);
                    }
                    if (entry.is(ServerLifecycleState.STOPPED)) {
                        return StopAdmission.rejected(ManualStopOutcome.ALREADY_STOPPED);
                    }
                    return new StopAdmission(entry, beginStop(entry), null);
                });

        CompletableFuture<ManualStopOutcome> stop = admission.rejected() != null
                ? CompletableFuture.completedFuture(admission.rejected())
                : admission.stop();
        recordWhenDone(TelemetryOperationType.MANUAL_STOP, mapping, startNanos, stop,
                ManualStopOutcome.STOP_FAILED, TelemetryOutcome::from);
        if (admission.rejected() == null) {
            runStop(admission.entry(), mapping, registeredServer, stop);
        }
        return stop;
    }

    // --- Restart ---

    /**
     * The restart future is completed in exactly one of three places:
     * <ol>
     *   <li>here, when admission rejects it;</li>
     *   <li>here, when its stop ends any way but STOPPED;</li>
     *   <li>in {@link #completeRestart}, when the startup that follows ends. That startup is opened
     *       by {@link #runStopThenStart}, or here when the server was already stopped.</li>
     * </ol>
     */
    CompletableFuture<ManualRestartOutcome> requestRestart(ServerMapping mapping, RegisteredServer registeredServer) {
        long startNanos = runtime.now();
        RestartAdmission admission = runtime.admit(mapping,
                RestartAdmission.rejected(ManualRestartOutcome.PROXY_SHUTDOWN),
                RestartAdmission.rejected(ManualRestartOutcome.MAPPING_CHANGED),
                entry -> {
                    ManualStopOutcome blocked = stopBlocker(entry, registeredServer);
                    if (blocked != null) {
                        return RestartAdmission.rejected(restartOutcome(blocked));
                    }
                    if (entry.is(ServerLifecycleState.STOPPED)) {
                        // Nothing to stop: go straight to the shared startup.
                        return new RestartAdmission(entry, null, entry.beginStartup(
                                ConnectionLifecycleStage.INSPECTING, runtime.now(), 0), null);
                    }
                    return new RestartAdmission(entry, beginStop(entry), null, null);
                });

        CompletableFuture<ManualRestartOutcome> restart = admission.rejected() != null
                ? CompletableFuture.completedFuture(admission.rejected())
                : new CompletableFuture<>();
        recordWhenDone(TelemetryOperationType.MANUAL_RESTART, mapping, startNanos, restart,
                ManualRestartOutcome.STOP_FAILED, TelemetryOutcome::from);
        if (admission.rejected() != null) {
            return restart;
        }

        if (admission.startup() != null) {
            pipeline.launch(admission.entry(), mapping, admission.startup());
            completeRestart(restart, admission.startup());
            return restart;
        }
        admission.stop().whenComplete((stopOutcome, error) -> {
            if (error != null) {
                restart.completeExceptionally(error);
            } else if (stopOutcome != ManualStopOutcome.STOPPED) {
                restart.complete(restartOutcome(stopOutcome));
            }
        });
        runStopThenStart(admission.entry(), mapping, registeredServer, admission.stop(), restart);
        return restart;
    }

    private static void completeRestart(CompletableFuture<ManualRestartOutcome> restart,
            CompletableFuture<StartupOutcome> startup) {
        startup.whenComplete((outcome, error) -> {
            if (error != null) {
                restart.complete(AutoStopperExecutor.classify(error, ManualRestartOutcome.OVERLOADED,
                        ManualRestartOutcome.CANCELLED, ManualRestartOutcome.START_FAILED));
            } else if (outcome == null) {
                restart.complete(ManualRestartOutcome.START_FAILED);
            } else {
                restart.complete(outcome.toManualRestartOutcome());
            }
        });
    }

    // --- Stop work shared by stop and restart ---

    /** Why a stop or restart cannot begin right now, or {@code null} if it can. Caller holds the entry lock. */
    private static ManualStopOutcome stopBlocker(LifecycleEntry entry, RegisteredServer registeredServer) {
        if (entry.isRetired()) {
            return ManualStopOutcome.MAPPING_CHANGED;
        }
        if (hasPlayers(registeredServer)) {
            return ManualStopOutcome.PLAYERS_CONNECTED;
        }
        if (entry.hasWaiters()) {
            return ManualStopOutcome.WAITERS_PRESENT;
        }
        if (entry.is(ServerLifecycleState.STARTING)) {
            return ManualStopOutcome.SERVER_STARTING;
        }
        if (entry.is(ServerLifecycleState.STOPPING)) {
            return ManualStopOutcome.SERVER_STOPPING;
        }
        return null;
    }

    /** Moves the entry to STOPPING, owned by the returned operation. Caller holds the entry lock. */
    private static CompletableFuture<ManualStopOutcome> beginStop(LifecycleEntry entry) {
        entry.beginStop();
        CompletableFuture<ManualStopOutcome> stop = new CompletableFuture<>();
        entry.attachOperation(stop);
        return stop;
    }

    /** Stops the container on a worker and completes {@code stop} with how it ended. */
    private void runStop(LifecycleEntry entry, ServerMapping mapping, RegisteredServer registeredServer,
            CompletableFuture<ManualStopOutcome> stop) {
        onWorker(entry, stop, () -> {
            if (mayStillStop(entry, registeredServer, stop)) {
                finishManualStop(entry, stop, serverManager.stopServer(mapping), "manual stop");
            }
        });
    }

    /**
     * Stops the container on a worker like {@link #runStop}, then, if it stopped, starts it again.
     * The entry goes from STOPPING to STARTING under one lock, so no player connection can slip in
     * between.
     */
    private void runStopThenStart(LifecycleEntry entry, ServerMapping mapping, RegisteredServer registeredServer,
            CompletableFuture<ManualStopOutcome> stop, CompletableFuture<ManualRestartOutcome> restart) {
        onWorker(entry, stop, () -> {
            if (!mayStillStop(entry, registeredServer, stop)) {
                return;
            }
            ContainerStatus result = serverManager.stopServer(mapping);
            CompletableFuture<StartupOutcome> startup;
            synchronized (entry) {
                if (!finishManualStop(entry, stop, result, "container stop during restart")
                        || result != ContainerStatus.STOPPED) {
                    return;
                }
                startup = entry.beginStartup(ConnectionLifecycleStage.STARTING, runtime.now(), 0);
            }
            pipeline.launchStart(entry, mapping, startup);
            completeRestart(restart, startup);
        });
    }

    /** Runs {@code work} on a worker; if it cannot be scheduled or throws, the stop is aborted. */
    private void onWorker(LifecycleEntry entry, CompletableFuture<ManualStopOutcome> stop, Runnable work) {
        try {
            executor.supply(() -> {
                work.run();
                return null;
            }).exceptionally(error -> {
                abortStop(entry, stop, stopFailure(error));
                return null;
            });
        } catch (RuntimeException error) {
            abortStop(entry, stop, stopFailure(error));
        }
    }

    /**
     * Re-checks on the worker, just before Docker is called, whatever may have changed since
     * admission, and aborts the stop if it must not go ahead.
     */
    private boolean mayStillStop(LifecycleEntry entry, RegisteredServer registeredServer,
            CompletableFuture<ManualStopOutcome> stop) {
        synchronized (entry) {
            ManualStopOutcome blocked = lateStopBlocker(entry, registeredServer, stop);
            if (blocked == null) {
                return true;
            }
            abortStop(entry, stop, blocked);
            return false;
        }
    }

    /** Like {@link #stopBlocker}, for an admitted stop about to call Docker. Caller holds the entry lock. */
    private ManualStopOutcome lateStopBlocker(LifecycleEntry entry, RegisteredServer registeredServer,
            CompletableFuture<ManualStopOutcome> stop) {
        ManualStopOutcome lost = lostOwnership(entry, stop);
        if (lost != null) {
            return lost;
        }
        if (hasPlayers(registeredServer)) {
            return ManualStopOutcome.PLAYERS_CONNECTED;
        }
        if (entry.hasWaiters()) {
            return ManualStopOutcome.WAITERS_PRESENT;
        }
        return null;
    }

    /**
     * Applies the Docker stop result and completes {@code stop} with how it ended.
     *
     * @return whether {@code stop} still owned the entry. When it did not, the result is only
     *         applied to the state, so the entry still matches the container.
     */
    private boolean finishManualStop(LifecycleEntry entry, CompletableFuture<ManualStopOutcome> stop,
            ContainerStatus result, String failureContext) {
        synchronized (entry) {
            ManualStopOutcome lost = lostOwnership(entry, stop);
            if (lost != null) {
                entry.applyStopResult(result);
                stop.complete(lost);
                return false;
            }
            entry.detachOperation(stop);
            entry.finishStop(result, () -> new OperationalFailure(Instant.now(), failureContext,
                    "container stop failed with " + result,
                    "Check Docker access and container state, then retry."));
            stop.complete(stopOutcome(result));
            return true;
        }
    }

    /**
     * Why {@code stop} no longer owns the entry, or {@code null} if it still does: the proxy is
     * shutting down, or something else took the entry out of STOPPING. Caller holds the entry lock.
     */
    private ManualStopOutcome lostOwnership(LifecycleEntry entry, CompletableFuture<ManualStopOutcome> stop) {
        if (runtime.isShutdown()) {
            return ManualStopOutcome.PROXY_SHUTDOWN;
        }
        if (!entry.ownsOperation(stop) || !entry.is(ServerLifecycleState.STOPPING)) {
            return ManualStopOutcome.CANCELLED;
        }
        return null;
    }

    /** Gives up an admitted stop: the server goes back to READY and {@code stop} ends with {@code outcome}. */
    private static void abortStop(LifecycleEntry entry, CompletableFuture<ManualStopOutcome> stop,
            ManualStopOutcome outcome) {
        synchronized (entry) {
            entry.cancelStop();
            entry.detachOperation(stop);
            stop.complete(outcome);
        }
    }

    private static ManualStopOutcome stopFailure(Throwable error) {
        return AutoStopperExecutor.classify(error,
                ManualStopOutcome.OVERLOADED, ManualStopOutcome.CANCELLED, ManualStopOutcome.STOP_FAILED);
    }

    private static boolean hasPlayers(RegisteredServer registeredServer) {
        return registeredServer != null && !registeredServer.getPlayersConnected().isEmpty();
    }

    private static ManualStopOutcome stopOutcome(ContainerStatus result) {
        return switch (result) {
            case STOPPED -> ManualStopOutcome.STOPPED;
            case MISSING -> ManualStopOutcome.CONTAINER_MISSING;
            case INACCESSIBLE -> ManualStopOutcome.DOCKER_INACCESSIBLE;
            case TIMED_OUT -> ManualStopOutcome.STOP_TIMED_OUT;
            case RUNNING, FAILED -> ManualStopOutcome.STOP_FAILED;
        };
    }

    /** How a restart reports a stop that did not end with the container stopped. */
    private static ManualRestartOutcome restartOutcome(ManualStopOutcome stopOutcome) {
        return switch (stopOutcome) {
            case STOPPED, ALREADY_STOPPED -> throw new IllegalArgumentException("not a restart failure: " + stopOutcome);
            case PLAYERS_CONNECTED -> ManualRestartOutcome.PLAYERS_CONNECTED;
            case WAITERS_PRESENT -> ManualRestartOutcome.WAITERS_PRESENT;
            case SERVER_STARTING -> ManualRestartOutcome.SERVER_STARTING;
            case SERVER_STOPPING -> ManualRestartOutcome.SERVER_STOPPING;
            case MAPPING_CHANGED -> ManualRestartOutcome.MAPPING_CHANGED;
            case CONTAINER_MISSING -> ManualRestartOutcome.CONTAINER_MISSING;
            case DOCKER_INACCESSIBLE -> ManualRestartOutcome.DOCKER_INACCESSIBLE;
            case STOP_TIMED_OUT -> ManualRestartOutcome.STOP_TIMED_OUT;
            case STOP_FAILED -> ManualRestartOutcome.STOP_FAILED;
            case OVERLOADED -> ManualRestartOutcome.OVERLOADED;
            case CANCELLED -> ManualRestartOutcome.CANCELLED;
            case PROXY_SHUTDOWN -> ManualRestartOutcome.PROXY_SHUTDOWN;
        };
    }

    /** Records the operation's telemetry once {@code result} completes. */
    private <T> CompletableFuture<T> recordWhenDone(TelemetryOperationType type, ServerMapping mapping,
            long startNanos, CompletableFuture<T> result, T fallback, Function<T, TelemetryOutcome> toTelemetry) {
        result.whenComplete((outcome, error) -> telemetry.recordOperation(type, mapping.serverName(),
                TelemetryOrigin.MANUAL_COMMAND, toTelemetry.apply(outcome != null ? outcome : fallback),
                runtime.elapsedSince(startNanos), 0));
        return result;
    }

    // --- Admission decisions, taken under the lifecycle locks and acted on after release ---

    /**
     * Either a rejection, or a startup to follow; {@code launch} is set only when this request
     * opened the startup and must start the pipeline.
     */
    private record StartAdmission(LifecycleEntry launch, CompletableFuture<StartupOutcome> startup,
            ManualStartOutcome rejected) {
        static StartAdmission rejected(ManualStartOutcome outcome) {
            return new StartAdmission(null, null, outcome);
        }
    }

    private record StopAdmission(LifecycleEntry entry, CompletableFuture<ManualStopOutcome> stop,
            ManualStopOutcome rejected) {
        static StopAdmission rejected(ManualStopOutcome outcome) {
            return new StopAdmission(null, null, outcome);
        }
    }

    /** Unless rejected, exactly one of {@code stop} (stop, then start) or {@code startup} (already stopped) is set. */
    private record RestartAdmission(LifecycleEntry entry, CompletableFuture<ManualStopOutcome> stop,
            CompletableFuture<StartupOutcome> startup, ManualRestartOutcome rejected) {
        static RestartAdmission rejected(ManualRestartOutcome outcome) {
            return new RestartAdmission(null, null, null, outcome);
        }
    }
}
