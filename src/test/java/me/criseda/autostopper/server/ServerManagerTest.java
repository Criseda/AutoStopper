package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ReadinessSettings;
import me.criseda.autostopper.config.ReadinessStrategy;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.docker.DockerManager;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.readiness.MinecraftStatusProbe;
import me.criseda.autostopper.readiness.ReadinessResult;
import me.criseda.autostopper.readiness.ServerReadinessChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ServerManagerTest {
    
    @Mock
    private ProxyServer proxyServer;
    
    @Mock
    private Logger logger;
    
    @Mock
    private AutoStopperConfig config;
    
    @Mock
    private DockerManager dockerManager;

    @Mock
    private ServerReadinessChecker readinessChecker;
    
    private AutoStopperExecutor executor;
    private ServerManager serverManager;
    
    @BeforeEach
    public void setup() {
        executor = new AutoStopperExecutor();
        serverManager = new ServerManager(proxyServer, logger, config, dockerManager, executor, readinessChecker);
    }

    @AfterEach
    public void teardown() {
        executor.shutdown();
    }
    
    @Test
    public void testGetServerStatus_Running() {
        // Setup
        when(config.snapshot()).thenReturn(snapshot(Map.of("server1", "container1")));
        when(dockerManager.getContainerStatus("container1")).thenReturn(ContainerStatus.RUNNING);
        
        // Execute
        Optional<ContainerStatus> result = serverManager.getServerStatus("server1");
        
        // Verify
        assertEquals(Optional.of(ContainerStatus.RUNNING), result);
        verify(dockerManager).getContainerStatus("container1");
    }
    
    @Test
    public void testGetServerStatus_Stopped() {
        // Setup
        when(config.snapshot()).thenReturn(snapshot(Map.of("server1", "container1")));
        when(dockerManager.getContainerStatus("container1")).thenReturn(ContainerStatus.STOPPED);
        
        // Execute
        Optional<ContainerStatus> result = serverManager.getServerStatus("server1");
        
        // Verify
        assertEquals(Optional.of(ContainerStatus.STOPPED), result);
        verify(dockerManager).getContainerStatus("container1");
    }
    
    @Test
    public void testStartServer() {
        // Setup
        when(config.snapshot()).thenReturn(snapshot(Map.of("server1", "container1")));
        when(dockerManager.startContainer("container1")).thenReturn(ContainerStatus.RUNNING);
        
        // Execute
        ContainerStatus result = serverManager.startServer("server1");
        
        // Verify
        assertEquals(ContainerStatus.RUNNING, result);
        verify(dockerManager).startContainer("container1");
    }
    
    @Test
    public void testStopServer() {
        // Setup
        when(config.snapshot()).thenReturn(snapshot(Map.of("server1", "container1")));
        when(dockerManager.stopContainer("container1")).thenReturn(ContainerStatus.STOPPED);
        
        // Execute
        ContainerStatus result = serverManager.stopServer("server1");
        
        // Verify
        assertEquals(ContainerStatus.STOPPED, result);
        verify(dockerManager).stopContainer("container1");
        verify(logger).info("Stopped server: {} (container: {})", "server1", "container1");
    }

    @Test
    public void testStopServerLogsTypedFailureWithoutClaimingSuccess() {
        when(config.snapshot()).thenReturn(snapshot(Map.of("server1", "container1")));
        when(dockerManager.stopContainer("container1")).thenReturn(ContainerStatus.TIMED_OUT);

        assertEquals(ContainerStatus.TIMED_OUT, serverManager.stopServer("server1"));

        verify(logger).warn("Could not stop server: {} (container: {}, result: {})",
                "server1", "container1", ContainerStatus.TIMED_OUT);
        verify(logger, never()).info(contains("Stopped server"), any(), any());
    }
    
    @Test
    public void testWaitForServerReady() {
        // Setup
        when(config.snapshot()).thenReturn(snapshot(Map.of("server1", "container1")));
        stubRegisteredTarget("server1", "127.0.0.1", 25565);
        ReadinessResult ready = ReadinessResult.ready(1);
        when(readinessChecker.awaitReady(any(ServerMapping.class), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ready));

        // Execute
        ReadinessResult result = serverManager.waitForServerReadyAsync("server1").join();

        // Verify
        assertSame(ready, result);
        verify(readinessChecker).awaitReady(
                any(ServerMapping.class),
                eq(new me.criseda.autostopper.config.ReadinessSettings.Target("127.0.0.1", 25565)),
                any());
    }

    @Test
    public void testConcurrentReadinessWaitsDoNotHoldWorkersBetweenAttempts() throws InterruptedException {
        AutoStopperExecutor singleWorker = new AutoStopperExecutor(1, 4);
        try {
            MinecraftStatusProbe neverReady = (host, port, connect, read, attempt) ->
                    new MinecraftStatusProbe.ProbeResult(MinecraftStatusProbe.Outcome.UNREACHABLE);
            ServerManager realReadiness = new ServerManager(proxyServer, logger, config, dockerManager, singleWorker,
                    new ServerReadinessChecker(logger, dockerManager, neverReady));
            lenient().when(dockerManager.getContainerStatus(anyString(), any())).thenReturn(ContainerStatus.RUNNING);
            when(dockerManager.getContainerStatus("container3")).thenReturn(ContainerStatus.STOPPED);
            ReadinessSettings slowStart = new ReadinessSettings(ReadinessStrategy.MINECRAFT_STATUS,
                    "127.0.0.1", 25565, Duration.ofMillis(200), Duration.ofSeconds(30),
                    Duration.ofMillis(100), Duration.ofMillis(100));

            CompletableFuture<ReadinessResult> first = realReadiness.waitForServerReadyAsync(
                    new ServerMapping("server1", "container1", slowStart));
            CompletableFuture<ReadinessResult> second = realReadiness.waitForServerReadyAsync(
                    new ServerMapping("server2", "container2", slowStart));

            // With one worker, a blocking wait would hold it for the whole 30-second readiness window.
            assertEquals(Optional.of(ContainerStatus.STOPPED), realReadiness.getServerStatusAsync(
                    new ServerMapping("server3", "container3")).orTimeout(5, TimeUnit.SECONDS).join());
            assertFalse(first.isDone());
            assertFalse(second.isDone());

            assertTrue(first.cancel(true));
            assertTrue(second.cancel(true));
        } finally {
            singleWorker.shutdown();
        }
    }
    
    @Test
    public void testIsMonitoredServer() {
        // Setup
        when(config.snapshot()).thenReturn(snapshot(Map.of("server1", "container1", "server2", "container2")));
        
        // Execute & Verify
        assertTrue(serverManager.isMonitoredServer("server1"));
        assertTrue(serverManager.isMonitoredServer("server2"));
        assertFalse(serverManager.isMonitoredServer("server3"));
    }
    
    @Test
    public void testGetContainerName() {
        // Setup
        Map<String, String> mapping = new HashMap<>();
        mapping.put("server1", "container1");
        when(config.snapshot()).thenReturn(snapshot(mapping));

        // Execute & Verify
        assertEquals("container1", serverManager.getContainerName("server1"));
        assertNull(serverManager.getContainerName("server2"));
    }

    @Test
    public void testUnmappedServerIsNeverInspectedStartedOrStopped() {
        // Setup - no mapping for "server2"
        when(config.snapshot()).thenReturn(snapshot(Map.of("server1", "container1")));

        // Execute & Verify
        assertEquals(Optional.empty(), serverManager.getServerStatus("server2"));
        assertEquals(ContainerStatus.MISSING, serverManager.startServer("server2"));
        assertEquals(ContainerStatus.MISSING, serverManager.stopServer("server2"));
        assertEquals(ReadinessResult.Outcome.CONTAINER_MISSING,
                serverManager.waitForServerReadyAsync("server2").join().outcome());
        verify(dockerManager, never()).getContainerStatus(anyString());
        verify(dockerManager, never()).startContainer(anyString());
        verify(dockerManager, never()).stopContainer(anyString());
        verifyNoInteractions(readinessChecker);
        verify(logger, times(4)).warn(contains("No container mapped for server:"), eq("server2"));
    }
    
    @Test
    public void testGetServer() {
        // Setup
        RegisteredServer registeredServer = mock(RegisteredServer.class);
        when(proxyServer.getServer("server1")).thenReturn(Optional.of(registeredServer));
        when(proxyServer.getServer("server2")).thenReturn(Optional.empty());
        
        // Execute & Verify
        assertEquals(Optional.of(registeredServer), serverManager.getServer("server1"));
        assertEquals(Optional.empty(), serverManager.getServer("server2"));
    }
    
    @Test
    public void testGetServerStatusAsync() {
        // Setup
        when(config.snapshot()).thenReturn(snapshot(Map.of("server1", "container1")));
        when(dockerManager.getContainerStatus("container1")).thenReturn(ContainerStatus.STOPPED);

        // Execute
        Optional<ContainerStatus> result = serverManager.getServerStatusAsync("server1").join();

        // Verify
        assertEquals(Optional.of(ContainerStatus.STOPPED), result);
        verify(dockerManager).getContainerStatus("container1");
    }

    @Test
    public void testStartServerAsync() {
        // Setup
        when(config.snapshot()).thenReturn(snapshot(Map.of("server1", "container1")));
        when(dockerManager.startContainer("container1")).thenReturn(ContainerStatus.RUNNING);

        // Execute
        ContainerStatus result = serverManager.startServerAsync("server1").join();

        // Verify
        assertEquals(ContainerStatus.RUNNING, result);
        verify(dockerManager).startContainer("container1");
    }

    @Test
    public void testWaitForServerReadyAsync() {
        // Setup
        when(config.snapshot()).thenReturn(snapshot(Map.of("server1", "container1")));
        stubRegisteredTarget("server1", "127.0.0.1", 25565);
        ReadinessResult ready = ReadinessResult.ready(1);
        when(readinessChecker.awaitReady(any(ServerMapping.class), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ready));

        // Execute
        ReadinessResult result = serverManager.waitForServerReadyAsync("server1").join();

        // Verify
        assertSame(ready, result);
        verify(readinessChecker).awaitReady(any(ServerMapping.class), any(), any());
    }

    @Test
    public void testWaitForServerReadyAsyncReportsTargetResolutionFailureAsFailedFuture() {
        when(proxyServer.getServer("server1")).thenThrow(new IllegalStateException("registry unavailable"));

        CompletableFuture<ReadinessResult> result = serverManager.waitForServerReadyAsync(
                new ServerMapping("server1", "container1"));

        CompletionException error = assertThrows(CompletionException.class, result::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());
        verifyNoInteractions(readinessChecker);
    }

    @Test
    public void testGetStatusesAsync_FansOutAndPreservesOrder() {
        // Setup
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("server1", "container1");
        mapping.put("server2", "container2");
        when(config.snapshot()).thenReturn(snapshot(mapping));
        when(dockerManager.getContainerStatus("container1")).thenReturn(ContainerStatus.RUNNING);
        when(dockerManager.getContainerStatus("container2")).thenReturn(ContainerStatus.TIMED_OUT);

        // Execute
        Map<String, Optional<ContainerStatus>> result =
                serverManager.getStatusesAsync(List.of("server1", "server2")).join();

        // Verify - each server inspected exactly once, in order
        assertEquals(Optional.of(ContainerStatus.RUNNING), result.get("server1"));
        assertEquals(Optional.of(ContainerStatus.TIMED_OUT), result.get("server2"));
        assertEquals(List.of("server1", "server2"), new java.util.ArrayList<>(result.keySet()));
        verify(dockerManager).getContainerStatus("container1");
        verify(dockerManager).getContainerStatus("container2");
    }

    @Test
    public void testGetStatusesAsync_CancellationInterruptsStatusChecks() throws InterruptedException {
        Map<String, String> mapping = Map.of(
                "server1", "container1",
                "server2", "container2");
        when(config.snapshot()).thenReturn(snapshot(mapping));
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        when(dockerManager.getContainerStatus(anyString())).thenAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return ContainerStatus.FAILED;
        });

        CompletableFuture<Map<String, Optional<ContainerStatus>>> statuses =
                serverManager.getStatusesAsync(List.of("server1", "server2"));

        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(statuses.cancel(true));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS),
                "cancelling the fan-in should interrupt every outstanding status check");
        assertTrue(statuses.isCancelled());
    }

    @Test
    public void testCapturedMappingPinsContainerAcrossReload() {
        ConfigSnapshot previous = snapshot(Map.of("server1", "old-container"));
        ConfigSnapshot current = snapshot(Map.of("server1", "new-container"));
        when(config.snapshot()).thenReturn(previous, current);
        when(dockerManager.getContainerStatus("old-container")).thenReturn(ContainerStatus.STOPPED);
        when(dockerManager.startContainer("old-container")).thenReturn(ContainerStatus.RUNNING);
        stubRegisteredTarget("server1", "127.0.0.1", 25565);
        ReadinessResult ready = ReadinessResult.ready(1);
        when(readinessChecker.awaitReady(any(ServerMapping.class), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ready));

        ServerMapping captured = serverManager.getServerMapping("server1").orElseThrow();
        assertEquals("new-container", serverManager.getContainerName("server1"));

        assertEquals(Optional.of(ContainerStatus.STOPPED), serverManager.getServerStatus(captured));
        assertEquals(ContainerStatus.RUNNING, serverManager.startServer(captured));
        assertSame(ready, serverManager.waitForServerReadyAsync(captured).join());
        verify(dockerManager, never()).getContainerStatus("new-container");
        verify(dockerManager, never()).startContainer("new-container");
    }

    @Test
    public void testGetStatusesAsyncUsesProvidedSnapshot() {
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("server1", "old-container-1");
        mappings.put("server2", "old-container-2");
        ConfigSnapshot captured = snapshot(mappings);
        when(dockerManager.getContainerStatus("old-container-1")).thenReturn(ContainerStatus.RUNNING);
        when(dockerManager.getContainerStatus("old-container-2")).thenReturn(ContainerStatus.STOPPED);

        Map<String, Optional<ContainerStatus>> result = serverManager.getStatusesAsync(captured).join();

        assertEquals(List.of("server1", "server2"), new java.util.ArrayList<>(result.keySet()));
        verifyNoInteractions(config);
        verify(dockerManager).getContainerStatus("old-container-1");
        verify(dockerManager).getContainerStatus("old-container-2");
    }

    private ConfigSnapshot snapshot(Map<String, String> mappings) {
        return new ConfigSnapshot(
                ConfigSnapshot.DEFAULT_INACTIVITY_TIMEOUT_SECONDS,
                mappings.entrySet().stream()
                        .map(entry -> new ServerMapping(entry.getKey(), entry.getValue()))
                        .toList());
    }

    private void stubRegisteredTarget(String serverName, String host, int port) {
        RegisteredServer registered = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(proxyServer.getServer(serverName)).thenReturn(Optional.of(registered));
        when(registered.getServerInfo()).thenReturn(info);
        when(info.getAddress()).thenReturn(new InetSocketAddress(host, port));
    }
}
