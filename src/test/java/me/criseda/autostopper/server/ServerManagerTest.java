package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.docker.DockerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
    
    private ServerManager serverManager;
    
    @BeforeEach
    public void setup() {
        // Create ServerManager with mocked dependencies
        serverManager = new ServerManager(proxyServer, logger, config);
        
        // Replace the internal DockerManager with our mock
        try {
            var dockerManagerField = ServerManager.class.getDeclaredField("dockerManager");
            dockerManagerField.setAccessible(true);
            dockerManagerField.set(serverManager, dockerManager);
        } catch (Exception e) {
            fail("Failed to inject mock DockerManager: " + e.getMessage());
        }
    }
    
    @Test
    public void testIsServerRunning() {
        // Setup
        when(config.getServerToContainerMap()).thenReturn(Map.of("server1", "container1"));
        when(dockerManager.isContainerRunning("container1")).thenReturn(true);
        
        // Execute
        boolean result = serverManager.isServerRunning("server1");
        
        // Verify
        assertTrue(result);
        verify(dockerManager).isContainerRunning("container1");
    }
    
    @Test
    public void testStartServer() {
        // Setup
        when(config.getServerToContainerMap()).thenReturn(Map.of("server1", "container1"));
        when(dockerManager.startContainer("container1")).thenReturn(true);
        
        // Execute
        boolean result = serverManager.startServer("server1");
        
        // Verify
        assertTrue(result);
        verify(dockerManager).startContainer("container1");
    }
    
    @Test
    public void testStopServer() {
        // Setup
        when(config.getServerToContainerMap()).thenReturn(Map.of("server1", "container1"));
        when(dockerManager.stopContainer("container1")).thenReturn(true);
        
        // Execute
        boolean result = serverManager.stopServer("server1");
        
        // Verify
        assertTrue(result);
        verify(dockerManager).stopContainer("container1");
        verify(logger).info(contains("Stopped server: server1"));
    }
    
    @Test
    public void testWaitForServerReady() {
        // Setup
        when(config.getServerToContainerMap()).thenReturn(Map.of("server1", "container1"));
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
        when(config.getServerNames()).thenReturn(new String[]{"server1", "server2"});
        
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
        when(config.getServerToContainerMap()).thenReturn(mapping);
        
        // Execute & Verify
        assertEquals("container1", serverManager.getContainerName("server1"));
        assertEquals("server2", serverManager.getContainerName("server2")); // Default behavior
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
}