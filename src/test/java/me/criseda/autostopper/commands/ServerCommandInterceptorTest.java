package me.criseda.autostopper.commands;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
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
    private CommandManager commandManager;
    
    @Mock
    private CommandMeta.Builder metaBuilder;
    
    @Mock
    private CommandMeta commandMeta;
    
    @Mock
    private Logger logger;
    
    @Mock
    private Scheduler scheduler;
    
    @Mock
    private Scheduler.TaskBuilder taskBuilder;
    
    @Mock
    private ScheduledTask scheduledTask;
    
    private SimpleCommand capturedCommand;

    @BeforeEach
    public void setup() {
        // Configure lenient mocks for common setup
        lenient().when(proxyServer.getCommandManager()).thenReturn(commandManager);
        lenient().when(commandManager.metaBuilder(anyString())).thenReturn(metaBuilder);
        lenient().when(metaBuilder.aliases(anyString(), anyString())).thenReturn(metaBuilder);
        lenient().when(metaBuilder.plugin(any())).thenReturn(metaBuilder);
        lenient().when(metaBuilder.build()).thenReturn(commandMeta);
        lenient().when(plugin.getLogger()).thenReturn(logger);
        lenient().when(proxyServer.getScheduler()).thenReturn(scheduler);
        lenient().when(scheduler.buildTask(any(), any(Runnable.class))).thenReturn(taskBuilder);
        lenient().when(taskBuilder.schedule()).thenReturn(scheduledTask);
        
        // Create the interceptor
        new ServerCommandInterceptor(proxyServer, plugin, serverManager, activityTracker);
        
        // Capture the registered command for later use
        ArgumentCaptor<SimpleCommand> commandCaptor = ArgumentCaptor.forClass(SimpleCommand.class);
        verify(commandManager).register(eq(commandMeta), commandCaptor.capture());
        capturedCommand = commandCaptor.getValue();
    }
    
    @Test
    public void testNonPlayerExecution() {
        // Arrange
        CommandSource source = mock(CommandSource.class);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"someserver"});
        
        // Act
        capturedCommand.execute(invocation);
        
        // Assert
        verify(source).sendMessage(any(Component.class));
        verify(commandManager, never()).executeAsync(any(), anyString());
    }
    
    @Test
    public void testNoArgumentsExecution() {
        // Arrange
        Player player = mock(Player.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{});
        
        // Act
        capturedCommand.execute(invocation);
        
        // Assert
        verify(commandManager).executeAsync(eq(player), eq("server "));
    }
    
    @Test
    public void testMultipleArgumentsExecution() {
        // Arrange
        Player player = mock(Player.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"server1", "extra", "args"});
        
        // Act
        capturedCommand.execute(invocation);
        
        // Assert
        verify(commandManager).executeAsync(eq(player), eq("server server1 extra args"));
    }
    
    @Test
    public void testUnknownServerExecution() {
        // Arrange
        Player player = mock(Player.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"unknownserver"});
        when(proxyServer.getServer("unknownserver")).thenReturn(Optional.empty());
        
        // Act
        capturedCommand.execute(invocation);
        
        // Assert
        verify(commandManager).executeAsync(eq(player), eq("server unknownserver"));
    }
    
    @Test
    public void testNonMonitoredServerExecution() {
        // Arrange
        Player player = mock(Player.class);
        RegisteredServer registeredServer = mock(RegisteredServer.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"normalserver"});
        
        when(proxyServer.getServer("normalserver")).thenReturn(Optional.of(registeredServer));
        when(serverManager.isMonitoredServer("normalserver")).thenReturn(false);
        
        // Act
        capturedCommand.execute(invocation);
        
        // Assert
        verify(commandManager).executeAsync(eq(player), eq("server normalserver"));
    }
    
    @Test
    public void testRunningMonitoredServerExecution() {
        // Arrange
        Player player = mock(Player.class);
        RegisteredServer registeredServer = mock(RegisteredServer.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"runningserver"});
        
        when(proxyServer.getServer("runningserver")).thenReturn(Optional.of(registeredServer));
        when(serverManager.isMonitoredServer("runningserver")).thenReturn(true);
        when(serverManager.isServerRunning("runningserver")).thenReturn(true);
        
        // Act
        capturedCommand.execute(invocation);
        
        // Assert
        verify(commandManager).executeAsync(eq(player), eq("server runningserver"));
    }
    
    @Test
    public void testStoppedMonitoredServerExecution_AlreadyStarting() {
        // Arrange
        Player player = mock(Player.class);
        RegisteredServer registeredServer = mock(RegisteredServer.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"stoppedserver"});
        
        when(proxyServer.getServer("stoppedserver")).thenReturn(Optional.of(registeredServer));
        when(serverManager.isMonitoredServer("stoppedserver")).thenReturn(true);
        when(serverManager.isServerRunning("stoppedserver")).thenReturn(false);
        
        AtomicBoolean isStarting = new AtomicBoolean(true);
        when(serverManager.getServerStartingStatus("stoppedserver")).thenReturn(isStarting);
        
        // Act
        capturedCommand.execute(invocation);
        
        // Assert
        verify(player, times(1)).sendMessage(argThat(component -> 
            component.toString().contains("already being started") || 
            component.toString().contains("wait")));
        verify(scheduler, never()).buildTask(any(), any(Runnable.class));
    }
    
    @Test
    public void testStoppedMonitoredServerExecution_SuccessfulStart() {
        // Arrange
        Player player = mock(Player.class);
        RegisteredServer registeredServer = mock(RegisteredServer.class);
        ConnectionRequestBuilder connectionRequest = mock(ConnectionRequestBuilder.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"stoppedserver"});
        
        when(proxyServer.getServer("stoppedserver")).thenReturn(Optional.of(registeredServer));
        when(serverManager.isMonitoredServer("stoppedserver")).thenReturn(true);
        when(serverManager.isServerRunning("stoppedserver")).thenReturn(false);
        
        AtomicBoolean isStarting = new AtomicBoolean(false);
        when(serverManager.getServerStartingStatus("stoppedserver")).thenReturn(isStarting);
        when(serverManager.waitForServerReady(eq("stoppedserver"), anyInt())).thenReturn(true);
        when(player.createConnectionRequest(any(RegisteredServer.class))).thenReturn(connectionRequest);
        
        // Act
        capturedCommand.execute(invocation);
        
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
        RegisteredServer registeredServer = mock(RegisteredServer.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"stoppedserver"});
        
        when(proxyServer.getServer("stoppedserver")).thenReturn(Optional.of(registeredServer));
        when(serverManager.isMonitoredServer("stoppedserver")).thenReturn(true);
        when(serverManager.isServerRunning("stoppedserver")).thenReturn(false);
        
        AtomicBoolean isStarting = new AtomicBoolean(false);
        when(serverManager.getServerStartingStatus("stoppedserver")).thenReturn(isStarting);
        when(serverManager.waitForServerReady(eq("stoppedserver"), anyInt())).thenReturn(false);
        
        // Act
        capturedCommand.execute(invocation);
        
        // Capture and execute the runnable
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).buildTask(eq(plugin), runnableCaptor.capture());
        runnableCaptor.getValue().run();
        
        // Assert
        verify(serverManager).startServer("stoppedserver");
        verify(serverManager).waitForServerReady("stoppedserver", 120);
        verify(player, times(2)).sendMessage(any(Component.class));
        verify(player, never()).createConnectionRequest(any());
        
        // Verify isStarting flag was reset
        assertFalse(isStarting.get(), "isStarting flag should be reset after failure");
    }
    
    @Test
    public void testStoppedMonitoredServerExecution_ExceptionDuringStart() {
        // Arrange
        Player player = mock(Player.class);
        RegisteredServer registeredServer = mock(RegisteredServer.class);
        SimpleCommand.Invocation invocation = mockInvocation(player, new String[]{"stoppedserver"});
        
        when(proxyServer.getServer("stoppedserver")).thenReturn(Optional.of(registeredServer));
        when(serverManager.isMonitoredServer("stoppedserver")).thenReturn(true);
        when(serverManager.isServerRunning("stoppedserver")).thenReturn(false);
        
        AtomicBoolean isStarting = new AtomicBoolean(false);
        when(serverManager.getServerStartingStatus("stoppedserver")).thenReturn(isStarting);
        doThrow(new RuntimeException("Test exception")).when(serverManager).startServer("stoppedserver");
        
        // Act
        capturedCommand.execute(invocation);
        
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
        // Act
        List<String> suggestions = capturedCommand.suggest(
            mockInvocation(mock(Player.class), new String[]{"partial"}));
        
        // Assert
        assertTrue(suggestions.isEmpty(), "Suggestions should be empty");
    }
    
    @Test
    public void testCommandPermission() {
        // Act
        boolean hasPermission = capturedCommand.hasPermission(
            mockInvocation(mock(Player.class), new String[]{}));
        
        // Assert
        assertTrue(hasPermission, "Should always have permission");
    }
    
    // Helper method
    private SimpleCommand.Invocation mockInvocation(CommandSource source, String[] args) {
        SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
        lenient().when(invocation.source()).thenReturn(source);
        lenient().when(invocation.arguments()).thenReturn(args);
        return invocation;
    }
}