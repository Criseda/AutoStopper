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
    private me.criseda.autostopper.lifecycle.ServerHoldRegistry holdRegistry;
    private me.criseda.autostopper.server.ServerManager serverManager;

    @BeforeEach
    public void setup() {
        lenient().when(pluginContainer.getDescription()).thenReturn(pluginDescription);
        lenient().when(pluginDescription.getVersion()).thenReturn(Optional.of("1.1.2"));
        lenient().when(source.getPermissionValue(anyString())).thenReturn(Tristate.UNDEFINED);
        lenient().when(operationalStatus.runPreflight(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(new PreflightSummary(1, 0)));
        holdRegistry = new me.criseda.autostopper.lifecycle.ServerHoldRegistry();
        lenient().when(lifecycleCoordinator.hold(any())).thenAnswer(inv -> holdRegistry.hold(inv.getArgument(0)));
        lenient().when(lifecycleCoordinator.release(anyString())).thenAnswer(inv -> holdRegistry.release(inv.getArgument(0)));
        lenient().when(lifecycleCoordinator.isHeld(anyString())).thenAnswer(inv -> holdRegistry.isHeld(inv.getArgument(0)));

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
        verify(source, times(10)).sendMessage(messageCaptor.capture());

        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("AutoStopper Commands"));
        assertTrue(plainText(messages.get(1)).contains("/autostopper"));
        assertTrue(plainText(messages.get(2)).contains("/autostopper help"));
        assertTrue(plainText(messages.get(3)).contains("/autostopper status"));
        assertTrue(plainText(messages.get(4)).contains("/autostopper reload"));
        assertTrue(plainText(messages.get(5)).contains("/autostopper start <server>"));
        assertTrue(plainText(messages.get(6)).contains("/autostopper stop <server>"));
        assertTrue(plainText(messages.get(7)).contains("/autostopper restart <server>"));
        assertTrue(plainText(messages.get(8)).contains("/autostopper hold <server>"));
        assertTrue(plainText(messages.get(9)).contains("/autostopper release <server>"));
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

        assertEquals(8, suggestions.size());
        assertTrue(suggestions.contains("help"));
        assertTrue(suggestions.contains("status"));
        assertTrue(suggestions.contains("reload"));
        assertTrue(suggestions.contains("start"));
        assertTrue(suggestions.contains("stop"));
        assertTrue(suggestions.contains("restart"));
        assertTrue(suggestions.contains("hold"));
        assertTrue(suggestions.contains("release"));
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

    @Test
    public void testExecuteStart_PermissionDenied() {
        deny(AutoStopperCommand.START_PERMISSION);
        command.execute(mockInvocation(source, new String[]{"start", "survival"}));
        assertPermissionDenied("start a server");
    }

    @Test
    public void testExecuteStart_MissingArgs() {
        grant(AutoStopperCommand.START_PERMISSION);
        command.execute(mockInvocation(source, new String[]{"start"}));
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(captor.capture());
        assertTrue(plainText(captor.getValue()).contains("Usage: /autostopper start <server>"));
    }

    @Test
    public void testExecuteStart_UnmappedServer() {
        grant(AutoStopperCommand.START_PERMISSION);
        when(config.snapshot()).thenReturn(snapshot("survival"));
        command.execute(mockInvocation(source, new String[]{"start", "creative"}));
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(captor.capture());
        assertTrue(plainText(captor.getValue()).contains("Server creative is not mapped in AutoStopper."));
    }

    @Test
    public void testExecuteStart_Success() {
        grant(AutoStopperCommand.START_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        when(lifecycleCoordinator.requestManualStart(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.completedFuture(me.criseda.autostopper.lifecycle.ManualStartOutcome.READY));

        command.execute(mockInvocation(source, new String[]{"start", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendMessage(captor.capture());
        List<Component> messages = captor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("Waking survival"));
        assertTrue(plainText(messages.get(1)).contains("Server survival is now ready!"));
    }

    @Test
    public void testExecuteStart_AlreadyReady() {
        grant(AutoStopperCommand.START_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        when(lifecycleCoordinator.requestManualStart(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.completedFuture(me.criseda.autostopper.lifecycle.ManualStartOutcome.ALREADY_READY));

        command.execute(mockInvocation(source, new String[]{"start", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendMessage(captor.capture());
        List<Component> messages = captor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("Waking survival"));
        assertTrue(plainText(messages.get(1)).contains("Server survival is already ready."));
    }

    @Test
    public void testExecuteStop_PermissionDenied() {
        deny(AutoStopperCommand.STOP_PERMISSION);
        command.execute(mockInvocation(source, new String[]{"stop", "survival"}));
        assertPermissionDenied("stop a server");
    }

    @Test
    public void testExecuteStop_MissingArgs() {
        grant(AutoStopperCommand.STOP_PERMISSION);
        command.execute(mockInvocation(source, new String[]{"stop"}));
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(captor.capture());
        assertTrue(plainText(captor.getValue()).contains("Usage: /autostopper stop <server>"));
    }

    @Test
    public void testExecuteStop_UnmappedServer() {
        grant(AutoStopperCommand.STOP_PERMISSION);
        when(config.snapshot()).thenReturn(snapshot("survival"));
        command.execute(mockInvocation(source, new String[]{"stop", "creative"}));
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(captor.capture());
        assertTrue(plainText(captor.getValue()).contains("Server creative is not mapped in AutoStopper."));
    }

    @Test
    public void testExecuteStop_Success() {
        grant(AutoStopperCommand.STOP_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        when(lifecycleCoordinator.requestManualStop(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.completedFuture(me.criseda.autostopper.lifecycle.ManualStopOutcome.STOPPED));

        command.execute(mockInvocation(source, new String[]{"stop", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendMessage(captor.capture());
        List<Component> messages = captor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("Stopping survival"));
        assertTrue(plainText(messages.get(1)).contains("Stopped server survival"));
        verify(activityTracker).removeActivity("survival");
    }

    @Test
    public void testExecuteStop_RefusedPlayers() {
        grant(AutoStopperCommand.STOP_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        when(lifecycleCoordinator.requestManualStop(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.completedFuture(me.criseda.autostopper.lifecycle.ManualStopOutcome.PLAYERS_CONNECTED));

        command.execute(mockInvocation(source, new String[]{"stop", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendMessage(captor.capture());
        List<Component> messages = captor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("Stopping survival"));
        assertTrue(plainText(messages.get(1)).contains("Cannot stop server survival"));
        assertTrue(plainText(messages.get(1)).contains("currently connected"));
    }

    @Test
    public void testExecuteRestart_PermissionDenied() {
        deny(AutoStopperCommand.RESTART_PERMISSION);
        command.execute(mockInvocation(source, new String[]{"restart", "survival"}));
        assertPermissionDenied("restart a server");
    }

    @Test
    public void testExecuteRestart_Success() {
        grant(AutoStopperCommand.RESTART_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        when(lifecycleCoordinator.requestManualRestart(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.completedFuture(me.criseda.autostopper.lifecycle.ManualRestartOutcome.RESTARTED_AND_READY));

        command.execute(mockInvocation(source, new String[]{"restart", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendMessage(captor.capture());
        List<Component> messages = captor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("Restarting survival"));
        assertTrue(plainText(messages.get(1)).contains("Restarted server survival"));
    }

    @Test
    public void testExecuteHold_Success() {
        grant(AutoStopperCommand.HOLD_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);

        command.execute(mockInvocation(source, new String[]{"hold", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(captor.capture());
        assertTrue(plainText(captor.getValue()).contains("Automatic shutdown held for server survival."));
        assertTrue(holdRegistry.isHeld("survival"));
    }

    @Test
    public void testExecuteHold_AlreadyActive() {
        grant(AutoStopperCommand.HOLD_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        holdRegistry.hold(snapshot.server("survival").orElseThrow());

        command.execute(mockInvocation(source, new String[]{"hold", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(captor.capture());
        assertTrue(plainText(captor.getValue()).contains("Automatic shutdown is already held for server survival."));
    }

    @Test
    public void testExecuteRelease_Success() {
        grant(AutoStopperCommand.RELEASE_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        holdRegistry.hold(snapshot.server("survival").orElseThrow());

        command.execute(mockInvocation(source, new String[]{"release", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(captor.capture());
        assertTrue(plainText(captor.getValue()).contains("Automatic shutdown hold released for server survival."));
        assertFalse(holdRegistry.isHeld("survival"));
        verify(activityTracker).updateActivity("survival");
    }

    @Test
    public void testExecuteRelease_NotActive() {
        grant(AutoStopperCommand.RELEASE_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);

        command.execute(mockInvocation(source, new String[]{"release", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(captor.capture());
        assertTrue(plainText(captor.getValue()).contains("Server survival does not have an active hold."));
    }

    @Test
    public void testSuggest_SubcommandsWithPermissions() {
        grant(AutoStopperCommand.START_PERMISSION);
        grant(AutoStopperCommand.STOP_PERMISSION);
        grant(AutoStopperCommand.RESTART_PERMISSION);
        grant(AutoStopperCommand.HOLD_PERMISSION);
        grant(AutoStopperCommand.RELEASE_PERMISSION);

        List<String> suggestions = command.suggest(mockInvocation(source, new String[]{""}));
        assertTrue(suggestions.contains("start"));
        assertTrue(suggestions.contains("stop"));
        assertTrue(suggestions.contains("restart"));
        assertTrue(suggestions.contains("hold"));
        assertTrue(suggestions.contains("release"));
    }

    @Test
    public void testSuggest_ServerNamesForSubcommands() {
        grant(AutoStopperCommand.START_PERMISSION);
        when(config.snapshot()).thenReturn(snapshot("survival", "creative", "skyblock"));

        List<String> suggestions = command.suggest(mockInvocation(source, new String[]{"start", "s"}));
        assertEquals(List.of("skyblock", "survival"), suggestions);
    }

    @Test
    public void testAdminPermissionUmbrellaForSubcommands() {
        grant(AutoStopperCommand.ADMIN_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);

        command.execute(mockInvocation(source, new String[]{"hold", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(captor.capture());
        assertTrue(plainText(captor.getValue()).contains("Automatic shutdown held for server survival."));
    }

    @Test
    public void testExecuteStart_FailureOutcomes() {
        grant(AutoStopperCommand.START_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);

        for (me.criseda.autostopper.lifecycle.ManualStartOutcome outcome : me.criseda.autostopper.lifecycle.ManualStartOutcome.values()) {
            if (outcome == me.criseda.autostopper.lifecycle.ManualStartOutcome.READY
                    || outcome == me.criseda.autostopper.lifecycle.ManualStartOutcome.ALREADY_READY) {
                continue;
            }
            when(lifecycleCoordinator.requestManualStart(snapshot.server("survival").orElseThrow()))
                    .thenReturn(CompletableFuture.completedFuture(outcome));

            command.execute(mockInvocation(source, new String[]{"start", "survival"}));
        }

        // Also test exceptional completion
        when(lifecycleCoordinator.requestManualStart(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));
        command.execute(mockInvocation(source, new String[]{"start", "survival"}));
    }

    @Test
    public void testExecuteStop_FailureOutcomes() {
        grant(AutoStopperCommand.STOP_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);

        for (me.criseda.autostopper.lifecycle.ManualStopOutcome outcome : me.criseda.autostopper.lifecycle.ManualStopOutcome.values()) {
            if (outcome == me.criseda.autostopper.lifecycle.ManualStopOutcome.STOPPED
                    || outcome == me.criseda.autostopper.lifecycle.ManualStopOutcome.ALREADY_STOPPED) {
                continue;
            }
            when(lifecycleCoordinator.requestManualStop(snapshot.server("survival").orElseThrow()))
                    .thenReturn(CompletableFuture.completedFuture(outcome));

            command.execute(mockInvocation(source, new String[]{"stop", "survival"}));
        }

        // Exceptional completion
        when(lifecycleCoordinator.requestManualStop(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));
        command.execute(mockInvocation(source, new String[]{"stop", "survival"}));
    }

    @Test
    public void testExecuteRestart_FailureOutcomes() {
        grant(AutoStopperCommand.RESTART_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);

        for (me.criseda.autostopper.lifecycle.ManualRestartOutcome outcome : me.criseda.autostopper.lifecycle.ManualRestartOutcome.values()) {
            if (outcome == me.criseda.autostopper.lifecycle.ManualRestartOutcome.RESTARTED_AND_READY) {
                continue;
            }
            when(lifecycleCoordinator.requestManualRestart(snapshot.server("survival").orElseThrow()))
                    .thenReturn(CompletableFuture.completedFuture(outcome));

            command.execute(mockInvocation(source, new String[]{"restart", "survival"}));
        }

        // Exceptional completion
        when(lifecycleCoordinator.requestManualRestart(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));
        command.execute(mockInvocation(source, new String[]{"restart", "survival"}));
    }

    @Test
    public void testSuggest_ServerNamesForOtherCommands() {
        grant(AutoStopperCommand.ADMIN_PERMISSION);
        when(config.snapshot()).thenReturn(snapshot("survival", "creative"));

        assertEquals(List.of("survival"), command.suggest(mockInvocation(source, new String[]{"stop", "s"})));
        assertEquals(List.of("survival"), command.suggest(mockInvocation(source, new String[]{"restart", "s"})));
        assertEquals(List.of("survival"), command.suggest(mockInvocation(source, new String[]{"hold", "s"})));
        assertEquals(List.of("survival"), command.suggest(mockInvocation(source, new String[]{"release", "s"})));
    }

    private OperationalServerStatus operational(OperationalState state) {
        return new OperationalServerStatus(state, 0, Optional.empty());
    }
}
