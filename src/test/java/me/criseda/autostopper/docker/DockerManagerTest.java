package me.criseda.autostopper.docker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@ExtendWith(MockitoExtension.class)
public class DockerManagerTest {

    @Mock
    private Logger logger;

    private DockerManager dockerManager;

    @BeforeEach
    public void setup() {
        dockerManager = new DockerManager(logger);
    }

    private void setupMockProcess(Process mockProcess, String stdout, String stderr, int exitCode) {
        when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(stdout.getBytes()));
        when(mockProcess.getErrorStream()).thenReturn(new ByteArrayInputStream(stderr.getBytes()));
        try {
            when(mockProcess.waitFor()).thenReturn(exitCode);
        } catch (InterruptedException e) {
            // Mock exception, theoretically unreachable in setup
        }
    }

    @Test
    public void testIsContainerRunning_True() {
        try (MockedConstruction<ProcessBuilder> mockedPb = mockConstruction(ProcessBuilder.class,
                (mock, context) -> {
                    Process process = mock(Process.class);
                    setupMockProcess(process, "true", "", 0);
                    when(mock.start()).thenReturn(process);
                    when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                })) {
            
            boolean result = dockerManager.isContainerRunning("test-container");
            assertTrue(result);
        }
    }

    @Test
    public void testIsContainerRunning_False() {
        try (MockedConstruction<ProcessBuilder> mockedPb = mockConstruction(ProcessBuilder.class,
                (mock, context) -> {
                    Process process = mock(Process.class);
                    setupMockProcess(process, "false", "", 0);
                    when(mock.start()).thenReturn(process);
                    when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                })) {

            boolean result = dockerManager.isContainerRunning("test-container");
            assertFalse(result);
        }
    }

    @Test
    public void testIsContainerRunning_FailExitCode() {
        try (MockedConstruction<ProcessBuilder> mockedPb = mockConstruction(ProcessBuilder.class,
                (mock, context) -> {
                    Process process = mock(Process.class);
                    setupMockProcess(process, "", "some error", 1);
                    when(mock.start()).thenReturn(process);
                    when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                })) {

            boolean result = dockerManager.isContainerRunning("test-container");
            assertFalse(result);
            verify(logger).warn(contains("Could not check status"), any(), any(), any());
        }
    }

    @Test
    public void testIsContainerRunning_IOException() {
        try (MockedConstruction<ProcessBuilder> mockedPb = mockConstruction(ProcessBuilder.class,
                (mock, context) -> {
                    when(mock.start()).thenThrow(new IOException("Test exception"));
                    when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                })) {

            boolean result = dockerManager.isContainerRunning("test-container");
            assertFalse(result);
            verify(logger).error(contains("Error executing command"), any(IOException.class));
        }
    }

    @Test
    public void testStartContainer_Success() {
        try (MockedConstruction<ProcessBuilder> mockedPb = mockConstruction(ProcessBuilder.class,
                (mock, context) -> {
                    List<?> args = context.arguments();
                    String[] commandValue = (String[]) args.get(0);
                    List<String> cmdList = Arrays.asList(commandValue);
                    
                    Process process = mock(Process.class);
                    when(mock.start()).thenReturn(process);
                    when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);

                    if (cmdList.contains("inspect")) {
                        setupMockProcess(process, "false", "", 0);
                    } else if (cmdList.contains("start")) {
                        setupMockProcess(process, "container started", "", 0);
                    }
                })) {

            boolean result = dockerManager.startContainer("test-container");
            assertTrue(result);
            verify(logger).info(contains("Starting container"));
            verify(logger).info(contains("Started container"));
        }
    }
    
    @Test
    public void testStartContainer_AlreadyRunning() {
        try (MockedConstruction<ProcessBuilder> mockedPb = mockConstruction(ProcessBuilder.class,
                (mock, context) -> {
                    Process process = mock(Process.class);
                    when(mock.start()).thenReturn(process);
                    when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                    setupMockProcess(process, "true", "", 0);
                })) {

            boolean result = dockerManager.startContainer("test-container");
            assertTrue(result);
            verify(logger).info(contains("is already running"));
        }
    }

    @Test
    public void testStartContainer_Failure() {
        try (MockedConstruction<ProcessBuilder> mockedPb = mockConstruction(ProcessBuilder.class,
                (mock, context) -> {
                    List<?> args = context.arguments();
                    String[] commandValue = (String[]) args.get(0);
                    List<String> cmdList = Arrays.asList(commandValue);
                    
                    Process process = mock(Process.class);
                    when(mock.start()).thenReturn(process);
                    when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);

                    if (cmdList.contains("inspect")) {
                        setupMockProcess(process, "false", "", 0);
                    } else if (cmdList.contains("start")) {
                        setupMockProcess(process, "", "permission denied", 1);
                    }
                })) {

            boolean result = dockerManager.startContainer("test-container");
            assertFalse(result);
            verify(logger).error(contains("Failed to start container"));
        }
    }

    @Test
    public void testStopContainer_Success() {
        try (MockedConstruction<ProcessBuilder> mockedPb = mockConstruction(ProcessBuilder.class,
                (mock, context) -> {
                    Process process = mock(Process.class);
                    setupMockProcess(process, "", "", 0);
                    when(mock.start()).thenReturn(process);
                    when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                })) {

            boolean result = dockerManager.stopContainer("test-container");
            assertTrue(result);
            verify(logger).info(contains("Stopped container"));
        }
    }

    @Test
    public void testWaitForContainerReady_Success() {
        String containerName = "test-container";
        String readyPattern = "Server started";
        
        try (MockedConstruction<ProcessBuilder> mockedPb = mockConstruction(ProcessBuilder.class,
                (mock, context) -> {
                    Process process = mock(Process.class);
                    // Standard input stream for logs
                    String output = "Starting up...\n" + readyPattern + "\n";
                    when(process.getInputStream()).thenReturn(new ByteArrayInputStream(output.getBytes()));
                    when(mock.start()).thenReturn(process);
                    when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                })) {
            
            boolean result = dockerManager.waitForContainerReady(containerName, 5, readyPattern);
            assertTrue(result);
            verify(logger).info(contains("is ready"));
        }
    }

    @Test
    public void testWaitForContainerReady_Timeout() {
        String containerName = "test-container";
        String readyPattern = "Ready";
        
        try (MockedConstruction<ProcessBuilder> mockedPb = mockConstruction(ProcessBuilder.class,
                (mock, context) -> {
                    Process process = mock(Process.class);
                    // Output without the pattern
                    String output = "Starting up...\nWaiting...\n";
                    when(process.getInputStream()).thenReturn(new ByteArrayInputStream(output.getBytes()));
                    // Ensure the process isn't "alive" forever to allow loop to exit if read isn't blocking (Test logic simulation)
                    // The loop checks (System.currentTimeMillis() - startTime) < timeoutMillis.
                    // If readLine returns, it checks pattern. If not found, it loops.
                    // ByteArrayInputStream yields bytes then is empty. `reader.ready()` might be false or `readLine` null.
                    // If `readLine` returns null, loop breaks -> returns false.
                    
                    when(mock.start()).thenReturn(process);
                    when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                })) {
            
            // Short timeout, but loop should exit on stream EOF anyway
            boolean result = dockerManager.waitForContainerReady(containerName, 1, readyPattern);
            assertFalse(result);
            verify(logger).warn(contains("Timeout waiting for container"));
        }
    }
}