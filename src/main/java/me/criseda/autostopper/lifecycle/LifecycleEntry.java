package me.criseda.autostopper.lifecycle;

import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.operational.OperationalFailure;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Lifecycle state machine for one mapped server. Every state change goes through a method here,
 * which enforces the legal transitions and bumps {@link #revision()} so that status readers can
 * detect concurrent change.
 *
 * <p>Not thread-safe on its own: callers must hold this entry's monitor for every method except
 * {@link #mapping()}, which is immutable.
 */
final class LifecycleEntry {
    private final ServerMapping mapping;
    private final LongSupplier revisions;
    private final Logger logger;
    private final Map<UUID, ConnectionWaiter> waiters = new LinkedHashMap<>();
    private ServerLifecycleState state = ServerLifecycleState.STOPPED;
    private ConnectionLifecycleStage progressStage;
    private CompletableFuture<StartupOutcome> startupFuture;
    private CompletableFuture<?> activeOperation;
    private CompletableFuture<ManualStopOutcome> manualStop;
    private ConnectionOutcome lastConnectionOutcome;
    private OperationalFailure lastFailure;
    private boolean readyConnectionSucceeded;
    private boolean retired;
    private long revision;
    private long startupStartNanos;
    private int peakWaiterCount;
    private boolean startupTelemetryRecorded;

    LifecycleEntry(ServerMapping mapping, LongSupplier revisions, Logger logger) {
        this.mapping = mapping;
        this.revisions = revisions;
        this.logger = logger;
        this.revision = revisions.getAsLong();
    }

    // --- Queries ---

    ServerMapping mapping() {
        return mapping;
    }

    boolean matches(ServerMapping other) {
        return mapping.equals(other);
    }

    ServerLifecycleState state() {
        return state;
    }

    boolean is(ServerLifecycleState expected) {
        return state == expected;
    }

    boolean isBusy() {
        return state == ServerLifecycleState.STARTING
                || state == ServerLifecycleState.STOPPING
                || !waiters.isEmpty();
    }

    boolean isRetired() {
        return retired;
    }

    /** A retired entry with no work left can be dropped from the lifecycle map. */
    boolean isDisposable() {
        return retired && !isBusy();
    }

    long revision() {
        return revision;
    }

    ConnectionLifecycleStage progressStage() {
        return progressStage;
    }

    Optional<CompletableFuture<StartupOutcome>> startupFuture() {
        return Optional.ofNullable(startupFuture);
    }

    Optional<OperationalFailure> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    Optional<ConnectionOutcome> lastConnectionOutcome() {
        return Optional.ofNullable(lastConnectionOutcome);
    }

    LifecycleStatusSnapshot snapshot() {
        return new LifecycleStatusSnapshot(Optional.of(state), waiters.size(),
                Optional.ofNullable(lastFailure), revision);
    }

    // --- Waiters ---

    int waiterCount() {
        return waiters.size();
    }

    boolean hasWaiters() {
        return !waiters.isEmpty();
    }

    ConnectionWaiter waiter(UUID playerId) {
        return waiters.get(playerId);
    }

    List<ConnectionWaiter> waiters() {
        return new ArrayList<>(waiters.values());
    }

    void addWaiter(ConnectionWaiter waiter) {
        waiters.put(waiter.playerId(), waiter);
        touch();
    }

    ConnectionWaiter removeWaiter(UUID playerId) {
        ConnectionWaiter removed = waiters.remove(playerId);
        if (removed != null) {
            touch();
        }
        return removed;
    }

    boolean removeWaiter(ConnectionWaiter waiter) {
        boolean removed = waiters.remove(waiter.playerId(), waiter);
        if (removed) {
            touch();
        }
        return removed;
    }

    /** Remembers the largest number of players that waited on the current startup. */
    void notePeakWaiters() {
        peakWaiterCount = Math.max(peakWaiterCount, waiters.size());
    }

    // --- Startup ---

    /** Moves to STARTING and opens a new startup operation owned by the returned future. */
    CompletableFuture<StartupOutcome> beginStartup(ConnectionLifecycleStage stage, long startNanos,
            int initialWaiterCount) {
        transition(ServerLifecycleState.STARTING);
        progressStage = stage;
        startupStartNanos = startNanos;
        peakWaiterCount = initialWaiterCount;
        startupTelemetryRecorded = false;
        CompletableFuture<StartupOutcome> operation = new CompletableFuture<>();
        startupFuture = operation;
        return operation;
    }

    /** Whether {@code operation} is still the live startup for this entry. */
    boolean ownsStartup(CompletableFuture<StartupOutcome> operation) {
        return state == ServerLifecycleState.STARTING && startupFuture == operation;
    }

    void advanceStartup(ConnectionLifecycleStage stage) {
        progressStage = stage;
    }

    long startupStartNanos() {
        return startupStartNanos;
    }

    int startupWaiterCount() {
        return Math.max(peakWaiterCount, waiters.size());
    }

    /** Returns {@code true} exactly once per startup, for whoever records its telemetry. */
    boolean claimStartupTelemetry() {
        if (startupTelemetryRecorded) {
            return false;
        }
        startupTelemetryRecorded = true;
        return true;
    }

    /**
     * Closes the current startup. On success the entry becomes READY and keeps its waiters for
     * connection; on failure it becomes FAILED and hands back every waiter, now detached.
     *
     * @return the waiters present when startup finished
     */
    List<ConnectionWaiter> finishStartup(StartupOutcome outcome, OperationalFailure failure) {
        startupFuture = null;
        activeOperation = null;
        List<ConnectionWaiter> finished = waiters();
        if (outcome.isReady()) {
            transition(ServerLifecycleState.READY);
            progressStage = ConnectionLifecycleStage.CONNECTING;
            readyConnectionSucceeded = false;
            lastFailure = null;
        } else {
            transition(ServerLifecycleState.FAILED);
            lastFailure = failure;
            waiters.clear();
            lastConnectionOutcome = outcome.connectionOutcome();
        }
        return finished;
    }

    // --- Operations (Docker and readiness calls, cancelled at shutdown) ---

    void attachOperation(CompletableFuture<?> operation) {
        activeOperation = operation;
    }

    boolean ownsOperation(CompletableFuture<?> operation) {
        return activeOperation == operation;
    }

    void detachOperation(CompletableFuture<?> operation) {
        if (activeOperation == operation) {
            activeOperation = null;
        }
    }

    // --- Stop ---

    void beginStop() {
        transition(ServerLifecycleState.STOPPING);
    }

    /** Hands the current stop to an operator command, which shutdown completes with PROXY_SHUTDOWN. */
    void attachManualStop(CompletableFuture<ManualStopOutcome> stop) {
        manualStop = stop;
    }

    /** Whether {@code stop} is still the live manual stop for this entry. */
    boolean ownsManualStop(CompletableFuture<ManualStopOutcome> stop) {
        return state == ServerLifecycleState.STOPPING && manualStop == stop;
    }

    void detachManualStop(CompletableFuture<ManualStopOutcome> stop) {
        if (manualStop == stop) {
            manualStop = null;
        }
    }

    /** Moves to STOPPED or FAILED to match what Docker did, without touching the recorded failure. */
    void applyStopResult(ContainerStatus result) {
        transition(result == ContainerStatus.STOPPED ? ServerLifecycleState.STOPPED : ServerLifecycleState.FAILED);
    }

    /** Applies a Docker stop result and records {@code failure} unless the container stopped. */
    void finishStop(ContainerStatus result, Supplier<OperationalFailure> failure) {
        applyStopResult(result);
        lastFailure = result == ContainerStatus.STOPPED ? null : failure.get();
    }

    /** Abandons an in-progress stop; the backend is still serving. */
    void cancelStop() {
        if (state == ServerLifecycleState.STOPPING) {
            transition(ServerLifecycleState.READY);
        }
    }

    // --- Observed backend state ---

    /** Records that a player reached the backend outside the startup path. */
    void markReady() {
        if (state == ServerLifecycleState.STOPPED || state == ServerLifecycleState.FAILED) {
            transition(ServerLifecycleState.READY);
        }
        lastFailure = null;
        readyConnectionSucceeded = true;
        touch();
    }

    /** Records that Docker reports the container stopped although AutoStopper did not stop it. */
    void markStoppedExternally() {
        if (state == ServerLifecycleState.READY || state == ServerLifecycleState.FAILED) {
            transition(ServerLifecycleState.STOPPED);
            lastFailure = null;
            readyConnectionSucceeded = false;
        }
    }

    /**
     * Records how one waiter's connection ended.
     *
     * @return {@code true} when the backend previously served players and is now refusing them,
     *         so Docker should be asked whether it was stopped externally
     */
    boolean recordConnectionOutcome(ConnectionOutcome outcome, Supplier<OperationalFailure> neverConnected) {
        lastConnectionOutcome = outcome;
        if (outcome.isSuccessful()) {
            lastFailure = null;
            readyConnectionSucceeded = true;
            if (state == ServerLifecycleState.FAILED) {
                transition(ServerLifecycleState.READY);
            }
            return false;
        }
        boolean refused = outcome == ConnectionOutcome.SERVER_DISCONNECTED
                || outcome == ConnectionOutcome.CONNECTION_FAILED;
        if (!refused || state != ServerLifecycleState.READY || !waiters.isEmpty()) {
            return false;
        }
        if (!readyConnectionSucceeded) {
            transition(ServerLifecycleState.FAILED);
            lastFailure = neverConnected.get();
            return false;
        }
        // A backend that served players before may have been stopped outside AutoStopper.
        // A kick from a still-running backend must not demote READY, so ask Docker first.
        return !retired;
    }

    // --- Configuration ---

    /** Marks a replaced or removed mapping; in-flight work finishes but players hear nothing more. */
    void retire() {
        retired = true;
        for (ConnectionWaiter waiter : waiters.values()) {
            waiter.suppressNotifications();
        }
        touch();
    }

    void unretire() {
        retired = false;
    }

    // --- Shutdown ---

    /**
     * Detaches everything still in flight into {@code drain}, so the caller can end it outside the
     * lock: operations to cancel, the startup and manual stop to complete with PROXY_SHUTDOWN, and
     * the stranded waiters. A startup that has not recorded its telemetry is reported as interrupted.
     */
    void drainForShutdown(ShutdownDrain drain) {
        if (activeOperation != null) {
            drain.addOperation(activeOperation);
            activeOperation = null;
        }
        if (manualStop != null) {
            drain.addManualStop(manualStop);
            manualStop = null;
        }
        if (startupFuture != null) {
            if (claimStartupTelemetry()) {
                drain.addInterruptedStartup(mapping.serverName(), startupStartNanos);
            }
            drain.addStartup(startupFuture);
            startupFuture = null;
        }
        for (ConnectionWaiter waiter : waiters.values()) {
            waiter.discard();
            CompletableFuture<?> connection = waiter.detachConnection();
            if (connection != null) {
                drain.addOperation(connection);
            }
            drain.addWaiter(waiter);
        }
        waiters.clear();
        lastConnectionOutcome = ConnectionOutcome.PROXY_SHUTDOWN;
    }

    private void transition(ServerLifecycleState next) {
        if (state == next) {
            return;
        }
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal lifecycle transition for " + mapping.serverName()
                    + ": " + state + " -> " + next);
        }
        logger.debug("Server {} lifecycle transitioned from {} to {}", mapping.serverName(), state, next);
        state = next;
        touch();
    }

    private void touch() {
        revision = revisions.getAsLong();
    }
}
