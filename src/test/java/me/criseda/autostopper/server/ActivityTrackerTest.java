package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import me.criseda.autostopper.AutoStopperPlugin;
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
    
    @Mock
    private AutoStopperPlugin plugin;

    private ActivityTracker activityTracker;

    @BeforeEach
    public void setup() {
        // Configure only what's needed for initialization
        String[] serverNames = {"server1", "server2"};
        when(config.getServerNames()).thenReturn(serverNames);
        
        // Create the ActivityTracker instance with the plugin instance
        activityTracker = new ActivityTracker(proxyServer, logger, config, serverManager, plugin);
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
        // Update to use plugin instead of activityTracker
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.repeat(1, TimeUnit.MINUTES)).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(scheduledTask);
        
        // Setup task scheduling
        activityTracker.startInactivityCheck();
        
        // Verify scheduler was called with the right parameters
        verify(scheduler).buildTask(eq(plugin), any(Runnable.class));
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
        // Update to use plugin instead of activityTracker
        when(scheduler.buildTask(eq(plugin), runnableCaptor.capture())).thenReturn(taskBuilder);
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
        // Update to use plugin instead of activityTracker
        when(scheduler.buildTask(eq(plugin), runnableCaptor.capture())).thenReturn(taskBuilder);
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

        // Ensure Server Manager says server is running so it can be stopped
        when(serverManager.isServerRunning("server2")).thenReturn(true);
        lenient().when(serverManager.isServerRunning("server1")).thenReturn(true);
        
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
    public void testInactivityCheckEvaluatesStoppedServer() {
        // Mock scheduler chain
        Scheduler scheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder taskBuilder = mock(Scheduler.TaskBuilder.class);

        // Capture the runnable passed to the scheduler
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), runnableCaptor.capture())).thenReturn(taskBuilder);
        when(taskBuilder.repeat(anyLong(), any(TimeUnit.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));

        // Start inactivity check
        activityTracker.startInactivityCheck();

        // Setup server mocks
        RegisteredServer server1 = mock(RegisteredServer.class);
        when(proxyServer.getServer("server1")).thenReturn(Optional.of(server1));
        
        // Server has no players
        when(server1.getPlayersConnected()).thenReturn(Collections.emptySet());

        // CRITICAL: Server is reported as NOT RUNNING
        when(serverManager.isServerRunning("server1")).thenReturn(false);

        // Manually place server in tracking map to verify it gets removed
        try {
            var field = ActivityTracker.class.getDeclaredField("lastActivity");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Instant> activityMap = (Map<String, Instant>) field.get(activityTracker);
            activityMap.put("server1", Instant.now().minus(Duration.ofHours(1))); // Very old activity
        } catch (Exception e) {
            fail("Reflection setup failed");
        }

        // Run check
        Runnable inactivityCheck = runnableCaptor.getValue();
        inactivityCheck.run();

        // Verify:
        // 1. stopServer was NEVER called (because it's already stopped)
        verify(serverManager, never()).stopServer("server1");
        
        // 2. The server was removed from tracking (Activity map should actully contain it initially from setup, but we want to verify removal)
        Instant activity = activityTracker.getLastActivity("server1");
        assertNull(activity, "Server should have been removed from tracking because it is stopped");
    }

    @Test
    public void testInactivityCheckTracksManuallyStartedServer() {
        // Mock scheduler chain
        Scheduler scheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder taskBuilder = mock(Scheduler.TaskBuilder.class);
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        
        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), runnableCaptor.capture())).thenReturn(taskBuilder);
        when(taskBuilder.repeat(anyLong(), any(TimeUnit.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));

        activityTracker.startInactivityCheck();

        RegisteredServer server1 = mock(RegisteredServer.class);
        when(proxyServer.getServer("server1")).thenReturn(Optional.of(server1));
        when(server1.getPlayersConnected()).thenReturn(Collections.emptySet());

        // CRITICAL: Server IS running, but NOT in our tracking map (simulating manual start)
        when(serverManager.isServerRunning("server1")).thenReturn(true);
        activityTracker.removeActivity("server1"); // Ensure map is empty

        // Run check
        runnableCaptor.getValue().run();

        // Verify it was added to the map
        assertNotNull(activityTracker.getLastActivity("server1"), "Manually started server should be auto-tracked");
        
        // Verify it was NOT stopped immediately (timeout hasn't passed)
        verify(serverManager, never()).stopServer("server1");
    }
}