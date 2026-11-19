package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ReadinessSettings;
import me.criseda.autostopper.config.ReadinessStrategy;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerInspection;
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
    
    private static final ServerMapping SERVER1 = new ServerMapping("server1", "container1");

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
        when(dockerManager.getContainerStatus("container1")).thenReturn(ContainerStatus.RUNNING);
        
        // Execute
        Optional<ContainerStatus> result = serverManager.getServerStatus(SERVER1);
        
        // Verify
        assertEquals(Optional.of(ContainerStatus.RUNNING), result);
        verify(dockerManager).getContainerStatus("container1");
    }
    
    @Test
    public void testGetServerStatus_Stopped() {
        // Setup
        when(dockerManager.getContainerStatus("container1")).thenReturn(ContainerStatus.STOPPED);
        
        // Execute
        Optional<ContainerStatus> result = serverManager.getServerStatus(SERVER1);
        
        // Verify
        assertEquals(Optional.of(ContainerStatus.STOPPED), result);
        verify(dockerManager).getContainerStatus("container1");
    }
    
    @Test
    public void testStartServer() {
        // Setup
        when(dockerManager.startContainer("container1")).thenReturn(ContainerStatus.RUNNING);
        
        // Execute
        ContainerStatus result = serverManager.startServer(SERVER1);
        
        // Verify
        assertEquals(ContainerStatus.RUNNING, result);
        verify(dockerManager).startContainer("container1");
    }
    
    @Test
    public void testStopServer() {
        // Setup
        when(dockerManager.stopContainer("container1")).thenReturn(ContainerStatus.STOPPED);
        
        // Execute
        ContainerStatus result = serverManager.stopServer(SERVER1);
        
        // Verify
        assertEquals(ContainerStatus.STOPPED, result);
        verify(dockerManager).stopContainer("container1");
        verify(logger).info("Stopped server: {} (container: {})", "server1", "container1");
    }

    @Test
    public void testStopServerLogsTypedFailureWithoutClaimingSuccess() {
        when(dockerManager.stopContainer("container1")).thenReturn(ContainerStatus.TIMED_OUT);

        assertEquals(ContainerStatus.TIMED_OUT, serverManager.stopServer(SERVER1));

        verify(logger).warn("Could not stop server: {} (container: {}, result: {})",
                "server1", "container1", ContainerStatus.TIMED_OUT);
        verify(logger, never()).info(contains("Stopped server"), any(), any());
    }
    
    @Test
    public void testWaitForServerReady() {
        // Setup
        stubRegisteredTarget("server1", "127.0.0.1", 25565);
        ReadinessResult ready = ReadinessResult.ready(1);
        when(readinessChecker.awaitReady(any(ServerMapping.class), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ready));

        // Execute
        ReadinessResult result = serverManager.waitForServerReadyAsync(SERVER1).join();

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
        when(dockerManager.getContainerStatus("container1")).thenReturn(ContainerStatus.STOPPED);

        // Execute
        Optional<ContainerStatus> result = serverManager.getServerStatusAsync(SERVER1).join();

        // Verify
        assertEquals(Optional.of(ContainerStatus.STOPPED), result);
        verify(dockerManager).getContainerStatus("container1");
    }

    @Test
    public void testStartServerAsync() {
        // Setup
        when(dockerManager.startContainer("container1")).thenReturn(ContainerStatus.RUNNING);

        // Execute
        ContainerStatus result = serverManager.startServerAsync(SERVER1).join();

        // Verify
        assertEquals(ContainerStatus.RUNNING, result);
        verify(dockerManager).startContainer("container1");
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
    public void testInspectContainersAsyncPreservesMappingOrder() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("server1", "container1");
        mapping.put("server2", "container2");
        ContainerInspection running = ContainerInspection.healthy(ContainerStatus.RUNNING);
        ContainerInspection stopped = ContainerInspection.healthy(ContainerStatus.STOPPED);
        when(dockerManager.inspectContainer("container1")).thenReturn(running);
        when(dockerManager.inspectContainer("container2")).thenReturn(stopped);

        Map<String, ContainerInspection> result = serverManager.inspectContainersAsync(snapshot(mapping)).join();

        // Each container inspected exactly once, keyed in mapping order
        assertSame(running, result.get("server1"));
        assertSame(stopped, result.get("server2"));
        assertEquals(List.of("server1", "server2"), new java.util.ArrayList<>(result.keySet()));
        verify(dockerManager).inspectContainer("container1");
        verify(dockerManager).inspectContainer("container2");
    }

    @Test
    public void testInspectContainersAsyncCancellationInterruptsInspects() throws InterruptedException {
        Map<String, String> mapping = Map.of(
                "server1", "container1",
                "server2", "container2");
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        when(dockerManager.inspectContainer(anyString())).thenAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return ContainerInspection.healthy(ContainerStatus.STOPPED);
        });

        CompletableFuture<Map<String, ContainerInspection>> inspections =
                serverManager.inspectContainersAsync(snapshot(mapping));

        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(inspections.cancel(true));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS),
                "cancelling the batch should interrupt every outstanding inspect");
        assertTrue(inspections.isCancelled());
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
        assertEquals("new-container", serverManager.getServerMapping("server1").orElseThrow().containerName());

        assertEquals(Optional.of(ContainerStatus.STOPPED), serverManager.getServerStatus(captured));
        assertEquals(ContainerStatus.RUNNING, serverManager.startServer(captured));
        assertSame(ready, serverManager.waitForServerReadyAsync(captured).join());
        verify(dockerManager, never()).getContainerStatus("new-container");
        verify(dockerManager, never()).startContainer("new-container");
    }

    @Test
    public void testInspectContainersAsyncUsesProvidedSnapshot() {
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("server1", "old-container-1");
        mappings.put("server2", "old-container-2");
        ConfigSnapshot captured = snapshot(mappings);
        when(dockerManager.inspectContainer(anyString()))
                .thenReturn(ContainerInspection.healthy(ContainerStatus.RUNNING));

        Map<String, ContainerInspection> result = serverManager.inspectContainersAsync(captured).join();

        assertEquals(List.of("server1", "server2"), new java.util.ArrayList<>(result.keySet()));
        verifyNoInteractions(config);
        verify(dockerManager).inspectContainer("old-container-1");
        verify(dockerManager).inspectContainer("old-container-2");
    }

    @Test
    public void testInspectContainersAsyncCompletesForMoreMappingsThanTheWorkerQueueHolds() {
        int mappingCount = AutoStopperExecutor.DEFAULT_WORKER_COUNT
                + AutoStopperExecutor.DEFAULT_QUEUE_CAPACITY + 26;
        Map<String, String> mappings = new LinkedHashMap<>();
        for (int i = 0; i < mappingCount; i++) {
            mappings.put("server" + i, "container" + i);
        }
        when(dockerManager.inspectContainer(anyString())).thenAnswer(invocation -> {
            Thread.sleep(5);
            return ContainerInspection.healthy(ContainerStatus.RUNNING);
        });

        Map<String, ContainerInspection> result =
                serverManager.inspectContainersAsync(snapshot(mappings)).join();

        assertEquals(new java.util.ArrayList<>(mappings.keySet()), new java.util.ArrayList<>(result.keySet()));
        assertTrue(result.values().stream().allMatch(ContainerInspection::healthy));
        verify(dockerManager, times(mappingCount)).inspectContainer(anyString());
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
