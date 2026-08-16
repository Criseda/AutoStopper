package me.criseda.autostopper.telemetry;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable record representing an intermediate stage completion logged at DEBUG.
 */
public record TelemetryStageRecord(
        TelemetryOperationType stage,
        String serverName,
        TelemetryOutcome outcome,
        Duration elapsed) {

    public TelemetryStageRecord {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(elapsed, "elapsed");
    }
}
