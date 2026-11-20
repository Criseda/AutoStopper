package me.criseda.autostopper.docker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import me.criseda.autostopper.config.DockerEndpoint;
import me.criseda.autostopper.config.DockerSettings;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DockerManager's own semantics over a scripted backend: deadlines, stop grace periods, the
 * timed-out stop re-check and backend selection. Failure classification belongs to
 * {@link EngineApiDockerBackendTest}.
 */
@ExtendWith(MockitoExtension.class)
public class DockerManagerTest {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

    @Mock
    private Logger logger;

    private ScriptedBackend backend;
    private DockerManager dockerManager;

    @BeforeEach
    public void setup() {
        backend = new ScriptedBackend("scripted");
        dockerManager = new DockerManager(logger, backend, COMMAND_TIMEOUT);
    }

    @Test
    public void statusAndInspectionComeFromTheBackendWithinTheCommandTimeout() {
        backend.inspections.add(ContainerInspection.healthy(ContainerStatus.RUNNING));
        backend.inspections.add(DockerManager.containerMissing());

        assertEquals(ContainerStatus.RUNNING, dockerManager.getContainerStatus("test-container"));
        assertEquals(DockerDiagnostic.CONTAINER_MISSING, dockerManager.inspectContainer("test-container").diagnostic());
        assertEquals(List.of("inspect", "inspect"), backend.calls);
        assertEquals(List.of(COMMAND_TIMEOUT, COMMAND_TIMEOUT), backend.timeouts);
    }

    @Test
    public void requestedTimeoutsAreCappedByTheCommandTimeout() {
        backend.inspections.add(ContainerInspection.healthy(ContainerStatus.STOPPED));
        backend.inspections.add(ContainerInspection.healthy(ContainerStatus.STOPPED));
        backend.healths.add(ContainerHealth.HEALTHY);
        backend.healths.add(ContainerHealth.STARTING);

        assertEquals(ContainerStatus.STOPPED, dockerManager.getContainerStatus("test-container", Duration.ofSeconds(30)));
        assertEquals(ContainerStatus.STOPPED, dockerManager.getContainerStatus("test-container", Duration.ofSeconds(1)));
        assertEquals(ContainerHealth.HEALTHY, dockerManager.getContainerHealth("test-container", Duration.ofSeconds(30)));
        assertEquals(ContainerHealth.STARTING, dockerManager.getContainerHealth("test-container", Duration.ofSeconds(2)));

        assertEquals(List.of(COMMAND_TIMEOUT, Duration.ofSeconds(1), COMMAND_TIMEOUT, Duration.ofSeconds(2)),
                backend.timeouts);
    }

    @Test
    public void nonPositiveTimeoutsAreRejected() {
        for (Duration timeout : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThrows(IllegalArgumentException.class, () -> dockerManager.inspectContainer("c", timeout));
            assertThrows(IllegalArgumentException.class, () -> dockerManager.getContainerHealth("c", timeout));
        }
        assertThrows(IllegalArgumentException.class, () -> new DockerManager(logger, backend, Duration.ZERO));
        assertTrue(backend.calls.isEmpty());
    }

    @Test
    public void startGoesStraightToTheBackend() {
        for (ContainerStatus result : ContainerStatus.values()) {
            backend.starts.add(result);

            assertEquals(result, dockerManager.startContainer("test-container"));
        }

        assertEquals(ContainerStatus.values().length, backend.calls.size());
        assertTrue(backend.calls.stream().allMatch("start"::equals), "no status check before a start: " + backend.calls);
        assertTrue(backend.timeouts.stream().allMatch(COMMAND_TIMEOUT::equals));
    }

    @Test
    public void stopDeadlineCoversTheConfiguredGracePeriod() {
        backend.stopTimeouts.add(OptionalLong.of(60));
        backend.stops.add(ContainerStatus.STOPPED);

        assertEquals(ContainerStatus.STOPPED, dockerManager.stopContainer("test-container"));
        assertEquals(List.of("stopTimeoutSeconds", "stop"), backend.calls);
        assertEquals(List.of(COMMAND_TIMEOUT, Duration.ofSeconds(70)), backend.timeouts);
    }

    @Test
    public void stopDeadlineFallsBackToDockerDefaultGracePeriod() {
        backend.stopTimeouts.add(OptionalLong.empty());
        backend.stops.add(ContainerStatus.STOPPED);

        dockerManager.stopContainer("test-container");

        assertEquals(DockerManager.DEFAULT_STOP_GRACE_PERIOD.plus(COMMAND_TIMEOUT), backend.timeouts.get(1));
    }

    @Test
    public void stopDeadlineCapsUnboundedOrExcessiveGracePeriods() {
        for (long configured : new long[] {-1, 86400}) {
            backend.stopTimeouts.add(OptionalLong.of(configured));
            backend.stops.add(ContainerStatus.STOPPED);

            dockerManager.stopContainer("test-container");

            assertEquals(DockerManager.MAX_STOP_GRACE_PERIOD.plus(COMMAND_TIMEOUT),
                    backend.timeouts.get(backend.timeouts.size() - 1), Long.toString(configured));
        }
        verify(logger, times(2)).warn(contains("unbounded or too long"), any(), any(), any());
    }

    @Test
    public void stopResultsOtherThanTimeoutAreReturnedWithoutARecheck() {
        for (ContainerStatus result : List.of(ContainerStatus.STOPPED, ContainerStatus.FAILED,
                ContainerStatus.MISSING, ContainerStatus.INACCESSIBLE)) {
            backend.stopTimeouts.add(OptionalLong.empty());
            backend.stops.add(result);

            assertEquals(result, dockerManager.stopContainer("test-container"));
        }
        assertFalse(backend.calls.contains("inspect"));
    }

    @Test
    public void timedOutStopIsRecheckedBeforeReportingATimeout() {
        backend.stopTimeouts.add(OptionalLong.empty());
        backend.stops.add(ContainerStatus.TIMED_OUT);
        backend.inspections.add(ContainerInspection.healthy(ContainerStatus.STOPPED));

        assertEquals(ContainerStatus.STOPPED, dockerManager.stopContainer("test-container"));
        verify(logger).warn(contains("exceeded"), eq("test-container"), eq(20_000L));

        backend.stopTimeouts.add(OptionalLong.empty());
        backend.stops.add(ContainerStatus.TIMED_OUT);
        backend.inspections.add(ContainerInspection.healthy(ContainerStatus.RUNNING));

        assertEquals(ContainerStatus.TIMED_OUT, dockerManager.stopContainer("test-container"));
        verify(logger).error(contains("Timed out after"), eq(20_000L), eq("test-container"), eq(10L));
    }

    @Test
    public void interruptedTimedOutStopSkipsTheRecheck() {
        backend.stopTimeouts.add(OptionalLong.empty());
        backend.stops.add(ContainerStatus.TIMED_OUT);

        Thread.currentThread().interrupt();
        try {
            assertEquals(ContainerStatus.TIMED_OUT, dockerManager.stopContainer("test-container"));
        } finally {
            Thread.interrupted();
        }
        assertEquals(List.of("stopTimeoutSeconds", "stop"), backend.calls);
    }

    @Test
    public void changedSettingsSelectANewBackendForLaterOperations() {
        AtomicReference<DockerSettings> settings = new AtomicReference<>(DockerSettings.defaults());
        List<DockerSettings> selections = new ArrayList<>();
        DockerManager manager = new DockerManager(logger, COMMAND_TIMEOUT, settings::get, selected -> {
            selections.add(selected);
            ScriptedBackend selectedBackend = new ScriptedBackend("backend " + selections.size());
            selectedBackend.inspections.add(ContainerInspection.healthy(ContainerStatus.RUNNING));
            selectedBackend.inspections.add(ContainerInspection.healthy(ContainerStatus.RUNNING));
            return selectedBackend;
        });

        assertTrue(manager.describeAccess().startsWith("not selected yet"));
        manager.getContainerStatus("test-container");
        manager.getContainerStatus("test-container");
        assertEquals(List.of(DockerSettings.defaults()), selections, "unchanged settings reuse the backend");
        assertEquals("backend 1", manager.describeAccess());

        DockerSettings changed = new DockerSettings(Optional.of(DockerEndpoint.tcp("proxy", 2375)));
        settings.set(changed);
        assertEquals(ContainerStatus.RUNNING, manager.getContainerStatus("test-container"));
        assertEquals(List.of(DockerSettings.defaults(), changed), selections);
        assertEquals("backend 2", manager.describeAccess());
        verify(logger).info("Docker access: {}", "backend 2");
    }

    @Test
    public void unsupportedAccessFailsEveryOperationAsInaccessible() {
        DockerManager manager = new DockerManager(logger, new UnsupportedDockerAccess("DOCKER_HOST uses ssh://"),
                COMMAND_TIMEOUT);

        ContainerInspection inspection = manager.inspectContainer("test-container");
        assertEquals(ContainerStatus.INACCESSIBLE, inspection.status());
        assertEquals(DockerDiagnostic.DAEMON_UNAVAILABLE, inspection.diagnostic());
        assertEquals("Docker endpoint is not supported: DOCKER_HOST uses ssh://", inspection.detail());
        assertEquals(UnsupportedDockerAccess.REMEDIATION, inspection.remediation());
        assertEquals(ContainerHealth.INACCESSIBLE, manager.getContainerHealth("test-container", COMMAND_TIMEOUT));
        assertEquals(ContainerStatus.INACCESSIBLE, manager.startContainer("test-container"));
        assertEquals(ContainerStatus.INACCESSIBLE, manager.stopContainer("test-container"));
        assertEquals("unsupported (DOCKER_HOST uses ssh://)", manager.describeAccess());
    }

    /** Answers each operation from its queue and records what was asked, in order. */
    private static final class ScriptedBackend implements DockerBackend {
        private final String description;
        private final Queue<ContainerInspection> inspections = new ArrayDeque<>();
        private final Queue<ContainerHealth> healths = new ArrayDeque<>();
        private final Queue<ContainerStatus> starts = new ArrayDeque<>();
        private final Queue<OptionalLong> stopTimeouts = new ArrayDeque<>();
        private final Queue<ContainerStatus> stops = new ArrayDeque<>();
        private final List<String> calls = new ArrayList<>();
        private final List<Duration> timeouts = new ArrayList<>();

        ScriptedBackend(String description) {
            this.description = description;
        }

        @Override
        public ContainerInspection inspect(String containerName, Duration timeout) {
            return next("inspect", timeout, inspections);
        }

        @Override
        public ContainerHealth health(String containerName, Duration timeout) {
            return next("health", timeout, healths);
        }

        @Override
        public ContainerStatus start(String containerName, Duration timeout) {
            return next("start", timeout, starts);
        }

        @Override
        public OptionalLong stopTimeoutSeconds(String containerName, Duration timeout) {
            return next("stopTimeoutSeconds", timeout, stopTimeouts);
        }

        @Override
        public ContainerStatus stop(String containerName, Duration deadline) {
            return next("stop", deadline, stops);
        }

        @Override
        public String describe() {
            return description;
        }

        private <T> T next(String call, Duration timeout, Queue<T> answers) {
            calls.add(call);
            timeouts.add(timeout);
            T answer = answers.poll();
            if (answer == null) {
                throw new AssertionError("Unexpected backend call: " + call);
            }
            return answer;
        }
    }
}
