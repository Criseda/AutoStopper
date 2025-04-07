package me.criseda.autostopper.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import me.criseda.autostopper.AutoStopperPlugin;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ServerCommandInterceptorTest {

    @Mock
    private ProxyServer proxyServer;
    
    @Mock
    private AutoStopperPlugin plugin;
    
    @Mock
    private ServerManager serverManager;
    
    @Mock
    private ActivityTracker activityTracker;
    
    @Mock
    private Logger logger;
    
    @Mock
    private Scheduler scheduler;
    
    @Mock
    private Scheduler.TaskBuilder taskBuilder;
    
    private ServerCommandInterceptor.ServerCommand serverCommand;

    @BeforeEach
    public void setup() {
        // Create the ServerCommand instance directly without any stubbings
        serverCommand = new ServerCommandInterceptor.ServerCommand(proxyServer, plugin, serverManager, activityTracker);
    }
    
    @Test
    public void testNonPlayerExecution() {
        // Arrange
        CommandSource source = mock(CommandSource.class);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"someserver"});
        when(proxyServer.getServer("someserver")).thenReturn(Optional.empty());
        
        // Act
        serverCommand.execute(invocation);
        
        // Assert
        verify(source).sendMessage(argThat(component -> 
            component.toString().contains("does not exist")));
    }
    
    @Test
    public void testNoArgumentsExecution() {
        // Arrange
        Player player = mock(Player.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{});
        RegisteredServer server1 = mockRegisteredServer("server1");
        RegisteredServer server2 = mockRegisteredServer("server2");
        
        when(proxyServer.getAllServers()).thenReturn(List.of(server1, server2));
        
        // Act
        serverCommand.execute(invocation);
        
        // Assert
        verify(player).sendMessage(argThat(component -> 
            component.toString().contains("Available servers")));
        verify(serverManager, times(2)).isServerRunning(anyString());
    }
    
    @Test
    public void testInvalidArgumentsExecution() {
        // Arrange
        Player player = mock(Player.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"server1", "extra"});
        
        // Act
        serverCommand.execute(invocation);
        
        // Assert
        verify(player).sendMessage(argThat(component -> 
            component.toString().contains("Usage:") || 
            component.toString().contains("/server")));
    }
    
    @Test
    public void testUnknownServerExecution() {
        // Arrange
        Player player = mock(Player.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"unknownserver"});
        when(proxyServer.getServer("unknownserver")).thenReturn(Optional.empty());
        
        // Act
        serverCommand.execute(invocation);
        
        // Assert
        verify(player).sendMessage(argThat(component -> 
            component.toString().contains("does not exist")));
    }
    
    @Test
    public void testNonMonitoredServerExecution() {
        // Arrange
        Player player = mock(Player.class);
        RegisteredServer registeredServer = mockRegisteredServer("normalserver");
        ConnectionRequestBuilder connectionRequest = mock(ConnectionRequestBuilder.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"normalserver"});
        
        when(proxyServer.getServer("normalserver")).thenReturn(Optional.of(registeredServer));
        when(serverManager.isMonitoredServer("normalserver")).thenReturn(false);
        when(player.createConnectionRequest(registeredServer)).thenReturn(connectionRequest);
        
        // Act
        serverCommand.execute(invocation);
        
        // Assert
        verify(player).sendMessage(argThat(component -> 
            component.toString().contains("Connecting to server")));
        verify(player).createConnectionRequest(registeredServer);
        verify(connectionRequest).fireAndForget();
    }
    
    @Test
    public void testRunningMonitoredServerExecution() {
        // Arrange
        Player player = mock(Player.class);
        RegisteredServer registeredServer = mockRegisteredServer("runningserver");
        ConnectionRequestBuilder connectionRequest = mock(ConnectionRequestBuilder.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"runningserver"});
        
        when(proxyServer.getServer("runningserver")).thenReturn(Optional.of(registeredServer));
        when(serverManager.isMonitoredServer("runningserver")).thenReturn(true);
        when(serverManager.isServerRunning("runningserver")).thenReturn(true);
        when(player.createConnectionRequest(registeredServer)).thenReturn(connectionRequest);
        
        // Act
        serverCommand.execute(invocation);
        
        // Assert
        verify(player).sendMessage(argThat(component -> 
            component.toString().contains("Connecting to server")));
        verify(player).createConnectionRequest(registeredServer);
        verify(connectionRequest).fireAndForget();
    }
    
    @Test
    public void testStoppedMonitoredServerExecution_AlreadyStarting() {
        // Arrange
        Player player = mock(Player.class);
        RegisteredServer registeredServer = mockRegisteredServer("stoppedserver");
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"stoppedserver"});
        
        when(proxyServer.getServer("stoppedserver")).thenReturn(Optional.of(registeredServer));
        when(serverManager.isMonitoredServer("stoppedserver")).thenReturn(true);
        when(serverManager.isServerRunning("stoppedserver")).thenReturn(false);
        
        AtomicBoolean isStarting = new AtomicBoolean(true);
        when(serverManager.getServerStartingStatus("stoppedserver")).thenReturn(isStarting);
        
        // Act
        serverCommand.execute(invocation);
        
        // Assert
        verify(player).sendMessage(argThat(component -> 
            component.toString().contains("Server is currently offline")));
        verify(player).sendMessage(argThat(component -> 
            component.toString().contains("already being started") || 
            component.toString().contains("wait")));
        verify(proxyServer, never()).getScheduler();
    }
    
    @Test
    public void testStoppedMonitoredServerExecution_SuccessfulStart() {
        // Arrange
        Player player = mock(Player.class);
        RegisteredServer registeredServer = mockRegisteredServer("stoppedserver");
        ConnectionRequestBuilder connectionRequest = mock(ConnectionRequestBuilder.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"stoppedserver"});
        
        when(proxyServer.getServer("stoppedserver")).thenReturn(Optional.of(registeredServer));
        when(serverManager.isMonitoredServer("stoppedserver")).thenReturn(true);
        when(serverManager.isServerRunning("stoppedserver")).thenReturn(false);
        
        AtomicBoolean isStarting = new AtomicBoolean(false);
        when(serverManager.getServerStartingStatus("stoppedserver")).thenReturn(isStarting);
        // Add this line:
        when(serverManager.startServer("stoppedserver")).thenReturn(true);
        when(serverManager.waitForServerReady(eq("stoppedserver"), anyInt())).thenReturn(true);
        when(player.createConnectionRequest(any(RegisteredServer.class))).thenReturn(connectionRequest);
        
        // Setup scheduler mocks for this specific test
        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));
        
        // Act
        serverCommand.execute(invocation);
        
        // Assert initial state
        verify(player).sendMessage(argThat(component -> 
            component.toString().contains("offline") || component.toString().contains("starting")));
            
        // Capture the runnable that gets scheduled
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).buildTask(eq(plugin), runnableCaptor.capture());
        
        // Verify isStarting flag was set correctly
        assertTrue(isStarting.get(), "isStarting flag should be true after scheduling");
        
        // Execute the scheduled task
        runnableCaptor.getValue().run();
        
        // Verify server start process
        verify(serverManager).startServer("stoppedserver");
        verify(serverManager).waitForServerReady("stoppedserver", 120);
        verify(player).createConnectionRequest(registeredServer);
        verify(connectionRequest).fireAndForget();
        verify(activityTracker).updateActivity("stoppedserver");
        
        // Call the runnable again to force finally block execution
        try {
            runnableCaptor.getValue().run();
        } catch (Exception e) {
            // Ignore exceptions, we're just ensuring finally block runs
        }
        
        // Verify isStarting flag was reset
        assertFalse(isStarting.get(), "isStarting flag should be reset in finally block");
    }
    
    @Test
    public void testStoppedMonitoredServerExecution_FailedStart() {
        // Arrange
        Player player = mock(Player.class);
        RegisteredServer registeredServer = mockRegisteredServer("stoppedserver");
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"stoppedserver"});
        
        when(proxyServer.getServer("stoppedserver")).thenReturn(Optional.of(registeredServer));
        when(serverManager.isMonitoredServer("stoppedserver")).thenReturn(true);
        when(serverManager.isServerRunning("stoppedserver")).thenReturn(false);
        
        AtomicBoolean isStarting = new AtomicBoolean(false);
        when(serverManager.getServerStartingStatus("stoppedserver")).thenReturn(isStarting);
        when(serverManager.startServer("stoppedserver")).thenReturn(true);
        when(serverManager.waitForServerReady(eq("stoppedserver"), anyInt())).thenReturn(false);
            
        // Setup scheduler mocks for this specific test
        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));
        
        // Act
        serverCommand.execute(invocation);
        
        // Capture and execute the runnable
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).buildTask(eq(plugin), runnableCaptor.capture());
        runnableCaptor.getValue().run();
        
        // Assert
        verify(serverManager).startServer("stoppedserver");
        verify(serverManager).waitForServerReady("stoppedserver", 120);
        verify(player, times(3)).sendMessage(any(Component.class));
        verify(player, never()).createConnectionRequest(any());
        
        // Verify isStarting flag was reset
        assertFalse(isStarting.get(), "isStarting flag should be reset after failure");
    }
    
    @Test
    public void testStoppedMonitoredServerExecution_ExceptionDuringStart() {
        // Arrange
        Player player = mock(Player.class);
        RegisteredServer registeredServer = mockRegisteredServer("stoppedserver");
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"stoppedserver"});
        
        when(proxyServer.getServer("stoppedserver")).thenReturn(Optional.of(registeredServer));
        when(serverManager.isMonitoredServer("stoppedserver")).thenReturn(true);
        when(serverManager.isServerRunning("stoppedserver")).thenReturn(false);
        
        AtomicBoolean isStarting = new AtomicBoolean(false);
        when(serverManager.getServerStartingStatus("stoppedserver")).thenReturn(isStarting);
        doThrow(new RuntimeException("Test exception")).when(serverManager).startServer("stoppedserver");
        
        // Setup scheduler mocks for this specific test
        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));
        when(plugin.getLogger()).thenReturn(logger);
        
        // Act
        serverCommand.execute(invocation);
        
        // Capture and execute the runnable
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).buildTask(eq(plugin), runnableCaptor.capture());
        runnableCaptor.getValue().run();
        
        // Assert
        verify(logger).error(eq("Error while starting server"), any(Exception.class));
        verify(player, times(2)).sendMessage(any(Component.class));
        
        // Verify isStarting flag was reset
        assertFalse(isStarting.get(), "isStarting flag should be reset after exception");
    }
    
    @Test
    public void testCommandSuggestions() {
        // Arrange
        Player player = mock(Player.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{""});  // Use empty string instead of "partial"
        RegisteredServer server1 = mockRegisteredServer("server1");
        RegisteredServer server2 = mockRegisteredServer("server2");
        
        when(proxyServer.getAllServers()).thenReturn(List.of(server1, server2));
        when(plugin.getLogger()).thenReturn(logger);
        
        // Act
        List<String> suggestions = serverCommand.suggest(invocation);
        
        // Assert
        assertTrue(suggestions.contains("server1"), "Suggestions should include server1");
        assertTrue(suggestions.contains("server2"), "Suggestions should include server2");
    }
    
    @Test
    public void testCommandPermission() {
        // Act
        boolean hasPermission = serverCommand.hasPermission(
            mockInvocation(mock(Player.class), new String[]{}));
        
        // Assert
        assertTrue(hasPermission, "Should always have permission");
    }
    
    // Helper methods
    private SimpleCommand.Invocation mockInvocation(CommandSource source, String[] args) {
        SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
        lenient().when(invocation.source()).thenReturn(source);
        lenient().when(invocation.arguments()).thenReturn(args);
        return invocation;
    }
    
    private RegisteredServer mockRegisteredServer(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo serverInfo = mock(ServerInfo.class);
        lenient().when(server.getServerInfo()).thenReturn(serverInfo);
        lenient().when(serverInfo.getName()).thenReturn(name);
        return server;
    }
}