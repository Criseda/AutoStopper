package me.criseda.autostopper.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AutoStopperCommandTest {

    @Mock
    private AutoStopperConfig config;
    
    @Mock
    private ServerManager serverManager;
    
    @Mock
    private ActivityTracker activityTracker;
    
    @Mock
    private CommandSource source;
    
    private AutoStopperCommand command;
    
    @BeforeEach
    public void setup() {
        command = new AutoStopperCommand(config, serverManager, activityTracker);
        // Default setup: user has admin permission
        when(source.hasPermission("autostopper.admin")).thenReturn(true);
    }
    
    @Test
    public void testExecuteWithNoArgs() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{});
        
        // Act
        command.execute(invocation);
        
        // Assert
        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendMessage(messageCaptor.capture());
        
        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(messages.get(0).toString().contains("AutoStopper v1.0"));
        assertTrue(messages.get(1).toString().contains("help"));
    }
    
    @Test
    public void testExecuteWithNoPermission() {
        // Arrange
        when(source.hasPermission("autostopper.admin")).thenReturn(false);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{});
        
        // Act
        command.execute(invocation);
        
        // Assert
        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(messageCaptor.capture());
        
        Component message = messageCaptor.getValue();
        assertTrue(message.toString().contains("don't have permission"));
    }
    
    @Test
    public void testExecuteHelpCommand() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"help"});
        when(source.hasPermission("autostopper.admin")).thenReturn(true); // Explicitly ensure the user has admin permission
        
        // Act
        command.execute(invocation);
        
        // Assert
        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(5)).sendMessage(messageCaptor.capture()); // Changed from times(4) to times(5)
        
        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(messages.get(0).toString().contains("Help"));
        assertTrue(messages.get(1).toString().contains("/autostopper"));
        assertTrue(messages.get(2).toString().contains("help"));
        assertTrue(messages.get(3).toString().contains("status"));
        // You may want to add an assertion for the 5th message as well
        assertTrue(messages.get(4).toString().contains("reload"));
    }
    
    @Test
    public void testExecuteStatusCommand_RunningServerWithActivity() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"status"});
        String[] serverNames = {"server1", "server2"};
        
        when(config.getServerNames()).thenReturn(serverNames);
        when(serverManager.isServerRunning("server1")).thenReturn(true);
        when(activityTracker.getLastActivity("server1")).thenReturn(Instant.now().minusSeconds(300));
        when(activityTracker.getMinutesSinceActivity("server1")).thenReturn(5L);
        
        when(serverManager.isServerRunning("server2")).thenReturn(false);
        
        // Act
        command.execute(invocation);
        
        // Assert
        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(3)).sendMessage(messageCaptor.capture());
        
        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(messages.get(0).toString().contains("Status"));
        assertTrue(messages.get(1).toString().contains("server1"));
        assertTrue(messages.get(1).toString().contains("Running"));
        assertTrue(messages.get(1).toString().contains("5 minutes"));
        assertTrue(messages.get(2).toString().contains("server2"));
        assertTrue(messages.get(2).toString().contains("Stopped"));
    }
    
    @Test
    public void testExecuteStatusCommand_RunningServerWithNoActivity() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"status"});
        String[] serverNames = {"server1"};
        
        when(config.getServerNames()).thenReturn(serverNames);
        when(serverManager.isServerRunning("server1")).thenReturn(true);
        when(activityTracker.getLastActivity("server1")).thenReturn(null);
        
        // Act
        command.execute(invocation);
        
        // Assert
        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendMessage(messageCaptor.capture());
        
        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(messages.get(1).toString().contains("No activity recorded"));
    }
    
    @Test
    public void testExecuteReloadCommand_WithPermission() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"reload"});
        when(source.hasPermission("autostopper.admin")).thenReturn(true);
        
        // Act
        command.execute(invocation);
        
        // Assert
        verify(config).loadConfig();
        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendMessage(messageCaptor.capture());
        
        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(messages.get(0).toString().contains("Reloading"));
        assertTrue(messages.get(1).toString().contains("reloaded successfully"));
    }
    
    @Test
    public void testExecuteReloadCommand_WithoutPermission() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"reload"});
        when(source.hasPermission("autostopper.admin")).thenReturn(false);
        
        // Act
        command.execute(invocation);
        
        // Assert
        verify(config, never()).loadConfig();
        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(messageCaptor.capture());
        
        assertTrue(messageCaptor.getValue().toString().contains("don't have permission"));
    }
    
    @Test
    public void testExecuteUnknownCommand() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"unknown"});
        
        // Act
        command.execute(invocation);
        
        // Assert
        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(messageCaptor.capture());
        
        // Now we expect the "Unknown command" message
        assertTrue(messageCaptor.getValue().toString().contains("Unknown command"));
    }
    
    @Test
    public void testSuggest_FirstArgument() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{""});
        when(source.hasPermission("autostopper.admin")).thenReturn(true);
        
        // Act
        List<String> suggestions = command.suggest(invocation);
        
        // Assert
        assertEquals(3, suggestions.size());
        assertTrue(suggestions.contains("help"));
        assertTrue(suggestions.contains("status"));
        assertTrue(suggestions.contains("reload"));
    }
    
    @Test
    public void testSuggest_WithoutPermission() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{""});
        when(source.hasPermission("autostopper.admin")).thenReturn(false);
        
        // Act
        List<String> suggestions = command.suggest(invocation);
        
        // Assert
        assertTrue(suggestions.isEmpty());
    }
    
    @Test
    public void testSuggest_SecondArgument() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"help", ""});
        
        // Act
        List<String> suggestions = command.suggest(invocation);
        
        // Assert
        assertTrue(suggestions.isEmpty());
    }
    
    @Test
    public void testHasPermission() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{});
        when(source.hasPermission("autostopper.admin")).thenReturn(true);
        
        // Act
        boolean result = command.hasPermission(invocation);
        
        // Assert
        assertTrue(result);
    }
    
    @Test
    public void testHasNoPermission() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{});
        when(source.hasPermission("autostopper.admin")).thenReturn(false);
        
        // Act
        boolean result = command.hasPermission(invocation);
        
        // Assert
        assertFalse(result);
    }
    
    private SimpleCommand.Invocation mockInvocation(CommandSource source, String[] args) {
        SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
        lenient().when(invocation.source()).thenReturn(source);
        lenient().when(invocation.arguments()).thenReturn(args);
        return invocation;
    }
}