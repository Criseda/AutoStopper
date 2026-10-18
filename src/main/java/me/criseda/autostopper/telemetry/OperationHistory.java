package me.criseda.autostopper.telemetry;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded per-server history of completed server-level lifecycle operations, newest first.
 *
 * <p>Each server's history is an immutable list replaced on every write, so readers on command
 * threads take a consistent snapshot without locking and never block lifecycle work. Connection
 * waits (one per player) and intermediate stages are not recorded. History is in memory only.
 */
final class OperationHistory {
    /** Completed operations kept per server. */
    static final int CAPACITY = 10;

    private static final Set<TelemetryOperationType> RECORDED = EnumSet.of(
            TelemetryOperationType.STARTUP,
            TelemetryOperationType.MANUAL_START,
            TelemetryOperationType.MANUAL_STOP,
            TelemetryOperationType.MANUAL_RESTART,
            TelemetryOperationType.AUTOMATIC_STOP);

    private final Map<String, List<TelemetryRecord>> byServer = new ConcurrentHashMap<>();

    static boolean records(TelemetryOperationType operation) {
        return RECORDED.contains(operation);
    }

    void record(TelemetryRecord record) {
        if (!records(record.operation())) {
            return;
        }
        byServer.compute(record.serverName(), (serverName, previous) -> {
            List<TelemetryRecord> next = new ArrayList<>(CAPACITY);
            next.add(record);
            if (previous != null) {
                next.addAll(previous.subList(0, Math.min(previous.size(), CAPACITY - 1)));
            }
            return List.copyOf(next);
        });
    }

    List<TelemetryRecord> recent(String serverName) {
        return byServer.getOrDefault(serverName, List.of());
    }

    void forget(String serverName) {
        byServer.remove(serverName);
    }

    void clear() {
        byServer.clear();
    }
}
