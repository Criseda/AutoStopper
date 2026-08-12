package me.criseda.autostopper.readiness;

import me.criseda.autostopper.config.ReadinessSettings;
import me.criseda.autostopper.config.ReadinessStrategy;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerHealth;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.docker.DockerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @BeforeEach
    void setUp() {
        clock = new AtomicLong();
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

        ReadinessResult result = checker.awaitReady(mapping, target());

        assertTrue(result.ready());
        assertEquals(2, result.attempts());
        verify(dockerManager).getContainerStatus(anyString(), any());
    }

    @Test
    void alreadyReadyMinecraftTargetSucceedsOnFirstAttempt() {
        MinecraftStatusProbe probe = (host, port, connect, read, attempt) ->
                new MinecraftStatusProbe.ProbeResult(MinecraftStatusProbe.Outcome.READY);
        ServerReadinessChecker checker = checker(probe);

        ReadinessResult result = checker.awaitReady(
                mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(100)), target());

        assertTrue(result.ready());
        assertEquals(1, result.attempts());
        verifyNoInteractions(dockerManager);
    }

    @Test
    void neverReadyTargetStopsAtOverallDeadline() {
        MinecraftStatusProbe probe = (host, port, connect, read, attempt) ->
                new MinecraftStatusProbe.ProbeResult(MinecraftStatusProbe.Outcome.UNREACHABLE);
        when(dockerManager.getContainerStatus(anyString(), any())).thenReturn(ContainerStatus.RUNNING);
        ServerReadinessChecker checker = checker(probe);

        ReadinessResult result = checker.awaitReady(
                mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(25)), target());

        assertEquals(ReadinessResult.Outcome.TIMED_OUT, result.outcome());
        assertEquals(3, result.attempts());
        assertEquals(MinecraftStatusProbe.Outcome.UNREACHABLE, result.lastStatusProbe());
    }

    @Test
    void crashedContainerFailsWithoutWaitingForDeadline() {
        MinecraftStatusProbe probe = (host, port, connect, read, attempt) ->
                new MinecraftStatusProbe.ProbeResult(MinecraftStatusProbe.Outcome.UNREACHABLE);
        when(dockerManager.getContainerStatus(anyString(), any())).thenReturn(ContainerStatus.STOPPED);
        ServerReadinessChecker checker = checker(probe);

        ReadinessResult result = checker.awaitReady(
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

        ReadinessResult result = checker.awaitReady(
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

        ReadinessResult healthOnly = checker.awaitReady(
                mapping(ReadinessStrategy.DOCKER_HEALTH, Duration.ofMillis(100)), null);
        ReadinessResult withFallback = checker.awaitReady(
                mapping(ReadinessStrategy.DOCKER_HEALTH_OR_STATUS, Duration.ofMillis(100)), target());

        assertEquals(ReadinessResult.Outcome.NO_HEALTHCHECK, healthOnly.outcome());
        assertTrue(withFallback.ready());
    }

    @Test
    void missingStatusTargetIsRejectedBeforeProbing() {
        MinecraftStatusProbe probe = org.mockito.Mockito.mock(MinecraftStatusProbe.class);
        ServerReadinessChecker checker = checker(probe);

        ReadinessResult result = checker.awaitReady(
                mapping(ReadinessStrategy.MINECRAFT_STATUS, Duration.ofMillis(100)), null);

        assertEquals(ReadinessResult.Outcome.INVALID_TARGET, result.outcome());
        verify(probe, never()).probe(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }

    private ServerReadinessChecker checker(MinecraftStatusProbe probe) {
        return new ServerReadinessChecker(
                logger,
                dockerManager,
                probe,
                clock::get,
                clock::addAndGet);
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
}
