package me.criseda.autostopper;

import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.api.scheduler.Scheduler.TaskBuilder;
import com.velocitypowered.api.scheduler.ScheduledTask;
import me.criseda.autostopper.commands.AutoStopperCommand;
import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.listeners.ConnectionListener;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AutoStopperPluginTest {

    // Use a real temporary directory instead of a mock
    @TempDir
    Path tempDir;

    @Mock
    private ProxyServer server;

    @Mock
    private Logger logger;

    @Mock
    private EventManager eventManager;

    @Mock
    private CommandManager commandManager;

    @Mock
    private CommandMeta.Builder commandMetaBuilder;

    @Mock
    private CommandMeta commandMeta;

    @Mock
    private AutoStopperConfig config;

    @Mock
    private ServerManager serverManager;

    @Mock
    private ActivityTracker activityTracker;

    // Add mocks for scheduler
    @Mock
    private Scheduler scheduler;

    @Mock
    private TaskBuilder taskBuilder;

    @Mock
    private TaskBuilder repeatingTaskBuilder;

    @Mock
    private ScheduledTask scheduledTask;

    private AutoStopperPlugin plugin;
    private AutoStopperPlugin spyPlugin;

    @BeforeEach
    void setUp() {
        // Mock chain
        when(server.getEventManager()).thenReturn(eventManager);
        when(server.getCommandManager()).thenReturn(commandManager);
        when(commandManager.metaBuilder(anyString())).thenReturn(commandMetaBuilder);

        // Fix for the varargs version of aliases()
        when(commandMetaBuilder.aliases(any(String[].class))).thenReturn(commandMetaBuilder);

        when(commandMetaBuilder.plugin(any())).thenReturn(commandMetaBuilder);
        when(commandMetaBuilder.build()).thenReturn(commandMeta);

        // Setup scheduler mock chain
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(any(), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.repeat(anyLong(), any(TimeUnit.class))).thenReturn(repeatingTaskBuilder);
        when(repeatingTaskBuilder.schedule()).thenReturn(scheduledTask);

        // Create plugin instance with real temp directory
        plugin = new AutoStopperPlugin(server, logger, tempDir);

        // Create a spy of the plugin to allow partial mocking
        spyPlugin = spy(plugin);

        // Inject mocked dependencies
        doReturn(config).when(spyPlugin).getConfig();
        doReturn(serverManager).when(spyPlugin).getServerManager();
        doReturn(activityTracker).when(spyPlugin).getActivityTracker();
    }

    @Test
    void testOnProxyInitialize() {
        // Prepare
        ProxyInitializeEvent event = mock(ProxyInitializeEvent.class);
        
        // Create mocks for the objects that will be created during initialization
        AutoStopperConfig mockConfig = mock(AutoStopperConfig.class);
        ServerManager mockServerManager = mock(ServerManager.class);
        ActivityTracker mockActivityTracker = mock(ActivityTracker.class);
        
        // Create a partial mock of the plugin to stub out the object creation
        AutoStopperPlugin spyPlugin = spy(plugin);
        
        // Stub the creation of new objects
        doReturn(mockConfig).when(spyPlugin).createConfig();
        doReturn(mockServerManager).when(spyPlugin).createServerManager(mockConfig);
        doReturn(mockActivityTracker).when(spyPlugin).createActivityTracker(mockConfig, mockServerManager);
        
        // Execute
        spyPlugin.onProxyInitialize(event);
        
        // Verify
        verify(logger).info("AutoStopper plugin initializing...");
        verify(logger).info("AutoStopper plugin initialized!");
        verify(eventManager).register(eq(spyPlugin), any(ConnectionListener.class));
        verify(commandManager).register(eq(commandMeta), any(AutoStopperCommand.class));
        verify(mockActivityTracker).startInactivityCheck();
    }

    @Test
    void testGetters() {
        // Inject mocked dependencies for the real plugin
        setPrivateField(plugin, "config", config);
        setPrivateField(plugin, "serverManager", serverManager);
        setPrivateField(plugin, "activityTracker", activityTracker);

        // Execute & Verify
        assertEquals(server, plugin.getServer());
        assertEquals(logger, plugin.getLogger());
        assertEquals(config, plugin.getConfig());
        assertEquals(serverManager, plugin.getServerManager());
        assertEquals(activityTracker, plugin.getActivityTracker());
    }
    
    @Test
    void testCreateConfig() {
        // Execute
        AutoStopperConfig createdConfig = plugin.createConfig();
        
        // Verify
        assertNotNull(createdConfig, "Created config should not be null");
        
        // The config should be initialized with the plugin's data directory and logger
        assertEquals(tempDir, getPrivateField(createdConfig, "dataDirectory"));
        assertEquals(logger, getPrivateField(createdConfig, "logger"));
    }
    
    @Test
    void testCreateServerManager() {
        // Prepare
        when(config.getServerNames()).thenReturn(new String[]{"test-server"});
        when(config.getServerToContainerMap()).thenReturn(java.util.Map.of("test-server", "test-container"));
        
        // Execute
        ServerManager createdServerManager = plugin.createServerManager(config);
        
        // Verify
        assertNotNull(createdServerManager, "Created server manager should not be null");
        assertEquals(server, getPrivateField(createdServerManager, "server"));
        assertEquals(logger, getPrivateField(createdServerManager, "logger"));
        assertEquals(config, getPrivateField(createdServerManager, "config"));
    }
    
    @Test
    void testCreateActivityTracker() {
        // Prepare - important to set up configuration for activity tracker initialization
        when(config.getInactivityTimeout()).thenReturn(300);
        when(config.getServerNames()).thenReturn(new String[]{"test-server"});
        Map<String, String> serverMap = new HashMap<>();
        serverMap.put("test-server", "test-container");
        when(config.getServerToContainerMap()).thenReturn(serverMap);
        
        // Execute
        ActivityTracker createdActivityTracker = plugin.createActivityTracker(config, serverManager);
        
        // Verify
        assertNotNull(createdActivityTracker, "Created activity tracker should not be null");
        assertEquals(server, getPrivateField(createdActivityTracker, "server"));
        assertEquals(logger, getPrivateField(createdActivityTracker, "logger"));
        assertEquals(config, getPrivateField(createdActivityTracker, "config"));
        assertEquals(serverManager, getPrivateField(createdActivityTracker, "serverManager"));
    }

    // Helper method to set private fields for testing
    private void setPrivateField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
    
    // Helper method to get private field values for testing
    private <T> T getPrivateField(Object target, String fieldName) {
        try {
            java.lang.reflect.Field field = getFieldFromClassHierarchy(target.getClass(), fieldName);
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            T value = (T) field.get(target);
            return value;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get field: " + fieldName, e);
        }
    }
    
    // Helper method to find a field in the class hierarchy
    private java.lang.reflect.Field getFieldFromClassHierarchy(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass == null) {
                throw e;
            }
            return getFieldFromClassHierarchy(superClass, fieldName);
        }
    }
}