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
        // Removed default permission setup - will be set in individual tests when needed
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
        assertTrue(messages.get(0).toString().contains("AutoStopper v1.1.1"));
        assertTrue(messages.get(1).toString().contains("help"));
    }
    
    @Test
    public void testExecuteHelpCommand() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"help"});
        
        // Act
        command.execute(invocation);
        
        // Assert
        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(5)).sendMessage(messageCaptor.capture());
        
        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(messages.get(0).toString().contains("Help"));
        assertTrue(messages.get(1).toString().contains("/autostopper"));
        assertTrue(messages.get(2).toString().contains("help"));
        assertTrue(messages.get(3).toString().contains("status"));
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
    public void testExecuteReloadCommand() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"reload"});
        
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
        
        // Act
        List<String> suggestions = command.suggest(invocation);
        
        // Assert
        assertEquals(1, suggestions.size());
        assertTrue(suggestions.contains("help"));
    }
    
    @Test
    public void testSuggest_WithoutPermission() {
        // Arrange
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{""});
        
        // Act
        List<String> suggestions = command.suggest(invocation);
        
        // Assert
        // Now we expect suggestions even without permissions
        assertEquals(1, suggestions.size());
        assertTrue(suggestions.contains("help"));
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
        
        // Act
        boolean result = command.hasPermission(invocation);
        
        // Assert - We now expect true since we updated the hasPermission method
        assertTrue(result, "hasPermission should always return true to show commands in tab completion");
    }
    
    private SimpleCommand.Invocation mockInvocation(CommandSource source, String[] args) {
        SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
        lenient().when(invocation.source()).thenReturn(source);
        lenient().when(invocation.arguments()).thenReturn(args);
        return invocation;
    }
}