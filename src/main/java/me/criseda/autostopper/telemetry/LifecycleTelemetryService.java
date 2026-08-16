package me.criseda.autostopper.telemetry;

import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Owns bounded process-lifetime lifecycle telemetry aggregation and structured completion logging.
 */
public final class LifecycleTelemetryService implements LifecycleTelemetry {
    private final Logger logger;
    private final LongSupplier nanoTime;
    private final Clock clock;

    private final Map<TelemetryOperationType, Map<TelemetryOutcome, LongAdder>> outcomeCounters =
            new ConcurrentHashMap<>();
    private final Map<TelemetryOperationType, DurationAggregate> durationAggregates =
            new ConcurrentHashMap<>();

    public LifecycleTelemetryService(Logger logger) {
        this(logger, System::nanoTime, Clock.systemUTC());
    }

    public LifecycleTelemetryService(Logger logger, LongSupplier nanoTime) {
        this(logger, nanoTime, Clock.systemUTC());
    }

    public LifecycleTelemetryService(Logger logger, LongSupplier nanoTime, Clock clock) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LongSupplier nanoTime() {
        return nanoTime;
    }

    public long currentNanos() {
        return nanoTime.getAsLong();
    }

    public Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos));
    }

    /**
     * Records an authoritative lifecycle operation completion, updating aggregates and emitting an INFO log.
     */
    public void recordOperation(TelemetryOperationType operation, String serverName,
            TelemetryOrigin origin, TelemetryOutcome outcome, Duration elapsed, int waiterCount) {
        try {
            TelemetryRecord record = new TelemetryRecord(
                    operation, serverName, origin, outcome, elapsed, waiterCount, clock.instant());
            recordOperation(record);
        } catch (Throwable error) {
            safeLogObservationalError("recordOperation", error);
        }
    }

    /**
     * Records an authoritative lifecycle operation completion.
     */
    public void recordOperation(TelemetryRecord record) {
        if (record == null) {
            return;
        }
        try {
            updateAggregates(record.operation(), record.outcome(), record.elapsed().toNanos());
            logCompletion(record);
        } catch (Throwable error) {
            safeLogObservationalError("recordOperation", error);
        }
    }

    /**
     * Records an intermediate stage completion, emitting a DEBUG log and updating stage duration aggregates.
     */
    public void recordStage(TelemetryOperationType stage, String serverName,
            TelemetryOutcome outcome, Duration elapsed) {
        try {
            TelemetryStageRecord record = new TelemetryStageRecord(stage, serverName, outcome, elapsed);
            recordStage(record);
        } catch (Throwable error) {
            safeLogObservationalError("recordStage", error);
        }
    }

    /**
     * Records an intermediate stage completion.
     */
    public void recordStage(TelemetryStageRecord record) {
        if (record == null) {
            return;
        }
        try {
            updateAggregates(record.stage(), record.outcome(), record.elapsed().toNanos());
            logStage(record);
        } catch (Throwable error) {
            safeLogObservationalError("recordStage", error);
        }
    }

    private void updateAggregates(TelemetryOperationType operation, TelemetryOutcome outcome, long durationNanos) {
        outcomeCounters.computeIfAbsent(operation, op -> new ConcurrentHashMap<>())
                .computeIfAbsent(outcome, oc -> new LongAdder())
                .increment();

        durationAggregates.computeIfAbsent(operation, op -> new DurationAggregate())
                .record(durationNanos);
    }

    private void logCompletion(TelemetryRecord record) {
        if (logger.isInfoEnabled()) {
            logger.info("AutoStopper lifecycle completed: op={} server={} origin={} outcome={} elapsed_ms={} waiters={}",
                    record.operation(),
                    record.serverName(),
                    record.origin(),
                    record.outcome(),
                    record.elapsed().toMillis(),
                    record.waiterCount());
        }
    }

    private void logStage(TelemetryStageRecord record) {
        if (logger.isDebugEnabled()) {
            logger.debug("AutoStopper lifecycle stage: op={} server={} outcome={} elapsed_ms={}",
                    record.stage(),
                    record.serverName(),
                    record.outcome(),
                    record.elapsed().toMillis());
        }
    }

    private void safeLogObservationalError(String context, Throwable error) {
        try {
            logger.debug("AutoStopper telemetry observational recording failed in {}", context, error);
        } catch (Throwable ignored) {
            // Observational failures must never propagate
        }
    }

    /**
     * Returns an immutable snapshot of all in-memory telemetry aggregates.
     */
    public TelemetrySnapshot snapshot() {
        Map<TelemetryOperationType, Map<TelemetryOutcome, Long>> outcomeSnapshot = new EnumMap<>(TelemetryOperationType.class);
        for (Map.Entry<TelemetryOperationType, Map<TelemetryOutcome, LongAdder>> opEntry : outcomeCounters.entrySet()) {
            Map<TelemetryOutcome, Long> counts = new EnumMap<>(TelemetryOutcome.class);
            for (Map.Entry<TelemetryOutcome, LongAdder> outcomeEntry : opEntry.getValue().entrySet()) {
                counts.put(outcomeEntry.getKey(), outcomeEntry.getValue().sum());
            }
            outcomeSnapshot.put(opEntry.getKey(), Collections.unmodifiableMap(counts));
        }

        Map<TelemetryOperationType, DurationAggregate> durationSnapshot = new EnumMap<>(TelemetryOperationType.class);
        for (Map.Entry<TelemetryOperationType, DurationAggregate> entry : durationAggregates.entrySet()) {
            durationSnapshot.put(entry.getKey(), entry.getValue().snapshot());
        }

        return new TelemetrySnapshot(
                Collections.unmodifiableMap(outcomeSnapshot),
                Collections.unmodifiableMap(durationSnapshot),
                clock.instant());
    }

    public long operationCount(TelemetryOperationType operation) {
        Map<TelemetryOutcome, LongAdder> counts = outcomeCounters.get(operation);
        if (counts == null) {
            return 0L;
        }
        return counts.values().stream().mapToLong(LongAdder::sum).sum();
    }

    public long outcomeCount(TelemetryOperationType operation, TelemetryOutcome outcome) {
        Map<TelemetryOutcome, LongAdder> counts = outcomeCounters.get(operation);
        if (counts == null) {
            return 0L;
        }
        LongAdder adder = counts.get(outcome);
        return adder == null ? 0L : adder.sum();
    }

    public DurationAggregate duration(TelemetryOperationType operation) {
        DurationAggregate aggregate = durationAggregates.get(operation);
        return aggregate == null ? new DurationAggregate() : aggregate.snapshot();
    }

    public void clear() {
        outcomeCounters.clear();
        durationAggregates.clear();
    }
}
