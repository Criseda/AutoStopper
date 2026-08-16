package me.criseda.autostopper.operational;

import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerInspection;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.docker.DockerDiagnostic;
import me.criseda.autostopper.lifecycle.LifecycleStatusSnapshot;
import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;
import me.criseda.autostopper.lifecycle.ServerLifecycleState;
import me.criseda.autostopper.server.ServerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OperationalStatusServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    @Mock private Logger logger;
    @Mock private ServerManager serverManager;
    @Mock private ServerLifecycleCoordinator lifecycleCoordinator;

    private OperationalStatusService service;

    @BeforeEach
    void setUp() {
        lenient().when(lifecycleCoordinator.statusSnapshot(any(ServerMapping.class)))
                .thenReturn(LifecycleStatusSnapshot.absent());
        lenient().when(lifecycleCoordinator.markStoppedIfUnchanged(any(ServerMapping.class), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1, Long.class) == 0L
                        ? Optional.of(LifecycleStatusSnapshot.absent())
                        : Optional.empty());
        lenient().when(lifecycleCoordinator.isHeld(anyString())).thenReturn(false);
        service = new OperationalStatusService(logger, serverManager, lifecycleCoordinator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void preflightReturnsImmediatelyAndRecordsPartialDegradation() {
        ConfigSnapshot snapshot = snapshot("healthy", "missing", "docker");
        CompletableFuture<Map<String, ContainerInspection>> inspections = new CompletableFuture<>();
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(inspections);

        CompletableFuture<PreflightSummary> preflight = service.runPreflight(snapshot, "startup");

        assertFalse(preflight.isDone(), "preflight must not wait on the caller thread");
        inspections.complete(linked(
                "healthy", ContainerInspection.healthy(ContainerStatus.STOPPED),
                "missing", failure(ContainerStatus.MISSING, DockerDiagnostic.CONTAINER_MISSING,
                        "configured container does not exist"),
                "docker", failure(ContainerStatus.INACCESSIBLE, DockerDiagnostic.DAEMON_UNAVAILABLE,
                        "Docker daemon is unavailable")));

        PreflightSummary summary = preflight.join();
        assertEquals(1, summary.healthyMappings());
        assertEquals(2, summary.degradedMappings());
        verify(logger).warn(contains("preflight degraded"), eq("startup"), eq(1), eq(2));
    }

    @Test
    void preflightSchedulingFailureLeavesPluginDegradedInsteadOfThrowing() {
        ConfigSnapshot snapshot = snapshot("survival");
        when(serverManager.inspectContainersAsync(snapshot))
                .thenThrow(new IllegalStateException("executor saturated"));

        PreflightSummary summary = service.runPreflight(snapshot, "startup").join();

        assertEquals(new PreflightSummary(0, 1), summary);
        verify(logger).warn(contains("preflight could not complete"), eq("startup"), eq("IllegalStateException"));
    }

    @Test
    void healthyObservationClearsStalePreflightFailure() {
        ConfigSnapshot snapshot = snapshot("survival");
        when(serverManager.inspectContainersAsync(snapshot))
                .thenReturn(CompletableFuture.completedFuture(Map.of(
                        "survival", failure(ContainerStatus.MISSING,
                                DockerDiagnostic.CONTAINER_MISSING, "container missing"))))
                .thenReturn(CompletableFuture.completedFuture(Map.of(
                        "survival", ContainerInspection.healthy(ContainerStatus.STOPPED))));

        service.runPreflight(snapshot, "startup").join();
        OperationalServerStatus recovered = service.collectStatuses(snapshot).join().get("survival");

        assertEquals(OperationalState.STOPPED, recovered.state());
        assertTrue(recovered.lastFailure().isEmpty(), "successful recovery clears stale failure state");
    }

    @Test
    void runningContainerWithoutVerifiedReadinessReportsRunningUnverified() {
        ConfigSnapshot snapshot = snapshot("survival");
        service.reconcileConfig(snapshot);
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        "survival", ContainerInspection.healthy(ContainerStatus.RUNNING))));
        OperationalServerStatus status = service.collectStatuses(snapshot).join().get("survival");

        assertEquals(OperationalState.RUNNING_UNVERIFIED, status.state());
        assertEquals(0, status.waitingPlayers());
        assertTrue(status.lastFailure().isEmpty());
    }

    @Test
    void runningContainerWithVerifiedReadinessReportsReady() {
        ConfigSnapshot snapshot = snapshot("survival");
        service.reconcileConfig(snapshot);
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        "survival", ContainerInspection.healthy(ContainerStatus.RUNNING))));
        when(lifecycleCoordinator.statusSnapshot(any(ServerMapping.class)))
                .thenReturn(lifecycle(ServerLifecycleState.READY, 0, Optional.empty(), 1));

        OperationalServerStatus status = service.collectStatuses(snapshot).join().get("survival");

        assertEquals(OperationalState.READY, status.state());
        assertTrue(status.lastFailure().isEmpty());
    }

    @Test
    void runningContainerWithFailedReadinessReportsFailed() {
        ConfigSnapshot snapshot = snapshot("survival");
        service.reconcileConfig(snapshot);
        OperationalFailure lifecycleFailure = new OperationalFailure(NOW.plusSeconds(1),
                "server startup", "readiness timed out", "Check the readiness endpoint.");
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        "survival", ContainerInspection.healthy(ContainerStatus.RUNNING))));
        when(lifecycleCoordinator.statusSnapshot(any(ServerMapping.class)))
                .thenReturn(lifecycle(ServerLifecycleState.FAILED, 2,
                        Optional.of(lifecycleFailure), 1));

        OperationalServerStatus status = service.collectStatuses(snapshot).join().get("survival");

        assertEquals(OperationalState.FAILED, status.state());
        assertEquals(2, status.waitingPlayers());
        assertEquals(Optional.of(lifecycleFailure), status.lastFailure());
    }

    @Test
    void stoppedContainerOverridesStaleReadyLifecycleStateAndReconcilesCoordinator() {
        ConfigSnapshot snapshot = snapshot("survival");
        service.reconcileConfig(snapshot);
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        "survival", ContainerInspection.healthy(ContainerStatus.STOPPED))));
        ServerMapping mapping = snapshot.server("survival").orElseThrow();
        LifecycleStatusSnapshot ready = lifecycle(
                ServerLifecycleState.READY, 0, Optional.empty(), 1);
        LifecycleStatusSnapshot stopped = lifecycle(
                ServerLifecycleState.STOPPED, 0, Optional.empty(), 2);
        when(lifecycleCoordinator.statusSnapshot(mapping))
                .thenReturn(ready, ready, stopped);
        when(lifecycleCoordinator.markStoppedIfUnchanged(mapping, 1))
                .thenReturn(Optional.of(stopped));

        OperationalServerStatus status = service.collectStatuses(snapshot).join().get("survival");

        assertEquals(OperationalState.STOPPED, status.state());
        verify(lifecycleCoordinator).markStoppedIfUnchanged(mapping, 1);
    }

    @Test
    void dockerAccessFailureOverridesIdleLifecycleWithDockerUnavailable() {
        ConfigSnapshot snapshot = snapshot("survival");
        service.reconcileConfig(snapshot);
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of("survival",
                        failure(ContainerStatus.INACCESSIBLE, DockerDiagnostic.PERMISSION_DENIED,
                                "permission denied accessing Docker"))));
        when(lifecycleCoordinator.statusSnapshot(any(ServerMapping.class)))
                .thenReturn(lifecycle(ServerLifecycleState.STOPPED, 0, Optional.empty(), 1));

        OperationalServerStatus status = service.collectStatuses(snapshot).join().get("survival");

        assertEquals(OperationalState.DOCKER_UNAVAILABLE, status.state());
        assertEquals(NOW, status.lastFailure().orElseThrow().timestamp());
        assertFalse(status.lastFailure().orElseThrow().detail().contains("socket path"));
    }

    @Test
    void timedOutInspectionProducesFailedStatusWithTimestampedRemediation() {
        ConfigSnapshot snapshot = snapshot("survival");
        service.reconcileConfig(snapshot);
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of("survival",
                        failure(ContainerStatus.TIMED_OUT, DockerDiagnostic.TIMED_OUT,
                                "Docker status check timed out"))));

        OperationalServerStatus status = service.collectStatuses(snapshot).join().get("survival");

        assertEquals(OperationalState.FAILED, status.state());
        OperationalFailure failure = status.lastFailure().orElseThrow();
        assertEquals(NOW, failure.timestamp());
        assertEquals("status check", failure.context());
        assertEquals("Docker status check timed out", failure.detail());
        assertEquals("Safe remediation.", failure.remediation());
    }

    @Test
    void activeLifecycleProgressRemainsVisibleDuringDockerFailure() {
        ConfigSnapshot snapshot = snapshot("survival");
        service.reconcileConfig(snapshot);
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of("survival",
                        failure(ContainerStatus.INACCESSIBLE, DockerDiagnostic.DAEMON_UNAVAILABLE,
                                "Docker daemon is unavailable"))));
        when(lifecycleCoordinator.statusSnapshot(any(ServerMapping.class)))
                .thenReturn(lifecycle(ServerLifecycleState.STARTING, 1, Optional.empty(), 1));

        OperationalServerStatus status = service.collectStatuses(snapshot).join().get("survival");

        assertEquals(OperationalState.STARTING, status.state());
        assertTrue(status.lastFailure().isPresent());
    }

    @Test
    void activeStoppingTransitionOverridesRunningDockerObservation() {
        ConfigSnapshot snapshot = snapshot("survival");
        service.reconcileConfig(snapshot);
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        "survival", ContainerInspection.healthy(ContainerStatus.RUNNING))));
        when(lifecycleCoordinator.statusSnapshot(any(ServerMapping.class)))
                .thenReturn(lifecycle(ServerLifecycleState.STOPPING, 0, Optional.empty(), 1));

        OperationalServerStatus status = service.collectStatuses(snapshot).join().get("survival");

        assertEquals(OperationalState.STOPPING, status.state());
    }

    @Test
    void reconcileBoundsFailureHistoryToCurrentMappings() {
        ConfigSnapshot oldSnapshot = snapshot("removed");
        when(serverManager.inspectContainersAsync(oldSnapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of("removed",
                        failure(ContainerStatus.TIMED_OUT, DockerDiagnostic.TIMED_OUT, "timed out"))));
        service.runPreflight(oldSnapshot, "startup").join();

        ConfigSnapshot current = snapshot("current");
        service.reconcileConfig(current);
        when(serverManager.inspectContainersAsync(current)).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        "current", ContainerInspection.healthy(ContainerStatus.STOPPED))));

        Map<String, OperationalServerStatus> statuses = service.collectStatuses(current).join();
        assertEquals(List.of("current"), List.copyOf(statuses.keySet()));
        assertTrue(statuses.get("current").lastFailure().isEmpty());
    }

    @Test
    void sameNameMappingReplacementRejectsPendingStatusObservation() {
        ServerMapping oldMapping = new ServerMapping("survival", "old-container");
        ServerMapping newMapping = new ServerMapping("survival", "new-container");
        ConfigSnapshot oldSnapshot = new ConfigSnapshot(300, List.of(oldMapping));
        ConfigSnapshot newSnapshot = new ConfigSnapshot(300, List.of(newMapping));
        service.reconcileConfig(oldSnapshot);
        CompletableFuture<Map<String, ContainerInspection>> inspections = new CompletableFuture<>();
        when(serverManager.inspectContainersAsync(oldSnapshot)).thenReturn(inspections);

        CompletableFuture<Map<String, OperationalServerStatus>> pending =
                service.collectStatuses(oldSnapshot);
        service.reconcileConfig(newSnapshot);
        inspections.complete(Map.of(
                "survival", ContainerInspection.healthy(ContainerStatus.STOPPED)));

        assertThrows(CompletionException.class, pending::join);
        verify(lifecycleCoordinator, never())
                .markStoppedIfUnchanged(eq(oldMapping), anyLong());
    }

    @Test
    void staleStoppedObservationCannotMisreportNewerReadyLifecycle() {
        ConfigSnapshot snapshot = snapshot("survival");
        ServerMapping mapping = snapshot.server("survival").orElseThrow();
        service.reconcileConfig(snapshot);
        CompletableFuture<Map<String, ContainerInspection>> inspections = new CompletableFuture<>();
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(inspections);
        LifecycleStatusSnapshot before = lifecycle(
                ServerLifecycleState.STOPPED, 0, Optional.empty(), 1);
        LifecycleStatusSnapshot newer = lifecycle(
                ServerLifecycleState.READY, 0, Optional.empty(), 2);
        when(lifecycleCoordinator.statusSnapshot(mapping)).thenReturn(before, newer, newer);

        CompletableFuture<Map<String, OperationalServerStatus>> pending =
                service.collectStatuses(snapshot);
        inspections.complete(Map.of(
                "survival", ContainerInspection.healthy(ContainerStatus.STOPPED)));

        OperationalServerStatus status = pending.join().get("survival");
        assertEquals(OperationalState.READY, status.state());
        assertTrue(status.lastFailure().isEmpty());
        verify(lifecycleCoordinator, never())
                .markStoppedIfUnchanged(eq(mapping), anyLong());
    }

    @Test
    void mappingAwareSnapshotDoesNotLeakRetiredFailureOrWaiters() {
        ConfigSnapshot snapshot = snapshot("survival");
        service.reconcileConfig(snapshot);
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        "survival", ContainerInspection.healthy(ContainerStatus.RUNNING))));
        OperationalServerStatus status = service.collectStatuses(snapshot).join().get("survival");

        assertEquals(OperationalState.RUNNING_UNVERIFIED, status.state());
        assertEquals(0, status.waitingPlayers());
        assertTrue(status.lastFailure().isEmpty());
        verify(lifecycleCoordinator, never()).waitingCount(anyString());
        verify(lifecycleCoordinator, never()).lastFailure(anyString());
    }

    @Test
    void shutdownCancelsOutstandingPreflightWithoutLoggingDegradation() {
        ConfigSnapshot snapshot = snapshot("survival");
        CompletableFuture<Map<String, ContainerInspection>> inspections = new CompletableFuture<>();
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(inspections);

        CompletableFuture<PreflightSummary> preflight = service.runPreflight(snapshot, "startup");
        service.shutdown();

        assertTrue(inspections.isCancelled());
        assertEquals(new PreflightSummary(0, 1), preflight.join());
        verify(logger, never()).warn(contains("preflight could not complete"), any(), any());
    }

    private ConfigSnapshot snapshot(String... names) {
        return new ConfigSnapshot(300, java.util.Arrays.stream(names)
                .map(name -> new ServerMapping(name, name + "-container")).toList());
    }

    private ContainerInspection failure(ContainerStatus status, DockerDiagnostic diagnostic, String detail) {
        return new ContainerInspection(status, diagnostic, detail, "Safe remediation.");
    }

    private LifecycleStatusSnapshot lifecycle(ServerLifecycleState state, int waitingPlayers,
            Optional<OperationalFailure> failure, long revision) {
        return new LifecycleStatusSnapshot(Optional.of(state), waitingPlayers, failure, revision);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ContainerInspection> linked(Object... entries) {
        Map<String, ContainerInspection> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], (ContainerInspection) entries[i + 1]);
        }
        return result;
    }
}
