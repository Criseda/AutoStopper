package me.criseda.autostopper.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigLoadResult;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;
import me.criseda.autostopper.operational.OperationalServerStatus;
import me.criseda.autostopper.operational.OperationalState;
import me.criseda.autostopper.operational.OperationalStatusService;
import me.criseda.autostopper.operational.PreflightSummary;
import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.server.ActivityTracker;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static me.criseda.autostopper.testing.ComponentTestUtils.plainText;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AutoStopperCommandTest {

    @Mock
    private AutoStopperConfig config;

    @Mock
    private ActivityTracker activityTracker;

    @Mock
    private ServerLifecycleCoordinator lifecycleCoordinator;

    @Mock
    private OperationalStatusService operationalStatus;

    @Mock
    private CommandSource source;

    @Mock
    private PluginContainer pluginContainer;

    @Mock
    private PluginDescription pluginDescription;

    private AutoStopperCommand command;

    @BeforeEach
    public void setup() {
        lenient().when(pluginContainer.getDescription()).thenReturn(pluginDescription);
        lenient().when(pluginDescription.getVersion()).thenReturn(Optional.of("1.1.2"));
        lenient().when(source.getPermissionValue(anyString())).thenReturn(Tristate.UNDEFINED);
        lenient().when(operationalStatus.runPreflight(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(new PreflightSummary(1, 0)));

        command = new AutoStopperCommand(
                config, activityTracker, lifecycleCoordinator,
                operationalStatus, pluginContainer);
    }

    @Test
    public void testExecuteWithNoArgs() {
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{});

        command.execute(invocation);

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(3)).sendMessage(messageCaptor.capture());

        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("AutoStopper 1.1.2"));
        assertTrue(plainText(messages.get(1)).contains("Empty servers sleep. Players wake them."));
        assertTrue(plainText(messages.get(2)).contains("help"));
    }

    @Test
    public void testExecuteWithNoArgs_UnknownVersion() {
        when(pluginDescription.getVersion()).thenReturn(Optional.empty());
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{});

        command.execute(invocation);

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(3)).sendMessage(messageCaptor.capture());

        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("AutoStopper Unknown"));
        assertTrue(plainText(messages.get(1)).contains("Empty servers sleep. Players wake them."));
        assertTrue(plainText(messages.get(2)).contains("help"));
    }

    @Test
    public void testExecuteHelpCommand() {
        grant(AutoStopperCommand.ADMIN_PERMISSION);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"help"});

        command.execute(invocation);

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(5)).sendMessage(messageCaptor.capture());

        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("AutoStopper Commands"));
        assertTrue(plainText(messages.get(1)).contains("/autostopper"));
        assertTrue(plainText(messages.get(2)).contains("/autostopper help"));
        assertTrue(plainText(messages.get(3)).contains("/autostopper status"));
        assertTrue(plainText(messages.get(4)).contains("/autostopper reload"));
    }

    @Test
    public void testExecuteHelpCommand_OnlyShowsPublicCommandsWhenPermissionsAreUndefined() {
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"help"});

        command.execute(invocation);

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(3)).sendMessage(messageCaptor.capture());
        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("AutoStopper Commands"));
        assertTrue(plainText(messages.get(1)).contains("/autostopper"));
        assertTrue(plainText(messages.get(2)).contains("/autostopper help"));
        assertTrue(messages.stream().noneMatch(message -> plainText(message).contains("status")));
        assertTrue(messages.stream().noneMatch(message -> plainText(message).contains("reload")));
    }

    @Test
    public void testExecuteStatusCommand_RunningServerWithActivity() {
        grant(AutoStopperCommand.STATUS_PERMISSION);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"status"});

        ConfigSnapshot snapshot = snapshot("server1", "server2");
        when(config.snapshot()).thenReturn(snapshot);
        CompletableFuture<Map<String, OperationalServerStatus>> statuses = new CompletableFuture<>();
        when(operationalStatus.collectStatuses(snapshot)).thenReturn(statuses);
        when(activityTracker.getLastActivity("server1")).thenReturn(Instant.now().minusSeconds(300));
        when(activityTracker.getMinutesSinceActivity("server1")).thenReturn(5L);

        command.execute(invocation);
        statuses.complete(Map.of(
                "server1", operational(OperationalState.READY),
                "server2", operational(OperationalState.STOPPED)));

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(3)).sendMessage(messageCaptor.capture());

        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("Server status"));
        assertTrue(plainText(messages.get(1)).contains("server1"));
        assertTrue(plainText(messages.get(1)).contains("Ready"));
        assertTrue(plainText(messages.get(1)).contains("active 5m ago"));
        assertTrue(plainText(messages.get(2)).contains("server2"));
        assertTrue(plainText(messages.get(2)).contains("Sleeping"));
    }

    @Test
    public void testExecuteStatusCommand_RunningServerWithNoActivity() {
        grant(AutoStopperCommand.STATUS_PERMISSION);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"status"});

        ConfigSnapshot snapshot = snapshot("server1");
        when(config.snapshot()).thenReturn(snapshot);
        CompletableFuture<Map<String, OperationalServerStatus>> statuses = new CompletableFuture<>();
        when(operationalStatus.collectStatuses(snapshot)).thenReturn(statuses);
        when(activityTracker.getLastActivity("server1")).thenReturn(null);

        command.execute(invocation);
        statuses.complete(Map.of("server1", operational(OperationalState.READY)));

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendMessage(messageCaptor.capture());

        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(plainText(messages.get(1)).contains("no activity recorded"));
    }

    @Test
    public void testExecuteStatusCommand_DistinctTypedStates() {
        grant(AutoStopperCommand.STATUS_PERMISSION);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"status"});
        String[] serverNames = {"s-stopped", "s-starting", "s-ready", "s-unverified", "s-stopping", "s-failed", "s-docker"};

        ConfigSnapshot snapshot = snapshot(serverNames);
        when(config.snapshot()).thenReturn(snapshot);
        CompletableFuture<Map<String, OperationalServerStatus>> statuses = new CompletableFuture<>();
        when(operationalStatus.collectStatuses(snapshot)).thenReturn(statuses);

        command.execute(invocation);
        statuses.complete(new java.util.LinkedHashMap<>(Map.of(
                "s-stopped", operational(OperationalState.STOPPED),
                "s-starting", operational(OperationalState.STARTING),
                "s-ready", operational(OperationalState.READY),
                "s-unverified", operational(OperationalState.RUNNING_UNVERIFIED),
                "s-stopping", operational(OperationalState.STOPPING),
                "s-failed", operational(OperationalState.FAILED),
                "s-docker", operational(OperationalState.DOCKER_UNAVAILABLE))));

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(8)).sendMessage(messageCaptor.capture());

        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(plainText(messages.get(1)).contains("Unavailable · Docker cannot be reached")); // s-docker (sorted first)
        assertTrue(plainText(messages.get(2)).contains("Failed")); // s-failed
        assertTrue(plainText(messages.get(3)).contains("Ready")); // s-ready
        assertTrue(plainText(messages.get(4)).contains("Waking")); // s-starting
        assertTrue(plainText(messages.get(5)).contains("Sleeping")); // s-stopped
        assertTrue(plainText(messages.get(6)).contains("Stopping")); // s-stopping
        assertTrue(plainText(messages.get(7)).contains("Running · readiness unverified")); // s-unverified
    }

    @Test
    public void testExecuteStatusCommand_StatusCollectionFails() {
        grant(AutoStopperCommand.STATUS_PERMISSION);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"status"});

        ConfigSnapshot snapshot = snapshot("server1");
        when(config.snapshot()).thenReturn(snapshot);
        CompletableFuture<Map<String, OperationalServerStatus>> statuses = new CompletableFuture<>();
        when(operationalStatus.collectStatuses(snapshot)).thenReturn(statuses);

        command.execute(invocation);
        statuses.completeExceptionally(new RuntimeException("daemon exploded"));

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendMessage(messageCaptor.capture());
        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(plainText(messages.get(1)).contains("Could not collect server statuses"));
    }

    @Test
    public void testExecuteStatusCommand_ExplicitDenialDoesNotStartStatusChecks() {
        deny(AutoStopperCommand.STATUS_PERMISSION);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"status"});

        command.execute(invocation);

        assertPermissionDenied("view AutoStopper status");
        verifyNoInteractions(config, activityTracker, operationalStatus);
    }

    @Test
    public void testExecuteStatusCommand_UndefinedPermissionDoesNotStartStatusChecks() {
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"status"});

        command.execute(invocation);

        assertPermissionDenied("view AutoStopper status");
        verifyNoInteractions(config, activityTracker, operationalStatus);
    }

    @Test
    public void testExecuteStatusCommand_AdminOverridesSpecificDenial() {
        deny(AutoStopperCommand.STATUS_PERMISSION);
        grant(AutoStopperCommand.ADMIN_PERMISSION);
        ConfigSnapshot snapshot = snapshot();
        when(config.snapshot()).thenReturn(snapshot);
        when(operationalStatus.collectStatuses(snapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of()));
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"status"});

        command.execute(invocation);

        verify(operationalStatus).collectStatuses(snapshot);
        verify(source).sendMessage(argThat(message -> plainText(message).contains("Server status")));
    }

    @Test
    public void testExecuteReloadCommand() {
        grant(AutoStopperCommand.RELOAD_PERMISSION);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"reload"});
        ConfigSnapshot previous = snapshot("server1");
        ConfigSnapshot current = snapshot("server2");
        when(config.snapshot()).thenReturn(previous);
        when(config.loadConfig()).thenReturn(ConfigLoadResult.success(current));

        command.execute(invocation);

        verify(config).loadConfig();
        verify(lifecycleCoordinator).reconcileConfig(previous, current);
        verify(activityTracker).reconcileConfig(previous, current);
        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(3)).sendMessage(messageCaptor.capture());

        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("Reloading"));
        assertTrue(plainText(messages.get(1)).contains("reloaded successfully"));
        assertTrue(plainText(messages.get(2)).contains("preflight passed"));
    }

    @Test
    public void testExecuteReloadCommand_InvalidConfigKeepsPreviousSnapshot() {
        grant(AutoStopperCommand.RELOAD_PERMISSION);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"reload"});
        ConfigSnapshot previous = snapshot("server1");
        when(config.snapshot()).thenReturn(previous);
        when(config.loadConfig()).thenReturn(
                ConfigLoadResult.failure(previous, List.of("monitored_servers[0].server is unknown")));

        command.execute(invocation);

        verify(lifecycleCoordinator, never()).reconcileConfig(any(), any());
        verify(activityTracker, never()).reconcileConfig(any(), any());
        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendMessage(messageCaptor.capture());
        assertTrue(plainText(messageCaptor.getAllValues().get(1)).contains("reload failed"));
        assertTrue(plainText(messageCaptor.getAllValues().get(1)).contains("monitored_servers[0].server"));
    }

    @Test
    public void testExecuteReloadCommand_ExplicitDenialDoesNotLoadConfig() {
        deny(AutoStopperCommand.RELOAD_PERMISSION);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"reload"});

        command.execute(invocation);

        assertPermissionDenied("reload AutoStopper configuration");
        verifyNoInteractions(config, activityTracker, operationalStatus);
    }

    @Test
    public void testExecuteReloadCommand_UndefinedPermissionDoesNotLoadConfig() {
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"reload"});

        command.execute(invocation);

        assertPermissionDenied("reload AutoStopper configuration");
        verifyNoInteractions(config, activityTracker, operationalStatus);
    }

    @Test
    public void testExecuteReloadCommand_AdminOverridesSpecificDenial() {
        deny(AutoStopperCommand.RELOAD_PERMISSION);
        grant(AutoStopperCommand.ADMIN_PERMISSION);
        ConfigSnapshot previous = snapshot("server1");
        ConfigSnapshot current = snapshot("server2");
        when(config.snapshot()).thenReturn(previous);
        when(config.loadConfig()).thenReturn(ConfigLoadResult.success(current));
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"reload"});

        command.execute(invocation);

        verify(config).loadConfig();
        verify(lifecycleCoordinator).reconcileConfig(previous, current);
        verify(activityTracker).reconcileConfig(previous, current);
    }

    @Test
    public void testExecuteUnknownCommand_ClosestMatchSuggestion() {
        grant(AutoStopperCommand.ADMIN_PERMISSION);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"statsu"});

        command.execute(invocation);

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(messageCaptor.capture());

        String message = plainText(messageCaptor.getValue());
        assertTrue(message.contains("Unknown command 'statsu'"));
        assertTrue(message.contains("Did you mean /autostopper status?"));
    }

    @Test
    public void testExecuteUnknownCommand_NoCloseMatchShowsHelpHint() {
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"xyzabc"});

        command.execute(invocation);

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(messageCaptor.capture());

        String message = plainText(messageCaptor.getValue());
        assertTrue(message.contains("Unknown command 'xyzabc'"));
        assertTrue(message.contains("Use /autostopper help"));
    }

    @Test
    public void testSuggest_FirstArgument() {
        grant(AutoStopperCommand.ADMIN_PERMISSION);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{""});

        List<String> suggestions = command.suggest(invocation);

        assertEquals(3, suggestions.size());
        assertTrue(suggestions.contains("help"));
        assertTrue(suggestions.contains("status"));
        assertTrue(suggestions.contains("reload"));
    }

    @Test
    public void testSuggest_WithoutPermission() {
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{""});

        List<String> suggestions = command.suggest(invocation);

        assertEquals(List.of("help"), suggestions);
    }

    @Test
    public void testSuggest_SecondArgument() {
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"help", ""});

        List<String> suggestions = command.suggest(invocation);

        assertTrue(suggestions.isEmpty());
    }

    @Test
    public void testHasPermission() {
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{});

        boolean result = command.hasPermission(invocation);

        assertTrue(result, "hasPermission should always return true to show commands in tab completion");
    }

    private SimpleCommand.Invocation mockInvocation(CommandSource source, String[] args) {
        SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
        lenient().when(invocation.source()).thenReturn(source);
        lenient().when(invocation.arguments()).thenReturn(args);
        return invocation;
    }

    private void grant(String permission) {
        when(source.getPermissionValue(permission)).thenReturn(Tristate.TRUE);
    }

    private void deny(String permission) {
        when(source.getPermissionValue(permission)).thenReturn(Tristate.FALSE);
    }

    private void assertPermissionDenied(String action) {
        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(messageCaptor.capture());
        String message = plainText(messageCaptor.getValue());
        assertTrue(message.contains("permission"));
        assertTrue(message.contains(action));
        assertFalse(message.contains("Docker"));
        assertFalse(message.contains("container"));
    }

    private ConfigSnapshot snapshot(String... serverNames) {
        List<ServerMapping> mappings = java.util.Arrays.stream(serverNames)
                .map(name -> new ServerMapping(name, name + "-container"))
                .toList();
        return new ConfigSnapshot(ConfigSnapshot.DEFAULT_INACTIVITY_TIMEOUT_SECONDS, mappings);
    }

    @Test
    public void testExecuteStatusCommand_IncludesSafeLastFailureAndRemediation() {
        grant(AutoStopperCommand.STATUS_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        OperationalFailure failure = new OperationalFailure(
                Instant.parse("2026-08-12T12:00:00Z"), "startup preflight",
                "permission denied accessing Docker", "Grant Docker socket access.");
        when(operationalStatus.collectStatuses(snapshot)).thenReturn(
                CompletableFuture.completedFuture(Map.of("survival",
                        new OperationalServerStatus(OperationalState.DOCKER_UNAVAILABLE,
                                0, Optional.of(failure)))));

        command.execute(mockInvocation(source, new String[]{"status"}));

        ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendMessage(messages.capture());
        String status = plainText(messages.getAllValues().get(1));
        assertTrue(status.contains("Unavailable · Docker cannot be reached"));
        assertTrue(status.contains("2026-08-12T12:00:00Z"));
        assertTrue(status.contains("Grant Docker socket access"));
        assertFalse(status.contains("/var/run/docker.sock"));
    }

    private OperationalServerStatus operational(OperationalState state) {
        return new OperationalServerStatus(state, 0, Optional.empty());
    }
}
