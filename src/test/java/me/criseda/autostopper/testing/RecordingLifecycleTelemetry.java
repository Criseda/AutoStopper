package me.criseda.autostopper.testing;

import me.criseda.autostopper.telemetry.LifecycleTelemetry;
import me.criseda.autostopper.telemetry.LifecycleTelemetryService;
import me.criseda.autostopper.telemetry.TelemetryOperationType;
import me.criseda.autostopper.telemetry.TelemetryOrigin;
import me.criseda.autostopper.telemetry.TelemetryOutcome;
import me.criseda.autostopper.telemetry.TelemetryRecord;
import org.slf4j.helpers.NOPLogger;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Production telemetry that also remembers every recorded operation and stage, so tests can assert
 * which typed outcomes the lifecycle recorded. Recording is synchronous, like the production
 * service, so an outcome is visible as soon as the call that recorded it returns.
 */
public final class RecordingLifecycleTelemetry implements LifecycleTelemetry {
    private record Recorded(TelemetryOperationType type, TelemetryOutcome outcome) {
    }

    private final LifecycleTelemetryService delegate =
            new LifecycleTelemetryService(NOPLogger.NOP_LOGGER);
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();

    @Override
    public long currentNanos() {
        return delegate.currentNanos();
    }

    @Override
    public Duration elapsedSince(long startNanos) {
        return delegate.elapsedSince(startNanos);
    }

    @Override
    public void recordOperation(TelemetryOperationType operation, String serverName, TelemetryOrigin origin,
            TelemetryOutcome outcome, Duration elapsed, int waiterCount) {
        recorded.add(new Recorded(operation, outcome));
        delegate.recordOperation(operation, serverName, origin, outcome, elapsed, waiterCount);
    }

    @Override
    public void recordStage(TelemetryOperationType stage, String serverName, TelemetryOutcome outcome,
            Duration elapsed) {
        recorded.add(new Recorded(stage, outcome));
        delegate.recordStage(stage, serverName, outcome, elapsed);
    }

    @Override
    public List<TelemetryRecord> recentOperations(String serverName) {
        return delegate.recentOperations(serverName);
    }

    @Override
    public void forgetServer(String serverName) {
        delegate.forgetServer(serverName);
    }

    @Override
    public void clear() {
        recorded.clear();
        delegate.clear();
    }

    /** How many operations or stages of {@code type} were recorded, with any outcome. */
    public long operationCount(TelemetryOperationType type) {
        return recorded.stream().filter(entry -> entry.type() == type).count();
    }

    /** How many operations or stages of {@code type} were recorded with {@code outcome}. */
    public long outcomeCount(TelemetryOperationType type, TelemetryOutcome outcome) {
        return recorded.stream().filter(entry -> entry.type() == type && entry.outcome() == outcome).count();
    }
}
