package me.criseda.autostopper.docker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

@ExtendWith(MockitoExtension.class)
public class DockerManagerTest {

    @Mock
    private Logger logger;

    @Mock
    private Process process;

    @Mock
    private Runtime runtime;

    private DockerManager dockerManager;

    @BeforeEach
    public void setup() {
        dockerManager = new DockerManager(logger);
    }

    @Test
    public void testIsContainerRunning_True() throws Exception {
        // Arrange
        String containerName = "test-container";
        InputStream inputStream = new ByteArrayInputStream("true".getBytes());
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenReturn(process);
            when(process.getInputStream()).thenReturn(inputStream);
            when(process.waitFor()).thenReturn(0);

            // Act
            boolean result = dockerManager.isContainerRunning(containerName);

            // Assert
            assertTrue(result);
            verify(runtime).exec(new String[] { "docker", "inspect", "-f", "{{.State.Running}}", containerName });
        }
    }

    @Test
    public void testIsContainerRunning_False() throws Exception {
        // Arrange
        String containerName = "test-container";
        InputStream inputStream = new ByteArrayInputStream("false".getBytes());
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenReturn(process);
            when(process.getInputStream()).thenReturn(inputStream);
            when(process.waitFor()).thenReturn(0);

            // Act
            boolean result = dockerManager.isContainerRunning(containerName);

            // Assert
            assertFalse(result);
        }
    }

    @Test
    public void testIsContainerRunning_EmptyResult() throws Exception {
        // Arrange
        String containerName = "test-container";
        InputStream inputStream = new ByteArrayInputStream("".getBytes());
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenReturn(process);
            when(process.getInputStream()).thenReturn(inputStream);
            when(process.waitFor()).thenReturn(0);

            // Act
            boolean result = dockerManager.isContainerRunning(containerName);

            // Assert
            assertFalse(result);
        }
    }

    @Test
    public void testIsContainerRunning_IOException() throws Exception {
        // Arrange
        String containerName = "test-container";
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenThrow(new IOException("Test exception"));

            // Act
            boolean result = dockerManager.isContainerRunning(containerName);

            // Assert
            assertFalse(result);
            verify(logger).error(contains("Error checking if container is running"), any(IOException.class));
        }
    }

    @Test
    public void testIsContainerRunning_InterruptedException() throws Exception {
        // Arrange
        String containerName = "test-container";
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenReturn(process);
            when(process.waitFor()).thenThrow(new InterruptedException("Test interruption"));

            // Act
            boolean result = dockerManager.isContainerRunning(containerName);

            // Assert
            assertFalse(result);
            verify(logger).error(contains("Error checking if container is running"), any(InterruptedException.class));
        }
    }

    @Test
    public void testStartContainer_Success() throws Exception {
        // Arrange
        String containerName = "test-container";
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenReturn(process);
            when(process.waitFor()).thenReturn(0);

            // Act
            boolean result = dockerManager.startContainer(containerName);

            // Assert
            assertTrue(result);
            verify(runtime).exec(new String[] { "docker", "start", containerName });
            verify(logger).info(contains("Starting container"));
            verify(logger).info(contains("Started container"));
        }
    }

    @Test
    public void testStartContainer_Failure() throws Exception {
        // Arrange
        String containerName = "test-container";
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenReturn(process);
            when(process.waitFor()).thenReturn(1);

            // Act
            boolean result = dockerManager.startContainer(containerName);

            // Assert
            assertFalse(result);
            verify(logger).error(contains("Failed to start container"));
        }
    }

    @Test
    public void testStartContainer_Exception() throws Exception {
        // Arrange
        String containerName = "test-container";
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenThrow(new IOException("Test exception"));

            // Act
            boolean result = dockerManager.startContainer(containerName);

            // Assert
            assertFalse(result);
            verify(logger).error(contains("Error starting container"), any(IOException.class));
        }
    }

    @Test
    public void testStopContainer_Success() throws Exception {
        // Arrange
        String containerName = "test-container";
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenReturn(process);
            when(process.waitFor()).thenReturn(0);

            // Act
            boolean result = dockerManager.stopContainer(containerName);

            // Assert
            assertTrue(result);
            verify(runtime).exec(new String[] { "docker", "stop", containerName });
            verify(logger).info(contains("Stopped container"));
        }
    }

    @Test
    public void testStopContainer_Failure() throws Exception {
        // Arrange
        String containerName = "test-container";
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenReturn(process);
            when(process.waitFor()).thenReturn(1);

            // Act
            boolean result = dockerManager.stopContainer(containerName);

            // Assert
            assertFalse(result);
            verify(logger).error(contains("Failed to stop container"));
        }
    }

    @Test
    public void testStopContainer_Exception() throws Exception {
        // Arrange
        String containerName = "test-container";
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenThrow(new IOException("Test exception"));

            // Act
            boolean result = dockerManager.stopContainer(containerName);

            // Assert
            assertFalse(result);
            verify(logger).error(contains("Error stopping container"), any(IOException.class));
        }
    }

    @Test
    public void testWaitForContainerReady_Success() throws Exception {
        // Arrange
        String containerName = "test-container";
        String readyPattern = "Server started";
        
        // Create a process that outputs the ready pattern after a short delay
        String output = "Starting up...\nLoading config...\nServer started\n";
        InputStream inputStream = new ByteArrayInputStream(output.getBytes());
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenReturn(process);
            when(process.getInputStream()).thenReturn(inputStream);
            
            // Act
            boolean result = dockerManager.waitForContainerReady(containerName, 5, readyPattern);
            
            // Assert
            assertTrue(result);
            verify(runtime).exec(new String[] { "docker", "logs", "--follow", "--tail=0", containerName });
            verify(logger).info(contains("Container " + containerName + " is ready"));
        }
    }

    @Test
    public void testWaitForContainerReady_Timeout() throws Exception {
        // Arrange
        String containerName = "test-container";
        String readyPattern = "Ready";
        
        // Create a process that never outputs the ready pattern
        String output = "Starting up...\nLoading config...\nWaiting...\n";
        InputStream inputStream = new ByteArrayInputStream(output.getBytes());
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenReturn(process);
            when(process.getInputStream()).thenReturn(inputStream);
            
            // Act - use a short timeout for testing
            boolean result = dockerManager.waitForContainerReady(containerName, 0, readyPattern);
            
            // Assert
            assertFalse(result);
            verify(logger).warn(contains("Timed out waiting for container"));
        }
    }

    @Test
    public void testWaitForContainerReady_Exception() throws Exception {
        // Arrange
        String containerName = "test-container";
        String readyPattern = "Ready";
        
        try (MockedStatic<Runtime> runtimeMockedStatic = mockStatic(Runtime.class)) {
            runtimeMockedStatic.when(Runtime::getRuntime).thenReturn(runtime);
            when(runtime.exec(any(String[].class))).thenThrow(new IOException("Test exception"));
            
            // Act
            boolean result = dockerManager.waitForContainerReady(containerName, 5, readyPattern);
            
            // Assert
            assertFalse(result);
            verify(logger).error(contains("Error monitoring docker logs"), any(IOException.class));
        }
    }
}