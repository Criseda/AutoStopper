package me.criseda.autostopper.lifecycle;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.operational.OperationalFailure;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LifecycleEntryTest {
    private static final OperationalFailure FAILURE =
            new OperationalFailure(Instant.EPOCH, "test", "detail", "remediation");

    private final AtomicLong revisions = new AtomicLong();
    private final ServerMapping mapping = new ServerMapping("survival", "survival-container");
    private LifecycleEntry entry;

    @BeforeEach
    void setUp() {
        entry = new LifecycleEntry(mapping, revisions::incrementAndGet, mock(Logger.class));
    }

    @Test
    void newEntryIsStoppedIdleAndCurrent() {
        assertEquals(ServerLifecycleState.STOPPED, entry.state());
        assertFalse(entry.isBusy());
        assertFalse(entry.isRetired());
        assertTrue(entry.startupFuture().isEmpty());
        assertTrue(entry.matches(mapping));
        assertFalse(entry.matches(new ServerMapping("survival", "other-container")));
    }

    @Test
    void beginStartupOwnsTheEntryUntilFinished() {
        CompletableFuture<StartupOutcome> startup = entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 10, 1);

        assertEquals(ServerLifecycleState.STARTING, entry.state());
        assertTrue(entry.isBusy());
        assertTrue(entry.ownsStartup(startup));
        assertFalse(entry.ownsStartup(new CompletableFuture<>()));
        assertSame(startup, entry.startupFuture().orElseThrow());
        assertEquals(ConnectionLifecycleStage.INSPECTING, entry.progressStage());
        assertEquals(10, entry.startupStartNanos());

        entry.finishStartup(StartupOutcome.READY_RUNNING, null);

        assertFalse(entry.ownsStartup(startup));
        assertTrue(entry.startupFuture().isEmpty());
    }

    @Test
    void successfulStartupBecomesReadyAndKeepsWaitersForConnection() {
        ConnectionWaiter waiter = waiter();
        entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 1);
        entry.addWaiter(waiter);

        List<ConnectionWaiter> finished = entry.finishStartup(StartupOutcome.READY_AFTER_START, null);

        assertEquals(ServerLifecycleState.READY, entry.state());
        assertEquals(List.of(waiter), finished);
        assertEquals(1, entry.waiterCount());
        assertEquals(ConnectionLifecycleStage.CONNECTING, entry.progressStage());
        assertTrue(entry.lastFailure().isEmpty());
    }

    @Test
    void failedStartupBecomesFailedAndHandsBackDetachedWaiters() {
        ConnectionWaiter waiter = waiter();
        entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 1);
        entry.addWaiter(waiter);

        List<ConnectionWaiter> finished = entry.finishStartup(StartupOutcome.START_FAILED, FAILURE);

        assertEquals(ServerLifecycleState.FAILED, entry.state());
        assertEquals(List.of(waiter), finished);
        assertFalse(entry.hasWaiters());
        assertEquals(Optional.of(FAILURE), entry.lastFailure());
        assertEquals(Optional.of(StartupOutcome.START_FAILED.connectionOutcome()), entry.lastConnectionOutcome());
    }

    @Test
    void startupTelemetryCanBeClaimedOncePerStartup() {
        entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 0);

        assertTrue(entry.claimStartupTelemetry());
        assertFalse(entry.claimStartupTelemetry());

        entry.finishStartup(StartupOutcome.NOT_READY, FAILURE);
        entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 0);

        assertTrue(entry.claimStartupTelemetry());
    }

    @Test
    void startupWaiterCountReportsThePeak() {
        ConnectionWaiter first = waiter();
        ConnectionWaiter second = waiter();
        entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 1);
        entry.addWaiter(first);
        entry.addWaiter(second);
        entry.notePeakWaiters();
        entry.removeWaiter(first);

        assertEquals(2, entry.startupWaiterCount());
    }

    @Test
    void illegalTransitionsAreRejected() {
        entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 0);

        IllegalStateException error = assertThrows(IllegalStateException.class, entry::beginStop);
        assertTrue(error.getMessage().contains("STARTING -> STOPPING"));
        assertEquals(ServerLifecycleState.STARTING, entry.state());
    }

    @Test
    void stopSucceedsOrFailsWithRecordedFailure() {
        readyEntry();
        entry.beginStop();
        assertEquals(ServerLifecycleState.STOPPING, entry.state());

        entry.finishStop(ContainerStatus.TIMED_OUT, () -> FAILURE);
        assertEquals(ServerLifecycleState.FAILED, entry.state());
        assertEquals(Optional.of(FAILURE), entry.lastFailure());

        entry.beginStop();
        entry.finishStop(ContainerStatus.STOPPED, () -> FAILURE);
        assertEquals(ServerLifecycleState.STOPPED, entry.state());
        assertTrue(entry.lastFailure().isEmpty());
    }

    @Test
    void cancelStopReturnsToReadyOnlyWhileStopping() {
        readyEntry();
        entry.beginStop();

        entry.cancelStop();
        assertEquals(ServerLifecycleState.READY, entry.state());

        entry.cancelStop();
        assertEquals(ServerLifecycleState.READY, entry.state());
    }

    @Test
    void operationOwnershipIsByIdentity() {
        CompletableFuture<Void> operation = new CompletableFuture<>();
        entry.attachOperation(operation);

        assertTrue(entry.ownsOperation(operation));
        entry.detachOperation(new CompletableFuture<>());
        assertTrue(entry.ownsOperation(operation));
        entry.detachOperation(operation);
        assertFalse(entry.ownsOperation(operation));
    }

    @Test
    void manualStopOwnershipIsByIdentity() {
        CompletableFuture<ManualStopOutcome> stop = new CompletableFuture<>();
        entry.attachManualStop(stop);

        assertTrue(entry.ownsManualStop(stop));
        entry.detachManualStop(new CompletableFuture<>());
        assertTrue(entry.ownsManualStop(stop));
        entry.detachManualStop(stop);
        assertFalse(entry.ownsManualStop(stop));
    }

    @Test
    void markReadyRecoversFromStoppedOrFailedAndClearsFailure() {
        entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 0);
        entry.finishStartup(StartupOutcome.START_FAILED, FAILURE);

        entry.markReady();

        assertEquals(ServerLifecycleState.READY, entry.state());
        assertTrue(entry.lastFailure().isEmpty());
    }

    @Test
    void refusedConnectionBeforeAnySuccessMarksTheServerFailed() {
        readyEntry();

        boolean verify = entry.recordConnectionOutcome(ConnectionOutcome.CONNECTION_FAILED, () -> FAILURE);

        assertFalse(verify);
        assertEquals(ServerLifecycleState.FAILED, entry.state());
        assertEquals(Optional.of(FAILURE), entry.lastFailure());
    }

    @Test
    void refusedConnectionAfterSuccessAsksForContainerCheckWithoutDemoting() {
        readyEntry();
        entry.recordConnectionOutcome(ConnectionOutcome.CONNECTED, () -> FAILURE);

        boolean verify = entry.recordConnectionOutcome(ConnectionOutcome.SERVER_DISCONNECTED, () -> FAILURE);

        assertTrue(verify);
        assertEquals(ServerLifecycleState.READY, entry.state());
        assertEquals(Optional.of(ConnectionOutcome.SERVER_DISCONNECTED), entry.lastConnectionOutcome());
    }

    @Test
    void retiredEntryNeverAsksForContainerCheck() {
        readyEntry();
        entry.recordConnectionOutcome(ConnectionOutcome.CONNECTED, () -> FAILURE);
        entry.retire();

        assertFalse(entry.recordConnectionOutcome(ConnectionOutcome.SERVER_DISCONNECTED, () -> FAILURE));
    }

    @Test
    void successfulConnectionRecoversFailedServer() {
        entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 0);
        entry.finishStartup(StartupOutcome.NOT_READY, FAILURE);

        entry.recordConnectionOutcome(ConnectionOutcome.CONNECTED, () -> FAILURE);

        assertEquals(ServerLifecycleState.READY, entry.state());
        assertTrue(entry.lastFailure().isEmpty());
    }

    @Test
    void markStoppedExternallyOnlyAppliesToReadyOrFailed() {
        readyEntry();
        entry.markStoppedExternally();
        assertEquals(ServerLifecycleState.STOPPED, entry.state());

        entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 0);
        entry.markStoppedExternally();
        assertEquals(ServerLifecycleState.STARTING, entry.state());
    }

    @Test
    void retiredEntryIsDisposableOnlyWhenIdle() {
        ConnectionWaiter waiter = waiter();
        entry.addWaiter(waiter);

        entry.retire();
        assertTrue(entry.isRetired());
        assertFalse(entry.isDisposable());

        entry.removeWaiter(waiter);
        assertTrue(entry.isDisposable());

        entry.unretire();
        assertFalse(entry.isDisposable());
    }

    @Test
    void retireSilencesWaiters() {
        ConnectionWaiter waiter = waiter();
        entry.addWaiter(waiter);

        entry.retire();
        waiter.queueStage(ConnectionLifecycleStage.STARTING, Component.text("starting"), false);

        List<ConnectionWaiter.WaiterNotification> delivered = new ArrayList<>();
        waiter.drainNotifications(delivered::add);
        assertTrue(delivered.isEmpty());
    }

    @Test
    void stateAndWaiterChangesAdvanceTheRevision() {
        long initial = entry.revision();
        entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 0);
        long afterStartup = entry.revision();
        assertNotEquals(initial, afterStartup);

        ConnectionWaiter waiter = waiter();
        entry.addWaiter(waiter);
        long afterAdd = entry.revision();
        assertNotEquals(afterStartup, afterAdd);

        assertFalse(entry.removeWaiter(waiter()));
        assertEquals(afterAdd, entry.revision());
        assertTrue(entry.removeWaiter(waiter));
        assertNotEquals(afterAdd, entry.revision());
    }

    @Test
    void snapshotReflectsStateWaitersAndFailure() {
        entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 0);
        entry.finishStartup(StartupOutcome.START_FAILED, FAILURE);

        LifecycleStatusSnapshot snapshot = entry.snapshot();

        assertEquals(new LifecycleStatusSnapshot(Optional.of(ServerLifecycleState.FAILED), 0,
                Optional.of(FAILURE), entry.revision()), snapshot);
    }

    @Test
    void drainForShutdownDetachesEverythingInFlight() {
        CompletableFuture<StartupOutcome> startup = entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 1);
        CompletableFuture<Void> operation = new CompletableFuture<>();
        entry.attachOperation(operation);
        ConnectionWaiter waiter = waiter();
        entry.addWaiter(waiter);

        List<CompletableFuture<?>> operations = new ArrayList<>();
        List<ConnectionWaiter> stranded = new ArrayList<>();
        boolean interrupted = entry.drainForShutdown(operations, new ArrayList<>(), stranded);

        assertTrue(interrupted);
        assertFalse(startup.isDone(), "the caller cancels the startup outside the entry lock");
        assertEquals(List.of(operation, startup), operations);
        assertEquals(List.of(waiter), stranded);
        assertTrue(waiter.isDiscarded());
        assertFalse(entry.hasWaiters());
        assertFalse(entry.ownsOperation(operation));
        assertEquals(Optional.of(ConnectionOutcome.PROXY_SHUTDOWN), entry.lastConnectionOutcome());
    }

    @Test
    void drainForShutdownDoesNotReportStartupWhoseTelemetryWasRecorded() {
        entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 0);
        entry.claimStartupTelemetry();

        assertFalse(entry.drainForShutdown(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
    }

    @Test
    void drainForShutdownHandsBackManualStopSeparatelyFromCancellableOperations() {
        readyEntry();
        entry.beginStop();
        CompletableFuture<ManualStopOutcome> stop = new CompletableFuture<>();
        entry.attachManualStop(stop);

        List<CompletableFuture<?>> operations = new ArrayList<>();
        List<CompletableFuture<ManualStopOutcome>> manualStops = new ArrayList<>();
        entry.drainForShutdown(operations, manualStops, new ArrayList<>());

        assertEquals(List.of(), operations);
        assertEquals(List.of(stop), manualStops);
        assertFalse(stop.isDone(), "the caller completes the stop outside the entry lock");
        assertFalse(entry.ownsManualStop(stop));
    }

    private void readyEntry() {
        entry.beginStartup(ConnectionLifecycleStage.INSPECTING, 0, 0);
        entry.finishStartup(StartupOutcome.READY_RUNNING, null);
    }

    private static ConnectionWaiter waiter() {
        return new ConnectionWaiter(UUID.randomUUID(), mock(Player.class), mock(RegisteredServer.class),
                "survival", 0);
    }
}
