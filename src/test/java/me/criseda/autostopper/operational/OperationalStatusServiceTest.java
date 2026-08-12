package me.criseda.autostopper.operational;

import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerInspection;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.docker.DockerDiagnostic;
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
        lenient().when(lifecycleCoordinator.state(anyString())).thenReturn(Optional.empty());
        lenient().when(lifecycleCoordinator.lastFailure(anyString())).thenReturn(Optional.empty());
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
    void statusUsesLifecycleStateAndLatestTimestampedFailure() {
        ConfigSnapshot snapshot = snapshot("survival");
        service.reconcileConfig(snapshot);
        OperationalFailure lifecycleFailure = new OperationalFailure(NOW.plusSeconds(1),
                "server startup", "readiness timed out", "Check the readiness endpoint.");
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        "survival", ContainerInspection.healthy(ContainerStatus.RUNNING))));
        when(lifecycleCoordinator.state("survival")).thenReturn(Optional.of(ServerLifecycleState.FAILED));
        when(lifecycleCoordinator.waitingCount("survival")).thenReturn(2);
        when(lifecycleCoordinator.lastFailure("survival")).thenReturn(Optional.of(lifecycleFailure));

        OperationalServerStatus status = service.collectStatuses(snapshot).join().get("survival");

        assertEquals(OperationalState.FAILED, status.state());
        assertEquals(2, status.waitingPlayers());
        assertEquals(Optional.of(lifecycleFailure), status.lastFailure());
    }

    @Test
    void dockerAccessFailureOverridesIdleLifecycleWithDockerUnavailable() {
        ConfigSnapshot snapshot = snapshot("survival");
        service.reconcileConfig(snapshot);
        when(serverManager.inspectContainersAsync(snapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of("survival",
                        failure(ContainerStatus.INACCESSIBLE, DockerDiagnostic.PERMISSION_DENIED,
                                "permission denied accessing Docker"))));
        when(lifecycleCoordinator.state("survival")).thenReturn(Optional.of(ServerLifecycleState.STOPPED));

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
        when(lifecycleCoordinator.state("survival")).thenReturn(Optional.of(ServerLifecycleState.STARTING));

        OperationalServerStatus status = service.collectStatuses(snapshot).join().get("survival");

        assertEquals(OperationalState.STARTING, status.state());
        assertTrue(status.lastFailure().isPresent());
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

    @SuppressWarnings("unchecked")
    private Map<String, ContainerInspection> linked(Object... entries) {
        Map<String, ContainerInspection> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], (ContainerInspection) entries[i + 1]);
        }
        return result;
    }
}
