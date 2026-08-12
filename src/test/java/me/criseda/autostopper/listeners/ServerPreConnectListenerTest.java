package me.criseda.autostopper.listeners;

import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import me.criseda.autostopper.AutoStopperPlugin;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;
import net.kyori.adventure.text.Component;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
    private Logger logger;

    @Mock
    private ConnectionRequestBuilder connectionRequest;

    @Mock
    private ConnectionRequestBuilder.Result connectionResult;

    private ServerPreConnectListener listener;

    private AtomicBoolean isStarting;
    private CompletableFuture<ConnectionRequestBuilder.Result> connectionResultFuture;

    @BeforeEach
    public void setup() {
        listener = new ServerPreConnectListener(plugin, serverManager, activityTracker);

        // Common setup for all tests
        lenient().when(event.getPlayer()).thenReturn(player);
        lenient().when(event.getResult()).thenReturn(ServerPreConnectEvent.ServerResult.allowed(targetServer));
        lenient().when(targetServer.getServerInfo()).thenReturn(serverInfo);
        lenient().when(serverInfo.getName()).thenReturn("testserver");
        lenient().when(player.createConnectionRequest(targetServer)).thenReturn(connectionRequest);
        connectionResultFuture = new CompletableFuture<>();
        lenient().when(connectionRequest.connect()).thenReturn(connectionResultFuture);
        lenient().when(connectionResult.isSuccessful()).thenReturn(true);
        isStarting = new AtomicBoolean(false);
    }

    @Test
    public void testOnServerPreConnect_ServerRunning() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        when(serverManager.getServerStatusAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));

        // Execute
        listener.onServerPreConnect(event);

        // Verify
        verify(serverManager).isMonitoredServer("testserver");
        verify(serverManager).getServerStatusAsync("testserver");
        verify(serverManager, never()).startServerAsync(anyString());
        verify(event).setResult(eq(ServerPreConnectEvent.ServerResult.denied()));
        verify(player).createConnectionRequest(targetServer);
        verify(connectionRequest).connect();
        assertFalse(isStarting.get(), "starting flag should be cleared after reconnecting");
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
        verify(serverManager, never()).getServerStatusAsync(anyString());
        verify(event, never()).setResult(any(ServerPreConnectEvent.ServerResult.class));
        verifyNoInteractions(activityTracker);
    }

    @Test
    public void testOnServerPreConnect_AlreadyStarting() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        isStarting.set(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);

        // Execute
        listener.onServerPreConnect(event);

        // Verify
        verify(serverManager).getServerStartingStatus("testserver");
        verify(serverManager, never()).getServerStatusAsync(anyString());
        verify(player).sendMessage(argThat(component ->
                component.toString().contains("already being started") || component.toString().contains("wait")));
        verify(event).setResult(eq(ServerPreConnectEvent.ServerResult.denied()));
        verifyNoMoreInteractions(plugin);
    }

    @Test
    public void testOnServerPreConnect_ReturnsPromptlyWhileStatusInFlight() {
        // Setup - a controlled future that stays incomplete while the handler runs
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        CompletableFuture<Optional<ContainerStatus>> pendingStatus = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync("testserver")).thenReturn(pendingStatus);

        // Execute - the handler must return without waiting on docker I/O
        listener.onServerPreConnect(event);

        // Handler returned and deferred resolution: connection denied, flag held, nothing sent yet
        verify(event).setResult(eq(ServerPreConnectEvent.ServerResult.denied()));
        assertTrue(isStarting.get(), "starting flag should stay set while status is in flight");
        verify(player, never()).sendMessage(any(Component.class));
        verify(player, never()).createConnectionRequest(any());

        // Now resolve the status asynchronously
        pendingStatus.complete(Optional.of(ContainerStatus.RUNNING));

        verify(player).createConnectionRequest(targetServer);
        verify(connectionRequest).connect();
        assertFalse(isStarting.get(), "starting flag should be cleared after resolution");
    }

    @Test
    public void testOnServerPreConnect_StartServerSuccess_ServerReady() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        when(serverManager.getServerStatusAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)));
        when(serverManager.startServerAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.RUNNING));
        when(serverManager.waitForServerReadyAsync(eq("testserver"), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // Execute
        listener.onServerPreConnect(event);

        // Verify start chain
        verify(serverManager).startServerAsync("testserver");
        verify(serverManager).waitForServerReadyAsync("testserver", 120);
        verify(player).createConnectionRequest(targetServer);
        verify(connectionRequest).connect();
        verify(activityTracker).updateActivity("testserver");

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(player, times(2)).sendMessage(messageCaptor.capture());
        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(messages.get(0).toString().contains("offline"), "should announce startup");
        assertTrue(messages.get(1).toString().contains("now ready"), "should announce readiness");
        assertFalse(isStarting.get(), "starting flag should be cleared after the full chain");
    }

    @Test
    public void testOnServerPreConnect_StartServerSuccess_ServerNotReady() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        when(serverManager.getServerStatusAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)));
        when(serverManager.startServerAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.RUNNING));
        when(serverManager.waitForServerReadyAsync(eq("testserver"), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(false));

        // Execute
        listener.onServerPreConnect(event);

        // Verify
        verify(serverManager).startServerAsync("testserver");
        verify(serverManager).waitForServerReadyAsync("testserver", 120);
        verify(player, never()).createConnectionRequest(any());
        verifyNoInteractions(activityTracker);

        verify(player, times(3)).sendMessage(any(Component.class)); // offline + not ready + try again
        assertFalse(isStarting.get());
    }

    @Test
    public void testOnServerPreConnect_StartServerFailed() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        when(serverManager.getServerStatusAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)));
        when(serverManager.startServerAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(ContainerStatus.FAILED));

        // Execute
        listener.onServerPreConnect(event);

        // Verify
        verify(serverManager).startServerAsync("testserver");
        verify(serverManager, never()).waitForServerReadyAsync(anyString(), anyInt());
        verify(player, never()).createConnectionRequest(any());
        verify(player).sendMessage(argThat(component -> component.toString().contains("Failed to start server")));
        verifyNoInteractions(activityTracker);
        assertFalse(isStarting.get());
    }

    @Test
    public void testOnServerPreConnect_StartServerThrows() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        when(serverManager.getServerStatusAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.STOPPED)));
        CompletableFuture<ContainerStatus> failedStart = new CompletableFuture<>();
        when(serverManager.startServerAsync("testserver")).thenReturn(failedStart);
        when(plugin.getLogger()).thenReturn(logger);

        // Execute
        listener.onServerPreConnect(event);

        // Complete the start future with a failure
        failedStart.completeExceptionally(new RuntimeException("Test exception"));

        // Verify
        verify(logger).error(eq("Error while starting server {}"), eq("testserver"), any(RuntimeException.class));
        verify(player).sendMessage(argThat(component -> component.toString().contains("Error starting server")));
        verify(player, never()).createConnectionRequest(any());
        assertFalse(isStarting.get());
    }

    @Test
    public void testOnServerPreConnect_StatusCheckThrows() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        CompletableFuture<Optional<ContainerStatus>> failedStatus = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync("testserver")).thenReturn(failedStatus);
        when(plugin.getLogger()).thenReturn(logger);

        // Execute
        listener.onServerPreConnect(event);

        // Complete the status future with a failure
        failedStatus.completeExceptionally(new RuntimeException("daemon exploded"));

        // Verify
        verify(logger).error(eq("Error while checking status for server {}"), eq("testserver"),
                any(RuntimeException.class));
        verify(player).sendMessage(argThat(component ->
                component.toString().contains("Error checking status")));
        verify(serverManager, never()).startServerAsync(anyString());
        assertFalse(isStarting.get());
    }

    @Test
    public void testOnServerPreConnect_SaturatedStatusCheck() {
        // Setup
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        CompletableFuture<Optional<ContainerStatus>> saturated = new CompletableFuture<>();
        when(serverManager.getServerStatusAsync("testserver")).thenReturn(saturated);

        // Execute
        listener.onServerPreConnect(event);

        saturated.completeExceptionally(new AutoStopperExecutor.SaturationException("busy", null));

        // Verify
        verify(plugin, never()).getLogger();
        verify(player).sendMessage(argThat(component -> component.toString().contains("overloaded")));
        verify(serverManager, never()).startServerAsync(anyString());
        assertFalse(isStarting.get());
    }

    @Test
    public void testOnServerPreConnect_DeniedEventDoesNotStart() {
        // Setup - another plugin denied the connection
        when(event.getResult()).thenReturn(ServerPreConnectEvent.ServerResult.denied());

        // Execute
        listener.onServerPreConnect(event);

        // Verify
        verify(serverManager, never()).isMonitoredServer(anyString());
        verify(serverManager, never()).getServerStatusAsync(anyString());
        verify(serverManager, never()).startServerAsync(anyString());
        verify(event, never()).setResult(any(ServerPreConnectEvent.ServerResult.class));
        verify(player, never()).sendMessage(any(Component.class));
        verifyNoInteractions(activityTracker);
    }

    @Test
    public void testOnServerPreConnect_ReroutedTarget() {
        // Setup - another plugin rerouted the event to a monitored server
        RegisteredServer reroutedServer = mock(RegisteredServer.class);
        ServerInfo reroutedInfo = mock(ServerInfo.class);
        when(reroutedServer.getServerInfo()).thenReturn(reroutedInfo);
        when(reroutedInfo.getName()).thenReturn("rerouted");
        when(event.getResult()).thenReturn(ServerPreConnectEvent.ServerResult.allowed(reroutedServer));

        when(serverManager.isMonitoredServer("rerouted")).thenReturn(true);
        when(serverManager.getServerStartingStatus("rerouted")).thenReturn(isStarting);
        when(serverManager.getServerStatusAsync("rerouted"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));
        when(player.createConnectionRequest(reroutedServer)).thenReturn(connectionRequest);

        // Execute
        listener.onServerPreConnect(event);

        // Verify the final allowed target was inspected, not the original server
        verify(serverManager).isMonitoredServer("rerouted");
        verify(serverManager).getServerStatusAsync("rerouted");
        verify(serverManager, never()).isMonitoredServer("testserver");
        verify(player).createConnectionRequest(reroutedServer);
    }

    @Test
    public void testOnServerPreConnect_ProgrammaticReconnectPassesThroughOnce() {
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        when(serverManager.getServerStatusAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.RUNNING)));

        // The original request is denied while its status is checked.
        listener.onServerPreConnect(event);
        verify(event).setResult(eq(ServerPreConnectEvent.ServerResult.denied()));
        verify(connectionRequest).connect();

        // Feeding the listener-created request back through the event must be
        // allowed without another inspect/deny/reconnect cycle.
        listener.onServerPreConnect(event);
        verify(event, times(1)).setResult(any(ServerPreConnectEvent.ServerResult.class));
        verify(serverManager, times(1)).getServerStatusAsync("testserver");
        verify(connectionRequest, times(1)).connect();

        connectionResultFuture.complete(connectionResult);
    }

    @Test
    public void testOnServerPreConnect_ReroutedToUnmonitoredTarget() {
        // Setup - another plugin rerouted the event to an unmonitored server
        RegisteredServer reroutedServer = mock(RegisteredServer.class);
        ServerInfo reroutedInfo = mock(ServerInfo.class);
        when(reroutedServer.getServerInfo()).thenReturn(reroutedInfo);
        when(reroutedInfo.getName()).thenReturn("hub");
        when(event.getResult()).thenReturn(ServerPreConnectEvent.ServerResult.allowed(reroutedServer));

        when(serverManager.isMonitoredServer("hub")).thenReturn(false);

        // Execute
        listener.onServerPreConnect(event);

        // Verify
        verify(serverManager).isMonitoredServer("hub");
        verify(serverManager, never()).getServerStatusAsync(anyString());
        verify(serverManager, never()).getServerStartingStatus(anyString());
        verify(serverManager, never()).startServerAsync(anyString());
        verify(event, never()).setResult(any(ServerPreConnectEvent.ServerResult.class));
        verifyNoInteractions(activityTracker);
    }

    @Test
    public void testOnServerPreConnect_MissingContainerDoesNotStart() {
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        when(serverManager.getServerStatusAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.MISSING)));

        listener.onServerPreConnect(event);

        verify(serverManager, never()).startServerAsync(anyString());
        verify(player).sendMessage(argThat(component -> component.toString().contains("does not exist")));
        assertFalse(isStarting.get());
    }

    @Test
    public void testOnServerPreConnect_InaccessibleDockerDoesNotStart() {
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        when(serverManager.getServerStatusAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.INACCESSIBLE)));

        listener.onServerPreConnect(event);

        verify(serverManager, never()).startServerAsync(anyString());
        verify(player).sendMessage(argThat(component -> component.toString().contains("Docker daemon")));
        assertFalse(isStarting.get());
    }

    @Test
    public void testOnServerPreConnect_TimedOutStatusDoesNotStart() {
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        when(serverManager.getServerStatusAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.TIMED_OUT)));

        listener.onServerPreConnect(event);

        verify(serverManager, never()).startServerAsync(anyString());
        verify(player).sendMessage(argThat(component -> component.toString().contains("Try again")));
        assertFalse(isStarting.get());
    }

    @Test
    public void testOnServerPreConnect_FailedStatusDoesNotStart() {
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        when(serverManager.getServerStatusAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(ContainerStatus.FAILED)));

        listener.onServerPreConnect(event);

        verify(serverManager, never()).startServerAsync(anyString());
        verify(player).sendMessage(argThat(component ->
                component.toString().contains("Could not check the status")));
        assertFalse(isStarting.get());
    }

    @Test
    public void testOnServerPreConnect_NoMappingDoesNotStart() {
        when(serverManager.isMonitoredServer("testserver")).thenReturn(true);
        when(serverManager.getServerStartingStatus("testserver")).thenReturn(isStarting);
        when(serverManager.getServerStatusAsync("testserver"))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        listener.onServerPreConnect(event);

        verify(serverManager, never()).startServerAsync(anyString());
        verify(player).sendMessage(argThat(component -> component.toString().contains("no container mapping")));
        assertFalse(isStarting.get());
    }
}
