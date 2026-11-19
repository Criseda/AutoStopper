package me.criseda.autostopper.telemetry;

import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Logs structured lifecycle completions and keeps the bounded per-server history of recent
 * operations shown by {@code /autostopper status <server>}.
 */
public final class LifecycleTelemetryService implements LifecycleTelemetry {
    private final Logger logger;
    private final LongSupplier nanoTime;
    private final Clock clock;

    private final OperationHistory history = new OperationHistory();

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

    public long currentNanos() {
        return nanoTime.getAsLong();
    }

    public Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos));
    }

    /**
     * Records an authoritative lifecycle operation completion in the recent history and emits an INFO log.
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
            history.record(record);
            logCompletion(record);
        } catch (Throwable error) {
            safeLogObservationalError("recordOperation", error);
        }
    }

    /**
     * Records an intermediate stage completion, emitting a DEBUG log.
     */
    public void recordStage(TelemetryOperationType stage, String serverName,
            TelemetryOutcome outcome, Duration elapsed) {
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("AutoStopper lifecycle stage: op={} server={} outcome={} elapsed_ms={}",
                        stage, serverName, outcome, elapsed.toMillis());
            }
        } catch (Throwable error) {
            safeLogObservationalError("recordStage", error);
        }
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

    private void safeLogObservationalError(String context, Throwable error) {
        try {
            logger.debug("AutoStopper telemetry observational recording failed in {}", context, error);
        } catch (Throwable ignored) {
            // Observational failures must never propagate
        }
    }

    @Override
    public List<TelemetryRecord> recentOperations(String serverName) {
        return history.recent(serverName);
    }

    @Override
    public void forgetServer(String serverName) {
        history.forget(serverName);
    }

    public void clear() {
        history.clear();
    }
}
