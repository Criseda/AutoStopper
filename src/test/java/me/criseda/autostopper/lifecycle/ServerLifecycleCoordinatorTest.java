package me.criseda.autostopper.lifecycle;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.server.ServerManager;
import me.criseda.autostopper.readiness.ReadinessResult;
import me.criseda.autostopper.readiness.MinecraftStatusProbe;
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

import static me.criseda.autostopper.testing.ComponentTestUtils.plainText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private PlayerHarness player(String name) {
        Player player = mock(Player.class, name);
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class, name + "-request");
        CompletableFuture<ConnectionRequestBuilder.Result> connection = new CompletableFuture<>();
        lenient().when(player.getUniqueId())
                .thenReturn(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        lenient().when(player.isActive()).thenReturn(true);
        lenient().when(player.createConnectionRequest(targetServer)).thenReturn(request);
        lenient().when(request.connect()).thenReturn(connection);
        return new PlayerHarness(player, connection);
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

    private record PlayerHarness(Player player,
            CompletableFuture<ConnectionRequestBuilder.Result> connection) {
        private void complete(ConnectionRequestBuilder.Status status) {
            ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
            when(result.getStatus()).thenReturn(status);
            connection.complete(result);
        }
    }
}
