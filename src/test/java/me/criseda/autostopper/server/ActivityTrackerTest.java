package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import me.criseda.autostopper.config.AutoStopperConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ActivityTrackerTest {

    @Mock
    private ProxyServer proxyServer;

    @Mock
    private Logger logger;

    @Mock
    private AutoStopperConfig config;

    @Mock
    private ServerManager serverManager;

    private ActivityTracker activityTracker;

    @BeforeEach
    public void setup() {
        // Configure only what's needed for initialization
        String[] serverNames = {"server1", "server2"};
        when(config.getServerNames()).thenReturn(serverNames);
        
        // Create the ActivityTracker instance
        activityTracker = new ActivityTracker(proxyServer, logger, config, serverManager);
    }

    @Test
    public void testInitialization() {
        // Verify initialization logged the correct messages
        verify(logger).info("Initialized activity tracking for server: server1");
        verify(logger).info("Initialized activity tracking for server: server2");
        verify(logger).info("Initial server activity state:");
        
        // Verify lastActivity was initialized for all servers
        Map<String, Instant> activity = activityTracker.getAllActivity();
        assertEquals(2, activity.size());
        assertTrue(activity.containsKey("server1"));
        assertTrue(activity.containsKey("server2"));
        
        // Verify timestamps are reasonably close to current time
        Instant now = Instant.now();
        assertTrue(Duration.between(activity.get("server1"), now).getSeconds() < 5);
        assertTrue(Duration.between(activity.get("server2"), now).getSeconds() < 5);
    }

    @Test
    public void testStartInactivityCheck() {
        // Mock scheduler chain - only for this test
        Scheduler scheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder taskBuilder = mock(Scheduler.TaskBuilder.class);
        ScheduledTask scheduledTask = mock(ScheduledTask.class);
        
        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(activityTracker), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.repeat(1, TimeUnit.MINUTES)).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(scheduledTask);
        
        // Setup task scheduling
        activityTracker.startInactivityCheck();
        
        // Verify scheduler was called with the right parameters
        verify(scheduler).buildTask(eq(activityTracker), any(Runnable.class));
        verify(taskBuilder).repeat(1, TimeUnit.MINUTES);
        verify(taskBuilder).schedule();
    }

    @Test
    public void testInactivityCheckWithActiveServer() {
        // Mock scheduler chain
        Scheduler scheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder taskBuilder = mock(Scheduler.TaskBuilder.class);
        
        // Capture the runnable passed to the scheduler
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(activityTracker), runnableCaptor.capture())).thenReturn(taskBuilder);
        when(taskBuilder.repeat(anyLong(), any(TimeUnit.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));
        
        // Start inactivity check
        activityTracker.startInactivityCheck();
        
        // Setup for server with players
        RegisteredServer server1 = mock(RegisteredServer.class);
        Set<Player> players = new HashSet<>();
        players.add(mock(Player.class));
        when(server1.getPlayersConnected()).thenReturn(players);
        when(proxyServer.getServer("server1")).thenReturn(Optional.of(server1));
        when(serverManager.isMonitoredServer("server1")).thenReturn(true);
        
        // Execute the captured runnable
        Runnable inactivityCheck = runnableCaptor.getValue();
        inactivityCheck.run();
        
        // Verify that updateActivity was called (via timestamps being updated)
        Instant initialActivity = activityTracker.getLastActivity("server1");
        
        // Wait briefly to ensure timestamps would be different
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            // Ignore
        }
        
        // Run check again
        inactivityCheck.run();
        
        // Verify logger debug message
        verify(logger, atLeastOnce()).debug("Players active on server1, refreshing timestamp");
        
        // Timestamps should be different due to update
        assertNotEquals(initialActivity, activityTracker.getLastActivity("server1"));
    }

    @Test
    public void testInactivityCheckWithInactiveServer() {
        // Mock scheduler chain
        Scheduler scheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder taskBuilder = mock(Scheduler.TaskBuilder.class);
        
        // Capture the runnable passed to the scheduler
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(activityTracker), runnableCaptor.capture())).thenReturn(taskBuilder);
        when(taskBuilder.repeat(anyLong(), any(TimeUnit.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));
        
        // Start inactivity check
        activityTracker.startInactivityCheck();
        
        // Create mocks for both servers since code will check all servers
        RegisteredServer server1 = mock(RegisteredServer.class);
        RegisteredServer server2 = mock(RegisteredServer.class);
        
        // Server 1 has players (will be active)
        Set<Player> players = new HashSet<>();
        players.add(mock(Player.class));
        when(server1.getPlayersConnected()).thenReturn(players);
        
        // Server 2 is empty (will be inactive)
        when(server2.getPlayersConnected()).thenReturn(Collections.emptySet());
        
        // Configure both getServer calls
        when(proxyServer.getServer("server1")).thenReturn(Optional.of(server1));
        when(proxyServer.getServer("server2")).thenReturn(Optional.of(server2));
        
        // Configure timeout
        when(config.getInactivityTimeout()).thenReturn(60); // 1 minute timeout
        
        // Set inactivity time to 70 seconds ago
        Map<String, Instant> lastActivity = new HashMap<>();
        lastActivity.put("server1", Instant.now());
        lastActivity.put("server2", Instant.now().minus(Duration.ofSeconds(70)));
        
        // Use reflection to set the last activity to our controlled value
        try {
            var field = ActivityTracker.class.getDeclaredField("lastActivity");
            field.setAccessible(true);
            field.set(activityTracker, lastActivity);
        } catch (Exception e) {
            fail("Failed to set lastActivity field: " + e.getMessage());
        }
        
        // Execute the captured runnable
        Runnable inactivityCheck = runnableCaptor.getValue();
        inactivityCheck.run();
        
        // Verify server was stopped
        verify(serverManager).stopServer("server2");
    }

    @Test
    public void testUpdateActivity() {
        // Setup
        when(serverManager.isMonitoredServer("server1")).thenReturn(true);
        when(serverManager.isMonitoredServer("server3")).thenReturn(false);
        
        // Initial timestamp
        Instant initialTime = activityTracker.getLastActivity("server1");
        
        // Wait briefly
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            // Ignore
        }
        
        // Update activity
        activityTracker.updateActivity("server1");
        
        // Verify timestamp was updated
        Instant updatedTime = activityTracker.getLastActivity("server1");
        assertTrue(updatedTime.isAfter(initialTime));
        
        // Verify unmonitored servers don't get updated
        activityTracker.updateActivity("server3");
        assertNull(activityTracker.getLastActivity("server3"));
    }

    @Test
    public void testRemoveActivity() {
        // Verify server exists initially
        assertNotNull(activityTracker.getLastActivity("server1"));
        
        // Remove activity
        activityTracker.removeActivity("server1");
        
        // Verify server no longer exists
        assertNull(activityTracker.getLastActivity("server1"));
    }

    @Test
    public void testGetMinutesSinceActivity() {
        // Set activity to 5 minutes ago
        Map<String, Instant> lastActivity = new HashMap<>();
        lastActivity.put("server1", Instant.now().minus(Duration.ofMinutes(5)));
        
        // Use reflection to set the last activity
        try {
            var field = ActivityTracker.class.getDeclaredField("lastActivity");
            field.setAccessible(true);
            field.set(activityTracker, lastActivity);
        } catch (Exception e) {
            fail("Failed to set lastActivity field: " + e.getMessage());
        }
        
        // Test minutes calculation
        assertEquals(5, activityTracker.getMinutesSinceActivity("server1"));
        
        // Test with non-existent server
        assertEquals(0, activityTracker.getMinutesSinceActivity("nonexistent"));
    }

    @Test
    public void testGetAllActivity() {
        // Check returned map is a copy
        Map<String, Instant> originalMap = activityTracker.getAllActivity();
        originalMap.put("newServer", Instant.now());
        
        // The internal map should not be affected
        Map<String, Instant> newMap = activityTracker.getAllActivity();
        assertFalse(newMap.containsKey("newServer"));
        assertEquals(2, newMap.size());
    }
}