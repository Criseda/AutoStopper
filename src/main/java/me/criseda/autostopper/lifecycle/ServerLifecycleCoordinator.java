package me.criseda.autostopper.lifecycle;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.server.ServerManager;
import me.criseda.autostopper.telemetry.LifecycleTelemetry;
import me.criseda.autostopper.telemetry.LifecycleTelemetryService;
import me.criseda.autostopper.telemetry.TelemetryOperationType;
import me.criseda.autostopper.telemetry.TelemetryOrigin;
import me.criseda.autostopper.telemetry.TelemetryOutcome;
import me.criseda.autostopper.telemetry.TelemetrySnapshot;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Public entry point for server lifecycle management: player connection admission, automatic
 * stop bookkeeping, configuration reconciliation, status queries, and shutdown.
 *
 * <p>Shared state (the per-server entries, permits, shutdown flag, and lock order) lives in
 * {@link LifecycleRuntime}. The package-private collaborators:
 * <ul>
 *   <li>{@link LifecycleEntry} - the per-server state machine; every state change goes through it.</li>
 *   <li>{@link StartupPipeline} - status, start, and readiness for the single shared startup.</li>
 *   <li>{@link WaiterConnector} - how each waiting player's request ends.</li>
 *   <li>{@link ManualOperations} - operator start, stop, and restart.</li>
 * </ul>
 */
public final class ServerLifecycleCoordinator {
    private final ServerManager serverManager;
    private final ServerHoldRegistry holdRegistry;
    private final LifecycleTelemetry telemetry;
    private final LifecycleRuntime runtime;
    private final WaiterConnector connector;
    private final StartupPipeline pipeline;
    private final ManualOperations manual;

    public ServerLifecycleCoordinator(Logger logger, ServerManager serverManager,
            ServerHoldRegistry holdRegistry, AutoStopperExecutor executor,
            LongSupplier nanoTime, LifecycleTelemetry telemetry) {
        Objects.requireNonNull(logger, "logger");
        this.serverManager = Objects.requireNonNull(serverManager, "serverManager");
        this.holdRegistry = Objects.requireNonNull(holdRegistry, "holdRegistry");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(nanoTime, "nanoTime");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.runtime = new LifecycleRuntime(logger, nanoTime);
        this.connector = new WaiterConnector(runtime, serverManager, telemetry, logger);
        this.pipeline = new StartupPipeline(runtime, connector, serverManager, telemetry, logger);
        this.manual = new ManualOperations(runtime, pipeline, serverManager, executor, telemetry);
    }

    public ServerLifecycleCoordinator(Logger logger, ServerManager serverManager,
            ServerHoldRegistry holdRegistry, AutoStopperExecutor executor, LongSupplier nanoTime) {
        this(logger, serverManager, holdRegistry, executor, nanoTime,
                new LifecycleTelemetryService(logger, nanoTime));
    }

    public ServerLifecycleCoordinator(Logger logger, ServerManager serverManager,
            ServerHoldRegistry holdRegistry, AutoStopperExecutor executor) {
        this(logger, serverManager, holdRegistry, executor, System::nanoTime);
    }

    public ServerLifecycleCoordinator(Logger logger, ServerManager serverManager) {
        this(logger, serverManager, new ServerHoldRegistry(), new AutoStopperExecutor(), System::nanoTime);
    }

    ServerLifecycleCoordinator(Logger logger, ServerManager serverManager, LongSupplier nanoTime) {
        this(logger, serverManager, new ServerHoldRegistry(), new AutoStopperExecutor(), nanoTime);
    }

    public TelemetrySnapshot snapshotTelemetry() {
        return telemetry.snapshot();
    }

    public boolean isHeld(String serverName) {
        return holdRegistry.isHeld(serverName);
    }

    public boolean hold(ServerMapping mapping) {
        return holdRegistry.hold(mapping);
    }

    public boolean release(String serverName) {
        return holdRegistry.release(serverName);
    }

    public int connectedPlayerCount(String serverName) {
        return serverManager.getServer(serverName)
                .map(server -> server.getPlayersConnected().size())
                .orElse(0);
    }

    public boolean consumeReconnectPermit(Player player, String serverName) {
        return runtime.consumeReconnect(player.getUniqueId(), serverName);
    }

    // --- Player connections ---

    public CompletableFuture<ConnectionOutcome> requestConnection(Player player, RegisteredServer targetServer,
            ServerMapping mapping) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(targetServer, "targetServer");
        Objects.requireNonNull(mapping, "mapping");
        String serverName = mapping.serverName();
        ConnectionAdmission admission = runtime.admit(mapping,
                ConnectionAdmission.rejected(ConnectionOutcome.PROXY_SHUTDOWN),
                ConnectionAdmission.rejected(ConnectionOutcome.MAPPING_CHANGED),
                entry -> admitConnection(entry, player, targetServer, serverName));

        if (admission.rejected() != null) {
            recordConnectionRejected(mapping, TelemetryOutcome.from(admission.rejected()));
            if (admission.rejected() != ConnectionOutcome.PROXY_SHUTDOWN) {
                connector.send(player, LifecycleMessages.rejected(serverName, admission.rejected()));
            }
            return CompletableFuture.completedFuture(admission.rejected());
        }
        switch (admission.next()) {
            case CONNECT -> connector.connectWaiter(admission.entry(), admission.waiter());
            case START -> pipeline.launch(admission.entry(), mapping, admission.startup());
            case WAIT -> {
                // The in-flight startup will connect this waiter when it finishes.
            }
        }
        connector.drainNotifications(admission.waiter());
        return admission.waiter().outcome();
    }

    /** Runs with the entry lock held, from {@link LifecycleRuntime#admit}. */
    private ConnectionAdmission admitConnection(LifecycleEntry entry, Player player,
            RegisteredServer targetServer, String serverName) {
        UUID playerId = player.getUniqueId();
        ConnectionWaiter existing = entry.waiter(playerId);
        if (existing != null) {
            if (entry.is(ServerLifecycleState.STARTING)) {
                existing.queueWaitingCount(entry.waiterCount());
                entry.notePeakWaiters();
            }
            return ConnectionAdmission.of(NextStep.WAIT, entry, existing, null);
        }
        if (entry.isRetired()) {
            return ConnectionAdmission.rejected(ConnectionOutcome.MAPPING_CHANGED);
        }
        if (entry.is(ServerLifecycleState.STOPPING)) {
            return ConnectionAdmission.rejected(ConnectionOutcome.SERVER_STOPPING);
        }

        ConnectionWaiter waiter = new ConnectionWaiter(playerId, player, targetServer, serverName, runtime.now());
        entry.addWaiter(waiter);
        if (entry.is(ServerLifecycleState.STARTING)) {
            entry.notePeakWaiters();
            waiter.queueStage(entry.progressStage(), LifecycleMessages.stage(entry.progressStage(), serverName), false);
            waiter.queueWaitingCount(entry.waiterCount());
            return ConnectionAdmission.of(NextStep.WAIT, entry, waiter, null);
        }
        if (entry.is(ServerLifecycleState.READY)) {
            waiter.queueStage(ConnectionLifecycleStage.CONNECTING,
                    LifecycleMessages.stage(ConnectionLifecycleStage.CONNECTING, serverName), false);
            return ConnectionAdmission.of(NextStep.CONNECT, entry, waiter, null);
        }

        CompletableFuture<StartupOutcome> startup = entry.beginStartup(
                ConnectionLifecycleStage.INSPECTING, runtime.now(), 1);
        waiter.queueStage(entry.progressStage(), LifecycleMessages.stage(entry.progressStage(), serverName), false);
        return ConnectionAdmission.of(NextStep.START, entry, waiter, startup);
    }

    private void recordConnectionRejected(ServerMapping mapping, TelemetryOutcome outcome) {
        telemetry.recordOperation(TelemetryOperationType.CONNECTION_WAIT, mapping.serverName(),
                TelemetryOrigin.PLAYER_CONNECTION, outcome, Duration.ZERO, 0);
    }

    public void discardPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        List<ConnectionWaiter> discarded = new ArrayList<>();
        runtime.updateAll(entry -> {
            ConnectionWaiter waiter = entry.removeWaiter(playerId);
            if (waiter != null) {
                waiter.discard();
                discarded.add(waiter);
            }
            return !entry.isDisposable();
        });
        runtime.revokeReconnects(playerId);
        for (ConnectionWaiter waiter : discarded) {
            connector.abandon(waiter, ConnectionOutcome.PLAYER_DISCONNECTED);
        }
    }

    // --- Manual operations ---

    public CompletableFuture<ManualStartOutcome> requestManualStart(ServerMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        return manual.requestStart(mapping);
    }

    public CompletableFuture<ManualStopOutcome> requestManualStop(ServerMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        RegisteredServer registered = serverManager.getServer(mapping.serverName()).orElse(null);
        return requestManualStop(mapping, registered);
    }

    public CompletableFuture<ManualStopOutcome> requestManualStop(ServerMapping mapping,
            RegisteredServer registeredServer) {
        Objects.requireNonNull(mapping, "mapping");
        return manual.requestStop(mapping, registeredServer);
    }

    public CompletableFuture<ManualRestartOutcome> requestManualRestart(ServerMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        RegisteredServer registered = serverManager.getServer(mapping.serverName()).orElse(null);
        return requestManualRestart(mapping, registered);
    }

    public CompletableFuture<ManualRestartOutcome> requestManualRestart(ServerMapping mapping,
            RegisteredServer registeredServer) {
        Objects.requireNonNull(mapping, "mapping");
        return manual.requestRestart(mapping, registeredServer);
    }

    // --- Automatic inactivity stop ---

    public boolean tryBeginStop(ServerMapping mapping) {
        if (runtime.isShutdown()) {
            return false;
        }
        return runtime.tryBeginStop(mapping);
    }

    public void completeStop(ServerMapping mapping, ContainerStatus result) {
        if (runtime.isShutdown()) {
            return;
        }
        runtime.update(mapping.serverName(), entry -> {
            if (!entry.matches(mapping) || !entry.is(ServerLifecycleState.STOPPING)) {
                return true;
            }
            entry.finishStop(result, () -> new OperationalFailure(Instant.now(), "container stop",
                    "container stop failed with " + result,
                    "Check Docker access and container state, then allow the bounded retry or retry manually."));
            return !entry.isDisposable();
        });
    }

    public void cancelStop(ServerMapping mapping) {
        if (runtime.isShutdown()) {
            return;
        }
        runtime.update(mapping.serverName(), entry -> {
            if (entry.matches(mapping)) {
                entry.cancelStop();
            }
            return !entry.isDisposable();
        });
    }

    // --- Observed backend state ---

    public void markReady(String serverName) {
        if (runtime.isShutdown()) {
            return;
        }
        runtime.update(serverName, entry -> {
            entry.markReady();
            return true;
        });
    }

    public Optional<LifecycleStatusSnapshot> markStoppedIfUnchanged(
            ServerMapping mapping, long expectedRevision) {
        Objects.requireNonNull(mapping, "mapping");
        return runtime.markStoppedIfUnchanged(mapping, expectedRevision);
    }

    public void reconcileConfig(ConfigSnapshot previous, ConfigSnapshot current) {
        if (runtime.isShutdown()) {
            return;
        }
        holdRegistry.reconcileConfig(previous, current);
        for (String serverName : previous.serverNames()) {
            Optional<ServerMapping> currentMapping = current.server(serverName);
            runtime.update(serverName, entry -> {
                if (currentMapping.isPresent() && entry.matches(currentMapping.get())) {
                    entry.unretire();
                    return true;
                }
                if (entry.isBusy()) {
                    entry.retire();
                    return true;
                }
                return false;
            });
        }
    }

    // --- Status queries ---

    public Optional<ServerLifecycleState> state(String serverName) {
        return runtime.read(serverName, Optional.empty(),
                entry -> entry.isRetired() ? Optional.empty() : Optional.of(entry.state()));
    }

    public Optional<ServerLifecycleState> state(ServerMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        return runtime.read(mapping.serverName(), Optional.empty(),
                entry -> entry.isRetired() || !entry.matches(mapping) ? Optional.empty() : Optional.of(entry.state()));
    }

    public LifecycleStatusSnapshot statusSnapshot(ServerMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        return runtime.read(mapping.serverName(), LifecycleStatusSnapshot.absent(),
                entry -> entry.isRetired() || !entry.matches(mapping)
                        ? LifecycleStatusSnapshot.absent()
                        : entry.snapshot());
    }

    public int waitingCount(String serverName) {
        return runtime.read(serverName, 0, LifecycleEntry::waiterCount);
    }

    public Optional<ConnectionOutcome> lastConnectionOutcome(String serverName) {
        return runtime.read(serverName, Optional.empty(), LifecycleEntry::lastConnectionOutcome);
    }

    public Optional<OperationalFailure> lastFailure(String serverName) {
        return runtime.read(serverName, Optional.empty(), LifecycleEntry::lastFailure);
    }

    // --- Shutdown ---

    public void shutdown() {
        List<CompletableFuture<?>> operations = new ArrayList<>();
        List<CompletableFuture<ManualStopOutcome>> manualStops = new ArrayList<>();
        List<ConnectionWaiter> waiters = new ArrayList<>();
        List<Map.Entry<String, Long>> interruptedStartups = new ArrayList<>();
        boolean initiated = runtime.shutdown(entry -> {
            if (entry.drainForShutdown(operations, manualStops, waiters)) {
                interruptedStartups.add(Map.entry(entry.mapping().serverName(), entry.startupStartNanos()));
            }
        });
        if (!initiated) {
            return;
        }
        holdRegistry.clear();

        for (Map.Entry<String, Long> startup : interruptedStartups) {
            telemetry.recordOperation(TelemetryOperationType.STARTUP, startup.getKey(),
                    TelemetryOrigin.INTERNAL, TelemetryOutcome.PROXY_SHUTDOWN,
                    runtime.elapsedSince(startup.getValue()), 0);
        }
        for (ConnectionWaiter waiter : waiters) {
            connector.abandon(waiter, ConnectionOutcome.PROXY_SHUTDOWN);
        }
        // A restart follows its stop, so it ends with PROXY_SHUTDOWN too.
        for (CompletableFuture<ManualStopOutcome> manualStop : manualStops) {
            manualStop.complete(ManualStopOutcome.PROXY_SHUTDOWN);
        }
        for (CompletableFuture<?> operation : operations) {
            operation.cancel(true);
        }
    }

    private enum NextStep {
        /** Server is ready: connect this waiter now. */
        CONNECT,
        /** This request opened a new startup and must launch the pipeline. */
        START,
        /** A startup is already in flight and will connect this waiter. */
        WAIT
    }

    /** Admission decision, taken under the lifecycle locks and acted on after release. */
    private record ConnectionAdmission(NextStep next, LifecycleEntry entry, ConnectionWaiter waiter,
            CompletableFuture<StartupOutcome> startup, ConnectionOutcome rejected) {
        static ConnectionAdmission of(NextStep next, LifecycleEntry entry, ConnectionWaiter waiter,
                CompletableFuture<StartupOutcome> startup) {
            return new ConnectionAdmission(next, entry, waiter, startup, null);
        }

        static ConnectionAdmission rejected(ConnectionOutcome outcome) {
            return new ConnectionAdmission(null, null, null, null, outcome);
        }
    }
}
