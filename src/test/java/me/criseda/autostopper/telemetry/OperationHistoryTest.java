package me.criseda.autostopper.telemetry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class OperationHistoryTest {

    private final OperationHistory history = new OperationHistory();

    @Test
    void keepsTheNewestOperationsFirstAndEvictsTheOldest() {
        for (int i = 0; i < OperationHistory.CAPACITY + 3; i++) {
            history.record(operation(TelemetryOperationType.MANUAL_START, "survival", i));
        }

        List<TelemetryRecord> recent = history.recent("survival");

        assertEquals(OperationHistory.CAPACITY, recent.size());
        assertEquals(Instant.ofEpochSecond(OperationHistory.CAPACITY + 2), recent.get(0).completedAt());
        assertEquals(Instant.ofEpochSecond(3), recent.get(OperationHistory.CAPACITY - 1).completedAt());
    }

    @Test
    void recordsOnlyServerLevelOperations() {
        for (TelemetryOperationType type : TelemetryOperationType.values()) {
            history.record(operation(type, "survival", 0));
        }

        assertEquals(List.of(
                        TelemetryOperationType.MANUAL_RESTART,
                        TelemetryOperationType.AUTOMATIC_STOP,
                        TelemetryOperationType.MANUAL_STOP,
                        TelemetryOperationType.MANUAL_START,
                        TelemetryOperationType.STARTUP),
                history.recent("survival").stream().map(TelemetryRecord::operation).toList());
        assertFalse(OperationHistory.records(TelemetryOperationType.CONNECTION_WAIT));
        assertFalse(OperationHistory.records(TelemetryOperationType.STATUS_CHECK));
        assertFalse(OperationHistory.records(TelemetryOperationType.CONTAINER_START));
        assertFalse(OperationHistory.records(TelemetryOperationType.READINESS_CHECK));
    }

    @Test
    void keepsServersApartAndForgetsOneServer() {
        history.record(operation(TelemetryOperationType.STARTUP, "survival", 1));
        history.record(operation(TelemetryOperationType.STARTUP, "creative", 2));

        history.forget("survival");

        assertTrue(history.recent("survival").isEmpty());
        assertEquals(1, history.recent("creative").size());
    }

    @Test
    void clearDropsEveryServer() {
        history.record(operation(TelemetryOperationType.STARTUP, "survival", 1));
        history.record(operation(TelemetryOperationType.STARTUP, "creative", 2));

        history.clear();

        assertTrue(history.recent("survival").isEmpty());
        assertTrue(history.recent("creative").isEmpty());
    }

    @Test
    void snapshotsAreImmutableAndUnaffectedByLaterWrites() {
        history.record(operation(TelemetryOperationType.STARTUP, "survival", 1));
        List<TelemetryRecord> snapshot = history.recent("survival");

        history.record(operation(TelemetryOperationType.MANUAL_STOP, "survival", 2));

        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(operation(TelemetryOperationType.STARTUP, "survival", 3)));
    }

    @Test
    void concurrentWritersAndReadersStayBoundedAndConsistent() throws Exception {
        int writers = 4;
        int perWriter = 500;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean writing = new AtomicBoolean(true);
        try {
            Future<?> reader = pool.submit(() -> {
                start.await();
                while (writing.get()) {
                    List<TelemetryRecord> recent = history.recent("survival");
                    assertTrue(recent.size() <= OperationHistory.CAPACITY, "history grew past its capacity");
                    assertEquals(recent.size(), recent.stream().distinct().count(), "a record appeared twice");
                }
                return null;
            });
            Future<?>[] writes = new Future<?>[writers];
            for (int w = 0; w < writers; w++) {
                int writer = w;
                writes[w] = pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        history.record(operation(TelemetryOperationType.STARTUP, "survival", writer * perWriter + i));
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<?> write : writes) {
                write.get(10, TimeUnit.SECONDS);
            }
            writing.set(false);
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(OperationHistory.CAPACITY, history.recent("survival").size());
    }

    private static TelemetryRecord operation(TelemetryOperationType type, String serverName, long second) {
        return new TelemetryRecord(type, serverName, TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.READY,
                Duration.ofSeconds(1), 0, Instant.ofEpochSecond(second));
    }
}
