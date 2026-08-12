package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.docker.DockerManager;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    
    private AutoStopperExecutor executor;
    private ServerManager serverManager;
    
    @BeforeEach
    public void setup() {
        executor = new AutoStopperExecutor();
        serverManager = new ServerManager(proxyServer, logger, config, dockerManager, executor);
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
        verify(logger).info(contains("Stopped server:"), eq("server1"), any(), any());
    }
    
    @Test
    public void testWaitForServerReady() {
        // Setup
        when(config.snapshot()).thenReturn(snapshot(Map.of("server1", "container1")));
        when(dockerManager.waitForContainerReady(eq("container1"), eq(30), anyString(), anyString(), anyString()))
            .thenReturn(true);
        
        // Execute
        boolean result = serverManager.waitForServerReady("server1", 30);
        
        // Verify
        assertTrue(result);
        verify(dockerManager).waitForContainerReady(
            eq("container1"), 
            eq(30), 
            eq("Done ("), 
            eq("] Done ("), 
            eq("For help, type \"help\"")
        );
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
        assertFalse(serverManager.waitForServerReady("server2", 30));
        verify(dockerManager, never()).getContainerStatus(anyString());
        verify(dockerManager, never()).startContainer(anyString());
        verify(dockerManager, never()).stopContainer(anyString());
        verify(dockerManager, never()).waitForContainerReady(anyString(), anyInt(), anyString());
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
    public void testGetServerStartingStatus() {
        // First call should create a new AtomicBoolean
        AtomicBoolean status1 = serverManager.getServerStartingStatus("server1");
        assertFalse(status1.get());
        
        // Change the status
        status1.set(true);
        
        // Second call should return the same instance
        AtomicBoolean status2 = serverManager.getServerStartingStatus("server1");
        assertTrue(status2.get());
        assertSame(status1, status2);
        
        // Different server should get a different instance
        AtomicBoolean status3 = serverManager.getServerStartingStatus("server2");
        assertFalse(status3.get());
        assertNotSame(status1, status3);
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
        when(dockerManager.waitForContainerReady(
                eq("container1"), eq(30), anyString(), anyString(), anyString())).thenReturn(true);

        // Execute
        boolean result = serverManager.waitForServerReadyAsync("server1", 30).join();

        // Verify
        assertTrue(result);
        verify(dockerManager).waitForContainerReady(
                eq("container1"), eq(30), eq("Done ("), eq("] Done ("), eq("For help, type \"help\""));
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
        when(dockerManager.waitForContainerReady(
                eq("old-container"), eq(30), anyString(), anyString(), anyString())).thenReturn(true);

        ServerMapping captured = serverManager.getServerMapping("server1").orElseThrow();
        assertEquals("new-container", serverManager.getContainerName("server1"));

        assertEquals(Optional.of(ContainerStatus.STOPPED), serverManager.getServerStatus(captured));
        assertEquals(ContainerStatus.RUNNING, serverManager.startServer(captured));
        assertTrue(serverManager.waitForServerReady(captured, 30));
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

    @Test
    public void testReconcileRemovesIdleStateButRetainsActiveStateUntilReleased() {
        ConfigSnapshot previous = snapshot(Map.of("server1", "container1"));
        ConfigSnapshot current = ConfigSnapshot.emptyDefault();

        AtomicBoolean idle = serverManager.getServerStartingStatus("server1");
        serverManager.reconcileConfig(previous, current);
        assertNotSame(idle, serverManager.getServerStartingStatus("server1"));

        AtomicBoolean active = serverManager.getServerStartingStatus("server1");
        active.set(true);
        serverManager.reconcileConfig(previous, current);
        assertSame(active, serverManager.getServerStartingStatus("server1"));

        when(config.snapshot()).thenReturn(current);
        serverManager.releaseServerStartingStatus("server1", active);
        assertFalse(active.get());
        assertNotSame(active, serverManager.getServerStartingStatus("server1"));
    }

    private ConfigSnapshot snapshot(Map<String, String> mappings) {
        return new ConfigSnapshot(
                ConfigSnapshot.DEFAULT_INACTIVITY_TIMEOUT_SECONDS,
                mappings.entrySet().stream()
                        .map(entry -> new ServerMapping(entry.getKey(), entry.getValue()))
                        .toList());
    }
}
