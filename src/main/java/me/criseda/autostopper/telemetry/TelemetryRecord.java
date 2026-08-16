package me.criseda.autostopper.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record representing an authoritative lifecycle operation completion.
 */
public record TelemetryRecord(
        TelemetryOperationType operation,
        String serverName,
        TelemetryOrigin origin,
        TelemetryOutcome outcome,
        Duration elapsed,
        int waiterCount,
        Instant completedAt) {

    public TelemetryRecord {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(completedAt, "completedAt");
        if (waiterCount < 0) {
            throw new IllegalArgumentException("waiterCount must not be negative");
        }
    }
}
