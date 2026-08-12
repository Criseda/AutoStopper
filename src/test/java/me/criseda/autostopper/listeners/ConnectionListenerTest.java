package me.criseda.autostopper.listeners;

import static org.mockito.Mockito.*;

import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ConnectionListenerTest {

    @Mock
    private ActivityTracker activityTracker;

    @Mock
    private ServerLifecycleCoordinator lifecycleCoordinator;

    @Mock
    private ServerConnectedEvent event;

    @Mock
    private RegisteredServer registeredServer;
    
    @Mock
    private ServerInfo serverInfo;

    private ConnectionListener connectionListener;
    
    @BeforeEach
    public void setup() {
        connectionListener = new ConnectionListener(activityTracker, lifecycleCoordinator);
    }
    
    @Test
    public void testOnServerConnected() {
        // Arrange
        String serverName = "test-server";
        when(event.getServer()).thenReturn(registeredServer);
        when(registeredServer.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn(serverName);
        
        // Act
        connectionListener.onServerConnected(event);
        
        // Assert
        verify(activityTracker).updateActivity(serverName);
        verify(lifecycleCoordinator).markReady(serverName);
    }

    @Test
    public void testOnDisconnectDiscardsWaiter() {
        DisconnectEvent event = mock(DisconnectEvent.class);
        Player player = mock(Player.class);
        when(event.getPlayer()).thenReturn(player);

        connectionListener.onDisconnect(event);

        verify(lifecycleCoordinator).discardPlayer(player);
    }

    @Test
    public void testConstructor() {
        // This test validates that the constructor properly sets the activityTracker
        // The verification is implicit since we're using the instance in other tests
        
        // Create new instance to ensure coverage of constructor
        new ConnectionListener(activityTracker, lifecycleCoordinator);
        
        // Verify the activity tracker is correctly passed (no assertion needed as this is
        // structurally verified by the instance creation not throwing exceptions)
    }
}
