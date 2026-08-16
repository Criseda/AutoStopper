package me.criseda.autostopper.lifecycle;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.ServerConnection;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.server.ServerManager;
import me.criseda.autostopper.readiness.ReadinessResult;
import me.criseda.autostopper.readiness.MinecraftStatusProbe;
import me.criseda.autostopper.telemetry.LifecycleTelemetryService;
import me.criseda.autostopper.telemetry.TelemetryOperationType;
import me.criseda.autostopper.telemetry.TelemetryOrigin;
import me.criseda.autostopper.telemetry.TelemetryOutcome;
import me.criseda.autostopper.telemetry.TelemetrySnapshot;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static me.criseda.autostopper.testing.ComponentTestUtils.plainText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServerLifecycleCoordinatorTest {
    @Mock
    private Logger logger;

    @Mock
    private ServerManager serverManager;

    @Mock
    private RegisteredServer targetServer;

    private ServerLifecycleCoordinator coordinator;
    private ServerMapping mapping;

    @BeforeEach
    void setUp() {
        coordinator = new ServerLifecycleCoordinator(logger, serverManager);
        mapping = new ServerMapping("survival", "survival-container");
        lenient().when(serverManager.waitForServerReadyAsync(any(ServerMapping.class)))
                .thenReturn(CompletableFuture.completedFuture(ReadinessResult.ready(1)));
    }

    @Test
    void alreadyRunningContainerMustPassReadinessBeforeAnyConnectionAttempt() {
        CompletableFuture<ReadinessResult> readiness = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        when(serverManager.waitForServerReadyAsync(mapping)).thenReturn(readiness);
        PlayerHarness player = player("waiting-on-running");

        CompletableFuture<ConnectionOutcome> outcome =
                coordinator.requestConnection(player.player, targetServer, mapping);

        verify(player.player, never()).createConnectionRequest(any(RegisteredServer.class));
        assertEquals(Optional.of(ServerLifecycleState.STARTING), coordinator.state("survival"));

        readiness.complete(ReadinessResult.ready(1));
        verify(player.player).createConnectionRequest(targetServer);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);
        assertEquals(ConnectionOutcome.CONNECTED, outcome.join());
        List<String> messages = sentMessages(player.player);
        assertEquals(List.of(
                        "AutoStopper › Checking server survival…",
                        "AutoStopper › Waiting for server survival to become ready…",
                        "AutoStopper › Connecting you to server survival…"),
                messages.subList(0, 3));
        assertTrue(messages.stream().noneMatch(message -> message.contains("Waking survival")));
    }

    @Test
    void readinessFailureReasonReachesEveryQueuedPlayer() {
        CompletableFuture<ReadinessResult> readiness = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        when(serverManager.waitForServerReadyAsync(mapping)).thenReturn(readiness);
        PlayerHarness first = player("first-readiness-waiter");
        PlayerHarness second = player("second-readiness-waiter");

        CompletableFuture<ConnectionOutcome> firstOutcome =
                coordinator.requestConnection(first.player, targetServer, mapping);
        CompletableFuture<ConnectionOutcome> secondOutcome =
                coordinator.requestConnection(second.player, targetServer, mapping);
        readiness.complete(ReadinessResult.failure(
                ReadinessResult.Outcome.CONTAINER_STOPPED,
                2,
                MinecraftStatusProbe.Outcome.UNREACHABLE));

        assertEquals(ConnectionOutcome.SERVER_NOT_READY, firstOutcome.join());
        assertEquals(ConnectionOutcome.SERVER_NOT_READY, secondOutcome.join());
        verify(first.player).sendMessage(argThat(this::containsContainerStopped));
        verify(second.player).sendMessage(argThat(this::containsContainerStopped));
        verify(first.player, never()).createConnectionRequest(any(RegisteredServer.class));
        verify(second.player, never()).createConnectionRequest(any(RegisteredServer.class));
    }

    @Test
    void readinessFailureDisconnectsInitialPlayerWithTypedReason() {
        CompletableFuture<ReadinessResult> readiness = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        when(serverManager.waitForServerReadyAsync(mapping)).thenReturn(readiness);
        PlayerHarness player = initialPlayer("initial-readiness-waiter");

        CompletableFuture<ConnectionOutcome> outcome =
                coordinator.requestConnection(player.player, targetServer, mapping);
        readiness.complete(ReadinessResult.failure(
                ReadinessResult.Outcome.TIMED_OUT,
                3,
                MinecraftStatusProbe.Outcome.UNREACHABLE));

        assertEquals(ConnectionOutcome.SERVER_NOT_READY, outcome.join());
        verify(player.player).disconnect(argThat(message ->
                plainText(message).toLowerCase().contains("remained unreachable")
                        && plainText(message).contains("Waited ")));
        verify(player.player, atLeastOnce()).sendMessage(any(Component.class));
        verify(player.player, never()).createConnectionRequest(any(RegisteredServer.class));
    }

    @Test
    void lateWaiterSeesCurrentStageThenSharedFutureTransitionsExactlyOnce() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        coordinator = new ServerLifecycleCoordinator(logger, serverManager, clock::get);
        CompletableFuture<Optional<ContainerStatus>> status = new CompletableFuture<>();
        CompletableFuture<ContainerStatus> start = new CompletableFuture<>();
        CompletableFuture<ReadinessResult> readiness = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping)).thenReturn(status);
        when(serverManager.startServerAsync(mapping)).thenReturn(start);
        when(serverManager.waitForServerReadyAsync(mapping)).thenReturn(readiness);
        PlayerHarness first = player("stage-first");
        PlayerHarness late = player("stage-late");

        CompletableFuture<ConnectionOutcome> firstOutcome =
                coordinator.requestConnection(first.player, targetServer, mapping);
        status.complete(Optional.of(ContainerStatus.STOPPED));
        CompletableFuture<ConnectionOutcome> lateOutcome =
                coordinator.requestConnection(late.player, targetServer, mapping);
        assertTrue(sentMessages(first.player).stream()
                .noneMatch(message -> message.contains("players waiting")));
        assertSame(firstOutcome, coordinator.requestConnection(first.player, targetServer, mapping));
        assertSame(firstOutcome, coordinator.requestConnection(first.player, targetServer, mapping));
        start.complete(ContainerStatus.RUNNING);
        readiness.complete(ReadinessResult.ready(2));
        clock.set(3_500_000_000L);
        first.complete(ConnectionRequestBuilder.Status.SUCCESS);
        late.complete(ConnectionRequestBuilder.Status.SUCCESS);

        assertEquals(ConnectionOutcome.CONNECTED, firstOutcome.join());
        assertEquals(ConnectionOutcome.CONNECTED, lateOutcome.join());
        assertEquals(List.of(
                        "AutoStopper › Checking server survival…",
                        "AutoStopper › Waking survival…",
                        "AutoStopper › 2 players waiting",
                        "AutoStopper › Waiting for server survival to become ready…",
                        "AutoStopper › Connecting you to server survival…",
                        "AutoStopper ✓ Connected to survival · 2.5s"),
                sentMessages(first.player));
        assertEquals(List.of(
                        "AutoStopper › Waking survival…",
                        "AutoStopper › 2 players waiting",
                        "AutoStopper › Waiting for server survival to become ready…",
                        "AutoStopper › Connecting you to server survival…",
                        "AutoStopper ✓ Connected to survival · 2.5s"),
                sentMessages(late.player));
        verify(serverManager).getServerStatusAsync(mapping);
        verify(serverManager).startServerAsync(mapping);
        verify(serverManager).waitForServerReadyAsync(mapping);
    }

    @Test
    void messageFailureIsolatedFromSharedLifecycleAndOtherWaiter() {
        CompletableFuture<Optional<ContainerStatus>> status = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping)).thenReturn(status);
        PlayerHarness failingMessages = player("message-failure");
        PlayerHarness unaffected = player("message-unaffected");
        doThrow(new IllegalStateException("audience unavailable"))
                .when(failingMessages.player).sendMessage(any(Component.class));

        CompletableFuture<ConnectionOutcome> failedMessageOutcome =
                coordinator.requestConnection(failingMessages.player, targetServer, mapping);
        CompletableFuture<ConnectionOutcome> unaffectedOutcome =
                coordinator.requestConnection(unaffected.player, targetServer, mapping);
        status.complete(Optional.of(ContainerStatus.RUNNING));
        failingMessages.complete(ConnectionRequestBuilder.Status.SUCCESS);
        unaffected.complete(ConnectionRequestBuilder.Status.SUCCESS);

        assertEquals(ConnectionOutcome.CONNECTED, failedMessageOutcome.join());
        assertEquals(ConnectionOutcome.CONNECTED, unaffectedOutcome.join());
        assertEquals(Optional.of(ServerLifecycleState.READY), coordinator.state(mapping));
        assertTrue(sentMessages(unaffected.player).stream()
                .anyMatch(message -> message.contains("Connected to survival")));
    }

    @Test
    void disconnectedWaiterReceivesNoStagesAfterDiscard() {
        CompletableFuture<Optional<ContainerStatus>> status = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping)).thenReturn(status);
        PlayerHarness player = player("discarded-progress");

        coordinator.requestConnection(player.player, targetServer, mapping);
        coordinator.discardPlayer(player.player);
        status.complete(Optional.of(ContainerStatus.STOPPED));

        assertEquals(List.of("AutoStopper › Checking server survival…"), sentMessages(player.player));
    }

    @Test
    void simultaneousRequestsShareExactlyOneStartupAndAllConnect() throws Exception {
        int playerCount = 12;
        CompletableFuture<Optional<ContainerStatus>> status = new CompletableFuture<>();
        CompletableFuture<ContainerStatus> start = new CompletableFuture<>();
        CompletableFuture<ReadinessResult> readiness = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping)).thenReturn(status);
        when(serverManager.startServerAsync(mapping)).thenReturn(start);
        when(serverManager.waitForServerReadyAsync(mapping)).thenReturn(readiness);

        List<PlayerHarness> players = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            players.add(player("player-" + i));
        }

        ExecutorService callers = Executors.newFixedThreadPool(playerCount);
        CountDownLatch ready = new CountDownLatch(playerCount);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<CompletableFuture<ConnectionOutcome>>> submitted = new ArrayList<>();
            for (PlayerHarness player : players) {
                submitted.add(callers.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(2, TimeUnit.SECONDS));
                    return coordinator.requestConnection(player.player, targetServer, mapping);
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            go.countDown();

            List<CompletableFuture<ConnectionOutcome>> outcomes = new ArrayList<>();
            for (Future<CompletableFuture<ConnectionOutcome>> future : submitted) {
                outcomes.add(future.get(2, TimeUnit.SECONDS));
            }

            verify(serverManager, times(1)).getServerStatusAsync(mapping);
            assertEquals(playerCount, coordinator.waitingCount("survival"));
            assertEquals(Optional.of(ServerLifecycleState.STARTING), coordinator.state("survival"));

            status.complete(Optional.of(ContainerStatus.STOPPED));
            verify(serverManager, times(1)).startServerAsync(mapping);
            start.complete(ContainerStatus.RUNNING);
            verify(serverManager, times(1)).waitForServerReadyAsync(mapping);
            readiness.complete(ReadinessResult.ready(2));

            for (PlayerHarness player : players) {
                verify(player.player).createConnectionRequest(targetServer);
                player.complete(ConnectionRequestBuilder.Status.SUCCESS);
            }
            for (CompletableFuture<ConnectionOutcome> outcome : outcomes) {
                assertEquals(ConnectionOutcome.CONNECTED, outcome.get(2, TimeUnit.SECONDS));
            }
            assertEquals(0, coordinator.waitingCount("survival"));
            assertEquals(Optional.of(ServerLifecycleState.READY), coordinator.state("survival"));
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void failedStartCompletesEveryWaiterAndNextRequestRetries() {
        CompletableFuture<ContainerStatus> firstStart = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)));
        when(serverManager.startServerAsync(mapping))
                .thenReturn(firstStart)
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.RUNNING));
        when(serverManager.waitForServerReadyAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ReadinessResult.ready(1)));

        PlayerHarness first = player("first");
        PlayerHarness second = player("second");
        CompletableFuture<ConnectionOutcome> firstOutcome =
                coordinator.requestConnection(first.player, targetServer, mapping);
        CompletableFuture<ConnectionOutcome> secondOutcome =
                coordinator.requestConnection(second.player, targetServer, mapping);

        firstStart.complete(ContainerStatus.TIMED_OUT);

        assertEquals(ConnectionOutcome.START_TIMED_OUT, firstOutcome.join());
        assertEquals(ConnectionOutcome.START_TIMED_OUT, secondOutcome.join());
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
        assertEquals(0, coordinator.waitingCount("survival"));

        PlayerHarness retry = player("retry");
        CompletableFuture<ConnectionOutcome> retryOutcome =
                coordinator.requestConnection(retry.player, targetServer, mapping);
        retry.complete(ConnectionRequestBuilder.Status.SUCCESS);

        assertEquals(ConnectionOutcome.CONNECTED, retryOutcome.join());
        verify(serverManager, times(2)).getServerStatusAsync(mapping);
        verify(serverManager, times(2)).startServerAsync(mapping);
    }

    @Test
    void disconnectEventDiscardsWaiterBeforeStartupCompletes() {
        CompletableFuture<Optional<ContainerStatus>> status = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping)).thenReturn(status);
        when(serverManager.startServerAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.RUNNING));
        when(serverManager.waitForServerReadyAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ReadinessResult.ready(1)));
        PlayerHarness player = player("departing");

        CompletableFuture<ConnectionOutcome> outcome =
                coordinator.requestConnection(player.player, targetServer, mapping);
        coordinator.discardPlayer(player.player);

        assertEquals(ConnectionOutcome.PLAYER_DISCONNECTED, outcome.join());
        assertEquals(0, coordinator.waitingCount("survival"));

        status.complete(Optional.of(ContainerStatus.STOPPED));
        verify(player.player, never()).createConnectionRequest(any(RegisteredServer.class));
    }

    @Test
    void disconnectAfterConnectionAttemptWinsOverLateConnectionResult() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("disconnect-race");

        CompletableFuture<ConnectionOutcome> outcome =
                coordinator.requestConnection(player.player, targetServer, mapping);
        verify(player.player).createConnectionRequest(targetServer);

        coordinator.discardPlayer(player.player);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        assertEquals(ConnectionOutcome.PLAYER_DISCONNECTED, outcome.join());
        assertEquals(0, coordinator.waitingCount("survival"));
    }

    @Test
    void connectionCancellationRefusalAndExceptionAreObservedAndCommunicated() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness cancelled = player("cancelled");

        CompletableFuture<ConnectionOutcome> cancelledOutcome =
                coordinator.requestConnection(cancelled.player, targetServer, mapping);
        cancelled.complete(ConnectionRequestBuilder.Status.CONNECTION_CANCELLED);

        assertEquals(ConnectionOutcome.CONNECTION_CANCELLED, cancelledOutcome.join());
        verify(cancelled.player).sendMessage(argThat(this::containsCancelled));
        assertEquals(Optional.of(ConnectionOutcome.CONNECTION_CANCELLED),
                coordinator.lastConnectionOutcome("survival"));

        PlayerHarness refused = player("refused");
        CompletableFuture<ConnectionOutcome> refusedOutcome =
                coordinator.requestConnection(refused.player, targetServer, mapping);
        refused.complete(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED);

        assertEquals(ConnectionOutcome.SERVER_DISCONNECTED, refusedOutcome.join());
        verify(refused.player).sendMessage(argThat(this::containsRefused));
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));

        PlayerHarness failed = player("failed");
        CompletableFuture<ConnectionOutcome> failedOutcome =
                coordinator.requestConnection(failed.player, targetServer, mapping);
        failed.connection.completeExceptionally(new IllegalStateException("connection exploded"));

        assertEquals(ConnectionOutcome.CONNECTION_FAILED, failedOutcome.join());
        verify(failed.player).sendMessage(argThat(this::containsCouldNotConnect));
        assertEquals(Optional.of(ConnectionOutcome.CONNECTION_FAILED),
                coordinator.lastConnectionOutcome("survival"));
    }

    @Test
    void stopCannotOverlapStartupOrPendingConnection() {
        CompletableFuture<Optional<ContainerStatus>> firstStatus = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(firstStatus)
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness first = player("first");
        CompletableFuture<ConnectionOutcome> firstOutcome =
                coordinator.requestConnection(first.player, targetServer, mapping);

        assertFalse(coordinator.tryBeginStop(mapping));

        firstStatus.complete(Optional.of(ContainerStatus.RUNNING));
        assertFalse(coordinator.tryBeginStop(mapping));

        first.complete(ConnectionRequestBuilder.Status.SUCCESS);
        assertEquals(ConnectionOutcome.CONNECTED, firstOutcome.join());
        assertTrue(coordinator.tryBeginStop(mapping));
        assertEquals(Optional.of(ServerLifecycleState.STOPPING), coordinator.state("survival"));

        PlayerHarness whileStopping = player("while-stopping");
        assertEquals(ConnectionOutcome.SERVER_STOPPING,
                coordinator.requestConnection(whileStopping.player, targetServer, mapping).join());
        verify(whileStopping.player, never()).createConnectionRequest(any(RegisteredServer.class));

        coordinator.completeStop(mapping, ContainerStatus.STOPPED);
        assertEquals(Optional.of(ServerLifecycleState.STOPPED), coordinator.state("survival"));

        PlayerHarness afterStop = player("after-stop");
        CompletableFuture<ConnectionOutcome> afterStopOutcome =
                coordinator.requestConnection(afterStop.player, targetServer, mapping);
        afterStop.complete(ConnectionRequestBuilder.Status.SUCCESS);
        assertEquals(ConnectionOutcome.CONNECTED, afterStopOutcome.join());
        verify(serverManager, times(2)).getServerStatusAsync(mapping);
    }

    @Test
    void admittedStopCanBeCancelledWhenActivityWinsTheRace() {
        assertTrue(coordinator.tryBeginStop(mapping));
        assertEquals(Optional.of(ServerLifecycleState.STOPPING), coordinator.state("survival"));

        coordinator.cancelStop(mapping);

        assertEquals(Optional.of(ServerLifecycleState.READY), coordinator.state("survival"));
        assertTrue(coordinator.tryBeginStop(mapping));
    }

    @Test
    void failedStopRecordsFailureAndSuccessfulRetryClearsIt() {
        assertTrue(coordinator.tryBeginStop(mapping));
        coordinator.completeStop(mapping, ContainerStatus.TIMED_OUT);

        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
        assertTrue(coordinator.lastFailure("survival").isPresent());
        assertTrue(coordinator.lastFailure("survival").orElseThrow().detail().contains("TIMED_OUT"));

        assertTrue(coordinator.tryBeginStop(mapping));
        coordinator.completeStop(mapping, ContainerStatus.STOPPED);

        assertEquals(Optional.of(ServerLifecycleState.STOPPED), coordinator.state("survival"));
        assertTrue(coordinator.lastFailure("survival").isEmpty());
    }

    @Test
    void replacementMappingDoesNotJoinCapturedStartup() {
        ServerMapping replacement = new ServerMapping("survival", "replacement-container");
        ConfigSnapshot previous = new ConfigSnapshot(300, List.of(mapping));
        ConfigSnapshot current = new ConfigSnapshot(300, List.of(replacement));
        CompletableFuture<Optional<ContainerStatus>> oldStatus = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping)).thenReturn(oldStatus);
        when(serverManager.getServerStatusAsync(replacement))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));

        PlayerHarness original = player("original");
        CompletableFuture<ConnectionOutcome> originalOutcome =
                coordinator.requestConnection(original.player, targetServer, mapping);
        coordinator.reconcileConfig(previous, current);

        PlayerHarness rejected = player("replacement-too-early");
        assertEquals(ConnectionOutcome.MAPPING_CHANGED,
                coordinator.requestConnection(rejected.player, targetServer, replacement).join());
        verify(serverManager, never()).startServerAsync(replacement);

        oldStatus.complete(Optional.of(ContainerStatus.RUNNING));
        original.complete(ConnectionRequestBuilder.Status.SUCCESS);
        assertEquals(ConnectionOutcome.CONNECTED, originalOutcome.join());
        assertEquals(List.of("AutoStopper › Checking server survival…"),
                sentMessages(original.player));
        assertEquals(Optional.empty(), coordinator.state("survival"));

        PlayerHarness retry = player("replacement-retry");
        CompletableFuture<ConnectionOutcome> retryOutcome =
                coordinator.requestConnection(retry.player, targetServer, replacement);
        retry.complete(ConnectionRequestBuilder.Status.SUCCESS);
        assertEquals(ConnectionOutcome.CONNECTED, retryOutcome.join());
        verify(serverManager).getServerStatusAsync(replacement);
    }

    @Test
    void shutdownDuringInspectCancelsOperationAndCompletesEveryWaiterExactlyOnce() {
        CompletableFuture<Optional<ContainerStatus>> status = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping)).thenReturn(status);
        PlayerHarness first = player("shutdown-inspect-first");
        PlayerHarness second = player("shutdown-inspect-second");
        CompletableFuture<ConnectionOutcome> firstOutcome =
                coordinator.requestConnection(first.player, targetServer, mapping);
        CompletableFuture<ConnectionOutcome> secondOutcome =
                coordinator.requestConnection(second.player, targetServer, mapping);
        java.util.concurrent.atomic.AtomicInteger completions = new java.util.concurrent.atomic.AtomicInteger();
        firstOutcome.whenComplete((ignored, error) -> completions.incrementAndGet());
        secondOutcome.whenComplete((ignored, error) -> completions.incrementAndGet());

        coordinator.shutdown();
        coordinator.shutdown();

        assertTrue(status.isCancelled());
        assertEquals(ConnectionOutcome.PROXY_SHUTDOWN, firstOutcome.join());
        assertEquals(ConnectionOutcome.PROXY_SHUTDOWN, secondOutcome.join());
        assertEquals(2, completions.get());
        assertEquals(0, coordinator.waitingCount("survival"));
        verify(first.player, never()).createConnectionRequest(any());
        verify(second.player, never()).createConnectionRequest(any());
    }

    @Test
    void shutdownDuringStartCancelsStartAndLeavesContainerUnchanged() {
        CompletableFuture<ContainerStatus> start = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)));
        when(serverManager.startServerAsync(mapping)).thenReturn(start);
        PlayerHarness player = player("shutdown-start");
        CompletableFuture<ConnectionOutcome> outcome =
                coordinator.requestConnection(player.player, targetServer, mapping);

        coordinator.shutdown();

        assertTrue(start.isCancelled());
        assertEquals(ConnectionOutcome.PROXY_SHUTDOWN, outcome.join());
        verify(serverManager, never()).stopServer(any(ServerMapping.class));
        verify(player.player, never()).createConnectionRequest(any());
    }

    @Test
    void shutdownDuringReadinessCancelsProbeAndSuppressesLateCallbacks() {
        CompletableFuture<ReadinessResult> readiness = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        when(serverManager.waitForServerReadyAsync(mapping)).thenReturn(readiness);
        PlayerHarness player = player("shutdown-readiness");
        CompletableFuture<ConnectionOutcome> outcome =
                coordinator.requestConnection(player.player, targetServer, mapping);
        clearInvocations(player.player);

        coordinator.shutdown();

        assertTrue(readiness.isCancelled());
        assertEquals(ConnectionOutcome.PROXY_SHUTDOWN, outcome.join());
        verifyNoInteractions(player.player);
    }

    @Test
    void markStoppedIfUnchangedReconcilesReadyAndFailedToStopped() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("ready-player");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);
        assertEquals(Optional.of(ServerLifecycleState.READY), coordinator.state("survival"));

        long readyRevision = coordinator.statusSnapshot(mapping).revision();
        assertTrue(coordinator.markStoppedIfUnchanged(mapping, readyRevision).isPresent());
        assertEquals(Optional.of(ServerLifecycleState.STOPPED), coordinator.state("survival"));

        assertTrue(coordinator.tryBeginStop(mapping));
        coordinator.completeStop(mapping, ContainerStatus.TIMED_OUT);
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
        assertTrue(coordinator.lastFailure("survival").isPresent());

        long failedRevision = coordinator.statusSnapshot(mapping).revision();
        assertTrue(coordinator.markStoppedIfUnchanged(mapping, failedRevision).isPresent());
        assertEquals(Optional.of(ServerLifecycleState.STOPPED), coordinator.state("survival"));
        assertTrue(coordinator.lastFailure("survival").isEmpty());
    }

    @Test
    void markStoppedIfUnchangedPreservesActiveStartingAndStoppingOperations() {
        CompletableFuture<Optional<ContainerStatus>> status = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping)).thenReturn(status);
        PlayerHarness player = player("active-waiter");

        coordinator.requestConnection(player.player, targetServer, mapping);
        assertEquals(Optional.of(ServerLifecycleState.STARTING), coordinator.state("survival"));

        long startingRevision = coordinator.statusSnapshot(mapping).revision();
        assertTrue(coordinator.markStoppedIfUnchanged(mapping, startingRevision).isEmpty());
        assertEquals(Optional.of(ServerLifecycleState.STARTING), coordinator.state("survival"));

        status.complete(Optional.of(ContainerStatus.RUNNING));
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);
        assertEquals(Optional.of(ServerLifecycleState.READY), coordinator.state("survival"));

        assertTrue(coordinator.tryBeginStop(mapping));
        assertEquals(Optional.of(ServerLifecycleState.STOPPING), coordinator.state("survival"));

        long stoppingRevision = coordinator.statusSnapshot(mapping).revision();
        assertTrue(coordinator.markStoppedIfUnchanged(mapping, stoppingRevision).isEmpty());
        assertEquals(Optional.of(ServerLifecycleState.STOPPING), coordinator.state("survival"));
    }

    @Test
    void staleStoppedObservationCannotOverwriteNewerReadyState() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness initial = player("initial-ready-player");
        coordinator.requestConnection(initial.player, targetServer, mapping);
        initial.complete(ConnectionRequestBuilder.Status.SUCCESS);

        long observationRevision = coordinator.statusSnapshot(mapping).revision();
        PlayerHarness later = player("later-ready-player");
        CompletableFuture<ConnectionOutcome> outcome =
                coordinator.requestConnection(later.player, targetServer, mapping);
        later.complete(ConnectionRequestBuilder.Status.SUCCESS);
        assertEquals(ConnectionOutcome.CONNECTED, outcome.join());

        assertTrue(coordinator.markStoppedIfUnchanged(mapping, observationRevision).isEmpty());
        assertEquals(Optional.of(ServerLifecycleState.READY), coordinator.state(mapping));
    }

    @Test
    void unchangedReloadPreservesReadyRevisionWithoutPartialInvalidation() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("unchanged-reload-player");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);
        LifecycleStatusSnapshot before = coordinator.statusSnapshot(mapping);

        ConfigSnapshot snapshot = new ConfigSnapshot(300, List.of(mapping));
        coordinator.reconcileConfig(snapshot, snapshot);

        assertEquals(before, coordinator.statusSnapshot(mapping));
    }

    @Test
    void stateWithMappingReturnsEmptyWhenMappingModifiedOrRetired() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("ready-player");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);
        assertEquals(Optional.of(ServerLifecycleState.READY), coordinator.state(mapping));

        ServerMapping modified = new ServerMapping("survival", "modified-container");
        assertEquals(Optional.empty(), coordinator.state(modified));

        ConfigSnapshot previous = new ConfigSnapshot(300, List.of(mapping));
        ConfigSnapshot current = new ConfigSnapshot(300, List.of(modified));
        coordinator.reconcileConfig(previous, current);

        assertEquals(Optional.empty(), coordinator.state("survival"));
        assertEquals(Optional.empty(), coordinator.state(mapping));
        assertEquals(Optional.empty(), coordinator.state(modified));
    }

    @Test
    void shutdownClosesAdmissionAndCancelsPendingVelocityConnection() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness connecting = player("shutdown-connection");
        CompletableFuture<ConnectionOutcome> outcome =
                coordinator.requestConnection(connecting.player, targetServer, mapping);
        clearInvocations(connecting.player);

        coordinator.shutdown();

        assertTrue(connecting.connection.isCancelled());
        assertEquals(ConnectionOutcome.PROXY_SHUTDOWN, outcome.join());
        PlayerHarness rejected = player("shutdown-rejected");
        assertEquals(ConnectionOutcome.PROXY_SHUTDOWN,
                coordinator.requestConnection(rejected.player, targetServer, mapping).join());
        verifyNoInteractions(connecting.player, rejected.player);
        assertFalse(coordinator.tryBeginStop(mapping));
    }

    @Test
    void manualStart_AlreadyReady() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        CompletableFuture<ManualStartOutcome> startFuture = coordinator.requestManualStart(mapping);
        assertEquals(ManualStartOutcome.ALREADY_READY, startFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.READY), coordinator.state("survival"));
    }

    @Test
    void manualStart_FromStopped_StartsAndReadies() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)));
        when(serverManager.startServerAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.RUNNING));
        when(serverManager.waitForServerReadyAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ReadinessResult.ready(1)));

        CompletableFuture<ManualStartOutcome> startFuture = coordinator.requestManualStart(mapping);
        assertEquals(ManualStartOutcome.READY, startFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.READY), coordinator.state("survival"));
    }

    @Test
    void manualStart_WhileStarting_JoinsInFlight() {
        CompletableFuture<ReadinessResult> readiness = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        when(serverManager.waitForServerReadyAsync(mapping)).thenReturn(readiness);

        PlayerHarness player = player("waiter");
        coordinator.requestConnection(player.player, targetServer, mapping);

        CompletableFuture<ManualStartOutcome> startFuture = coordinator.requestManualStart(mapping);
        assertFalse(startFuture.isDone());

        readiness.complete(ReadinessResult.ready(1));
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        assertEquals(ManualStartOutcome.READY, startFuture.join());
    }

    @Test
    void manualStart_WhileStopping_Rejected() {
        assertTrue(coordinator.tryBeginStop(mapping));
        CompletableFuture<ManualStartOutcome> startFuture = coordinator.requestManualStart(mapping);
        assertEquals(ManualStartOutcome.SERVER_STOPPING, startFuture.join());
    }

    @Test
    void manualStop_AlreadyStopped() {
        CompletableFuture<ManualStopOutcome> stopFuture =
                coordinator.requestManualStop(mapping, targetServer);
        assertEquals(ManualStopOutcome.ALREADY_STOPPED, stopFuture.join());
        verify(serverManager, never()).stopServer(any(ServerMapping.class));
    }

    @Test
    void manualStop_RefusedWhenPlayersConnected() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        when(targetServer.getPlayersConnected()).thenReturn(List.of(player.player));

        CompletableFuture<ManualStopOutcome> stopFuture =
                coordinator.requestManualStop(mapping, targetServer);
        assertEquals(ManualStopOutcome.PLAYERS_CONNECTED, stopFuture.join());
        verify(serverManager, never()).stopServer(any(ServerMapping.class));
        assertEquals(Optional.of(ServerLifecycleState.READY), coordinator.state("survival"));
    }

    @Test
    void manualStop_RefusedWhenWaitersPresent() {
        CompletableFuture<ReadinessResult> readiness = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        when(serverManager.waitForServerReadyAsync(mapping)).thenReturn(readiness);

        PlayerHarness player = player("waiting-player");
        coordinator.requestConnection(player.player, targetServer, mapping);

        CompletableFuture<ManualStopOutcome> stopFuture =
                coordinator.requestManualStop(mapping, targetServer);
        assertEquals(ManualStopOutcome.WAITERS_PRESENT, stopFuture.join());
        verify(serverManager, never()).stopServer(any(ServerMapping.class));
    }

    @Test
    void manualStop_Success_TransitionsToStopped() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        when(targetServer.getPlayersConnected()).thenReturn(List.of());
        when(serverManager.stopServer(mapping)).thenReturn(ContainerStatus.STOPPED);

        CompletableFuture<ManualStopOutcome> stopFuture =
                coordinator.requestManualStop(mapping, targetServer);
        assertEquals(ManualStopOutcome.STOPPED, stopFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.STOPPED), coordinator.state("survival"));
    }

    @Test
    void manualStop_DockerFailure_TransitionsToFailed() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        when(targetServer.getPlayersConnected()).thenReturn(List.of());
        when(serverManager.stopServer(mapping)).thenReturn(ContainerStatus.INACCESSIBLE);

        CompletableFuture<ManualStopOutcome> stopFuture =
                coordinator.requestManualStop(mapping, targetServer);
        assertEquals(ManualStopOutcome.DOCKER_INACCESSIBLE, stopFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
    }

    @Test
    void manualStop_ImmediatePreDockerRace_AbortsWhenPlayerConnects() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        when(targetServer.getPlayersConnected())
                .thenReturn(List.of())
                .thenReturn(List.of(player.player));

        CompletableFuture<ManualStopOutcome> stopFuture =
                coordinator.requestManualStop(mapping, targetServer);

        assertEquals(ManualStopOutcome.PLAYERS_CONNECTED, stopFuture.join());
        verify(serverManager, never()).stopServer(any(ServerMapping.class));
        assertEquals(Optional.of(ServerLifecycleState.READY), coordinator.state("survival"));
    }

    @Test
    void manualRestart_RefusedWhenPlayersConnected() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        when(targetServer.getPlayersConnected()).thenReturn(List.of(player.player));

        CompletableFuture<ManualRestartOutcome> restartFuture =
                coordinator.requestManualRestart(mapping, targetServer);
        assertEquals(ManualRestartOutcome.PLAYERS_CONNECTED, restartFuture.join());
        verify(serverManager, never()).stopServer(any(ServerMapping.class));
    }

    @Test
    void manualRestart_FromReady_ExecutesStopThenStartAndReadiness() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        when(targetServer.getPlayersConnected()).thenReturn(List.of());
        when(serverManager.stopServer(mapping)).thenReturn(ContainerStatus.STOPPED);
        when(serverManager.startServerAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.RUNNING));
        when(serverManager.waitForServerReadyAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ReadinessResult.ready(1)));

        CompletableFuture<ManualRestartOutcome> restartFuture =
                coordinator.requestManualRestart(mapping, targetServer);
        assertEquals(ManualRestartOutcome.RESTARTED_AND_READY, restartFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.READY), coordinator.state("survival"));
    }

    @Test
    void manualRestart_FromStopped_ExecutesStartAndReadiness() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)));
        when(serverManager.startServerAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.RUNNING));
        when(serverManager.waitForServerReadyAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ReadinessResult.ready(1)));

        CompletableFuture<ManualRestartOutcome> restartFuture =
                coordinator.requestManualRestart(mapping, targetServer);
        assertEquals(ManualRestartOutcome.RESTARTED_AND_READY, restartFuture.join());
        verify(serverManager, never()).stopServer(any(ServerMapping.class));
        assertEquals(Optional.of(ServerLifecycleState.READY), coordinator.state("survival"));
    }

    @Test
    void manualRestart_StopFails_HaltsSequenceAndTransitionsToFailed() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        when(targetServer.getPlayersConnected()).thenReturn(List.of());
        when(serverManager.stopServer(mapping)).thenReturn(ContainerStatus.FAILED);

        CompletableFuture<ManualRestartOutcome> restartFuture =
                coordinator.requestManualRestart(mapping, targetServer);
        assertEquals(ManualRestartOutcome.STOP_FAILED, restartFuture.join());
        verify(serverManager, never()).startServerAsync(any(ServerMapping.class));
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
    }

    @Test
    void manualRestart_ReadinessFails_TransitionsToFailed() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        when(targetServer.getPlayersConnected()).thenReturn(List.of());
        when(serverManager.stopServer(mapping)).thenReturn(ContainerStatus.STOPPED);
        when(serverManager.startServerAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.RUNNING));
        when(serverManager.waitForServerReadyAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ReadinessResult.failure(ReadinessResult.Outcome.TIMED_OUT, 1, null)));

        CompletableFuture<ManualRestartOutcome> restartFuture =
                coordinator.requestManualRestart(mapping, targetServer);
        assertEquals(ManualRestartOutcome.SERVER_NOT_READY, restartFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
    }

    @Test
    void manualStart_ContainerMissing() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.MISSING)));

        CompletableFuture<ManualStartOutcome> startFuture = coordinator.requestManualStart(mapping);
        assertEquals(ManualStartOutcome.CONTAINER_MISSING, startFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
    }

    @Test
    void manualStart_DockerInaccessible() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.INACCESSIBLE)));

        CompletableFuture<ManualStartOutcome> startFuture = coordinator.requestManualStart(mapping);
        assertEquals(ManualStartOutcome.DOCKER_INACCESSIBLE, startFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
    }

    @Test
    void manualStart_StartTimedOut() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)));
        when(serverManager.startServerAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.TIMED_OUT));

        CompletableFuture<ManualStartOutcome> startFuture = coordinator.requestManualStart(mapping);
        assertEquals(ManualStartOutcome.START_TIMED_OUT, startFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
    }

    @Test
    void manualStart_ReadinessFails() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)));
        when(serverManager.startServerAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.RUNNING));
        when(serverManager.waitForServerReadyAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ReadinessResult.failure(ReadinessResult.Outcome.TIMED_OUT, 1, null)));

        CompletableFuture<ManualStartOutcome> startFuture = coordinator.requestManualStart(mapping);
        assertEquals(ManualStartOutcome.SERVER_NOT_READY, startFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
    }

    @Test
    void manualStop_StopTimedOut() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        when(targetServer.getPlayersConnected()).thenReturn(List.of());
        when(serverManager.stopServer(mapping)).thenReturn(ContainerStatus.TIMED_OUT);

        CompletableFuture<ManualStopOutcome> stopFuture =
                coordinator.requestManualStop(mapping, targetServer);
        assertEquals(ManualStopOutcome.STOP_TIMED_OUT, stopFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
    }

    @Test
    void manualRestart_ContainerMissing() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        when(targetServer.getPlayersConnected()).thenReturn(List.of());
        when(serverManager.stopServer(mapping)).thenReturn(ContainerStatus.MISSING);

        CompletableFuture<ManualRestartOutcome> restartFuture =
                coordinator.requestManualRestart(mapping, targetServer);
        assertEquals(ManualRestartOutcome.CONTAINER_MISSING, restartFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
    }

    @Test
    void manualRestart_DockerInaccessible() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        when(targetServer.getPlayersConnected()).thenReturn(List.of());
        when(serverManager.stopServer(mapping)).thenReturn(ContainerStatus.INACCESSIBLE);

        CompletableFuture<ManualRestartOutcome> restartFuture =
                coordinator.requestManualRestart(mapping, targetServer);
        assertEquals(ManualRestartOutcome.DOCKER_INACCESSIBLE, restartFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
    }

    @Test
    void manualRestart_StopTimedOut() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        when(targetServer.getPlayersConnected()).thenReturn(List.of());
        when(serverManager.stopServer(mapping)).thenReturn(ContainerStatus.TIMED_OUT);

        CompletableFuture<ManualRestartOutcome> restartFuture =
                coordinator.requestManualRestart(mapping, targetServer);
        assertEquals(ManualRestartOutcome.STOP_TIMED_OUT, restartFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
    }

    @Test
    void manualRestart_StartTimedOut() {
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)));
        when(serverManager.startServerAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.TIMED_OUT));

        CompletableFuture<ManualRestartOutcome> restartFuture =
                coordinator.requestManualRestart(mapping, targetServer);
        assertEquals(ManualRestartOutcome.START_TIMED_OUT, restartFuture.join());
        assertEquals(Optional.of(ServerLifecycleState.FAILED), coordinator.state("survival"));
    }

    @Test
    void hold_Release_IsHeld_Delegation() {
        assertFalse(coordinator.isHeld("survival"));
        assertTrue(coordinator.hold(mapping));
        assertTrue(coordinator.isHeld("survival"));
        assertFalse(coordinator.hold(mapping));
        assertTrue(coordinator.release("survival"));
        assertFalse(coordinator.isHeld("survival"));
        assertFalse(coordinator.release("survival"));
    }

    @Test
    void manualStop_ConvenienceOverload_UsesServerManagerLookup() {
        when(serverManager.getServer("survival")).thenReturn(Optional.of(targetServer));
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        PlayerHarness player = player("p1");
        coordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        when(targetServer.getPlayersConnected()).thenReturn(List.of());
        when(serverManager.stopServer(mapping)).thenReturn(ContainerStatus.STOPPED);

        CompletableFuture<ManualStopOutcome> stopFuture = coordinator.requestManualStop(mapping);
        assertEquals(ManualStopOutcome.STOPPED, stopFuture.join());
    }

    @Test
    void manualRestart_ConvenienceOverload_UsesServerManagerLookup() {
        when(serverManager.getServer("survival")).thenReturn(Optional.of(targetServer));
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)));
        when(serverManager.startServerAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.RUNNING));
        when(serverManager.waitForServerReadyAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ReadinessResult.ready(1)));

        CompletableFuture<ManualRestartOutcome> restartFuture = coordinator.requestManualRestart(mapping);
        assertEquals(ManualRestartOutcome.RESTARTED_AND_READY, restartFuture.join());
    }

    @Test
    void sharedStartup_RecordsOneStartupOperationAndIndividualWaiterOutcomes() {
        CompletableFuture<Optional<ContainerStatus>> statusFuture = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync(mapping)).thenReturn(statusFuture);
        when(serverManager.startServerAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.RUNNING));
        when(serverManager.waitForServerReadyAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ReadinessResult.ready(1)));

        PlayerHarness p1 = player("p1");
        PlayerHarness p2 = player("p2");

        CompletableFuture<ConnectionOutcome> f1 = coordinator.requestConnection(p1.player, targetServer, mapping);
        CompletableFuture<ConnectionOutcome> f2 = coordinator.requestConnection(p2.player, targetServer, mapping);

        statusFuture.complete(Optional.of(ContainerStatus.STOPPED));
        p1.complete(ConnectionRequestBuilder.Status.SUCCESS);
        p2.complete(ConnectionRequestBuilder.Status.SUCCESS);

        assertEquals(ConnectionOutcome.CONNECTED, f1.join());
        assertEquals(ConnectionOutcome.CONNECTED, f2.join());

        TelemetrySnapshot snapshot = coordinator.snapshotTelemetry();
        assertEquals(1, snapshot.operationCount(TelemetryOperationType.STARTUP));
        assertEquals(1, snapshot.outcomeCount(TelemetryOperationType.STARTUP, TelemetryOutcome.READY));
        assertEquals(2, snapshot.operationCount(TelemetryOperationType.CONNECTION_WAIT));
        assertEquals(2, snapshot.outcomeCount(TelemetryOperationType.CONNECTION_WAIT, TelemetryOutcome.CONNECTED));
    }

    @Test
    void manualLifecycleOperations_RecordTelemetryOutcomes() {
        when(serverManager.getServer("survival")).thenReturn(Optional.of(targetServer));
        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)));
        when(serverManager.startServerAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.RUNNING));
        when(serverManager.waitForServerReadyAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ReadinessResult.ready(1)));

        CompletableFuture<ManualStartOutcome> startFuture = coordinator.requestManualStart(mapping);
        assertEquals(ManualStartOutcome.READY, startFuture.join());

        when(targetServer.getPlayersConnected()).thenReturn(List.of());
        when(serverManager.stopServer(mapping)).thenReturn(ContainerStatus.STOPPED);

        CompletableFuture<ManualStopOutcome> stopFuture = coordinator.requestManualStop(mapping);
        assertEquals(ManualStopOutcome.STOPPED, stopFuture.join());

        CompletableFuture<ManualRestartOutcome> restartFuture = coordinator.requestManualRestart(mapping);
        assertEquals(ManualRestartOutcome.RESTARTED_AND_READY, restartFuture.join());

        TelemetrySnapshot snapshot = coordinator.snapshotTelemetry();
        assertEquals(1, snapshot.operationCount(TelemetryOperationType.MANUAL_START));
        assertEquals(1, snapshot.outcomeCount(TelemetryOperationType.MANUAL_START, TelemetryOutcome.READY));
        assertEquals(1, snapshot.operationCount(TelemetryOperationType.MANUAL_STOP));
        assertEquals(1, snapshot.outcomeCount(TelemetryOperationType.MANUAL_STOP, TelemetryOutcome.STOPPED));
        assertEquals(1, snapshot.operationCount(TelemetryOperationType.MANUAL_RESTART));
        assertEquals(1, snapshot.outcomeCount(TelemetryOperationType.MANUAL_RESTART, TelemetryOutcome.RESTARTED_AND_READY));
    }

    @Test
    void rejectedAdmissions_RecordTelemetryOutcomes() {
        coordinator.shutdown();

        PlayerHarness player = player("rejected");
        CompletableFuture<ConnectionOutcome> connFuture = coordinator.requestConnection(player.player, targetServer, mapping);
        assertEquals(ConnectionOutcome.PROXY_SHUTDOWN, connFuture.join());

        CompletableFuture<ManualStartOutcome> startFuture = coordinator.requestManualStart(mapping);
        assertEquals(ManualStartOutcome.PROXY_SHUTDOWN, startFuture.join());

        CompletableFuture<ManualStopOutcome> stopFuture = coordinator.requestManualStop(mapping);
        assertEquals(ManualStopOutcome.PROXY_SHUTDOWN, stopFuture.join());

        CompletableFuture<ManualRestartOutcome> restartFuture = coordinator.requestManualRestart(mapping);
        assertEquals(ManualRestartOutcome.PROXY_SHUTDOWN, restartFuture.join());

        TelemetrySnapshot snapshot = coordinator.snapshotTelemetry();
        assertEquals(1, snapshot.outcomeCount(TelemetryOperationType.CONNECTION_WAIT, TelemetryOutcome.PROXY_SHUTDOWN));
        assertEquals(1, snapshot.outcomeCount(TelemetryOperationType.MANUAL_START, TelemetryOutcome.PROXY_SHUTDOWN));
        assertEquals(1, snapshot.outcomeCount(TelemetryOperationType.MANUAL_STOP, TelemetryOutcome.PROXY_SHUTDOWN));
        assertEquals(1, snapshot.outcomeCount(TelemetryOperationType.MANUAL_RESTART, TelemetryOutcome.PROXY_SHUTDOWN));
    }

    @Test
    void telemetryObservationalSafety_CoordinatorCompletesNormallyEvenIfTelemetryFails() {
        Logger brokenLogger = mock(Logger.class);
        when(brokenLogger.isInfoEnabled()).thenThrow(new RuntimeException("logger failure"));
        LifecycleTelemetryService telemetryService = new LifecycleTelemetryService(brokenLogger);

        ServerLifecycleCoordinator brokenCoordinator = new ServerLifecycleCoordinator(
                logger, serverManager, new ServerHoldRegistry(), new AutoStopperExecutor(),
                System::nanoTime, telemetryService);

        when(serverManager.getServerStatusAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        when(serverManager.waitForServerReadyAsync(mapping))
                .thenReturn(CompletableFuture.completedFuture(ReadinessResult.ready(1)));
        PlayerHarness player = player("safe");
        CompletableFuture<ConnectionOutcome> future = brokenCoordinator.requestConnection(player.player, targetServer, mapping);
        player.complete(ConnectionRequestBuilder.Status.SUCCESS);

        assertEquals(ConnectionOutcome.CONNECTED, future.join());
    }

    private PlayerHarness player(String name) {
        Player player = mock(Player.class, name);
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class, name + "-request");
        CompletableFuture<ConnectionRequestBuilder.Result> connection = new CompletableFuture<>();
        lenient().when(player.getUniqueId())
                .thenReturn(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        lenient().when(player.isActive()).thenReturn(true);
        lenient().when(player.getCurrentServer()).thenReturn(Optional.of(mock(ServerConnection.class)));
        lenient().when(player.createConnectionRequest(targetServer)).thenReturn(request);
        lenient().when(request.connect()).thenReturn(connection);
        return new PlayerHarness(player, connection);
    }

    private PlayerHarness initialPlayer(String name) {
        PlayerHarness player = player(name);
        when(player.player.getCurrentServer()).thenReturn(Optional.empty());
        return player;
    }

    private boolean containsCancelled(Component message) {
        return plainText(message).toLowerCase().contains("cancelled");
    }

    private boolean containsCouldNotConnect(Component message) {
        return plainText(message).contains("Could not connect");
    }

    private boolean containsRefused(Component message) {
        return plainText(message).toLowerCase().contains("refused");
    }

    private boolean containsContainerStopped(Component message) {
        return plainText(message).toLowerCase().contains("container stopped");
    }

    private List<String> sentMessages(Player player) {
        return mockingDetails(player).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sendMessage"))
                .map(invocation -> plainText((Component) invocation.getArgument(0)))
                .toList();
    }

    private record PlayerHarness(Player player,
            CompletableFuture<ConnectionRequestBuilder.Result> connection) {
        private void complete(ConnectionRequestBuilder.Status status) {
            ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
            when(result.getStatus()).thenReturn(status);
            connection.complete(result);
        }
    }
}
