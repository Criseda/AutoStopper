package me.criseda.autostopper.telemetry;

import java.time.Duration;
import java.util.List;

/**
 * Service contract for recording authoritative lifecycle operation and intermediate stage telemetry.
 */
public interface LifecycleTelemetry {

    /**
     * Returns the current monotonic timestamp in nanoseconds.
     */
    long currentNanos();

    /**
     * Calculates the monotonic duration elapsed since the given nanosecond timestamp.
     */
    Duration elapsedSince(long startNanos);

    /**
     * Records an authoritative lifecycle operation completion, updating aggregates and emitting an INFO log.
     *
     * @param operation operation category
     * @param serverName target server name
     * @param origin initiator source
     * @param outcome terminal outcome
     * @param elapsed monotonic duration
     * @param waiterCount peak waiter count
     */
    void recordOperation(
            TelemetryOperationType operation,
            String serverName,
            TelemetryOrigin origin,
            TelemetryOutcome outcome,
            Duration elapsed,
            int waiterCount);

    /**
     * Records an intermediate lifecycle stage completion, emitting a DEBUG log if enabled.
     *
     * @param stage stage category
     * @param serverName target server name
     * @param outcome stage outcome
     * @param elapsed monotonic duration
     */
    void recordStage(
            TelemetryOperationType stage,
            String serverName,
            TelemetryOutcome outcome,
            Duration elapsed);

    /**
     * Returns an immutable snapshot of in-memory telemetry aggregates.
     */
    TelemetrySnapshot snapshot();

    /**
     * Returns the server's recent completed startups, manual operations, and automatic stops, newest
     * first. The list is an immutable snapshot.
     */
    default List<TelemetryRecord> recentOperations(String serverName) {
        return List.of();
    }

    /**
     * Drops the recent-operation history of one server, for example when its mapping is removed or
     * changed by a reload.
     */
    default void forgetServer(String serverName) {
    }

    /**
     * Resets all in-memory telemetry aggregates and recent-operation history.
     */
    void clear();
}
