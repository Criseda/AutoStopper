package me.criseda.autostopper.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LifecycleTelemetryServiceTest {

    @Test
    @DisplayName("feeds server-level completions into the per-server history and clears it")
    void feedsAndClearsRecentOperations() {
        LifecycleTelemetryService service = new LifecycleTelemetryService(mock(Logger.class), () -> 0L,
                Clock.fixed(Instant.ofEpochSecond(42), ZoneId.of("UTC")));

        service.recordOperation(TelemetryOperationType.MANUAL_STOP, "survival", TelemetryOrigin.MANUAL_COMMAND,
                TelemetryOutcome.STOPPED, Duration.ofSeconds(4), 0);
        service.recordOperation(TelemetryOperationType.CONNECTION_WAIT, "survival", TelemetryOrigin.PLAYER_CONNECTION,
                TelemetryOutcome.CONNECTED, Duration.ofSeconds(1), 1);
        service.recordStage(TelemetryOperationType.STATUS_CHECK, "survival", TelemetryOutcome.STOPPED,
                Duration.ofMillis(20));

        List<TelemetryRecord> recent = service.recentOperations("survival");
        assertEquals(1, recent.size());
        assertEquals(TelemetryOperationType.MANUAL_STOP, recent.get(0).operation());
        assertEquals(TelemetryOrigin.MANUAL_COMMAND, recent.get(0).origin());
        assertEquals(TelemetryOutcome.STOPPED, recent.get(0).outcome());
        assertEquals(Duration.ofSeconds(4), recent.get(0).elapsed());
        assertEquals(Instant.ofEpochSecond(42), recent.get(0).completedAt());

        service.forgetServer("survival");
        assertTrue(service.recentOperations("survival").isEmpty());

        service.recordOperation(TelemetryOperationType.STARTUP, "survival", TelemetryOrigin.PLAYER_CONNECTION,
                TelemetryOutcome.READY, Duration.ofSeconds(9), 1);
        service.clear();
        assertTrue(service.recentOperations("survival").isEmpty());
    }

    @Test
    @DisplayName("records an authoritative operation in the history and logs it at INFO")
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

        List<TelemetryRecord> recent = service.recentOperations("survival");
        assertEquals(1, recent.size());
        assertEquals(TelemetryOutcome.READY, recent.get(0).outcome());
        assertEquals(Duration.ofMillis(3500), recent.get(0).elapsed());

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
    @DisplayName("logs an intermediate stage at DEBUG without adding it to the history")
    void recordsStage() {
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        LifecycleTelemetryService service = new LifecycleTelemetryService(logger);

        service.recordStage(
                TelemetryOperationType.CONTAINER_START,
                "lobby",
                TelemetryOutcome.RUNNING,
                Duration.ofMillis(1200));

        assertTrue(service.recentOperations("lobby").isEmpty());

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
    @DisplayName("observational error safety: logger throwing exception does not throw or propagate")
    void errorInsulation() {
        Logger logger = mock(Logger.class);
        when(logger.isInfoEnabled()).thenThrow(new RuntimeException("logger exploded"));
        LifecycleTelemetryService service = new LifecycleTelemetryService(logger);

        assertDoesNotThrow(() -> service.recordOperation(
                TelemetryOperationType.STARTUP, "creative", TelemetryOrigin.PLAYER_CONNECTION,
                TelemetryOutcome.READY, Duration.ofMillis(100), 1));
    }
}
