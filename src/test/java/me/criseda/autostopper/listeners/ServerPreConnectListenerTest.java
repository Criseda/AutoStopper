package me.criseda.autostopper.listeners;

import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.lifecycle.ConnectionOutcome;
import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServerPreConnectListenerTest {
    @Mock
    private ServerManager serverManager;

    @Mock
    private ServerLifecycleCoordinator lifecycleCoordinator;

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

    private ServerPreConnectListener listener;
    private ServerMapping mapping;

    @BeforeEach
    void setUp() {
        listener = new ServerPreConnectListener(serverManager, lifecycleCoordinator, activityTracker);
        mapping = new ServerMapping("testserver", "test-container");

        lenient().when(event.getPlayer()).thenReturn(player);
        lenient().when(event.getResult())
                .thenReturn(ServerPreConnectEvent.ServerResult.allowed(targetServer));
        lenient().when(targetServer.getServerInfo()).thenReturn(serverInfo);
        lenient().when(serverInfo.getName()).thenReturn("testserver");
        lenient().when(lifecycleCoordinator.consumeReconnectPermit(player, "testserver"))
                .thenReturn(false);
        lenient().when(serverManager.getServerMapping("testserver")).thenReturn(Optional.of(mapping));
    }

    @Test
    void monitoredConnectionIsDeniedAndDelegatedWithoutBlocking() {
        CompletableFuture<ConnectionOutcome> outcome = new CompletableFuture<>();
        when(lifecycleCoordinator.requestConnection(player, targetServer, mapping)).thenReturn(outcome);

        listener.onServerPreConnect(event);

        verify(event).setResult(ServerPreConnectEvent.ServerResult.denied());
        verify(lifecycleCoordinator).requestConnection(player, targetServer, mapping);
        verifyNoInteractions(activityTracker);

        outcome.complete(ConnectionOutcome.CONNECTED);
        verify(activityTracker).updateActivity("testserver");
    }

    @Test
    void unsuccessfulObservedConnectionDoesNotRecordActivity() {
        when(lifecycleCoordinator.requestConnection(player, targetServer, mapping))
                .thenReturn(CompletableFuture.completedFuture(ConnectionOutcome.CONNECTION_CANCELLED));

        listener.onServerPreConnect(event);

        verify(event).setResult(ServerPreConnectEvent.ServerResult.denied());
        verifyNoInteractions(activityTracker);
    }

    @Test
    void deniedConnectionFromAnotherPluginIsUntouched() {
        when(event.getResult()).thenReturn(ServerPreConnectEvent.ServerResult.denied());

        listener.onServerPreConnect(event);

        verifyNoInteractions(serverManager, lifecycleCoordinator, activityTracker);
        verify(event, never()).setResult(any());
    }

    @Test
    void unmonitoredTargetKeepsVelocityConnectionSemantics() {
        when(serverManager.getServerMapping("testserver")).thenReturn(Optional.empty());

        listener.onServerPreConnect(event);

        verify(lifecycleCoordinator, never()).requestConnection(any(), any(), any());
        verify(event, never()).setResult(any());
        verifyNoInteractions(activityTracker);
    }

    @Test
    void coordinatorReconnectPermitPassesThroughExactlyOnce() {
        when(lifecycleCoordinator.consumeReconnectPermit(player, "testserver")).thenReturn(true);

        listener.onServerPreConnect(event);

        verify(serverManager, never()).getServerMapping(anyString());
        verify(lifecycleCoordinator, never()).requestConnection(any(), any(), any());
        verify(event, never()).setResult(any());
    }

    @Test
    void finalReroutedTargetIsUsed() {
        RegisteredServer rerouted = mock(RegisteredServer.class);
        ServerInfo reroutedInfo = mock(ServerInfo.class);
        ServerMapping reroutedMapping = new ServerMapping("rerouted", "rerouted-container");
        when(event.getResult()).thenReturn(ServerPreConnectEvent.ServerResult.allowed(rerouted));
        when(rerouted.getServerInfo()).thenReturn(reroutedInfo);
        when(reroutedInfo.getName()).thenReturn("rerouted");
        when(lifecycleCoordinator.consumeReconnectPermit(player, "rerouted")).thenReturn(false);
        when(serverManager.getServerMapping("rerouted")).thenReturn(Optional.of(reroutedMapping));
        when(lifecycleCoordinator.requestConnection(player, rerouted, reroutedMapping))
                .thenReturn(CompletableFuture.completedFuture(ConnectionOutcome.CONNECTED));

        listener.onServerPreConnect(event);

        verify(lifecycleCoordinator).requestConnection(player, rerouted, reroutedMapping);
        verify(activityTracker).updateActivity("rerouted");
    }
}
