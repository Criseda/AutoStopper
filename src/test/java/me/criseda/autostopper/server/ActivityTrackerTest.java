package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import me.criseda.autostopper.AutoStopperPlugin;
import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.config.StopRetrySettings;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.lifecycle.ServerHoldRegistry;
import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;
import me.criseda.autostopper.telemetry.LifecycleTelemetryService;
import me.criseda.autostopper.telemetry.TelemetryOperationType;
import me.criseda.autostopper.telemetry.TelemetryOrigin;
import me.criseda.autostopper.telemetry.TelemetryOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    @Mock
    private ServerLifecycleCoordinator lifecycleCoordinator;

    private LifecycleTelemetryService telemetry;
    private ActivityTracker activityTracker;
    private AutoStopperExecutor executor;
    private ServerHoldRegistry holdRegistry;
    private ServerMapping mapping1;
    private ServerMapping mapping2;

    @BeforeEach
    public void setup() {
        // Configure only what's needed for initialization
        mapping1 = new ServerMapping("server1", "container1");
        mapping2 = new ServerMapping("server2", "container2");
        when(config.snapshot()).thenReturn(new ConfigSnapshot(300, List.of(mapping1, mapping2)));
        holdRegistry = new me.criseda.autostopper.lifecycle.ServerHoldRegistry();
        telemetry = new LifecycleTelemetryService(logger);
        lenient().when(lifecycleCoordinator.isHeld(anyString())).thenAnswer(inv -> holdRegistry.isHeld(inv.getArgument(0)));
        lenient().when(lifecycleCoordinator.tryBeginStop(any(ServerMapping.class))).thenReturn(true);
        
        executor = new AutoStopperExecutor();
        activityTracker = new ActivityTracker(
                proxyServer, logger, config, serverManager, executor, plugin, lifecycleCoordinator, telemetry);
    }

    @AfterEach
    public void teardown() {
        executor.shutdown();
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
    public void shutdownCancelsScheduledIdleChecksAndIsIdempotent() {
        Scheduler scheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder taskBuilder = mock(Scheduler.TaskBuilder.class);
        ScheduledTask task = mock(ScheduledTask.class);
        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.repeat(1, TimeUnit.MINUTES)).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(task);
        activityTracker.startInactivityCheck();

        activityTracker.shutdown();
        activityTracker.shutdown();

        verify(task).cancel();
        assertTrue(activityTracker.requestInactivityCheck().isDone());
        verifyNoInteractions(serverManager);
    }

    @Test
    public void shutdownCancelsActiveIdleScanAndPreventsRetryWork() {
        AutoStopperExecutor controlledExecutor = mock(AutoStopperExecutor.class);
        CompletableFuture<Void> scan = new CompletableFuture<>();
        when(controlledExecutor.supply(org.mockito.ArgumentMatchers.<java.util.function.Supplier<Void>>any()))
                .thenReturn(scan);
        ActivityTracker tracker = new ActivityTracker(
                proxyServer, logger, config, serverManager, controlledExecutor, plugin, lifecycleCoordinator);

        assertSame(scan, tracker.requestInactivityCheck());
        tracker.shutdown();

        assertTrue(scan.isCancelled());
        tracker.updateActivity("server1");
        tracker.reconcileConfig(config.snapshot(), config.snapshot());
        tracker.removeActivity("server1");
        assertNotNull(tracker.getLastActivity("server1"));
        assertTrue(tracker.requestInactivityCheck().isDone());
        verify(controlledExecutor, times(1)).supply(any());
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
        
        // Execute the captured runnable
        Runnable inactivityCheck = runnableCaptor.getValue();
        runAndWait(inactivityCheck);
        
        // Verify that updateActivity was called (via timestamps being updated)
        Instant initialActivity = activityTracker.getLastActivity("server1");
        
        // Wait briefly to ensure timestamps would be different
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            // Ignore
        }
        
        // Run check again
        runAndWait(inactivityCheck);
        
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
        when(serverManager.getServerStatus(mapping2)).thenReturn(Optional.of(ContainerStatus.RUNNING));
        when(serverManager.stopServer(mapping2)).thenReturn(ContainerStatus.STOPPED);
        lenient().when(serverManager.getServerStatus(mapping1)).thenReturn(Optional.of(ContainerStatus.RUNNING));
        
        // Configure timeout
        when(config.snapshot()).thenReturn(new ConfigSnapshot(60, List.of(mapping1, mapping2)));
        
        // Set inactivity time to 70 seconds ago
        activityTracker.setLastActivityForTest("server1", Instant.now());
        activityTracker.setLastActivityForTest("server2", Instant.now().minus(Duration.ofSeconds(70)));
        
        // Execute the captured runnable
        Runnable inactivityCheck = runnableCaptor.getValue();
        runAndWait(inactivityCheck);
        
        // Verify server was stopped
        verify(serverManager).stopServer(mapping2);
        verify(lifecycleCoordinator).tryBeginStop(mapping2);
        verify(lifecycleCoordinator).completeStop(mapping2, ContainerStatus.STOPPED);
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

        // CRITICAL: Server is reported as NOT RUNNING (stopped)
        when(serverManager.getServerStatus(mapping1)).thenReturn(Optional.of(ContainerStatus.STOPPED));

        // Manually place server in tracking map to verify it gets removed
        activityTracker.setLastActivityForTest("server1", Instant.now().minus(Duration.ofHours(1)));

        // Run check
        Runnable inactivityCheck = runnableCaptor.getValue();
        runAndWait(inactivityCheck);

        // Verify:
        // 1. stopServer was NEVER called (because it's already stopped)
        verify(serverManager, never()).stopServer(mapping1);
        
        // 2. The server was removed from tracking (Activity map should actully contain it initially from setup, but we want to verify removal)
        Instant activity = activityTracker.getLastActivity("server1");
        assertNull(activity, "Server should have been removed from tracking because it is stopped");
    }

    @Test
    public void testInactivityCheckSkipsShutdownWhenStatusIndeterminate() {
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

        // Indeterminate status: Docker daemon unreachable
        when(serverManager.getServerStatus(mapping1))
                .thenReturn(Optional.of(ContainerStatus.INACCESSIBLE));

        // Server is in tracking map with old activity
        activityTracker.setLastActivityForTest("server1", Instant.now().minus(Duration.ofHours(1)));

        // Run check
        runAndWait(runnableCaptor.getValue());

        // Verify: no shutdown attempted, tracking retained, operator warned
        verify(serverManager, never()).stopServer(mapping1);
        assertNotNull(activityTracker.getLastActivity("server1"),
                "Server activity should survive an indeterminate status result");
        verify(logger).warn(contains("retaining activity"), eq("server1"),
                eq(ContainerStatus.INACCESSIBLE));
    }

    @Test
    public void testInactivityCheckSkipsShutdownWhenUnmapped() {
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
        when(serverManager.getServerStatus(mapping1)).thenReturn(Optional.empty());

        // Run check
        runAndWait(runnableCaptor.getValue());

        verify(serverManager, never()).stopServer(mapping1);
        assertNull(activityTracker.getLastActivity("server1"));
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
        when(serverManager.getServerStatus(mapping1)).thenReturn(Optional.of(ContainerStatus.RUNNING));
        activityTracker.removeActivity("server1"); // Ensure map is empty

        // Run check
        runAndWait(runnableCaptor.getValue());

        // Verify it was added to the map
        assertNotNull(activityTracker.getLastActivity("server1"), "Manually started server should be auto-tracked");
        
        // Verify it was NOT stopped immediately (timeout hasn't passed)
        verify(serverManager, never()).stopServer(mapping1);
    }

    @Test
    public void testInactivityScanUsesOneSnapshotAcrossConcurrentReload() throws ReflectiveOperationException {
        Scheduler scheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder taskBuilder = mock(Scheduler.TaskBuilder.class);
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), runnableCaptor.capture())).thenReturn(taskBuilder);
        when(taskBuilder.repeat(anyLong(), any(TimeUnit.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));

        ConfigSnapshot oldSnapshot = new ConfigSnapshot(60, List.of(mapping1));
        ServerMapping replacement = new ServerMapping("server1", "replacement-container");
        ConfigSnapshot newSnapshot = new ConfigSnapshot(3_600, List.of(replacement));
        java.util.concurrent.atomic.AtomicReference<ConfigSnapshot> visibleSnapshot =
                new java.util.concurrent.atomic.AtomicReference<>(oldSnapshot);
        when(config.snapshot()).thenAnswer(ignored -> visibleSnapshot.get());

        RegisteredServer server1 = mock(RegisteredServer.class);
        when(proxyServer.getServer("server1")).thenReturn(Optional.of(server1));
        when(server1.getPlayersConnected()).thenReturn(Collections.emptySet());
        when(serverManager.getServerStatus(mapping1)).thenAnswer(ignored -> {
            visibleSnapshot.set(newSnapshot);
            return Optional.of(ContainerStatus.RUNNING);
        });
        when(serverManager.stopServer(mapping1)).thenReturn(ContainerStatus.STOPPED);

        activityTracker.setLastActivityForTest("server1", Instant.now().minusSeconds(70));

        activityTracker.startInactivityCheck();
        runAndWait(runnableCaptor.getValue());

        verify(serverManager).stopServer(mapping1);
        verify(serverManager, never()).stopServer(replacement);
        verify(lifecycleCoordinator).completeStop(mapping1, ContainerStatus.STOPPED);
    }

    @Test
    public void testSchedulerCallbackReturnsPromptlyAndSkipsOverlappingScan() throws InterruptedException {
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

        CountDownLatch statusStarted = new CountDownLatch(1);
        CountDownLatch releaseStatus = new CountDownLatch(1);
        when(serverManager.getServerStatus(mapping1)).thenAnswer(invocation -> {
            statusStarted.countDown();
            releaseStatus.await();
            return Optional.of(ContainerStatus.STOPPED);
        });

        Runnable scheduledCallback = runnableCaptor.getValue();
        long start = System.nanoTime();
        scheduledCallback.run();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMillis < 100, "scheduler callback should only submit work");
        assertTrue(statusStarted.await(1, TimeUnit.SECONDS));

        scheduledCallback.run();
        verify(serverManager, times(1)).getServerStatus(mapping1);
        verify(logger).debug(contains("previous scan is still running"));

        releaseStatus.countDown();
        waitForScanCompletion();
    }

    @Test
    public void testRejectedScanClearsActiveFlagForNextRun() {
        AutoStopperExecutor rejectingExecutor = mock(AutoStopperExecutor.class);
        CompletableFuture<Void> rejected = CompletableFuture.failedFuture(
                new AutoStopperExecutor.SaturationException("full", null));
        when(rejectingExecutor.supply(org.mockito.ArgumentMatchers.<java.util.function.Supplier<Void>>any()))
                .thenReturn(rejected)
                .thenReturn(CompletableFuture.completedFuture(null));

        ActivityTracker tracker = new ActivityTracker(
                proxyServer, logger, config, serverManager, rejectingExecutor, plugin, lifecycleCoordinator);

        assertTrue(tracker.requestInactivityCheck().isCompletedExceptionally());
        assertTrue(tracker.requestInactivityCheck().isDone());
        verify(rejectingExecutor, times(2)).supply(any());
    }

    @Test
    public void testReconcileConfigPrunesRemovedAndInitializesAddedServers() {
        ConfigSnapshot previous = new ConfigSnapshot(300, List.of(mapping1, mapping2));
        ServerMapping mapping3 = new ServerMapping("server3", "container3");
        ConfigSnapshot current = new ConfigSnapshot(300, List.of(mapping2, mapping3));

        activityTracker.reconcileConfig(previous, current);

        assertNull(activityTracker.getLastActivity("server1"));
        assertNotNull(activityTracker.getLastActivity("server2"));
        assertNotNull(activityTracker.getLastActivity("server3"));
    }

    @Test
    public void testReconcileConfigResetsActivityForReplacementMapping() {
        activityTracker.setLastActivityForTest("server1", Instant.now().minus(Duration.ofHours(1)));
        ServerMapping replacement = new ServerMapping("server1", "replacement-container");

        activityTracker.reconcileConfig(
                new ConfigSnapshot(300, List.of(mapping1)),
                new ConfigSnapshot(300, List.of(replacement)));

        assertTrue(Duration.between(activityTracker.getLastActivity("server1"), Instant.now()).getSeconds() < 5);
    }

    @Test
    public void failedAndTimedOutStopsRetryWithCappedBackoffUntilSuccess() {
        Instant start = Instant.parse("2026-08-12T10:00:00Z");
        MutableClock clock = new MutableClock(start);
        ConfigSnapshot snapshot = new ConfigSnapshot(60,
                new StopRetrySettings(3, Duration.ofSeconds(10), Duration.ofSeconds(15)),
                List.of(mapping1));
        when(config.snapshot()).thenReturn(snapshot);

        ActivityTracker tracker = new ActivityTracker(
                proxyServer, logger, config, serverManager, executor, plugin, lifecycleCoordinator, clock);
        RegisteredServer registered = mock(RegisteredServer.class);
        when(proxyServer.getServer("server1")).thenReturn(Optional.of(registered));
        when(registered.getPlayersConnected()).thenReturn(Collections.emptySet());
        when(serverManager.getServerStatus(mapping1)).thenReturn(Optional.of(ContainerStatus.RUNNING));
        when(serverManager.stopServer(mapping1)).thenReturn(
                ContainerStatus.TIMED_OUT, ContainerStatus.FAILED, ContainerStatus.STOPPED);
        tracker.setLastActivityForTest("server1", start.minusSeconds(61));

        tracker.requestInactivityCheck().join();
        assertEquals(1, tracker.getFailedStopAttemptsForTest("server1"));
        tracker.requestInactivityCheck().join();
        verify(serverManager, times(1)).stopServer(mapping1);

        clock.advance(Duration.ofSeconds(10));
        tracker.requestInactivityCheck().join();
        assertEquals(2, tracker.getFailedStopAttemptsForTest("server1"));
        clock.advance(Duration.ofSeconds(14));
        tracker.requestInactivityCheck().join();
        verify(serverManager, times(2)).stopServer(mapping1);

        clock.advance(Duration.ofSeconds(1));
        tracker.requestInactivityCheck().join();
        verify(serverManager, times(3)).stopServer(mapping1);
        assertNull(tracker.getLastActivity("server1"));
        verify(lifecycleCoordinator).completeStop(mapping1, ContainerStatus.TIMED_OUT);
        verify(lifecycleCoordinator).completeStop(mapping1, ContainerStatus.FAILED);
        verify(lifecycleCoordinator).completeStop(mapping1, ContainerStatus.STOPPED);
    }

    @Test
    public void concurrentActivityCancelsAdmittedStopBeforeDockerCall() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1, List.of(mapping1));
        when(config.snapshot()).thenReturn(snapshot);
        RegisteredServer registered = mock(RegisteredServer.class);
        when(proxyServer.getServer("server1")).thenReturn(Optional.of(registered));
        when(registered.getPlayersConnected()).thenReturn(Collections.emptySet());
        when(serverManager.getServerStatus(mapping1)).thenReturn(Optional.of(ContainerStatus.RUNNING));
        activityTracker.setLastActivityForTest("server1", Instant.now().minusSeconds(2));
        when(lifecycleCoordinator.tryBeginStop(mapping1)).thenAnswer(ignored -> {
            activityTracker.updateActivity("server1");
            return true;
        });

        activityTracker.requestInactivityCheck().join();

        verify(serverManager, never()).stopServer(mapping1);
        verify(lifecycleCoordinator).cancelStop(mapping1);
        verify(lifecycleCoordinator, never()).completeStop(eq(mapping1), any());
    }

    @Test
    public void concurrentActivityIsNotRemovedByStaleStoppedStatus() {
        when(config.snapshot()).thenReturn(new ConfigSnapshot(60, List.of(mapping1)));
        RegisteredServer registered = mock(RegisteredServer.class);
        when(proxyServer.getServer("server1")).thenReturn(Optional.of(registered));
        when(registered.getPlayersConnected()).thenReturn(Collections.emptySet());
        Instant previous = Instant.now().minus(Duration.ofHours(1));
        activityTracker.setLastActivityForTest("server1", previous);
        when(serverManager.getServerStatus(mapping1)).thenAnswer(ignored -> {
            activityTracker.updateActivity("server1");
            return Optional.of(ContainerStatus.STOPPED);
        });

        activityTracker.requestInactivityCheck().join();

        assertTrue(activityTracker.getLastActivity("server1").isAfter(previous));
        verify(serverManager, never()).stopServer(mapping1);
    }

    @Test
    public void exhaustedStopRetriesRequireANewInactivityPeriod() {
        Instant start = Instant.parse("2026-08-12T10:00:00Z");
        MutableClock clock = new MutableClock(start);
        ConfigSnapshot snapshot = new ConfigSnapshot(60,
                new StopRetrySettings(2, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                List.of(mapping1));
        when(config.snapshot()).thenReturn(snapshot);
        ActivityTracker tracker = new ActivityTracker(
                proxyServer, logger, config, serverManager, executor, plugin, lifecycleCoordinator, clock);
        RegisteredServer registered = mock(RegisteredServer.class);
        when(proxyServer.getServer("server1")).thenReturn(Optional.of(registered));
        when(registered.getPlayersConnected()).thenReturn(Collections.emptySet());
        when(serverManager.getServerStatus(mapping1)).thenReturn(Optional.of(ContainerStatus.RUNNING));
        when(serverManager.stopServer(mapping1)).thenReturn(ContainerStatus.FAILED);
        tracker.setLastActivityForTest("server1", start.minusSeconds(61));

        tracker.requestInactivityCheck().join();
        clock.advance(Duration.ofSeconds(1));
        tracker.requestInactivityCheck().join();
        tracker.requestInactivityCheck().join();

        verify(serverManager, times(2)).stopServer(mapping1);
        assertEquals(start.plusSeconds(1), tracker.getLastActivity("server1"));
        assertEquals(0, tracker.getFailedStopAttemptsForTest("server1"));
        verify(logger).warn(contains("Stop retries exhausted"), eq("server1"), eq(2),
                eq(ContainerStatus.FAILED));
    }

    @Test
    public void testInactivityCheck_SkipsStopWhenServerIsHeld() {
        RegisteredServer registeredServer1 = mock(RegisteredServer.class);
        RegisteredServer registeredServer2 = mock(RegisteredServer.class);
        when(proxyServer.getServer("server1")).thenReturn(Optional.of(registeredServer1));
        when(proxyServer.getServer("server2")).thenReturn(Optional.of(registeredServer2));
        when(registeredServer1.getPlayersConnected()).thenReturn(Collections.emptyList());
        when(registeredServer2.getPlayersConnected()).thenReturn(Collections.emptyList());
        when(serverManager.getServerStatus(mapping1)).thenReturn(Optional.of(ContainerStatus.RUNNING));
        when(serverManager.getServerStatus(mapping2)).thenReturn(Optional.of(ContainerStatus.RUNNING));

        Instant start = Instant.parse("2026-08-16T12:00:00Z");
        MutableClock clock = new MutableClock(start);
        ActivityTracker tracker = new ActivityTracker(
                proxyServer, logger, config, serverManager, executor, plugin, lifecycleCoordinator, clock);

        // Put hold on server1
        holdRegistry.hold(mapping1);

        // Fast forward 6 minutes (inactivity timeout is 5 min)
        clock.advance(Duration.ofMinutes(6));

        tracker.requestInactivityCheck().join();

        // server1 is held, so stopServer should NOT be called for mapping1
        verify(serverManager, never()).stopServer(mapping1);
        // server2 is NOT held, so stopServer should be called for mapping2
        verify(serverManager).stopServer(mapping2);
    }

    private void runAndWait(Runnable inactivityCheck) {
        inactivityCheck.run();
        waitForScanCompletion();
    }

    private void waitForScanCompletion() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (isScanActive() && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertFalse(isScanActive(), "inactivity scan did not complete in time");
    }

    @Test
    public void recordsAutomaticStopTelemetry() {
        RegisteredServer server1 = mock(RegisteredServer.class);
        when(server1.getPlayersConnected()).thenReturn(Collections.emptyList());
        when(proxyServer.getServer("server1")).thenReturn(Optional.of(server1));
        when(serverManager.getServerStatus(mapping1)).thenReturn(Optional.of(ContainerStatus.RUNNING));
        when(serverManager.stopServer(mapping1)).thenReturn(ContainerStatus.STOPPED);

        Instant oldTime = Instant.now().minus(Duration.ofMinutes(10));
        activityTracker.setLastActivityForTest("server1", oldTime);

        activityTracker.requestInactivityCheck().join();

        assertEquals(1, telemetry.operationCount(TelemetryOperationType.AUTOMATIC_STOP));
        assertEquals(1, telemetry.outcomeCount(TelemetryOperationType.AUTOMATIC_STOP, TelemetryOutcome.STOPPED));
    }

    private boolean isScanActive() {
        try {
            var field = ActivityTracker.class.getDeclaredField("inactivityScanActive");
            field.setAccessible(true);
            return ((java.util.concurrent.atomic.AtomicBoolean) field.get(activityTracker)).get();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        private void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
