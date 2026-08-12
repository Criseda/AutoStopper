package me.criseda.autostopper.docker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

@ExtendWith(MockitoExtension.class)
public class DockerManagerTest {

    @Mock
    private Logger logger;

    private FakeCommandRunner commandRunner;
    private DockerManager dockerManager;

    @BeforeEach
    public void setup() {
        commandRunner = new FakeCommandRunner();
        dockerManager = new DockerManager(logger, commandRunner);
    }

    @Test
    public void testGetContainerStatus_Running() {
        commandRunner.stage("inspect", completed(0, "true", ""));

        assertEquals(ContainerStatus.RUNNING, dockerManager.getContainerStatus("test-container"));
        assertEquals(Duration.ofSeconds(10), commandRunner.lastTimeout);
    }

    @Test
    public void testGetContainerStatus_Stopped() {
        commandRunner.stage("inspect", completed(0, "false", ""));

        assertEquals(ContainerStatus.STOPPED, dockerManager.getContainerStatus("test-container"));
    }

    @Test
    public void testGetContainerStatus_Missing() {
        commandRunner.stage("inspect", completed(1, "", "Error: No such object: test-container"));

        assertEquals(ContainerStatus.MISSING, dockerManager.getContainerStatus("test-container"));
        verify(logger).warn(contains("does not exist"), anyString(), anyString());
    }

    @Test
    public void testGetContainerStatus_PermissionDenied() {
        commandRunner.stage("inspect", completed(1, "",
                "Got permission denied while trying to connect to the Docker daemon socket"));

        assertEquals(ContainerStatus.INACCESSIBLE, dockerManager.getContainerStatus("test-container"));
        verify(logger).warn(contains("inaccessible"), anyString(), anyString());
    }

    @Test
    public void testGetContainerStatus_GenericFailure() {
        commandRunner.stage("inspect", completed(1, "", "some daemon error"));

        assertEquals(ContainerStatus.FAILED, dockerManager.getContainerStatus("test-container"));
        verify(logger).warn(contains("Could not check status"), anyString(), anyString(), anyInt());
    }

    @Test
    public void testGetContainerStatus_MalformedOutput() {
        commandRunner.stage("inspect", completed(0, "yes", ""));

        assertEquals(ContainerStatus.FAILED, dockerManager.getContainerStatus("test-container"));
        verify(logger).warn(contains("Unexpected output"), anyString(), anyString(), anyInt());
    }

    @Test
    public void testGetContainerStatus_Timeout() {
        commandRunner.stage("inspect", new CommandOutput(CommandOutput.Outcome.TIMED_OUT, -1, "", ""));

        assertEquals(ContainerStatus.TIMED_OUT, dockerManager.getContainerStatus("test-container"));
        verify(logger).warn(contains("Timed out after"), any(Object.class), anyString(), anyString());
    }

    @Test
    public void testGetContainerStatus_SpawnFailed() {
        commandRunner.stage("inspect",
                new CommandOutput(CommandOutput.Outcome.SPAWN_FAILED, -1, "", "docker not found"));

        assertEquals(ContainerStatus.FAILED, dockerManager.getContainerStatus("test-container"));
        verify(logger).error(contains("Could not execute docker inspect"), anyString(), anyString());
    }

    @Test
    public void testInspectContainer_DistinguishesOperationalDiagnostics() {
        commandRunner.stage("inspect",
                new CommandOutput(CommandOutput.Outcome.SPAWN_FAILED, -1, "", "docker not found"));
        commandRunner.stage("inspect", completed(1, "", "Cannot connect to the Docker daemon"));
        commandRunner.stage("inspect", completed(1, "", "permission denied"));
        commandRunner.stage("inspect", completed(1, "", "No such object: absent"));
        commandRunner.stage("inspect",
                new CommandOutput(CommandOutput.Outcome.TIMED_OUT, -1, "", ""));
        commandRunner.stage("inspect", completed(1, "", "unexpected inspect failure"));

        assertEquals(DockerDiagnostic.CLI_MISSING,
                dockerManager.inspectContainer("test-container").diagnostic());
        assertEquals(DockerDiagnostic.DAEMON_UNAVAILABLE,
                dockerManager.inspectContainer("test-container").diagnostic());
        assertEquals(DockerDiagnostic.PERMISSION_DENIED,
                dockerManager.inspectContainer("test-container").diagnostic());
        assertEquals(DockerDiagnostic.CONTAINER_MISSING,
                dockerManager.inspectContainer("test-container").diagnostic());
        assertEquals(DockerDiagnostic.TIMED_OUT,
                dockerManager.inspectContainer("test-container").diagnostic());
        assertEquals(DockerDiagnostic.INDETERMINATE,
                dockerManager.inspectContainer("test-container").diagnostic());
    }

    @Test
    public void testStartContainer_AlreadyRunning() {
        commandRunner.stage("inspect", completed(0, "true", ""));

        assertEquals(ContainerStatus.RUNNING, dockerManager.startContainer("test-container"));
        assertEquals(1, commandRunner.commands.size());
        assertEquals("inspect", commandRunner.commands.get(0).get(1));
    }

    @Test
    public void testStartContainer_Success() {
        commandRunner.stage("inspect", completed(0, "false", ""));
        commandRunner.stage("start", completed(0, "test-container", ""));

        assertEquals(ContainerStatus.RUNNING, dockerManager.startContainer("test-container"));
        assertEquals(2, commandRunner.commands.size());
        assertEquals("start", commandRunner.commands.get(1).get(1));
    }

    @Test
    public void testStartContainer_PermissionDeniedOnStart() {
        commandRunner.stage("inspect", completed(0, "false", ""));
        commandRunner.stage("start", completed(1, "", "permission denied"));

        assertEquals(ContainerStatus.INACCESSIBLE, dockerManager.startContainer("test-container"));
        verify(logger).error(contains("Permission denied starting container"), anyString(), anyString());
    }

    @Test
    public void testStartContainer_FailedToStart() {
        commandRunner.stage("inspect", completed(0, "false", ""));
        commandRunner.stage("start", completed(1, "", "container errored"));

        assertEquals(ContainerStatus.FAILED, dockerManager.startContainer("test-container"));
        verify(logger).error(contains("Failed to start container"), anyString(), anyString(), anyInt());
    }

    @Test
    public void testStartContainer_TimedOut() {
        commandRunner.stage("inspect", completed(0, "false", ""));
        commandRunner.stage("start", new CommandOutput(CommandOutput.Outcome.TIMED_OUT, -1, "", ""));

        assertEquals(ContainerStatus.TIMED_OUT, dockerManager.startContainer("test-container"));
    }

    @Test
    public void testStartContainer_NotAttemptedAfterMissing() {
        commandRunner.stage("inspect", completed(1, "", "No such object: test-container"));

        assertEquals(ContainerStatus.MISSING, dockerManager.startContainer("test-container"));
        assertOnlyInspectIssued();
    }

    @Test
    public void testStartContainer_NotAttemptedAfterInaccessible() {
        commandRunner.stage("inspect", completed(1, "", "permission denied"));

        assertEquals(ContainerStatus.INACCESSIBLE, dockerManager.startContainer("test-container"));
        assertOnlyInspectIssued();
    }

    @Test
    public void testStartContainer_NotAttemptedAfterTimeout() {
        commandRunner.stage("inspect", new CommandOutput(CommandOutput.Outcome.TIMED_OUT, -1, "", ""));

        assertEquals(ContainerStatus.TIMED_OUT, dockerManager.startContainer("test-container"));
        assertOnlyInspectIssued();
    }

    @Test
    public void testStartContainer_NotAttemptedAfterFailedStatus() {
        commandRunner.stage("inspect", completed(1, "", "daemon error"));

        assertEquals(ContainerStatus.FAILED, dockerManager.startContainer("test-container"));
        assertOnlyInspectIssued();
    }

    @Test
    public void testStopContainer_Success() {
        commandRunner.stage("stop", completed(0, "test-container", ""));

        assertEquals(ContainerStatus.STOPPED, dockerManager.stopContainer("test-container"));
        verify(logger).info(contains("Stopped container"), anyString());
    }

    @Test
    public void testStopContainer_Failed() {
        commandRunner.stage("stop", completed(1, "", "container error"));

        assertEquals(ContainerStatus.FAILED, dockerManager.stopContainer("test-container"));
    }

    @Test
    public void testStopContainer_TimedOut() {
        commandRunner.stage("stop", new CommandOutput(CommandOutput.Outcome.TIMED_OUT, -1, "", ""));

        assertEquals(ContainerStatus.TIMED_OUT, dockerManager.stopContainer("test-container"));
    }

    @Test
    public void testGetContainerHealth_Healthy() {
        commandRunner.stage("inspect", completed(0, "healthy", ""));

        assertEquals(ContainerHealth.HEALTHY,
                dockerManager.getContainerHealth("test-container", Duration.ofSeconds(30)));
        assertEquals(Duration.ofSeconds(10), commandRunner.lastTimeout,
                "health commands remain capped by the Docker command timeout");
    }

    @Test
    public void testGetContainerHealth_NoHealthcheck() {
        commandRunner.stage("inspect", completed(0, "none", ""));

        assertEquals(ContainerHealth.NO_HEALTHCHECK,
                dockerManager.getContainerHealth("test-container", Duration.ofSeconds(1)));
    }

    @Test
    public void testGetContainerHealth_StoppedAndMissing() {
        commandRunner.stage("inspect", completed(0, "stopped", ""));
        commandRunner.stage("inspect", completed(1, "", "No such object: test-container"));

        assertEquals(ContainerHealth.STOPPED,
                dockerManager.getContainerHealth("test-container", Duration.ofSeconds(1)));
        assertEquals(ContainerHealth.MISSING,
                dockerManager.getContainerHealth("test-container", Duration.ofSeconds(1)));
    }

    @Test
    public void testGetContainerHealth_InaccessibleAndTimedOut() {
        commandRunner.stage("inspect", completed(1, "", "permission denied"));
        commandRunner.stage("inspect", new CommandOutput(CommandOutput.Outcome.TIMED_OUT, -1, "", ""));

        assertEquals(ContainerHealth.INACCESSIBLE,
                dockerManager.getContainerHealth("test-container", Duration.ofSeconds(1)));
        assertEquals(ContainerHealth.TIMED_OUT,
                dockerManager.getContainerHealth("test-container", Duration.ofSeconds(1)));
    }

    private void assertOnlyInspectIssued() {
        assertEquals(1, commandRunner.commands.size());
        assertEquals("inspect", commandRunner.commands.get(0).get(1));
    }

    private static CommandOutput completed(int exitCode, String stdout, String stderr) {
        return new CommandOutput(CommandOutput.Outcome.COMPLETED, exitCode, stdout, stderr);
    }

    private static class FakeCommandRunner implements CommandRunner {
        private final Map<String, Queue<CommandOutput>> responses = new LinkedHashMap<>();
        private final List<List<String>> commands = new ArrayList<>();
        private Duration lastTimeout;

        void stage(String command, CommandOutput output) {
            responses.computeIfAbsent(command, k -> new LinkedList<>()).add(output);
        }

        @Override
        public CommandOutput run(List<String> command, Duration timeout) {
            commands.add(command);
            lastTimeout = timeout;
            Queue<CommandOutput> queue = responses.get(command.get(1));
            if (queue == null || queue.isEmpty()) {
                throw new AssertionError("Unexpected docker command: " + command);
            }
            return queue.poll();
        }
    }
}
