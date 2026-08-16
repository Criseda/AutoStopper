package me.criseda.autostopper.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LifecycleTelemetryServiceTest {

    @Test
    @DisplayName("records authoritative operation, updates count and duration aggregates")
    void recordsAuthoritativeOperation() {
        Logger logger = mock(Logger.class);
        when(logger.isInfoEnabled()).thenReturn(true);
        AtomicLong simulatedNanos = new AtomicLong(1_000_000_000L);
        LifecycleTelemetryService service = new LifecycleTelemetryService(
                logger, simulatedNanos::get, Clock.fixed(Instant.ofEpochMilli(10000), ZoneId.of("UTC")));

        service.recordOperation(
                TelemetryOperationType.STARTUP,
                "survival",
                TelemetryOrigin.PLAYER_CONNECTION,
                TelemetryOutcome.READY,
                Duration.ofMillis(3500),
                2);

        assertEquals(1, service.operationCount(TelemetryOperationType.STARTUP));
        assertEquals(1, service.outcomeCount(TelemetryOperationType.STARTUP, TelemetryOutcome.READY));
        assertEquals(0, service.outcomeCount(TelemetryOperationType.STARTUP, TelemetryOutcome.START_FAILED));

        DurationAggregate duration = service.duration(TelemetryOperationType.STARTUP);
        assertEquals(1, duration.count());
        assertEquals(Duration.ofMillis(3500), duration.totalDuration());
        assertEquals(Duration.ofMillis(3500), duration.minDuration());
        assertEquals(Duration.ofMillis(3500), duration.maxDuration());

        verify(logger).info(
                eq("AutoStopper lifecycle completed: op={} server={} origin={} outcome={} elapsed_ms={} waiters={}"),
                eq(TelemetryOperationType.STARTUP),
                eq("survival"),
                eq(TelemetryOrigin.PLAYER_CONNECTION),
                eq(TelemetryOutcome.READY),
                eq(3500L),
                eq(2));
    }

    @Test
    @DisplayName("records intermediate stage, logs at DEBUG, updates stage duration aggregates")
    void recordsStage() {
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        LifecycleTelemetryService service = new LifecycleTelemetryService(logger);

        service.recordStage(
                TelemetryOperationType.CONTAINER_START,
                "lobby",
                TelemetryOutcome.RUNNING,
                Duration.ofMillis(1200));

        assertEquals(1, service.operationCount(TelemetryOperationType.CONTAINER_START));
        assertEquals(1, service.outcomeCount(TelemetryOperationType.CONTAINER_START, TelemetryOutcome.RUNNING));

        DurationAggregate duration = service.duration(TelemetryOperationType.CONTAINER_START);
        assertEquals(1, duration.count());
        assertEquals(Duration.ofMillis(1200), duration.totalDuration());

        verify(logger).debug(
                eq("AutoStopper lifecycle stage: op={} server={} outcome={} elapsed_ms={}"),
                eq(TelemetryOperationType.CONTAINER_START),
                eq("lobby"),
                eq(TelemetryOutcome.RUNNING),
                eq(1200L));
    }

    @Test
    @DisplayName("preserves monotonic timing regardless of clock adjustments")
    void monotonicTiming() {
        Logger logger = mock(Logger.class);
        AtomicLong simulatedNanos = new AtomicLong(10_000_000_000L);
        LifecycleTelemetryService service = new LifecycleTelemetryService(logger, simulatedNanos::get);

        long start = service.currentNanos();
        simulatedNanos.addAndGet(500_000_000L); // +500ms
        Duration elapsed = service.elapsedSince(start);

        assertEquals(Duration.ofMillis(500), elapsed);
    }

    @Test
    @DisplayName("snapshot provides immutable view of aggregates across operations")
    void snapshotImmutability() {
        Logger logger = mock(Logger.class);
        LifecycleTelemetryService service = new LifecycleTelemetryService(logger);

        service.recordOperation(
                TelemetryOperationType.MANUAL_STOP, "survival", TelemetryOrigin.MANUAL_COMMAND,
                TelemetryOutcome.STOPPED, Duration.ofMillis(1000), 0);
        service.recordOperation(
                TelemetryOperationType.MANUAL_STOP, "survival", TelemetryOrigin.MANUAL_COMMAND,
                TelemetryOutcome.STOP_FAILED, Duration.ofMillis(2000), 0);

        TelemetrySnapshot snapshot = service.snapshot();
        assertEquals(2, snapshot.operationCount(TelemetryOperationType.MANUAL_STOP));
        assertEquals(1, snapshot.outcomeCount(TelemetryOperationType.MANUAL_STOP, TelemetryOutcome.STOPPED));
        assertEquals(1, snapshot.outcomeCount(TelemetryOperationType.MANUAL_STOP, TelemetryOutcome.STOP_FAILED));
        assertEquals(Duration.ofMillis(3000), snapshot.duration(TelemetryOperationType.MANUAL_STOP).totalDuration());
        assertEquals(Duration.ofMillis(1000), snapshot.duration(TelemetryOperationType.MANUAL_STOP).minDuration());
        assertEquals(Duration.ofMillis(2000), snapshot.duration(TelemetryOperationType.MANUAL_STOP).maxDuration());

        service.clear();
        assertEquals(0, service.operationCount(TelemetryOperationType.MANUAL_STOP));
        // snapshot taken earlier remains valid and unaffected by clear
        assertEquals(2, snapshot.operationCount(TelemetryOperationType.MANUAL_STOP));
    }

    @Test
    @DisplayName("observational error safety: logger throwing exception does not throw or propagate")
    void errorInsulation() {
        Logger logger = mock(Logger.class);
        when(logger.isInfoEnabled()).thenThrow(new RuntimeException("logger exploded"));
        LifecycleTelemetryService service = new LifecycleTelemetryService(logger);

        assertDoesNotThrow(() -> service.recordOperation(
                TelemetryOperationType.STARTUP, "creative", TelemetryOrigin.PLAYER_CONNECTION,
                TelemetryOutcome.READY, Duration.ofMillis(100), 1));
    }

    @Test
    @DisplayName("thread-safe bounded aggregation under concurrent recording")
    void concurrentRecording() throws InterruptedException {
        Logger logger = mock(Logger.class);
        LifecycleTelemetryService service = new LifecycleTelemetryService(logger);

        int threadCount = 8;
        int operationsPerThread = 250;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        TelemetryOutcome outcome = (j % 2 == 0) ? TelemetryOutcome.READY : TelemetryOutcome.START_FAILED;
                        service.recordOperation(
                                TelemetryOperationType.STARTUP,
                                "server-" + (threadIndex % 2),
                                TelemetryOrigin.PLAYER_CONNECTION,
                                outcome,
                                Duration.ofMillis(10 + j),
                                1);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        int expectedTotal = threadCount * operationsPerThread;
        assertEquals(expectedTotal, service.operationCount(TelemetryOperationType.STARTUP));
        assertEquals(expectedTotal / 2, service.outcomeCount(TelemetryOperationType.STARTUP, TelemetryOutcome.READY));
        assertEquals(expectedTotal / 2, service.outcomeCount(TelemetryOperationType.STARTUP, TelemetryOutcome.START_FAILED));
        assertEquals(expectedTotal, service.duration(TelemetryOperationType.STARTUP).count());
    }
}
