package me.criseda.autostopper.readiness;

import me.criseda.autostopper.config.ReadinessSettings;
import me.criseda.autostopper.config.ReadinessStrategy;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerHealth;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.docker.DockerManager;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServerReadinessCheckerTest {
    @Mock
    private Logger logger;

    @Mock
    private DockerManager dockerManager;

    private AtomicLong clock;
    private FakeScheduler scheduler;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong();
        scheduler = new FakeScheduler();
    }

    @Test
    void delayedMinecraftStatusReadinessRetriesUntilReady() {
        Queue<MinecraftStatusProbe.Outcome> outcomes = new ArrayDeque<>();
        outcomes.add(MinecraftStatusProbe.Outcome.UNREACHABLE);
        outcomes.add(MinecraftStatusProbe.Outcome.READY);
        MinecraftStatusProbe probe = (host, port, connect, read, attempt) ->
                new MinecraftStatusProbe.ProbeResult(outcomes.remove());
        when(dockerManager.getContainerStatus(anyString(), any())).thenReturn(ContainerStatus.RUNNING);
        ServerReadinessChecker checker = checker(probe);
        ServerMapping mapping = mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(100));

        ReadinessResult result = run(checker, mapping, target());

        assertTrue(result.ready());
        assertEquals(2, result.attempts());
        verify(dockerManager).getContainerStatus(anyString(), any());
    }

    @Test
    void alreadyReadyMinecraftTargetSucceedsOnFirstAttempt() {
        MinecraftStatusProbe probe = (host, port, connect, read, attempt) ->
                new MinecraftStatusProbe.ProbeResult(MinecraftStatusProbe.Outcome.READY);
        ServerReadinessChecker checker = checker(probe);

        ReadinessResult result = run(checker,
                mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(100)), target());

        assertTrue(result.ready());
        assertEquals(1, result.attempts());
        assertEquals(List.of(Duration.ZERO), scheduler.delays);
        verifyNoInteractions(dockerManager);
    }

    @Test
    void neverReadyTargetStopsAtOverallDeadline() {
        MinecraftStatusProbe probe = (host, port, connect, read, attempt) ->
                new MinecraftStatusProbe.ProbeResult(MinecraftStatusProbe.Outcome.UNREACHABLE);
        when(dockerManager.getContainerStatus(anyString(), any())).thenReturn(ContainerStatus.RUNNING);
        ServerReadinessChecker checker = checker(probe);

        ReadinessResult result = run(checker,
                mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(25)), target());

        assertEquals(ReadinessResult.Outcome.TIMED_OUT, result.outcome());
        assertEquals(3, result.attempts());
        assertEquals(MinecraftStatusProbe.Outcome.UNREACHABLE, result.lastStatusProbe());
    }

    @Test
    void attemptsAreScheduledOneProbeIntervalApartWithoutOverrunningTheDeadline() {
        MinecraftStatusProbe probe = (host, port, connect, read, attempt) ->
                new MinecraftStatusProbe.ProbeResult(MinecraftStatusProbe.Outcome.UNREACHABLE);
        when(dockerManager.getContainerStatus(anyString(), any())).thenReturn(ContainerStatus.RUNNING);
        ServerReadinessChecker checker = checker(probe);

        run(checker, mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(25)), target());

        assertEquals(List.of(Duration.ZERO, Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(5)),
                scheduler.delays);
    }

    @Test
    void crashedContainerFailsWithoutWaitingForDeadline() {
        MinecraftStatusProbe probe = (host, port, connect, read, attempt) ->
                new MinecraftStatusProbe.ProbeResult(MinecraftStatusProbe.Outcome.UNREACHABLE);
        when(dockerManager.getContainerStatus(anyString(), any())).thenReturn(ContainerStatus.STOPPED);
        ServerReadinessChecker checker = checker(probe);

        ReadinessResult result = run(checker,
                mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(100)), target());

        assertEquals(ReadinessResult.Outcome.CONTAINER_STOPPED, result.outcome());
        assertEquals(1, result.attempts());
    }

    @Test
    void dockerHealthStrategyWaitsForHealthyStateWithoutTcpProbe() {
        MinecraftStatusProbe probe = org.mockito.Mockito.mock(MinecraftStatusProbe.class);
        when(dockerManager.getContainerHealth(anyString(), any()))
                .thenReturn(ContainerHealth.STARTING, ContainerHealth.HEALTHY);
        ServerReadinessChecker checker = checker(probe);

        ReadinessResult result = run(checker,
                mapping(ReadinessStrategy.DOCKER_HEALTH, Duration.ofMillis(100)), null);

        assertTrue(result.ready());
        assertEquals(2, result.attempts());
        verifyNoInteractions(probe);
    }

    @Test
    void dockerHealthRequiresAnExplicitHealthcheckUnlessStatusFallbackIsConfigured() {
        MinecraftStatusProbe probe = (host, port, connect, read, attempt) ->
                new MinecraftStatusProbe.ProbeResult(MinecraftStatusProbe.Outcome.READY);
        when(dockerManager.getContainerHealth(anyString(), any())).thenReturn(ContainerHealth.NO_HEALTHCHECK);
        ServerReadinessChecker checker = checker(probe);

        ReadinessResult healthOnly = run(checker,
                mapping(ReadinessStrategy.DOCKER_HEALTH, Duration.ofMillis(100)), null);
        ReadinessResult withFallback = run(checker,
                mapping(ReadinessStrategy.DOCKER_HEALTH_OR_STATUS, Duration.ofMillis(100)), target());

        assertEquals(ReadinessResult.Outcome.NO_HEALTHCHECK, healthOnly.outcome());
        assertTrue(withFallback.ready());
    }

    @Test
    void missingStatusTargetIsRejectedBeforeProbing() {
        MinecraftStatusProbe probe = org.mockito.Mockito.mock(MinecraftStatusProbe.class);
        ServerReadinessChecker checker = checker(probe);

        ReadinessResult result = run(checker,
                mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(100)), null);

        assertEquals(ReadinessResult.Outcome.INVALID_TARGET, result.outcome());
        assertTrue(scheduler.delays.isEmpty());
        verify(probe, never()).probe(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }

    @Test
    void cancellationCancelsTheScheduledAttemptAndSchedulesNoMore() {
        AtomicInteger probes = new AtomicInteger();
        MinecraftStatusProbe probe = (host, port, connect, read, attempt) -> {
            probes.incrementAndGet();
            return new MinecraftStatusProbe.ProbeResult(MinecraftStatusProbe.Outcome.UNREACHABLE);
        };
        when(dockerManager.getContainerStatus(anyString(), any())).thenReturn(ContainerStatus.RUNNING);
        ServerReadinessChecker checker = checker(probe);
        CompletableFuture<ReadinessResult> wait = checker.awaitReady(
                mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(100)), target(), scheduler);
        scheduler.runNext();
        FakeScheduler.Pending scheduled = scheduler.pending.peek();

        assertTrue(wait.cancel(true));
        scheduler.runAll();

        assertTrue(wait.isCancelled());
        assertTrue(scheduled.future().isCancelled(), "the attempt waiting for its delay must be cancelled");
        assertEquals(1, probes.get());
        assertEquals(2, scheduler.delays.size());
    }

    @Test
    void saturationBeforeTheFirstAttemptFailsTheWaitSoTheStartupReportsOverload() {
        MinecraftStatusProbe probe = org.mockito.Mockito.mock(MinecraftStatusProbe.class);
        ServerReadinessChecker checker = checker(probe);
        AutoStopperExecutor.SaturationException saturation =
                new AutoStopperExecutor.SaturationException("saturated", null);
        scheduler.failures.put(0, saturation);

        CompletableFuture<ReadinessResult> wait = checker.awaitReady(
                mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(100)), target(), scheduler);
        scheduler.runAll();

        CompletionException error = assertThrows(CompletionException.class, wait::join);
        assertSame(saturation, error.getCause());
        verifyNoInteractions(probe);
    }

    @Test
    void saturationDuringALaterAttemptRetriesAtTheNextIntervalWithinTheDeadline() {
        Queue<MinecraftStatusProbe.Outcome> outcomes = new ArrayDeque<>();
        outcomes.add(MinecraftStatusProbe.Outcome.UNREACHABLE);
        outcomes.add(MinecraftStatusProbe.Outcome.READY);
        MinecraftStatusProbe probe = (host, port, connect, read, attempt) ->
                new MinecraftStatusProbe.ProbeResult(outcomes.remove());
        when(dockerManager.getContainerStatus(anyString(), any())).thenReturn(ContainerStatus.RUNNING);
        ServerReadinessChecker checker = checker(probe);
        scheduler.failures.put(1, new AutoStopperExecutor.SaturationException("saturated", null));

        ReadinessResult result = run(checker,
                mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(100)), target());

        assertTrue(result.ready());
        assertEquals(2, result.attempts());
        assertEquals(List.of(Duration.ZERO, Duration.ofMillis(10), Duration.ofMillis(10)), scheduler.delays);
    }

    @Test
    void persistentSaturationStillEndsAtTheReadinessDeadline() {
        MinecraftStatusProbe probe = (host, port, connect, read, attempt) ->
                new MinecraftStatusProbe.ProbeResult(MinecraftStatusProbe.Outcome.UNREACHABLE);
        when(dockerManager.getContainerStatus(anyString(), any())).thenReturn(ContainerStatus.RUNNING);
        ServerReadinessChecker checker = checker(probe);
        for (int attempt = 1; attempt < 10; attempt++) {
            scheduler.failures.put(attempt, new AutoStopperExecutor.SaturationException("saturated", null));
        }

        ReadinessResult result = run(checker,
                mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(25)), target());

        assertEquals(ReadinessResult.Outcome.TIMED_OUT, result.outcome());
        assertEquals(1, result.attempts());
        assertEquals(MinecraftStatusProbe.Outcome.UNREACHABLE, result.lastStatusProbe());
    }

    @Test
    void shutdownOfTheSchedulerFailsTheWait() {
        MinecraftStatusProbe probe = (host, port, connect, read, attempt) ->
                new MinecraftStatusProbe.ProbeResult(MinecraftStatusProbe.Outcome.UNREACHABLE);
        when(dockerManager.getContainerStatus(anyString(), any())).thenReturn(ContainerStatus.RUNNING);
        ServerReadinessChecker checker = checker(probe);
        AutoStopperExecutor.ShutdownException shutdown =
                new AutoStopperExecutor.ShutdownException("shut down", null);
        scheduler.failures.put(1, shutdown);

        CompletableFuture<ReadinessResult> wait = checker.awaitReady(
                mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(100)), target(), scheduler);
        scheduler.runAll();

        CompletionException error = assertThrows(CompletionException.class, wait::join);
        assertSame(shutdown, error.getCause());
    }

    @Test
    void schedulerRejectingSynchronouslyFailsTheWait() {
        MinecraftStatusProbe probe = org.mockito.Mockito.mock(MinecraftStatusProbe.class);
        ServerReadinessChecker checker = checker(probe);
        IllegalStateException rejected = new IllegalStateException("rejected");

        CompletableFuture<ReadinessResult> wait = checker.awaitReady(
                mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(100)), target(),
                (delay, attempt) -> {
                    throw rejected;
                });

        CompletionException error = assertThrows(CompletionException.class, wait::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertFalse(wait.isCancelled());
        verifyNoInteractions(probe);
    }

    private ReadinessResult run(ServerReadinessChecker checker, ServerMapping mapping,
            ReadinessSettings.Target target) {
        CompletableFuture<ReadinessResult> wait = checker.awaitReady(mapping, target, scheduler);
        scheduler.runAll();
        assertTrue(wait.isDone(), "readiness must finish once every scheduled attempt has run");
        return wait.join();
    }

    private ServerReadinessChecker checker(MinecraftStatusProbe probe) {
        return new ServerReadinessChecker(
                logger,
                dockerManager,
                probe,
                clock::get);
    }

    private ServerMapping mapping(ReadinessStrategy strategy, Duration timeout) {
        return new ServerMapping(
                "survival",
                "survival-container",
                new ReadinessSettings(
                        strategy,
                        "127.0.0.1",
                        25565,
                        Duration.ofMillis(10),
                        timeout,
                        Duration.ofMillis(5),
                        Duration.ofMillis(5)));
    }

    private ReadinessSettings.Target target() {
        return new ReadinessSettings.Target("127.0.0.1", 25565);
    }

    /** Runs scheduled attempts on the test thread, advancing the fake clock by each attempt's delay. */
    private final class FakeScheduler implements ServerReadinessChecker.AttemptScheduler {
        private final List<Duration> delays = new ArrayList<>();
        private final Queue<Pending> pending = new ArrayDeque<>();
        private final Map<Integer, RuntimeException> failures = new HashMap<>();

        @Override
        public CompletableFuture<ReadinessResult> schedule(Duration delay, Supplier<ReadinessResult> attempt) {
            int index = delays.size();
            delays.add(delay);
            CompletableFuture<ReadinessResult> future = new CompletableFuture<>();
            pending.add(new Pending(index, delay, attempt, future));
            return future;
        }

        private void runAll() {
            while (!pending.isEmpty()) {
                runNext();
            }
        }

        private void runNext() {
            Pending next = pending.remove();
            if (next.future().isDone()) {
                return;
            }
            clock.addAndGet(next.delay().toNanos());
            RuntimeException failure = failures.get(next.index());
            if (failure != null) {
                next.future().completeExceptionally(failure);
            } else {
                next.future().complete(next.attempt().get());
            }
        }

        private record Pending(int index, Duration delay, Supplier<ReadinessResult> attempt,
                CompletableFuture<ReadinessResult> future) {
        }
    }
}
