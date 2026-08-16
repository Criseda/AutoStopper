package me.criseda.autostopper.lifecycle;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.messages.AutoStopperMessages;
import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.readiness.ReadinessResult;
import me.criseda.autostopper.server.ServerManager;
import me.criseda.autostopper.telemetry.LifecycleTelemetry;
import me.criseda.autostopper.telemetry.LifecycleTelemetryService;
import me.criseda.autostopper.telemetry.TelemetryOperationType;
import me.criseda.autostopper.telemetry.TelemetryOrigin;
import me.criseda.autostopper.telemetry.TelemetryOutcome;
import me.criseda.autostopper.telemetry.TelemetrySnapshot;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Owns lifecycle state, the single in-flight startup operation, and connection waiters for every
 * managed server. Map updates serialize admission and reconciliation per server; asynchronous
 * callbacks synchronize on the same entry before changing its state or waiter collection.
 */
public final class ServerLifecycleCoordinator {
    private static final Map<ServerLifecycleState, Set<ServerLifecycleState>> LEGAL_TRANSITIONS =
            legalTransitions();

    private final Logger logger;
    private final ServerManager serverManager;
    private final ServerHoldRegistry holdRegistry;
    private final AutoStopperExecutor executor;
    private final LongSupplier nanoTime;
    private final LifecycleTelemetry telemetry;
    private final Map<String, LifecycleEntry> lifecycles = new ConcurrentHashMap<>();
    private final Set<ReconnectPermit> reconnectPermits = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicLong lifecycleRevision = new AtomicLong();
    private final Object shutdownLock = new Object();

    public ServerLifecycleCoordinator(Logger logger, ServerManager serverManager,
            ServerHoldRegistry holdRegistry, AutoStopperExecutor executor,
            LongSupplier nanoTime, LifecycleTelemetry telemetry) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.serverManager = Objects.requireNonNull(serverManager, "serverManager");
        this.holdRegistry = Objects.requireNonNull(holdRegistry, "holdRegistry");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
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
        if (shutdown.get()) {
            return false;
        }
        return reconnectPermits.remove(new ReconnectPermit(player.getUniqueId(), serverName));
    }

    public CompletableFuture<ConnectionOutcome> requestConnection(Player player, RegisteredServer targetServer,
            ServerMapping mapping) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(targetServer, "targetServer");
        Objects.requireNonNull(mapping, "mapping");
        if (shutdown.get()) {
            telemetry.recordOperation(TelemetryOperationType.CONNECTION_WAIT, mapping.serverName(),
                    TelemetryOrigin.PLAYER_CONNECTION, TelemetryOutcome.PROXY_SHUTDOWN, Duration.ZERO, 0);
            return CompletableFuture.completedFuture(ConnectionOutcome.PROXY_SHUTDOWN);
        }

        AtomicReference<Admission> admitted = new AtomicReference<>();
        synchronized (shutdownLock) {
            lifecycles.compute(mapping.serverName(), (serverName, current) -> {
                if (shutdown.get()) {
                    admitted.set(Admission.rejected(ConnectionOutcome.PROXY_SHUTDOWN));
                    return current;
                }
                LifecycleEntry entry = current;
                if (entry == null) {
                    entry = new LifecycleEntry(mapping, nextRevision());
                }

                synchronized (entry) {
                    if (!entry.mapping.equals(mapping)) {
                        if (entry.isBusy()) {
                            admitted.set(Admission.rejected(ConnectionOutcome.MAPPING_CHANGED));
                            return entry;
                        }
                        entry = new LifecycleEntry(mapping, nextRevision());
                    } else if (entry.retired && !entry.isBusy()) {
                        entry.retired = false;
                    }

                    UUID playerId = player.getUniqueId();
                    ConnectionWaiter existing = entry.waiters.get(playerId);
                    if (existing != null) {
                        if (entry.state == ServerLifecycleState.STARTING) {
                            queueWaitingCount(existing, entry.waiters.size());
                            entry.peakWaiterCount = Math.max(entry.peakWaiterCount, entry.waiters.size());
                        }
                        admitted.set(Admission.queued(entry, existing));
                        return entry;
                    }

                    if (entry.retired) {
                        admitted.set(Admission.rejected(ConnectionOutcome.MAPPING_CHANGED));
                        return entry;
                    }
                    if (entry.state == ServerLifecycleState.STOPPING) {
                        admitted.set(Admission.rejected(ConnectionOutcome.SERVER_STOPPING));
                        return entry;
                    }

                    ConnectionWaiter waiter = new ConnectionWaiter(
                            playerId, player, targetServer, mapping.serverName(), nanoTime.getAsLong());
                    entry.waiters.put(playerId, waiter);
                    touch(entry);
                    if (entry.state == ServerLifecycleState.STARTING) {
                        entry.peakWaiterCount = Math.max(entry.peakWaiterCount, entry.waiters.size());
                        queueStage(waiter, entry.progressStage,
                                stageMessage(entry.progressStage, mapping.serverName()), false);
                        queueWaitingCount(waiter, entry.waiters.size());
                        admitted.set(Admission.queued(entry, waiter));
                        return entry;
                    }
                    if (entry.state == ServerLifecycleState.READY) {
                        queueStage(waiter, ConnectionLifecycleStage.CONNECTING,
                                stageMessage(ConnectionLifecycleStage.CONNECTING, mapping.serverName()), false);
                        admitted.set(Admission.connect(entry, waiter));
                        return entry;
                    }

                    transition(entry, ServerLifecycleState.STARTING);
                    entry.progressStage = ConnectionLifecycleStage.INSPECTING;
                    entry.startupStartNanos = nanoTime.getAsLong();
                    entry.peakWaiterCount = 1;
                    entry.startupTelemetryRecorded = false;
                    queueStage(waiter, entry.progressStage,
                            stageMessage(entry.progressStage, mapping.serverName()), false);
                    CompletableFuture<StartupOutcome> operation = new CompletableFuture<>();
                    entry.startupFuture = operation;
                    admitted.set(Admission.start(entry, waiter, operation));
                    return entry;
                }
            });
        }

        Admission admission = admitted.get();
        if (admission == null) {
            telemetry.recordOperation(TelemetryOperationType.CONNECTION_WAIT, mapping.serverName(),
                    TelemetryOrigin.PLAYER_CONNECTION, TelemetryOutcome.START_FAILED, Duration.ZERO, 0);
            return CompletableFuture.completedFuture(ConnectionOutcome.START_FAILED);
        }
        if (admission.rejectedOutcome != null) {
            telemetry.recordOperation(TelemetryOperationType.CONNECTION_WAIT, mapping.serverName(),
                    TelemetryOrigin.PLAYER_CONNECTION, TelemetryOutcome.from(admission.rejectedOutcome),
                    Duration.ZERO, 0);
            if (admission.rejectedOutcome != ConnectionOutcome.PROXY_SHUTDOWN) {
                notifyRejected(player, mapping.serverName(), admission.rejectedOutcome);
            }
            return CompletableFuture.completedFuture(admission.rejectedOutcome);
        }
        if (admission.connectNow) {
            connectWaiter(admission.entry, admission.waiter);
        } else if (admission.launchStartup) {
            launchStatusCheck(admission.entry, mapping, admission.startupFuture);
        }
        drainNotifications(admission.waiter);
        return admission.waiter.future;
    }

    public CompletableFuture<ManualStartOutcome> requestManualStart(ServerMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        long startNanos = nanoTime.getAsLong();
        if (shutdown.get()) {
            telemetry.recordOperation(TelemetryOperationType.MANUAL_START, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.PROXY_SHUTDOWN, Duration.ZERO, 0);
            return CompletableFuture.completedFuture(ManualStartOutcome.PROXY_SHUTDOWN);
        }

        AtomicReference<ManualStartAdmission> admitted = new AtomicReference<>();
        synchronized (shutdownLock) {
            lifecycles.compute(mapping.serverName(), (serverName, current) -> {
                if (shutdown.get()) {
                    admitted.set(ManualStartAdmission.rejected(ManualStartOutcome.PROXY_SHUTDOWN));
                    return current;
                }
                LifecycleEntry entry = current;
                if (entry == null) {
                    entry = new LifecycleEntry(mapping, nextRevision());
                }

                synchronized (entry) {
                    if (!entry.mapping.equals(mapping)) {
                        if (entry.isBusy()) {
                            admitted.set(ManualStartAdmission.rejected(ManualStartOutcome.MAPPING_CHANGED));
                            return entry;
                        }
                        entry = new LifecycleEntry(mapping, nextRevision());
                    } else if (entry.retired && !entry.isBusy()) {
                        entry.retired = false;
                    }

                    if (entry.retired) {
                        admitted.set(ManualStartAdmission.rejected(ManualStartOutcome.MAPPING_CHANGED));
                        return entry;
                    }
                    if (entry.state == ServerLifecycleState.STOPPING) {
                        admitted.set(ManualStartAdmission.rejected(ManualStartOutcome.SERVER_STOPPING));
                        return entry;
                    }
                    if (entry.state == ServerLifecycleState.READY) {
                        admitted.set(ManualStartAdmission.completed(ManualStartOutcome.ALREADY_READY));
                        return entry;
                    }
                    if (entry.state == ServerLifecycleState.STARTING && entry.startupFuture != null) {
                        admitted.set(ManualStartAdmission.track(entry.startupFuture));
                        return entry;
                    }

                    transition(entry, ServerLifecycleState.STARTING);
                    entry.progressStage = ConnectionLifecycleStage.INSPECTING;
                    entry.startupStartNanos = nanoTime.getAsLong();
                    entry.peakWaiterCount = 0;
                    entry.startupTelemetryRecorded = false;
                    CompletableFuture<StartupOutcome> operation = new CompletableFuture<>();
                    entry.startupFuture = operation;
                    admitted.set(ManualStartAdmission.start(entry, operation));
                    return entry;
                }
            });
        }

        ManualStartAdmission admission = admitted.get();
        if (admission == null) {
            telemetry.recordOperation(TelemetryOperationType.MANUAL_START, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.START_FAILED,
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos)), 0);
            return CompletableFuture.completedFuture(ManualStartOutcome.START_FAILED);
        }
        if (admission.rejectedOutcome != null) {
            telemetry.recordOperation(TelemetryOperationType.MANUAL_START, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.from(admission.rejectedOutcome),
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos)), 0);
            return CompletableFuture.completedFuture(admission.rejectedOutcome);
        }
        if (admission.completedOutcome != null) {
            telemetry.recordOperation(TelemetryOperationType.MANUAL_START, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.from(admission.completedOutcome),
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos)), 0);
            return CompletableFuture.completedFuture(admission.completedOutcome);
        }
        if (admission.launchStartup) {
            launchStatusCheck(admission.entry, mapping, admission.startupFuture);
        }
        CompletableFuture<ManualStartOutcome> resultFuture = admission.startupFuture.thenApply(this::toManualStartOutcome)
                .exceptionally(this::exceptionalStartOutcome);
        resultFuture.whenComplete((outcome, error) -> {
            ManualStartOutcome result = outcome != null ? outcome : ManualStartOutcome.START_FAILED;
            telemetry.recordOperation(TelemetryOperationType.MANUAL_START, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.from(result),
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos)), 0);
        });
        return resultFuture;
    }

    public CompletableFuture<ManualStopOutcome> requestManualStop(ServerMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        RegisteredServer registered = serverManager.getServer(mapping.serverName()).orElse(null);
        return requestManualStop(mapping, registered);
    }

    public CompletableFuture<ManualStopOutcome> requestManualStop(ServerMapping mapping,
            RegisteredServer registeredServer) {
        Objects.requireNonNull(mapping, "mapping");
        long startNanos = nanoTime.getAsLong();
        if (shutdown.get()) {
            telemetry.recordOperation(TelemetryOperationType.MANUAL_STOP, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.PROXY_SHUTDOWN, Duration.ZERO, 0);
            return CompletableFuture.completedFuture(ManualStopOutcome.PROXY_SHUTDOWN);
        }

        AtomicReference<ManualStopAdmission> admitted = new AtomicReference<>();
        synchronized (shutdownLock) {
            lifecycles.compute(mapping.serverName(), (serverName, current) -> {
                if (shutdown.get()) {
                    admitted.set(ManualStopAdmission.rejected(ManualStopOutcome.PROXY_SHUTDOWN));
                    return current;
                }
                LifecycleEntry entry = current;
                if (entry == null) {
                    entry = new LifecycleEntry(mapping, nextRevision());
                }

                synchronized (entry) {
                    if (!entry.mapping.equals(mapping)) {
                        if (entry.isBusy()) {
                            admitted.set(ManualStopAdmission.rejected(ManualStopOutcome.MAPPING_CHANGED));
                            return entry;
                        }
                        entry = new LifecycleEntry(mapping, nextRevision());
                    } else if (entry.retired && !entry.isBusy()) {
                        entry.retired = false;
                    }

                    if (entry.retired) {
                        admitted.set(ManualStopAdmission.rejected(ManualStopOutcome.MAPPING_CHANGED));
                        return entry;
                    }
                    if (registeredServer != null && !registeredServer.getPlayersConnected().isEmpty()) {
                        admitted.set(ManualStopAdmission.rejected(ManualStopOutcome.PLAYERS_CONNECTED));
                        return entry;
                    }
                    if (!entry.waiters.isEmpty()) {
                        admitted.set(ManualStopAdmission.rejected(ManualStopOutcome.WAITERS_PRESENT));
                        return entry;
                    }
                    if (entry.state == ServerLifecycleState.STARTING) {
                        admitted.set(ManualStopAdmission.rejected(ManualStopOutcome.SERVER_STARTING));
                        return entry;
                    }
                    if (entry.state == ServerLifecycleState.STOPPING) {
                        admitted.set(ManualStopAdmission.rejected(ManualStopOutcome.SERVER_STOPPING));
                        return entry;
                    }
                    if (entry.state == ServerLifecycleState.STOPPED) {
                        admitted.set(ManualStopAdmission.completed(ManualStopOutcome.ALREADY_STOPPED));
                        return entry;
                    }

                    transition(entry, ServerLifecycleState.STOPPING);
                    CompletableFuture<ManualStopOutcome> operation = new CompletableFuture<>();
                    entry.activeOperation = operation;
                    admitted.set(ManualStopAdmission.start(entry, operation));
                    return entry;
                }
            });
        }

        ManualStopAdmission admission = admitted.get();
        if (admission == null) {
            telemetry.recordOperation(TelemetryOperationType.MANUAL_STOP, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.STOP_FAILED,
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos)), 0);
            return CompletableFuture.completedFuture(ManualStopOutcome.STOP_FAILED);
        }
        if (admission.rejectedOutcome != null) {
            telemetry.recordOperation(TelemetryOperationType.MANUAL_STOP, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.from(admission.rejectedOutcome),
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos)), 0);
            return CompletableFuture.completedFuture(admission.rejectedOutcome);
        }
        if (admission.completedOutcome != null) {
            telemetry.recordOperation(TelemetryOperationType.MANUAL_STOP, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.from(admission.completedOutcome),
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos)), 0);
            return CompletableFuture.completedFuture(admission.completedOutcome);
        }

        admission.stopFuture.whenComplete((outcome, error) -> {
            ManualStopOutcome result = outcome != null ? outcome : ManualStopOutcome.STOP_FAILED;
            telemetry.recordOperation(TelemetryOperationType.MANUAL_STOP, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.from(result),
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos)), 0);
        });

        executeManualStop(admission.entry, mapping, registeredServer, admission.stopFuture);
        return admission.stopFuture;
    }

    public CompletableFuture<ManualRestartOutcome> requestManualRestart(ServerMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        RegisteredServer registered = serverManager.getServer(mapping.serverName()).orElse(null);
        return requestManualRestart(mapping, registered);
    }

    public CompletableFuture<ManualRestartOutcome> requestManualRestart(ServerMapping mapping,
            RegisteredServer registeredServer) {
        Objects.requireNonNull(mapping, "mapping");
        long startNanos = nanoTime.getAsLong();
        if (shutdown.get()) {
            telemetry.recordOperation(TelemetryOperationType.MANUAL_RESTART, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.PROXY_SHUTDOWN, Duration.ZERO, 0);
            return CompletableFuture.completedFuture(ManualRestartOutcome.PROXY_SHUTDOWN);
        }

        AtomicReference<ManualRestartAdmission> admitted = new AtomicReference<>();
        synchronized (shutdownLock) {
            lifecycles.compute(mapping.serverName(), (serverName, current) -> {
                if (shutdown.get()) {
                    admitted.set(ManualRestartAdmission.rejected(ManualRestartOutcome.PROXY_SHUTDOWN));
                    return current;
                }
                LifecycleEntry entry = current;
                if (entry == null) {
                    entry = new LifecycleEntry(mapping, nextRevision());
                }

                synchronized (entry) {
                    if (!entry.mapping.equals(mapping)) {
                        if (entry.isBusy()) {
                            admitted.set(ManualRestartAdmission.rejected(ManualRestartOutcome.MAPPING_CHANGED));
                            return entry;
                        }
                        entry = new LifecycleEntry(mapping, nextRevision());
                    } else if (entry.retired && !entry.isBusy()) {
                        entry.retired = false;
                    }

                    if (entry.retired) {
                        admitted.set(ManualRestartAdmission.rejected(ManualRestartOutcome.MAPPING_CHANGED));
                        return entry;
                    }
                    if (registeredServer != null && !registeredServer.getPlayersConnected().isEmpty()) {
                        admitted.set(ManualRestartAdmission.rejected(ManualRestartOutcome.PLAYERS_CONNECTED));
                        return entry;
                    }
                    if (!entry.waiters.isEmpty()) {
                        admitted.set(ManualRestartAdmission.rejected(ManualRestartOutcome.WAITERS_PRESENT));
                        return entry;
                    }
                    if (entry.state == ServerLifecycleState.STARTING) {
                        admitted.set(ManualRestartAdmission.rejected(ManualRestartOutcome.SERVER_STARTING));
                        return entry;
                    }
                    if (entry.state == ServerLifecycleState.STOPPING) {
                        admitted.set(ManualRestartAdmission.rejected(ManualRestartOutcome.SERVER_STOPPING));
                        return entry;
                    }

                    CompletableFuture<ManualRestartOutcome> operation = new CompletableFuture<>();
                    entry.activeOperation = operation;
                    if (entry.state == ServerLifecycleState.STOPPED) {
                        transition(entry, ServerLifecycleState.STARTING);
                        entry.progressStage = ConnectionLifecycleStage.INSPECTING;
                        entry.startupStartNanos = nanoTime.getAsLong();
                        entry.peakWaiterCount = 0;
                        entry.startupTelemetryRecorded = false;
                        CompletableFuture<StartupOutcome> startupFuture = new CompletableFuture<>();
                        entry.startupFuture = startupFuture;
                        admitted.set(ManualRestartAdmission.startOnly(entry, operation, startupFuture));
                        return entry;
                    }

                    transition(entry, ServerLifecycleState.STOPPING);
                    admitted.set(ManualRestartAdmission.stopThenStart(entry, operation));
                    return entry;
                }
            });
        }

        ManualRestartAdmission admission = admitted.get();
        if (admission == null) {
            telemetry.recordOperation(TelemetryOperationType.MANUAL_RESTART, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.STOP_FAILED,
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos)), 0);
            return CompletableFuture.completedFuture(ManualRestartOutcome.STOP_FAILED);
        }
        if (admission.rejectedOutcome != null) {
            telemetry.recordOperation(TelemetryOperationType.MANUAL_RESTART, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.from(admission.rejectedOutcome),
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos)), 0);
            return CompletableFuture.completedFuture(admission.rejectedOutcome);
        }

        admission.restartFuture.whenComplete((outcome, error) -> {
            ManualRestartOutcome result = outcome != null ? outcome : ManualRestartOutcome.STOP_FAILED;
            telemetry.recordOperation(TelemetryOperationType.MANUAL_RESTART, mapping.serverName(),
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.from(result),
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos)), 0);
        });

        if (admission.startOnly) {
            launchStatusCheck(admission.entry, mapping, admission.startupFuture);
            admission.startupFuture.whenComplete((outcome, error) -> {
                synchronized (admission.entry) {
                    if (admission.entry.activeOperation == admission.restartFuture) {
                        admission.entry.activeOperation = null;
                    }
                }
                if (error != null) {
                    admission.restartFuture.complete(exceptionalRestartOutcome(error));
                } else if (outcome != null && outcome.isReady()) {
                    admission.restartFuture.complete(ManualRestartOutcome.RESTARTED_AND_READY);
                } else if (outcome != null) {
                    admission.restartFuture.complete(toManualRestartOutcome(outcome));
                } else {
                    admission.restartFuture.complete(ManualRestartOutcome.START_FAILED);
                }
            });
            return admission.restartFuture;
        }

        executeManualRestart(admission.entry, mapping, registeredServer, admission.restartFuture);
        return admission.restartFuture;
    }

    public void discardPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        List<ConnectionWaiter> discarded = new ArrayList<>();
        for (String serverName : List.copyOf(lifecycles.keySet())) {
            lifecycles.computeIfPresent(serverName, (ignored, entry) -> {
                synchronized (entry) {
                    ConnectionWaiter waiter = entry.waiters.remove(playerId);
                    if (waiter != null) {
                        waiter.discarded = true;
                        discarded.add(waiter);
                        touch(entry);
                    }
                    return entry.retired && !entry.isBusy() ? null : entry;
                }
            });
        }
        reconnectPermits.removeIf(permit -> permit.playerId.equals(playerId));
        for (ConnectionWaiter waiter : discarded) {
            telemetry.recordOperation(TelemetryOperationType.CONNECTION_WAIT,
                    waiter.serverName,
                    TelemetryOrigin.PLAYER_CONNECTION, TelemetryOutcome.PLAYER_DISCONNECTED,
                    elapsed(waiter), 0);
            waiter.future.complete(ConnectionOutcome.PLAYER_DISCONNECTED);
        }
    }

    public boolean tryBeginStop(ServerMapping mapping) {
        if (shutdown.get()) {
            return false;
        }
        AtomicBoolean admitted = new AtomicBoolean(false);
        synchronized (shutdownLock) {
            lifecycles.compute(mapping.serverName(), (ignored, current) -> {
                if (shutdown.get()) {
                    return current;
                }
                LifecycleEntry entry = current;
                if (entry == null) {
                    entry = new LifecycleEntry(mapping, nextRevision());
                }
                synchronized (entry) {
                    if (!entry.mapping.equals(mapping)) {
                        if (entry.isBusy()) {
                            return entry;
                        }
                        entry = new LifecycleEntry(mapping, nextRevision());
                    }
                    if (entry.state == ServerLifecycleState.STARTING
                            || entry.state == ServerLifecycleState.STOPPING
                            || !entry.waiters.isEmpty()) {
                        return entry;
                    }
                    transition(entry, ServerLifecycleState.STOPPING);
                    admitted.set(true);
                    return entry;
                }
            });
        }
        return admitted.get();
    }

    public void completeStop(ServerMapping mapping, ContainerStatus result) {
        if (shutdown.get()) {
            return;
        }
        lifecycles.computeIfPresent(mapping.serverName(), (ignored, entry) -> {
            synchronized (entry) {
                if (!entry.mapping.equals(mapping) || entry.state != ServerLifecycleState.STOPPING) {
                    return entry;
                }
                transition(entry, result == ContainerStatus.STOPPED
                        ? ServerLifecycleState.STOPPED
                        : ServerLifecycleState.FAILED);
                if (result == ContainerStatus.STOPPED) {
                    entry.lastFailure = null;
                } else {
                    entry.lastFailure = failure("container stop",
                            "container stop failed with " + result,
                            "Check Docker access and container state, then allow the bounded retry or retry manually.");
                }
                return entry.retired && !entry.isBusy() ? null : entry;
            }
        });
    }

    public void cancelStop(ServerMapping mapping) {
        if (shutdown.get()) {
            return;
        }
        lifecycles.computeIfPresent(mapping.serverName(), (ignored, entry) -> {
            synchronized (entry) {
                if (entry.mapping.equals(mapping) && entry.state == ServerLifecycleState.STOPPING) {
                    transition(entry, ServerLifecycleState.READY);
                }
                return entry.retired && !entry.isBusy() ? null : entry;
            }
        });
    }

    public void markReady(String serverName) {
        if (shutdown.get()) {
            return;
        }
        lifecycles.computeIfPresent(serverName, (ignored, entry) -> {
            synchronized (entry) {
                if (entry.state == ServerLifecycleState.STOPPED
                        || entry.state == ServerLifecycleState.FAILED) {
                    transition(entry, ServerLifecycleState.READY);
                }
                entry.lastFailure = null;
                entry.readyConnectionSucceeded = true;
                touch(entry);
                return entry;
            }
        });
    }

    public Optional<LifecycleStatusSnapshot> markStoppedIfUnchanged(
            ServerMapping mapping, long expectedRevision) {
        Objects.requireNonNull(mapping, "mapping");
        if (shutdown.get()) {
            return Optional.empty();
        }
        AtomicReference<LifecycleStatusSnapshot> accepted = new AtomicReference<>();
        lifecycles.compute(mapping.serverName(), (ignored, entry) -> {
            if (entry == null) {
                if (expectedRevision == 0) {
                    accepted.set(LifecycleStatusSnapshot.absent());
                }
                return null;
            }
            synchronized (entry) {
                if (entry.retired || !entry.mapping.equals(mapping)
                        || entry.revision != expectedRevision || entry.isBusy()) {
                    return entry;
                }
                if (entry.state == ServerLifecycleState.READY
                        || entry.state == ServerLifecycleState.FAILED) {
                    transition(entry, ServerLifecycleState.STOPPED);
                    entry.lastFailure = null;
                    entry.readyConnectionSucceeded = false;
                }
                accepted.set(snapshot(entry));
                return entry;
            }
        });
        return Optional.ofNullable(accepted.get());
    }

    public void reconcileConfig(ConfigSnapshot previous, ConfigSnapshot current) {
        if (shutdown.get()) {
            return;
        }
        holdRegistry.reconcileConfig(previous, current);
        for (String serverName : previous.serverNames()) {
            Optional<ServerMapping> currentMapping = current.server(serverName);
            lifecycles.computeIfPresent(serverName, (ignored, entry) -> {
                synchronized (entry) {
                    if (currentMapping.isPresent() && entry.mapping.equals(currentMapping.get())) {
                        entry.retired = false;
                        return entry;
                    }
                    if (entry.isBusy()) {
                        entry.retired = true;
                        for (ConnectionWaiter waiter : entry.waiters.values()) {
                            suppressNotifications(waiter);
                        }
                        touch(entry);
                        return entry;
                    }
                    return null;
                }
            });
        }
    }

    public Optional<ServerLifecycleState> state(String serverName) {
        LifecycleEntry entry = lifecycles.get(serverName);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            if (entry.retired) {
                return Optional.empty();
            }
            return Optional.of(entry.state);
        }
    }

    public Optional<ServerLifecycleState> state(ServerMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        LifecycleEntry entry = lifecycles.get(mapping.serverName());
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            if (entry.retired || !entry.mapping.equals(mapping)) {
                return Optional.empty();
            }
            return Optional.of(entry.state);
        }
    }

    public LifecycleStatusSnapshot statusSnapshot(ServerMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        LifecycleEntry entry = lifecycles.get(mapping.serverName());
        if (entry == null) {
            return LifecycleStatusSnapshot.absent();
        }
        synchronized (entry) {
            if (entry.retired || !entry.mapping.equals(mapping)) {
                return LifecycleStatusSnapshot.absent();
            }
            return snapshot(entry);
        }
    }

    public int waitingCount(String serverName) {
        LifecycleEntry entry = lifecycles.get(serverName);
        if (entry == null) {
            return 0;
        }
        synchronized (entry) {
            return entry.waiters.size();
        }
    }

    public Optional<ConnectionOutcome> lastConnectionOutcome(String serverName) {
        LifecycleEntry entry = lifecycles.get(serverName);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            return Optional.ofNullable(entry.lastConnectionOutcome);
        }
    }

    public Optional<OperationalFailure> lastFailure(String serverName) {
        LifecycleEntry entry = lifecycles.get(serverName);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            return Optional.ofNullable(entry.lastFailure);
        }
    }

    public void shutdown() {
        List<CompletableFuture<?>> operations = new ArrayList<>();
        List<ConnectionWaiter> waiters = new ArrayList<>();
        List<Map.Entry<String, Long>> interruptedStartups = new ArrayList<>();
        synchronized (shutdownLock) {
            if (!shutdown.compareAndSet(false, true)) {
                return;
            }
            holdRegistry.clear();
            for (LifecycleEntry entry : lifecycles.values()) {
                synchronized (entry) {
                    if (entry.activeOperation != null) {
                        operations.add(entry.activeOperation);
                        entry.activeOperation = null;
                    }
                    if (entry.startupFuture != null) {
                        if (!entry.startupTelemetryRecorded) {
                            entry.startupTelemetryRecorded = true;
                            interruptedStartups.add(Map.entry(entry.mapping.serverName(), entry.startupStartNanos));
                        }
                        entry.startupFuture.cancel(false);
                        entry.startupFuture = null;
                    }
                    for (ConnectionWaiter waiter : entry.waiters.values()) {
                        waiter.discarded = true;
                        if (waiter.connectionFuture != null) {
                            operations.add(waiter.connectionFuture);
                            waiter.connectionFuture = null;
                        }
                        waiters.add(waiter);
                    }
                    entry.waiters.clear();
                    entry.lastConnectionOutcome = ConnectionOutcome.PROXY_SHUTDOWN;
                }
            }
            lifecycles.clear();
            reconnectPermits.clear();
        }

        for (Map.Entry<String, Long> startup : interruptedStartups) {
            telemetry.recordOperation(TelemetryOperationType.STARTUP, startup.getKey(),
                    TelemetryOrigin.INTERNAL, TelemetryOutcome.PROXY_SHUTDOWN,
                    Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startup.getValue())), 0);
        }
        for (ConnectionWaiter waiter : waiters) {
            telemetry.recordOperation(TelemetryOperationType.CONNECTION_WAIT,
                    waiter.serverName,
                    TelemetryOrigin.PLAYER_CONNECTION, TelemetryOutcome.PROXY_SHUTDOWN,
                    elapsed(waiter), 0);
            waiter.future.complete(ConnectionOutcome.PROXY_SHUTDOWN);
        }
        for (CompletableFuture<?> operation : operations) {
            operation.cancel(true);
        }
    }

    private void launchStatusCheck(LifecycleEntry entry, ServerMapping mapping,
            CompletableFuture<StartupOutcome> operation) {
        long stageStart = nanoTime.getAsLong();
        CompletableFuture<Optional<ContainerStatus>> statusFuture;
        try {
            statusFuture = serverManager.getServerStatusAsync(mapping);
        } catch (RuntimeException error) {
            Duration stageElapsed = Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - stageStart));
            StartupOutcome outcome = exceptionalOutcome(error, StartupStage.STATUS, mapping.serverName());
            telemetry.recordStage(TelemetryOperationType.STATUS_CHECK, mapping.serverName(),
                    toTelemetryOutcome(outcome), stageElapsed);
            completeStartup(entry, mapping, operation, outcome);
            return;
        }
        if (statusFuture == null) {
            Duration stageElapsed = Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - stageStart));
            telemetry.recordStage(TelemetryOperationType.STATUS_CHECK, mapping.serverName(),
                    TelemetryOutcome.STATUS_FAILED, stageElapsed);
            completeStartup(entry, mapping, operation, StartupOutcome.STATUS_ERROR);
            return;
        }
        if (!ownOperation(entry, operation, statusFuture)) {
            return;
        }
        statusFuture.whenComplete((status, error) -> {
            if (shutdown.get()) {
                return;
            }
            Duration stageElapsed = Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - stageStart));
            if (error != null) {
                StartupOutcome outcome = exceptionalOutcome(error, StartupStage.STATUS, mapping.serverName());
                telemetry.recordStage(TelemetryOperationType.STATUS_CHECK, mapping.serverName(),
                        toTelemetryOutcome(outcome), stageElapsed);
                completeStartup(entry, mapping, operation, outcome);
                return;
            }
            if (status == null) {
                telemetry.recordStage(TelemetryOperationType.STATUS_CHECK, mapping.serverName(),
                        TelemetryOutcome.STATUS_FAILED, stageElapsed);
                completeStartup(entry, mapping, operation, StartupOutcome.STATUS_ERROR);
                return;
            }
            if (status.isEmpty()) {
                telemetry.recordStage(TelemetryOperationType.STATUS_CHECK, mapping.serverName(),
                        TelemetryOutcome.NO_MAPPING, stageElapsed);
                completeStartup(entry, mapping, operation, StartupOutcome.STATUS_NO_MAPPING);
                return;
            }
            ContainerStatus containerStatus = status.get();
            telemetry.recordStage(TelemetryOperationType.STATUS_CHECK, mapping.serverName(),
                    TelemetryOutcome.from(containerStatus), stageElapsed);
            switch (containerStatus) {
                case RUNNING -> launchReadiness(entry, mapping, operation, false);
                case STOPPED -> launchStart(entry, mapping, operation);
                case MISSING -> completeStartup(entry, mapping, operation, StartupOutcome.STATUS_MISSING);
                case INACCESSIBLE -> completeStartup(entry, mapping, operation, StartupOutcome.STATUS_INACCESSIBLE);
                case TIMED_OUT -> completeStartup(entry, mapping, operation, StartupOutcome.STATUS_TIMED_OUT);
                case FAILED -> completeStartup(entry, mapping, operation, StartupOutcome.STATUS_FAILED);
            }
        });
    }

    private void launchStart(LifecycleEntry entry, ServerMapping mapping,
            CompletableFuture<StartupOutcome> operation) {
        long stageStart = nanoTime.getAsLong();
        List<ConnectionWaiter> stageWaiters = recordSharedStage(
                entry, operation, ConnectionLifecycleStage.STARTING);
        if (stageWaiters == null) {
            return;
        }
        CompletableFuture<ContainerStatus> startFuture;
        try {
            startFuture = serverManager.startServerAsync(mapping);
        } catch (RuntimeException error) {
            Duration stageElapsed = Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - stageStart));
            StartupOutcome outcome = exceptionalOutcome(error, StartupStage.START, mapping.serverName());
            telemetry.recordStage(TelemetryOperationType.CONTAINER_START, mapping.serverName(),
                    toTelemetryOutcome(outcome), stageElapsed);
            completeStartup(entry, mapping, operation, outcome);
            return;
        }
        if (startFuture == null) {
            Duration stageElapsed = Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - stageStart));
            telemetry.recordStage(TelemetryOperationType.CONTAINER_START, mapping.serverName(),
                    TelemetryOutcome.START_FAILED, stageElapsed);
            completeStartup(entry, mapping, operation, StartupOutcome.START_ERROR);
            return;
        }
        if (!ownOperation(entry, operation, startFuture)) {
            return;
        }
        startFuture.whenComplete((result, error) -> {
            if (shutdown.get()) {
                return;
            }
            Duration stageElapsed = Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - stageStart));
            if (error != null) {
                StartupOutcome outcome = exceptionalOutcome(error, StartupStage.START, mapping.serverName());
                telemetry.recordStage(TelemetryOperationType.CONTAINER_START, mapping.serverName(),
                        toTelemetryOutcome(outcome), stageElapsed);
                completeStartup(entry, mapping, operation, outcome);
                return;
            }
            if (result == null) {
                telemetry.recordStage(TelemetryOperationType.CONTAINER_START, mapping.serverName(),
                        TelemetryOutcome.START_FAILED, stageElapsed);
                completeStartup(entry, mapping, operation, StartupOutcome.START_ERROR);
                return;
            }
            telemetry.recordStage(TelemetryOperationType.CONTAINER_START, mapping.serverName(),
                    TelemetryOutcome.from(result), stageElapsed);
            switch (result) {
                case RUNNING -> launchReadiness(entry, mapping, operation, true);
                case MISSING -> completeStartup(entry, mapping, operation, StartupOutcome.START_MISSING);
                case INACCESSIBLE -> completeStartup(entry, mapping, operation, StartupOutcome.START_INACCESSIBLE);
                case TIMED_OUT -> completeStartup(entry, mapping, operation, StartupOutcome.START_TIMED_OUT);
                case STOPPED, FAILED -> completeStartup(entry, mapping, operation, StartupOutcome.START_FAILED);
            }
        });
        drainNotifications(stageWaiters);
    }

    private void launchReadiness(LifecycleEntry entry, ServerMapping mapping,
            CompletableFuture<StartupOutcome> operation, boolean startedContainer) {
        long stageStart = nanoTime.getAsLong();
        List<ConnectionWaiter> stageWaiters = recordSharedStage(
                entry, operation, ConnectionLifecycleStage.WAITING_FOR_READINESS);
        if (stageWaiters == null) {
            return;
        }
        CompletableFuture<ReadinessResult> readinessFuture;
        try {
            readinessFuture = serverManager.waitForServerReadyAsync(mapping);
        } catch (RuntimeException error) {
            Duration stageElapsed = Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - stageStart));
            StartupOutcome outcome = exceptionalOutcome(error, StartupStage.READINESS, mapping.serverName());
            telemetry.recordStage(TelemetryOperationType.READINESS_CHECK, mapping.serverName(),
                    toTelemetryOutcome(outcome), stageElapsed);
            completeStartup(entry, mapping, operation, outcome);
            return;
        }
        if (readinessFuture == null) {
            Duration stageElapsed = Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - stageStart));
            telemetry.recordStage(TelemetryOperationType.READINESS_CHECK, mapping.serverName(),
                    TelemetryOutcome.SERVER_NOT_READY, stageElapsed);
            completeStartup(entry, mapping, operation, StartupOutcome.READINESS_ERROR);
            return;
        }
        if (!ownOperation(entry, operation, readinessFuture)) {
            return;
        }
        readinessFuture.whenComplete((ready, error) -> {
            if (shutdown.get()) {
                return;
            }
            Duration stageElapsed = Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - stageStart));
            if (error != null) {
                StartupOutcome outcome = exceptionalOutcome(error, StartupStage.READINESS, mapping.serverName());
                telemetry.recordStage(TelemetryOperationType.READINESS_CHECK, mapping.serverName(),
                        toTelemetryOutcome(outcome), stageElapsed);
                completeStartup(entry, mapping, operation, outcome);
            } else if (ready != null && ready.ready()) {
                telemetry.recordStage(TelemetryOperationType.READINESS_CHECK, mapping.serverName(),
                        TelemetryOutcome.READY, stageElapsed);
                completeStartup(entry, mapping, operation,
                        startedContainer ? StartupOutcome.READY_AFTER_START : StartupOutcome.READY_RUNNING);
            } else {
                TelemetryOutcome stageOutcome = ready == null
                        ? TelemetryOutcome.SERVER_NOT_READY
                        : TelemetryOutcome.from(ready.outcome());
                telemetry.recordStage(TelemetryOperationType.READINESS_CHECK, mapping.serverName(),
                        stageOutcome, stageElapsed);
                completeStartup(entry, mapping, operation, StartupOutcome.NOT_READY, ready);
            }
        });
        drainNotifications(stageWaiters);
    }

    private void completeStartup(LifecycleEntry entry, ServerMapping mapping,
            CompletableFuture<StartupOutcome> operation, StartupOutcome outcome) {
        completeStartup(entry, mapping, operation, outcome, null);
    }

    private void completeStartup(LifecycleEntry entry, ServerMapping mapping,
            CompletableFuture<StartupOutcome> operation, StartupOutcome outcome,
            ReadinessResult readinessFailure) {
        List<ConnectionWaiter> waiters;
        boolean accepted;
        long startupDurationNanos;
        int waiterCount;
        synchronized (entry) {
            if (shutdown.get()) {
                return;
            }
            accepted = entry.state == ServerLifecycleState.STARTING && entry.startupFuture == operation;
            if (!accepted) {
                return;
            }
            startupDurationNanos = Math.max(0, nanoTime.getAsLong() - entry.startupStartNanos);
            waiterCount = Math.max(entry.peakWaiterCount, entry.waiters.size());
            if (!entry.startupTelemetryRecorded) {
                entry.startupTelemetryRecorded = true;
                TelemetryOutcome teleOutcome = outcome.ready ? TelemetryOutcome.READY : toTelemetryOutcome(outcome);
                telemetry.recordOperation(TelemetryOperationType.STARTUP, mapping.serverName(),
                        TelemetryOrigin.PLAYER_CONNECTION, teleOutcome,
                        Duration.ofNanos(startupDurationNanos), waiterCount);
            }
            entry.startupFuture = null;
            entry.activeOperation = null;
            transition(entry, outcome.ready ? ServerLifecycleState.READY : ServerLifecycleState.FAILED);
            if (outcome.ready) {
                entry.progressStage = ConnectionLifecycleStage.CONNECTING;
                entry.readyConnectionSucceeded = false;
                entry.lastFailure = null;
            } else {
                String detail = readinessFailure == null
                        ? startupFailureDetail(outcome)
                        : readinessFailure.playerDetail();
                entry.lastFailure = failure("server startup", detail,
                        startupRemediation(outcome));
            }
            waiters = new ArrayList<>(entry.waiters.values());
            if (outcome.ready) {
                for (ConnectionWaiter waiter : waiters) {
                    queueStage(waiter, ConnectionLifecycleStage.CONNECTING,
                            stageMessage(ConnectionLifecycleStage.CONNECTING, mapping.serverName()), false);
                }
            }
            if (!outcome.ready) {
                entry.waiters.clear();
                entry.lastConnectionOutcome = outcome.connectionOutcome;
            }
        }

        operation.complete(outcome);
        if (outcome.ready) {
            for (ConnectionWaiter waiter : waiters) {
                connectWaiter(entry, waiter);
                drainNotifications(waiter);
            }
        } else {
            for (ConnectionWaiter waiter : waiters) {
                boolean active = isPlayerActive(waiter.player);
                boolean initialConnection = active && isInitialConnection(waiter.player);
                if (active) {
                    Component failureMessage = AutoStopperMessages.lifecycleFailed(
                            startupFailureMessage(mapping.serverName(), outcome, readinessFailure),
                            elapsed(waiter));
                    queueStage(waiter, ConnectionLifecycleStage.FAILED, failureMessage, initialConnection);
                }
                ConnectionOutcome waiterOutcome = active
                        ? outcome.connectionOutcome
                        : ConnectionOutcome.PLAYER_DISCONNECTED;
                telemetry.recordOperation(TelemetryOperationType.CONNECTION_WAIT, mapping.serverName(),
                        TelemetryOrigin.PLAYER_CONNECTION, TelemetryOutcome.from(waiterOutcome),
                        elapsed(waiter), 0);
                waiter.future.complete(waiterOutcome);
                drainNotifications(waiter);
                if (active && !initialConnection && (outcome == StartupOutcome.NOT_READY
                        || outcome == StartupOutcome.READINESS_ERROR)) {
                    safeSend(waiter.player, AutoStopperMessages.retryServerCommand(mapping.serverName()));
                }
            }
            cleanupRetired(mapping.serverName(), entry);
        }
    }

    private void connectWaiter(LifecycleEntry entry, ConnectionWaiter waiter) {
        if (shutdown.get() || waiter.discarded || waiter.future.isDone()) {
            return;
        }
        if (!isPlayerActive(waiter.player)) {
            finishWaiter(entry, waiter, ConnectionOutcome.PLAYER_DISCONNECTED);
            return;
        }

        String serverName = entry.mapping.serverName();
        ReconnectPermit permit = new ReconnectPermit(waiter.playerId, serverName);
        reconnectPermits.add(permit);
        CompletableFuture<ConnectionRequestBuilder.Result> connection;
        try {
            connection = waiter.player.createConnectionRequest(waiter.targetServer).connect();
        } catch (RuntimeException error) {
            reconnectPermits.remove(permit);
            logger.error("Error creating connection request for server {}", serverName, error);
            finishWaiter(entry, waiter, ConnectionOutcome.CONNECTION_FAILED);
            return;
        }

        if (connection == null) {
            reconnectPermits.remove(permit);
            logger.error("Connection request for server {} returned no future", serverName);
            finishWaiter(entry, waiter, ConnectionOutcome.CONNECTION_FAILED);
            return;
        }
        synchronized (entry) {
            if (shutdown.get() || waiter.discarded || waiter.future.isDone()) {
                reconnectPermits.remove(permit);
                connection.cancel(true);
                return;
            }
            waiter.connectionFuture = connection;
        }

        connection.whenComplete((result, error) -> {
            reconnectPermits.remove(permit);
            if (shutdown.get()) {
                return;
            }
            ConnectionOutcome outcome;
            try {
                if (error != null) {
                    logger.error("Error connecting player to server {}", serverName, unwrap(error));
                    outcome = ConnectionOutcome.CONNECTION_FAILED;
                } else if (result == null) {
                    logger.warn("Connection request for server {} completed without a result", serverName);
                    outcome = ConnectionOutcome.CONNECTION_FAILED;
                } else {
                    ConnectionRequestBuilder.Status status = result.getStatus();
                    outcome = switch (status) {
                        case SUCCESS -> ConnectionOutcome.CONNECTED;
                        case ALREADY_CONNECTED -> ConnectionOutcome.ALREADY_CONNECTED;
                        case CONNECTION_IN_PROGRESS -> ConnectionOutcome.CONNECTION_IN_PROGRESS;
                        case CONNECTION_CANCELLED -> ConnectionOutcome.CONNECTION_CANCELLED;
                        case SERVER_DISCONNECTED -> ConnectionOutcome.SERVER_DISCONNECTED;
                    };
                    if (!outcome.isSuccessful()) {
                        logger.warn("Connection to server {} completed with status {}", serverName, status);
                    }
                }
            } catch (RuntimeException classificationError) {
                logger.error("Could not classify connection result for server {}",
                        serverName, classificationError);
                outcome = ConnectionOutcome.CONNECTION_FAILED;
            }
            finishWaiter(entry, waiter, outcome);
        });
    }

    private void finishWaiter(LifecycleEntry entry, ConnectionWaiter waiter, ConnectionOutcome outcome) {
        boolean owned;
        Duration elapsed = elapsed(waiter);
        int remainingWaiters;
        synchronized (entry) {
            owned = entry.waiters.remove(waiter.playerId, waiter);
            if (!owned) {
                return;
            }
            remainingWaiters = entry.waiters.size();
            touch(entry);
            entry.lastConnectionOutcome = outcome;
            waiter.connectionFuture = null;
            if (outcome.isSuccessful()) {
                entry.lastFailure = null;
                entry.readyConnectionSucceeded = true;
                if (entry.state == ServerLifecycleState.FAILED) {
                    transition(entry, ServerLifecycleState.READY);
                }
            } else if ((outcome == ConnectionOutcome.SERVER_DISCONNECTED
                    || outcome == ConnectionOutcome.CONNECTION_FAILED)
                    && entry.state == ServerLifecycleState.READY
                    && entry.waiters.isEmpty()
                    && !entry.readyConnectionSucceeded) {
                transition(entry, ServerLifecycleState.FAILED);
                entry.lastFailure = failure("player connection",
                        "Velocity could not complete the backend connection: " + outcome,
                        "Check the backend listener and Velocity server address, then retry.");
            }
        }
        telemetry.recordOperation(TelemetryOperationType.CONNECTION_WAIT, entry.mapping.serverName(),
                TelemetryOrigin.PLAYER_CONNECTION, TelemetryOutcome.from(outcome), elapsed, remainingWaiters);
        if (outcome.isSuccessful()) {
            queueStage(waiter, ConnectionLifecycleStage.SUCCEEDED,
                    AutoStopperMessages.lifecycleSucceeded(entry.mapping.serverName(), elapsed(waiter)), false);
        } else if (outcome != ConnectionOutcome.PLAYER_DISCONNECTED
                && outcome != ConnectionOutcome.PROXY_SHUTDOWN) {
            queueStage(waiter, ConnectionLifecycleStage.FAILED,
                    AutoStopperMessages.lifecycleFailed(
                            connectionFailureMessage(entry.mapping.serverName(), outcome), elapsed(waiter)), false);
        }
        waiter.future.complete(outcome);
        drainNotifications(waiter);
        cleanupRetired(entry.mapping.serverName(), entry);
    }

    private void cleanupRetired(String serverName, LifecycleEntry expected) {
        lifecycles.computeIfPresent(serverName, (ignored, current) -> {
            if (current != expected) {
                return current;
            }
            synchronized (current) {
                return current.retired && !current.isBusy() ? null : current;
            }
        });
    }

    private void notifyRejected(Player player, String serverName, ConnectionOutcome outcome) {
        if (outcome == ConnectionOutcome.SERVER_STOPPING) {
            safeSend(player, AutoStopperMessages.serverStopping(serverName));
        } else {
            safeSend(player, AutoStopperMessages.mappingChanged(serverName));
        }
    }

    private Component startupFailureMessage(String serverName, StartupOutcome outcome,
            ReadinessResult readinessFailure) {
        return switch (outcome) {
            case STATUS_NO_MAPPING -> AutoStopperMessages.noContainerMapping(serverName);
            case STATUS_MISSING, START_MISSING -> AutoStopperMessages.containerMissing(serverName);
            case STATUS_INACCESSIBLE -> AutoStopperMessages.dockerUnavailable("manage", serverName);
            case START_INACCESSIBLE -> AutoStopperMessages.dockerUnavailable("start", serverName);
            case STATUS_TIMED_OUT -> AutoStopperMessages.statusCheckTimedOut(serverName);
            case STATUS_FAILED -> AutoStopperMessages.statusCheckFailed(serverName);
            case STATUS_ERROR -> AutoStopperMessages.statusCheckError(serverName);
            case START_TIMED_OUT -> AutoStopperMessages.startTimedOut(serverName);
            case START_FAILED -> AutoStopperMessages.startFailed(serverName);
            case START_ERROR -> AutoStopperMessages.startError(serverName);
            case NOT_READY, READINESS_ERROR -> readinessFailure == null
                    ? AutoStopperMessages.serverNotReady(serverName)
                    : AutoStopperMessages.serverNotReady(serverName, readinessFailure.playerDetail());
            case CANCELLED -> AutoStopperMessages.startCancelled(serverName);
            case OVERLOADED -> AutoStopperMessages.overloaded();
            case READY_RUNNING, READY_AFTER_START -> throw new IllegalArgumentException("ready outcome is not a failure");
        };
    }

    private Component connectionFailureMessage(String serverName, ConnectionOutcome outcome) {
        return switch (outcome) {
            case CONNECTION_IN_PROGRESS -> AutoStopperMessages.connectionInProgress(serverName);
            case CONNECTION_CANCELLED -> AutoStopperMessages.connectionCancelled(serverName);
            case SERVER_DISCONNECTED -> AutoStopperMessages.connectionRefused(serverName);
            default -> AutoStopperMessages.connectionFailed(serverName);
        };
    }

    private List<ConnectionWaiter> recordSharedStage(LifecycleEntry entry,
            CompletableFuture<StartupOutcome> operation, ConnectionLifecycleStage stage) {
        synchronized (entry) {
            if (shutdown.get() || entry.startupFuture != operation
                    || entry.state != ServerLifecycleState.STARTING) {
                return null;
            }
            entry.progressStage = stage;
            List<ConnectionWaiter> waiters = new ArrayList<>(entry.waiters.values());
            Component message = stageMessage(stage, entry.mapping.serverName());
            for (ConnectionWaiter waiter : waiters) {
                queueStage(waiter, stage, message, false);
            }
            return waiters;
        }
    }

    private Component stageMessage(ConnectionLifecycleStage stage, String serverName) {
        return switch (stage) {
            case INSPECTING -> AutoStopperMessages.lifecycleInspecting(serverName);
            case STARTING -> AutoStopperMessages.lifecycleStarting(serverName);
            case WAITING_FOR_READINESS -> AutoStopperMessages.lifecycleWaitingForReadiness(serverName);
            case CONNECTING -> AutoStopperMessages.lifecycleConnecting(serverName);
            case SUCCEEDED, FAILED -> throw new IllegalArgumentException("Terminal stage requires an outcome message");
        };
    }

    private void queueStage(ConnectionWaiter waiter, ConnectionLifecycleStage stage,
            Component message, boolean disconnectInitial) {
        synchronized (waiter) {
            if (waiter.discarded || waiter.notificationsSuppressed || waiter.queuedStages.contains(stage)
                    || waiter.deliveredStages.contains(stage)) {
                return;
            }
            waiter.queuedStages.add(stage);
            waiter.notifications.addLast(new WaiterNotification(
                    Optional.of(stage), message, disconnectInitial));
        }
    }

    private void queueWaitingCount(ConnectionWaiter waiter, int count) {
        synchronized (waiter) {
            if (count <= 1 || waiter.discarded || waiter.notificationsSuppressed
                    || waiter.lastWaitingCountReported == count) {
                return;
            }
            waiter.lastWaitingCountReported = count;
            waiter.notifications.addLast(new WaiterNotification(
                    Optional.empty(), AutoStopperMessages.playersWaiting(count), false));
        }
    }

    private void suppressNotifications(ConnectionWaiter waiter) {
        synchronized (waiter) {
            waiter.notificationsSuppressed = true;
            waiter.notifications.clear();
            waiter.queuedStages.clear();
        }
    }

    private void drainNotifications(List<ConnectionWaiter> waiters) {
        for (ConnectionWaiter waiter : waiters) {
            drainNotifications(waiter);
        }
    }

    private void drainNotifications(ConnectionWaiter waiter) {
        synchronized (waiter) {
            if (waiter.deliveringNotifications) {
                return;
            }
            waiter.deliveringNotifications = true;
        }
        while (true) {
            WaiterNotification notification;
            synchronized (waiter) {
                notification = waiter.notifications.pollFirst();
                if (notification == null) {
                    waiter.deliveringNotifications = false;
                    return;
                }
                notification.stage().ifPresent(waiter.queuedStages::remove);
                if (waiter.discarded || waiter.notificationsSuppressed
                        || notification.stage().map(waiter.deliveredStages::contains).orElse(false)) {
                    continue;
                }
                notification.stage().ifPresent(waiter.deliveredStages::add);
            }
            deliverNotification(waiter, notification);
        }
    }

    private void deliverNotification(ConnectionWaiter waiter, WaiterNotification notification) {
        if (shutdown.get() || waiter.discarded || !isPlayerActive(waiter.player)) {
            return;
        }
        if (notification.disconnectInitial()) {
            try {
                if (waiter.player.getCurrentServer().isEmpty()) {
                    waiter.player.disconnect(notification.message());
                    return;
                }
            } catch (RuntimeException error) {
                logger.debug("Could not inspect or disconnect an initial lifecycle waiter", error);
            }
        }
        safeSend(waiter.player, notification.message());
    }

    private boolean isInitialConnection(Player player) {
        try {
            return player.getCurrentServer().isEmpty();
        } catch (RuntimeException error) {
            logger.debug("Could not inspect whether a lifecycle waiter has a current server", error);
            return false;
        }
    }

    private Duration elapsed(ConnectionWaiter waiter) {
        return Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - waiter.startNanos));
    }

    private void safeSend(Player player, Component message) {
        if (shutdown.get() || !isPlayerActive(player)) {
            return;
        }
        try {
            player.sendMessage(message);
        } catch (RuntimeException error) {
            logger.debug("Could not send lifecycle message to a player", error);
        }
    }

    private boolean isPlayerActive(Player player) {
        try {
            return player.isActive();
        } catch (RuntimeException error) {
            logger.debug("Could not check whether a lifecycle waiter is active", error);
            return false;
        }
    }

    private StartupOutcome exceptionalOutcome(Throwable error, StartupStage stage, String serverName) {
        Throwable cause = unwrap(error);
        if (cause instanceof AutoStopperExecutor.SaturationException) {
            return StartupOutcome.OVERLOADED;
        }
        if (cause instanceof CancellationException || cause instanceof AutoStopperExecutor.ShutdownException) {
            return StartupOutcome.CANCELLED;
        }
        logger.error("Lifecycle {} operation failed for server {}",
                stage.name().toLowerCase(), serverName, cause);
        return switch (stage) {
            case STATUS -> StartupOutcome.STATUS_ERROR;
            case START -> StartupOutcome.START_ERROR;
            case READINESS -> StartupOutcome.READINESS_ERROR;
        };
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private OperationalFailure failure(String context, String detail, String remediation) {
        return new OperationalFailure(Instant.now(), context, detail, remediation);
    }

    private String startupFailureDetail(StartupOutcome outcome) {
        return switch (outcome) {
            case STATUS_NO_MAPPING -> "no active container mapping";
            case STATUS_MISSING, START_MISSING -> "configured container does not exist";
            case STATUS_INACCESSIBLE, START_INACCESSIBLE -> "Docker is unavailable";
            case STATUS_TIMED_OUT, START_TIMED_OUT -> "Docker operation timed out";
            case OVERLOADED -> "AutoStopper worker queue is saturated";
            case CANCELLED -> "startup was cancelled";
            case STATUS_FAILED, STATUS_ERROR, START_FAILED, START_ERROR -> "Docker operation failed";
            case NOT_READY, READINESS_ERROR -> "server readiness check failed";
            case READY_RUNNING, READY_AFTER_START -> throw new IllegalArgumentException("ready outcome is not a failure");
        };
    }

    private String startupRemediation(StartupOutcome outcome) {
        return switch (outcome) {
            case STATUS_NO_MAPPING -> "Reload a valid monitored server mapping.";
            case STATUS_MISSING, START_MISSING -> "Create the container or correct container_name, then retry.";
            case STATUS_INACCESSIBLE, START_INACCESSIBLE -> "Restore Docker daemon and socket access, then retry.";
            case STATUS_TIMED_OUT, START_TIMED_OUT -> "Check Docker daemon responsiveness and host load, then retry.";
            case OVERLOADED -> "Wait for current AutoStopper operations to finish, then retry.";
            case CANCELLED -> "Retry after the current reload or shutdown completes.";
            case STATUS_FAILED, STATUS_ERROR, START_FAILED, START_ERROR -> "Review proxy logs and Docker state, then retry.";
            case NOT_READY, READINESS_ERROR -> "Verify the configured readiness strategy and backend endpoint, then retry.";
            case READY_RUNNING, READY_AFTER_START -> throw new IllegalArgumentException("ready outcome is not a failure");
        };
    }

    private void transition(LifecycleEntry entry, ServerLifecycleState next) {
        if (entry.state == next) {
            return;
        }
        if (!LEGAL_TRANSITIONS.get(entry.state).contains(next)) {
            throw new IllegalStateException("Illegal lifecycle transition for " + entry.mapping.serverName()
                    + ": " + entry.state + " -> " + next);
        }
        logger.debug("Server {} lifecycle transitioned from {} to {}",
                entry.mapping.serverName(), entry.state, next);
        entry.state = next;
        touch(entry);
    }

    private long nextRevision() {
        return lifecycleRevision.incrementAndGet();
    }

    private void touch(LifecycleEntry entry) {
        entry.revision = nextRevision();
    }

    private LifecycleStatusSnapshot snapshot(LifecycleEntry entry) {
        return new LifecycleStatusSnapshot(Optional.of(entry.state), entry.waiters.size(),
                Optional.ofNullable(entry.lastFailure), entry.revision);
    }

    private boolean ownOperation(LifecycleEntry entry, CompletableFuture<StartupOutcome> startup,
            CompletableFuture<?> operation) {
        synchronized (entry) {
            if (shutdown.get() || entry.startupFuture != startup
                    || entry.state != ServerLifecycleState.STARTING) {
                operation.cancel(true);
                return false;
            }
            entry.activeOperation = operation;
            return true;
        }
    }

    private static Map<ServerLifecycleState, Set<ServerLifecycleState>> legalTransitions() {
        Map<ServerLifecycleState, Set<ServerLifecycleState>> transitions =
                new EnumMap<>(ServerLifecycleState.class);
        transitions.put(ServerLifecycleState.STOPPED,
                EnumSet.of(ServerLifecycleState.STARTING, ServerLifecycleState.READY,
                        ServerLifecycleState.STOPPING));
        transitions.put(ServerLifecycleState.STARTING,
                EnumSet.of(ServerLifecycleState.READY, ServerLifecycleState.FAILED));
        transitions.put(ServerLifecycleState.READY,
                EnumSet.of(ServerLifecycleState.STOPPING, ServerLifecycleState.FAILED,
                        ServerLifecycleState.STOPPED));
        transitions.put(ServerLifecycleState.STOPPING,
                EnumSet.of(ServerLifecycleState.STOPPED, ServerLifecycleState.FAILED,
                        ServerLifecycleState.READY));
        transitions.put(ServerLifecycleState.FAILED,
                EnumSet.of(ServerLifecycleState.STARTING, ServerLifecycleState.READY,
                        ServerLifecycleState.STOPPING, ServerLifecycleState.STOPPED));
        return Map.copyOf(transitions);
    }

    private static final class LifecycleEntry {
        private final ServerMapping mapping;
        private final Map<UUID, ConnectionWaiter> waiters = new LinkedHashMap<>();
        private ServerLifecycleState state = ServerLifecycleState.STOPPED;
        private ConnectionLifecycleStage progressStage;
        private CompletableFuture<StartupOutcome> startupFuture;
        private CompletableFuture<?> activeOperation;
        private ConnectionOutcome lastConnectionOutcome;
        private OperationalFailure lastFailure;
        private boolean readyConnectionSucceeded;
        private boolean retired;
        private long revision;
        private long startupStartNanos;
        private int peakWaiterCount;
        private boolean startupTelemetryRecorded;

        private LifecycleEntry(ServerMapping mapping, long revision) {
            this.mapping = mapping;
            this.revision = revision;
        }

        private boolean isBusy() {
            return state == ServerLifecycleState.STARTING
                    || state == ServerLifecycleState.STOPPING
                    || !waiters.isEmpty();
        }
    }

    private static final class ConnectionWaiter {
        private final UUID playerId;
        private final Player player;
        private final RegisteredServer targetServer;
        private final String serverName;
        private final CompletableFuture<ConnectionOutcome> future = new CompletableFuture<>();
        private final long startNanos;
        private final ArrayDeque<WaiterNotification> notifications = new ArrayDeque<>();
        private final Set<ConnectionLifecycleStage> queuedStages =
                EnumSet.noneOf(ConnectionLifecycleStage.class);
        private final Set<ConnectionLifecycleStage> deliveredStages =
                EnumSet.noneOf(ConnectionLifecycleStage.class);
        private volatile boolean discarded;
        private boolean notificationsSuppressed;
        private boolean deliveringNotifications;
        private int lastWaitingCountReported;
        private CompletableFuture<ConnectionRequestBuilder.Result> connectionFuture;

        private ConnectionWaiter(UUID playerId, Player player, RegisteredServer targetServer,
                String serverName, long startNanos) {
            this.playerId = playerId;
            this.player = player;
            this.targetServer = targetServer;
            this.serverName = serverName;
            this.startNanos = startNanos;
        }
    }

    private record WaiterNotification(Optional<ConnectionLifecycleStage> stage, Component message,
            boolean disconnectInitial) {
    }

    private static final class Admission {
        private final LifecycleEntry entry;
        private final ConnectionWaiter waiter;
        private final CompletableFuture<StartupOutcome> startupFuture;
        private final ConnectionOutcome rejectedOutcome;
        private final boolean launchStartup;
        private final boolean connectNow;
        private final boolean queued;

        private Admission(LifecycleEntry entry, ConnectionWaiter waiter,
                CompletableFuture<StartupOutcome> startupFuture, ConnectionOutcome rejectedOutcome,
                boolean launchStartup, boolean connectNow, boolean queued) {
            this.entry = entry;
            this.waiter = waiter;
            this.startupFuture = startupFuture;
            this.rejectedOutcome = rejectedOutcome;
            this.launchStartup = launchStartup;
            this.connectNow = connectNow;
            this.queued = queued;
        }

        private static Admission start(LifecycleEntry entry, ConnectionWaiter waiter,
                CompletableFuture<StartupOutcome> startupFuture) {
            return new Admission(entry, waiter, startupFuture, null, true, false, false);
        }

        private static Admission connect(LifecycleEntry entry, ConnectionWaiter waiter) {
            return new Admission(entry, waiter, null, null, false, true, false);
        }

        private static Admission queued(LifecycleEntry entry, ConnectionWaiter waiter) {
            return new Admission(entry, waiter, null, null, false, false, true);
        }

        private static Admission rejected(ConnectionOutcome outcome) {
            return new Admission(null, null, null, outcome, false, false, false);
        }
    }

    private enum StartupStage {
        STATUS,
        START,
        READINESS
    }

    private enum StartupOutcome {
        READY_RUNNING(true, ConnectionOutcome.CONNECTED),
        READY_AFTER_START(true, ConnectionOutcome.CONNECTED),
        STATUS_NO_MAPPING(false, ConnectionOutcome.STATUS_FAILED),
        STATUS_MISSING(false, ConnectionOutcome.CONTAINER_MISSING),
        STATUS_INACCESSIBLE(false, ConnectionOutcome.DOCKER_INACCESSIBLE),
        STATUS_TIMED_OUT(false, ConnectionOutcome.STATUS_FAILED),
        STATUS_FAILED(false, ConnectionOutcome.STATUS_FAILED),
        STATUS_ERROR(false, ConnectionOutcome.STATUS_FAILED),
        START_MISSING(false, ConnectionOutcome.CONTAINER_MISSING),
        START_INACCESSIBLE(false, ConnectionOutcome.DOCKER_INACCESSIBLE),
        START_TIMED_OUT(false, ConnectionOutcome.START_TIMED_OUT),
        START_FAILED(false, ConnectionOutcome.START_FAILED),
        START_ERROR(false, ConnectionOutcome.START_FAILED),
        NOT_READY(false, ConnectionOutcome.SERVER_NOT_READY),
        READINESS_ERROR(false, ConnectionOutcome.SERVER_NOT_READY),
        CANCELLED(false, ConnectionOutcome.START_CANCELLED),
        OVERLOADED(false, ConnectionOutcome.OVERLOADED);

        private final boolean ready;
        private final ConnectionOutcome connectionOutcome;

        StartupOutcome(boolean ready, ConnectionOutcome connectionOutcome) {
            this.ready = ready;
            this.connectionOutcome = connectionOutcome;
        }

        boolean isReady() {
            return ready;
        }
    }

    private TelemetryOutcome toTelemetryOutcome(StartupOutcome outcome) {
        return switch (outcome) {
            case READY_RUNNING, READY_AFTER_START -> TelemetryOutcome.READY;
            case STATUS_NO_MAPPING -> TelemetryOutcome.NO_MAPPING;
            case STATUS_MISSING, START_MISSING -> TelemetryOutcome.CONTAINER_MISSING;
            case STATUS_INACCESSIBLE, START_INACCESSIBLE -> TelemetryOutcome.DOCKER_INACCESSIBLE;
            case STATUS_TIMED_OUT -> TelemetryOutcome.STATUS_TIMED_OUT;
            case STATUS_FAILED, STATUS_ERROR -> TelemetryOutcome.STATUS_FAILED;
            case START_TIMED_OUT -> TelemetryOutcome.START_TIMED_OUT;
            case START_FAILED, START_ERROR -> TelemetryOutcome.START_FAILED;
            case NOT_READY, READINESS_ERROR -> TelemetryOutcome.SERVER_NOT_READY;
            case CANCELLED -> TelemetryOutcome.CANCELLED;
            case OVERLOADED -> TelemetryOutcome.OVERLOADED;
        };
    }

    private void executeManualStop(LifecycleEntry entry, ServerMapping mapping,
            RegisteredServer registeredServer, CompletableFuture<ManualStopOutcome> stopFuture) {
        try {
            executor.supply(() -> {
                synchronized (entry) {
                    if (shutdown.get()) {
                        abortStopUnderLock(entry, stopFuture, ManualStopOutcome.PROXY_SHUTDOWN);
                        return ManualStopOutcome.PROXY_SHUTDOWN;
                    }
                    if (entry.activeOperation != stopFuture || entry.state != ServerLifecycleState.STOPPING) {
                        abortStopUnderLock(entry, stopFuture, ManualStopOutcome.CANCELLED);
                        return ManualStopOutcome.CANCELLED;
                    }
                    if (registeredServer != null && !registeredServer.getPlayersConnected().isEmpty()) {
                        abortStopUnderLock(entry, stopFuture, ManualStopOutcome.PLAYERS_CONNECTED);
                        return ManualStopOutcome.PLAYERS_CONNECTED;
                    }
                    if (!entry.waiters.isEmpty()) {
                        abortStopUnderLock(entry, stopFuture, ManualStopOutcome.WAITERS_PRESENT);
                        return ManualStopOutcome.WAITERS_PRESENT;
                    }
                }

                ContainerStatus result = serverManager.stopServer(mapping);
                return completeManualStop(entry, mapping, stopFuture, result);
            }).exceptionally(error -> {
                synchronized (entry) {
                    abortStopUnderLock(entry, stopFuture, exceptionalStopOutcome(error));
                }
                return null;
            });
        } catch (AutoStopperExecutor.SaturationException e) {
            synchronized (entry) {
                abortStopUnderLock(entry, stopFuture, ManualStopOutcome.OVERLOADED);
            }
        } catch (RuntimeException e) {
            synchronized (entry) {
                abortStopUnderLock(entry, stopFuture, ManualStopOutcome.STOP_FAILED);
            }
        }
    }

    private ManualStopOutcome completeManualStop(LifecycleEntry entry, ServerMapping mapping,
            CompletableFuture<ManualStopOutcome> operation, ContainerStatus result) {
        synchronized (entry) {
            if (shutdown.get() || entry.activeOperation != operation || entry.state != ServerLifecycleState.STOPPING) {
                transition(entry, result == ContainerStatus.STOPPED
                        ? ServerLifecycleState.STOPPED : ServerLifecycleState.FAILED);
                operation.complete(ManualStopOutcome.PROXY_SHUTDOWN);
                return ManualStopOutcome.PROXY_SHUTDOWN;
            }
            entry.activeOperation = null;
            if (result == ContainerStatus.STOPPED) {
                transition(entry, ServerLifecycleState.STOPPED);
                entry.lastFailure = null;
                operation.complete(ManualStopOutcome.STOPPED);
                return ManualStopOutcome.STOPPED;
            } else {
                transition(entry, ServerLifecycleState.FAILED);
                entry.lastFailure = failure("manual stop",
                        "container stop failed with " + result,
                        "Check Docker access and container state, then retry.");
                ManualStopOutcome outcome = toManualStopOutcome(result);
                operation.complete(outcome);
                return outcome;
            }
        }
    }

    private void executeManualRestart(LifecycleEntry entry, ServerMapping mapping,
            RegisteredServer registeredServer, CompletableFuture<ManualRestartOutcome> restartFuture) {
        try {
            executor.supply(() -> {
                synchronized (entry) {
                    if (shutdown.get()) {
                        abortStopUnderLock(entry, restartFuture, ManualRestartOutcome.PROXY_SHUTDOWN);
                        return null;
                    }
                    if (entry.activeOperation != restartFuture || entry.state != ServerLifecycleState.STOPPING) {
                        abortStopUnderLock(entry, restartFuture, ManualRestartOutcome.CANCELLED);
                        return null;
                    }
                    if (registeredServer != null && !registeredServer.getPlayersConnected().isEmpty()) {
                        abortStopUnderLock(entry, restartFuture, ManualRestartOutcome.PLAYERS_CONNECTED);
                        return null;
                    }
                    if (!entry.waiters.isEmpty()) {
                        abortStopUnderLock(entry, restartFuture, ManualRestartOutcome.WAITERS_PRESENT);
                        return null;
                    }
                }

                ContainerStatus stopResult = serverManager.stopServer(mapping);
                CompletableFuture<StartupOutcome> startupFuture;
                synchronized (entry) {
                    if (shutdown.get() || entry.activeOperation != restartFuture) {
                        transition(entry, stopResult == ContainerStatus.STOPPED
                                ? ServerLifecycleState.STOPPED : ServerLifecycleState.FAILED);
                        restartFuture.complete(ManualRestartOutcome.PROXY_SHUTDOWN);
                        return null;
                    }
                    if (stopResult != ContainerStatus.STOPPED) {
                        transition(entry, ServerLifecycleState.FAILED);
                        entry.lastFailure = failure("container stop during restart",
                                "container stop failed with " + stopResult,
                                "Check Docker access and container state, then retry.");
                        entry.activeOperation = null;
                        restartFuture.complete(toRestartStopFailure(stopResult));
                        return null;
                    }

                    transition(entry, ServerLifecycleState.STOPPED);
                    transition(entry, ServerLifecycleState.STARTING);
                    entry.progressStage = ConnectionLifecycleStage.STARTING;
                    entry.startupStartNanos = nanoTime.getAsLong();
                    entry.peakWaiterCount = 0;
                    entry.startupTelemetryRecorded = false;
                    startupFuture = new CompletableFuture<>();
                    entry.startupFuture = startupFuture;
                }

                launchStart(entry, mapping, startupFuture);
                startupFuture.whenComplete((outcome, error) -> {
                    synchronized (entry) {
                        if (entry.activeOperation == restartFuture) {
                            entry.activeOperation = null;
                        }
                    }
                    if (error != null) {
                        restartFuture.complete(exceptionalRestartOutcome(error));
                    } else if (outcome != null && outcome.isReady()) {
                        restartFuture.complete(ManualRestartOutcome.RESTARTED_AND_READY);
                    } else if (outcome != null) {
                        restartFuture.complete(toManualRestartOutcome(outcome));
                    } else {
                        restartFuture.complete(ManualRestartOutcome.START_FAILED);
                    }
                });
                return null;
            }).exceptionally(error -> {
                synchronized (entry) {
                    abortStopUnderLock(entry, restartFuture, exceptionalRestartOutcome(error));
                }
                return null;
            });
        } catch (AutoStopperExecutor.SaturationException e) {
            synchronized (entry) {
                abortStopUnderLock(entry, restartFuture, ManualRestartOutcome.OVERLOADED);
            }
        } catch (RuntimeException e) {
            synchronized (entry) {
                abortStopUnderLock(entry, restartFuture, ManualRestartOutcome.STOP_FAILED);
            }
        }
    }

    private <T> void abortStopUnderLock(LifecycleEntry entry, CompletableFuture<T> operation, T outcome) {
        if (entry.state == ServerLifecycleState.STOPPING) {
            transition(entry, ServerLifecycleState.READY);
        }
        if (entry.activeOperation == operation) {
            entry.activeOperation = null;
        }
        operation.complete(outcome);
    }

    private ManualStartOutcome toManualStartOutcome(StartupOutcome outcome) {
        if (outcome == null) {
            return ManualStartOutcome.START_FAILED;
        }
        return switch (outcome) {
            case READY_RUNNING, READY_AFTER_START -> ManualStartOutcome.READY;
            case STATUS_NO_MAPPING, CANCELLED -> ManualStartOutcome.CANCELLED;
            case STATUS_MISSING, START_MISSING -> ManualStartOutcome.CONTAINER_MISSING;
            case STATUS_INACCESSIBLE, START_INACCESSIBLE -> ManualStartOutcome.DOCKER_INACCESSIBLE;
            case STATUS_TIMED_OUT -> ManualStartOutcome.STATUS_TIMED_OUT;
            case STATUS_FAILED, STATUS_ERROR -> ManualStartOutcome.STATUS_FAILED;
            case START_TIMED_OUT -> ManualStartOutcome.START_TIMED_OUT;
            case START_FAILED, START_ERROR -> ManualStartOutcome.START_FAILED;
            case NOT_READY, READINESS_ERROR -> ManualStartOutcome.SERVER_NOT_READY;
            case OVERLOADED -> ManualStartOutcome.OVERLOADED;
        };
    }

    private ManualStopOutcome toManualStopOutcome(ContainerStatus status) {
        return switch (status) {
            case STOPPED -> ManualStopOutcome.STOPPED;
            case MISSING -> ManualStopOutcome.CONTAINER_MISSING;
            case INACCESSIBLE -> ManualStopOutcome.DOCKER_INACCESSIBLE;
            case TIMED_OUT -> ManualStopOutcome.STOP_TIMED_OUT;
            case RUNNING, FAILED -> ManualStopOutcome.STOP_FAILED;
        };
    }

    private ManualRestartOutcome toRestartStopFailure(ContainerStatus status) {
        return switch (status) {
            case STOPPED -> throw new IllegalArgumentException("stopped is not a failure");
            case MISSING -> ManualRestartOutcome.CONTAINER_MISSING;
            case INACCESSIBLE -> ManualRestartOutcome.DOCKER_INACCESSIBLE;
            case TIMED_OUT -> ManualRestartOutcome.STOP_TIMED_OUT;
            case RUNNING, FAILED -> ManualRestartOutcome.STOP_FAILED;
        };
    }

    private ManualRestartOutcome toManualRestartOutcome(StartupOutcome outcome) {
        if (outcome == null) {
            return ManualRestartOutcome.START_FAILED;
        }
        return switch (outcome) {
            case READY_RUNNING, READY_AFTER_START -> ManualRestartOutcome.RESTARTED_AND_READY;
            case STATUS_NO_MAPPING, CANCELLED -> ManualRestartOutcome.CANCELLED;
            case STATUS_MISSING, START_MISSING -> ManualRestartOutcome.CONTAINER_MISSING;
            case STATUS_INACCESSIBLE, START_INACCESSIBLE -> ManualRestartOutcome.DOCKER_INACCESSIBLE;
            case STATUS_TIMED_OUT, START_TIMED_OUT -> ManualRestartOutcome.START_TIMED_OUT;
            case STATUS_FAILED, STATUS_ERROR, START_FAILED, START_ERROR -> ManualRestartOutcome.START_FAILED;
            case NOT_READY, READINESS_ERROR -> ManualRestartOutcome.SERVER_NOT_READY;
            case OVERLOADED -> ManualRestartOutcome.OVERLOADED;
        };
    }

    private ManualStartOutcome exceptionalStartOutcome(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof AutoStopperExecutor.SaturationException) {
            return ManualStartOutcome.OVERLOADED;
        }
        if (cause instanceof CancellationException || cause instanceof AutoStopperExecutor.ShutdownException) {
            return ManualStartOutcome.CANCELLED;
        }
        return ManualStartOutcome.START_FAILED;
    }

    private ManualStopOutcome exceptionalStopOutcome(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof AutoStopperExecutor.SaturationException) {
            return ManualStopOutcome.OVERLOADED;
        }
        if (cause instanceof CancellationException || cause instanceof AutoStopperExecutor.ShutdownException) {
            return ManualStopOutcome.CANCELLED;
        }
        return ManualStopOutcome.STOP_FAILED;
    }

    private ManualRestartOutcome exceptionalRestartOutcome(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof AutoStopperExecutor.SaturationException) {
            return ManualRestartOutcome.OVERLOADED;
        }
        if (cause instanceof CancellationException || cause instanceof AutoStopperExecutor.ShutdownException) {
            return ManualRestartOutcome.CANCELLED;
        }
        return ManualRestartOutcome.STOP_FAILED;
    }

    private static final class ManualStartAdmission {
        private final LifecycleEntry entry;
        private final CompletableFuture<StartupOutcome> startupFuture;
        private final ManualStartOutcome rejectedOutcome;
        private final ManualStartOutcome completedOutcome;
        private final boolean launchStartup;

        private ManualStartAdmission(LifecycleEntry entry,
                CompletableFuture<StartupOutcome> startupFuture,
                ManualStartOutcome rejectedOutcome,
                ManualStartOutcome completedOutcome,
                boolean launchStartup) {
            this.entry = entry;
            this.startupFuture = startupFuture;
            this.rejectedOutcome = rejectedOutcome;
            this.completedOutcome = completedOutcome;
            this.launchStartup = launchStartup;
        }

        static ManualStartAdmission start(LifecycleEntry entry, CompletableFuture<StartupOutcome> startupFuture) {
            return new ManualStartAdmission(entry, startupFuture, null, null, true);
        }

        static ManualStartAdmission track(CompletableFuture<StartupOutcome> startupFuture) {
            return new ManualStartAdmission(null, startupFuture, null, null, false);
        }

        static ManualStartAdmission completed(ManualStartOutcome outcome) {
            return new ManualStartAdmission(null, null, null, outcome, false);
        }

        static ManualStartAdmission rejected(ManualStartOutcome outcome) {
            return new ManualStartAdmission(null, null, outcome, null, false);
        }
    }

    private static final class ManualStopAdmission {
        private final LifecycleEntry entry;
        private final CompletableFuture<ManualStopOutcome> stopFuture;
        private final ManualStopOutcome rejectedOutcome;
        private final ManualStopOutcome completedOutcome;

        private ManualStopAdmission(LifecycleEntry entry,
                CompletableFuture<ManualStopOutcome> stopFuture,
                ManualStopOutcome rejectedOutcome,
                ManualStopOutcome completedOutcome) {
            this.entry = entry;
            this.stopFuture = stopFuture;
            this.rejectedOutcome = rejectedOutcome;
            this.completedOutcome = completedOutcome;
        }

        static ManualStopAdmission start(LifecycleEntry entry, CompletableFuture<ManualStopOutcome> stopFuture) {
            return new ManualStopAdmission(entry, stopFuture, null, null);
        }

        static ManualStopAdmission completed(ManualStopOutcome outcome) {
            return new ManualStopAdmission(null, null, null, outcome);
        }

        static ManualStopAdmission rejected(ManualStopOutcome outcome) {
            return new ManualStopAdmission(null, null, outcome, null);
        }
    }

    private static final class ManualRestartAdmission {
        private final LifecycleEntry entry;
        private final CompletableFuture<ManualRestartOutcome> restartFuture;
        private final CompletableFuture<StartupOutcome> startupFuture;
        private final ManualRestartOutcome rejectedOutcome;
        private final boolean startOnly;

        private ManualRestartAdmission(LifecycleEntry entry,
                CompletableFuture<ManualRestartOutcome> restartFuture,
                CompletableFuture<StartupOutcome> startupFuture,
                ManualRestartOutcome rejectedOutcome,
                boolean startOnly) {
            this.entry = entry;
            this.restartFuture = restartFuture;
            this.startupFuture = startupFuture;
            this.rejectedOutcome = rejectedOutcome;
            this.startOnly = startOnly;
        }

        static ManualRestartAdmission stopThenStart(LifecycleEntry entry,
                CompletableFuture<ManualRestartOutcome> restartFuture) {
            return new ManualRestartAdmission(entry, restartFuture, null, null, false);
        }

        static ManualRestartAdmission startOnly(LifecycleEntry entry,
                CompletableFuture<ManualRestartOutcome> restartFuture,
                CompletableFuture<StartupOutcome> startupFuture) {
            return new ManualRestartAdmission(entry, restartFuture, startupFuture, null, true);
        }

        static ManualRestartAdmission rejected(ManualRestartOutcome outcome) {
            return new ManualRestartAdmission(null, null, null, outcome, false);
        }
    }

    private record ReconnectPermit(UUID playerId, String serverName) {
    }
}
