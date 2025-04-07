package me.criseda.autostopper.listeners;

import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import me.criseda.autostopper.AutoStopperPlugin;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;
import net.kyori.adventure.text.Component;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicBoolean;

@ExtendWith(MockitoExtension.class)
public class ServerPreConnectListenerTest {

    @Mock
    private AutoStopperPlugin plugin;
    
    @Mock
    private ServerManager serverManager;
    
    @Mock
    private ActivityTracker activityTracker;
    
    @Mock
    private Player player;
    
    @Mock
    private ServerPreConnectEvent event;
    
    @Mock
    private RegisteredServer targetServer;
    
    @Mock
    private ServerInfo serverInfo;
    
    @Mock
    private Scheduler scheduler;
    
    @Mock
    private Scheduler.TaskBuilder taskBuilder;
    
    @Mock
    private Logger logger;
    
    @Mock
    private ConnectionRequestBuilder connectionRequest;
    
    private ServerPreConnectListener listener;
    
    @BeforeEach
    public void setup() {
        listener = new ServerPreConnectListener(plugin, serverManager, activityTracker);
        
        // Common setup for all tests
        lenient().when(event.getPlayer()).thenReturn(player);
        lenient().when(event.getOriginalServer()).thenReturn(targetServer);
        lenient().when(targetServer.getServerInfo()).thenReturn(serverInfo);
        lenient().when(serverInfo.getName()).thenReturn("testserver");
        lenient().when(plugin.getServer()).thenReturn(mock(com.velocitypowered.api.proxy.ProxyServer.class));
    }
    
    @Test
    public void testOnServerPreConnect_ServerRunning() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.isServerRunning("testserver")).thenReturn(true);
        
        // Execute
        listener.onServerPreConnect(event);
        
        // Verify
        verify(serverManager).isMonitoredServer("testserver");
        verify(serverManager).isServerRunning("testserver");
        verify(event, never()).setResult(any(ServerPreConnectEvent.ServerResult.class));
        verifyNoInteractions(activityTracker);
    }
    
    @Test
    public void testOnServerPreConnect_NotMonitored() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(false);
        
        // Execute
        listener.onServerPreConnect(event);
        
        // Verify
        verify(serverManager).isMonitoredServer("testserver");
        verify(serverManager, never()).isServerRunning(anyString());
        verify(event, never()).setResult(any(ServerPreConnectEvent.ServerResult.class));
        verifyNoInteractions(activityTracker);
    }
    
    @Test
    public void testOnServerPreConnect_AlreadyStarting() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.isServerRunning("testserver")).thenReturn(false);
        
        AtomicBoolean isStarting = new AtomicBoolean(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        
        // Execute
        listener.onServerPreConnect(event);
        
        // Verify
        verify(serverManager).isMonitoredServer("testserver");
        verify(serverManager).isServerRunning("testserver");
        verify(serverManager).getServerStartingStatus("testserver");
        verify(player).sendMessage(argThat(component -> 
            component.toString().contains("offline") || component.toString().contains("Starting")));
        verify(player).sendMessage(argThat(component -> 
            component.toString().contains("already being started") || component.toString().contains("wait")));
        verify(event).setResult(eq(ServerPreConnectEvent.ServerResult.denied()));
        verifyNoMoreInteractions(plugin.getServer());
    }
    
    @Test
    public void testOnServerPreConnect_StartServerSuccess_ServerReady() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.isServerRunning("testserver")).thenReturn(false);
        
        AtomicBoolean isStarting = new AtomicBoolean(false);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        
        when(plugin.getServer().getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));
        
        when(serverManager.startServer("testserver")).thenReturn(true);
        when(serverManager.waitForServerReady(eq("testserver"), anyInt())).thenReturn(true);
        
        when(player.createConnectionRequest(targetServer)).thenReturn(connectionRequest);
        
        // Execute
        listener.onServerPreConnect(event);
        
        // Verify initial setup
        verify(serverManager).isMonitoredServer("testserver");
        verify(serverManager).isServerRunning("testserver");
        verify(serverManager).getServerStartingStatus("testserver");
        verify(player).sendMessage(argThat(component -> 
            component.toString().contains("offline") || component.toString().contains("Starting")));
        verify(event).setResult(eq(ServerPreConnectEvent.ServerResult.denied()));
        
        // Capture and run the task
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).buildTask(eq(plugin), runnableCaptor.capture());
        
        // Verify isStarting was set
        assertTrue(isStarting.get(), "isStarting flag should be set to true");
        
        // Run the task
        runnableCaptor.getValue().run();
        
        // Verify server operations
        verify(serverManager).startServer("testserver");
        verify(serverManager).waitForServerReady("testserver", 120);
        
        // Verify player notification and connection
        verify(player, times(2)).sendMessage(any(Component.class)); // Initial + success message
        verify(player).createConnectionRequest(targetServer);
        verify(connectionRequest).fireAndForget();
        
        // Verify activity tracking
        verify(activityTracker).updateActivity("testserver");
        
        // Verify isStarting was reset
        assertFalse(isStarting.get(), "isStarting flag should be reset to false");
    }
    
    @Test
    public void testOnServerPreConnect_StartServerSuccess_ServerNotReady() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.isServerRunning("testserver")).thenReturn(false);
        
        AtomicBoolean isStarting = new AtomicBoolean(false);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        
        when(plugin.getServer().getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));
        
        when(serverManager.startServer("testserver")).thenReturn(true);
        when(serverManager.waitForServerReady(eq("testserver"), anyInt())).thenReturn(false);
        
        // Execute
        listener.onServerPreConnect(event);
        
        // Capture and run the task
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).buildTask(eq(plugin), runnableCaptor.capture());
        runnableCaptor.getValue().run();
        
        // Verify server operations
        verify(serverManager).startServer("testserver");
        verify(serverManager).waitForServerReady("testserver", 120);
        
        // Verify player notification
        verify(player, times(3)).sendMessage(any(Component.class)); // Initial + not ready + try again messages
        verify(player, never()).createConnectionRequest(any());
        
        // Verify no activity tracking
        verifyNoInteractions(activityTracker);
        
        // Verify isStarting was reset
        assertFalse(isStarting.get(), "isStarting flag should be reset to false");
    }
    
    @Test
    public void testOnServerPreConnect_StartServerFailed() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.isServerRunning("testserver")).thenReturn(false);
        
        AtomicBoolean isStarting = new AtomicBoolean(false);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        
        when(plugin.getServer().getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));
        
        when(serverManager.startServer("testserver")).thenReturn(false);
        
        // Execute
        listener.onServerPreConnect(event);
        
        // Capture and run the task
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).buildTask(eq(plugin), runnableCaptor.capture());
        runnableCaptor.getValue().run();
        
        // Verify server operations
        verify(serverManager).startServer("testserver");
        verify(serverManager, never()).waitForServerReady(anyString(), anyInt());
        
        // Verify player notification
        verify(player, times(2)).sendMessage(any(Component.class)); // Initial + failed messages
        verify(player, never()).createConnectionRequest(any());
        
        // Verify no activity tracking
        verifyNoInteractions(activityTracker);
        
        // Verify isStarting was reset
        assertFalse(isStarting.get(), "isStarting flag should be reset to false");
    }
    
    @Test
    public void testOnServerPreConnect_Exception() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.isServerRunning("testserver")).thenReturn(false);
        
        AtomicBoolean isStarting = new AtomicBoolean(false);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        
        when(plugin.getServer().getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));
        
        when(plugin.getLogger()).thenReturn(logger);
        doThrow(new RuntimeException("Test exception")).when(serverManager).startServer("testserver");
        
        // Execute
        listener.onServerPreConnect(event);
        
        // Capture and run the task
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).buildTask(eq(plugin), runnableCaptor.capture());
        runnableCaptor.getValue().run();
        
        // Verify logger error
        verify(logger).error(eq("Error while starting server"), any(Exception.class));
        
        // Verify player notification
        verify(player, times(2)).sendMessage(any(Component.class)); // Initial + error messages
        verify(player, never()).createConnectionRequest(any());
        
        // Verify no activity tracking
        verifyNoInteractions(activityTracker);
        
        // Verify isStarting was reset
        assertFalse(isStarting.get(), "isStarting flag should be reset to false");
    }
    
    // Helper methods for assertions
    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
    
    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }
}