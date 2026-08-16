package me.criseda.autostopper.telemetry;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of in-memory telemetry aggregates for internal inspection and tests.
 */
public record TelemetrySnapshot(
        Map<TelemetryOperationType, Map<TelemetryOutcome, Long>> outcomeCounts,
        Map<TelemetryOperationType, DurationAggregate> durationAggregates,
        Instant snapshotTime) {

    public TelemetrySnapshot {
        Objects.requireNonNull(outcomeCounts, "outcomeCounts");
        Objects.requireNonNull(durationAggregates, "durationAggregates");
        Objects.requireNonNull(snapshotTime, "snapshotTime");
        Map<TelemetryOperationType, Map<TelemetryOutcome, Long>> copyCounts = new EnumMap<>(TelemetryOperationType.class);
        for (Map.Entry<TelemetryOperationType, Map<TelemetryOutcome, Long>> entry : outcomeCounts.entrySet()) {
            copyCounts.put(entry.getKey(), Collections.unmodifiableMap(new EnumMap<>(entry.getValue())));
        }
        outcomeCounts = Collections.unmodifiableMap(copyCounts);
        durationAggregates = Collections.unmodifiableMap(new EnumMap<>(durationAggregates));
    }

    @Override
    public Map<TelemetryOperationType, Map<TelemetryOutcome, Long>> outcomeCounts() {
        return outcomeCounts;
    }

    @Override
    public Map<TelemetryOperationType, DurationAggregate> durationAggregates() {
        return durationAggregates;
    }

    public long operationCount(TelemetryOperationType operation) {
        Map<TelemetryOutcome, Long> counts = outcomeCounts.get(operation);
        if (counts == null) {
            return 0L;
        }
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    public long outcomeCount(TelemetryOperationType operation, TelemetryOutcome outcome) {
        Map<TelemetryOutcome, Long> counts = outcomeCounts.get(operation);
        if (counts == null) {
            return 0L;
        }
        return counts.getOrDefault(outcome, 0L);
    }

    public DurationAggregate duration(TelemetryOperationType operation) {
        return durationAggregates.getOrDefault(operation, new DurationAggregate());
    }
}
