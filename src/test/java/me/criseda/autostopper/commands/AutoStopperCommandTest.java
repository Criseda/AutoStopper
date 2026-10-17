package me.criseda.autostopper.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.proxy.Player;
import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigLoadResult;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.lifecycle.ManualStartOutcome;
import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;
import me.criseda.autostopper.operational.OperationalServerStatus;
import me.criseda.autostopper.operational.OperationalState;
import me.criseda.autostopper.operational.OperationalStatusService;
import me.criseda.autostopper.operational.PreflightSummary;
import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.testing.ComponentTestUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
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
        verify(source, times(4)).sendMessage(messageCaptor.capture());

        List<Component> messages = messageCaptor.getAllValues();
        assertEquals("AutoStopper 1.1.2", plainText(messages.get(0)));
        assertTrue(plainText(messages.get(1)).contains("Empty servers sleep. Players wake them."));
        assertEquals("Docs: https://github.com/Criseda/AutoStopper/blob/1.1.2/README.md\n"
                + "GitHub: https://github.com/Criseda/AutoStopper\n"
                + "Modrinth: https://modrinth.com/plugin/autostopper\n"
                + "Changelog: https://github.com/Criseda/AutoStopper/blob/1.1.2/CHANGELOG.md",
                plainText(messages.get(2)));
        assertTrue(plainText(messages.get(3)).contains("help"));
    }

    @Test
    public void testExecuteWithNoArgs_UnknownVersion() {
        when(pluginDescription.getVersion()).thenReturn(Optional.empty());
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{});

        command.execute(invocation);

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(4)).sendMessage(messageCaptor.capture());

        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("AutoStopper Unknown"));
        assertTrue(plainText(messages.get(1)).contains("Empty servers sleep. Players wake them."));
        assertTrue(plainText(messages.get(2)).contains("blob/master/README.md"));
        assertTrue(plainText(messages.get(3)).contains("help"));
    }

    @Test
    public void testExecuteWithNoArgs_PlayerWithoutPermissionsGetsClickableProjectLinks() {
        Player player = mock(Player.class);
        lenient().when(player.getPermissionValue(anyString())).thenReturn(Tristate.UNDEFINED);

        command.execute(mockInvocation(player, new String[]{}));

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(player, times(4)).sendMessage(messageCaptor.capture());
        List<Component> messages = messageCaptor.getAllValues();
        assertEquals("AutoStopper 1.1.2", plainText(messages.get(0)));
        assertEquals("Docs · GitHub · Modrinth · Changelog", plainText(messages.get(2)));
        assertEquals(ClickEvent.openUrl("https://github.com/Criseda/AutoStopper/releases/tag/1.1.2"),
                clickEvent(messages.get(0), "1.1.2"));
        assertEquals(ClickEvent.openUrl("https://github.com/Criseda/AutoStopper/blob/1.1.2/README.md"),
                clickEvent(messages.get(2), "Docs"));
        assertEquals(ClickEvent.openUrl("https://github.com/Criseda/AutoStopper"), clickEvent(messages.get(2), "GitHub"));
        assertEquals(ClickEvent.openUrl("https://modrinth.com/plugin/autostopper"), clickEvent(messages.get(2), "Modrinth"));
        assertEquals(ClickEvent.openUrl("https://github.com/Criseda/AutoStopper/blob/1.1.2/CHANGELOG.md"),
                clickEvent(messages.get(2), "Changelog"));
    }

    @Test
    public void testExecuteHelpCommand() {
        grant(AutoStopperCommand.ADMIN_PERMISSION);
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"help"});

        command.execute(invocation);

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(12)).sendMessage(messageCaptor.capture());

        List<String> lines = messageCaptor.getAllValues().stream().map(ComponentTestUtils::plainText).toList();
        assertEquals(List.of(
                "AutoStopper Commands",
                "General",
                "  /autostopper status · Show the status of every mapped server",
                "  /autostopper reload · Reload and validate the configuration",
                "Lifecycle",
                "  /autostopper start <server> · Start a mapped server and wait until it is ready",
                "  /autostopper stop <server> · Stop a mapped server that has no players",
                "  /autostopper restart <server> · Restart a mapped server that has no players",
                "Holds",
                "  /autostopper hold <server> · Keep a server from stopping automatically",
                "  /autostopper release <server> · Let a held server stop automatically again"),
                lines.subList(0, 11));
        String footer = lines.get(11);
        assertTrue(footer.contains("Troubleshooting: https://github.com/Criseda/AutoStopper/blob/1.1.2/docs/troubleshooting.md"));
        assertTrue(footer.contains("Get help: https://github.com/Criseda/AutoStopper/issues/new/choose"));
    }

    @Test
    public void testExecuteHelpCommand_OnlyListsGroupsWithPermittedCommands() {
        grant(AutoStopperCommand.HOLD_PERMISSION);
        grant(AutoStopperCommand.STATUS_PERMISSION);

        command.execute(mockInvocation(source, new String[]{"help"}));

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(6)).sendMessage(messageCaptor.capture());
        List<String> lines = messageCaptor.getAllValues().stream().map(ComponentTestUtils::plainText).toList();
        assertEquals("General", lines.get(1));
        assertTrue(lines.get(2).contains("/autostopper status"));
        assertEquals("Holds", lines.get(3));
        assertTrue(lines.get(4).contains("/autostopper hold <server>"));
        assertTrue(lines.get(5).contains("Troubleshooting:"));
        assertTrue(lines.stream().noneMatch(line -> line.contains("Lifecycle") || line.contains("release")));
    }

    @Test
    public void testExecuteHelpCommand_OnlyShowsPublicCommandsWhenPermissionsAreUndefined() {
        SimpleCommand.Invocation invocation = mockInvocation(source, new String[]{"help"});

        command.execute(invocation);

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(3)).sendMessage(messageCaptor.capture());
        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(plainText(messages.get(0)).contains("AutoStopper Commands"));
        assertEquals("You have no AutoStopper operator commands.", plainText(messages.get(1)));
        assertTrue(plainText(messages.get(2)).contains("Docs: https://github.com/Criseda/AutoStopper/blob/1.1.2/README.md"));
        assertTrue(messages.stream().noneMatch(message -> plainText(message).contains("status")));
        assertTrue(messages.stream().noneMatch(message -> plainText(message).contains("reload")));
        assertTrue(messages.stream().noneMatch(message -> plainText(message).contains("Troubleshooting")));
    }

    @Test
    public void testExecuteHelpCommand_PlayerHoverNamesDescriptionAndPermission() {
        Player player = mock(Player.class);
        lenient().when(player.getPermissionValue(anyString())).thenReturn(Tristate.UNDEFINED);
        when(player.getPermissionValue(AutoStopperCommand.START_PERMISSION)).thenReturn(Tristate.TRUE);

        command.execute(mockInvocation(player, new String[]{"help"}));

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(player, times(4)).sendMessage(messageCaptor.capture());
        Component entry = messageCaptor.getAllValues().get(2);
        TextComponent commandText = findText(entry, "/autostopper start <server>");
        assertNotNull(commandText);
        assertEquals(ClickEvent.suggestCommand("/autostopper start <server>"), commandText.clickEvent());
        assertNotNull(commandText.hoverEvent());
        assertEquals("Start a mapped server and wait until it is ready\n"
                + "Permission: autostopper.command.start or autostopper.admin\n"
                + "Click to suggest this command",
                plainText((Component) commandText.hoverEvent().value()));
    }

    @Test
    public void testUsageMessageReusesTheHelpDescription() {
        grant(AutoStopperCommand.RELEASE_PERMISSION);

        command.execute(mockInvocation(source, new String[]{"release"}));

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(messageCaptor.capture());
        assertEquals("AutoStopper ! Usage: /autostopper release <server> · Let a held server stop automatically again",
                plainText(messageCaptor.getValue()));
    }

    @Test
    public void testSubcommandsAreCaseInsensitive() {
        command.execute(mockInvocation(source, new String[]{"HELP"}));

        ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(3)).sendMessage(messageCaptor.capture());
        assertTrue(plainText(messageCaptor.getAllValues().get(0)).contains("AutoStopper Commands"));
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
        verify(source, times(9)).sendMessage(messageCaptor.capture());

        List<Component> messages = messageCaptor.getAllValues();
        assertTrue(plainText(messages.get(1)).contains("Unavailable · Docker cannot be reached")); // s-docker (sorted first)
        assertTrue(plainText(messages.get(2)).contains("Failed")); // s-failed
        assertTrue(plainText(messages.get(3)).contains("Ready")); // s-ready
        assertTrue(plainText(messages.get(4)).contains("Waking")); // s-starting
        assertTrue(plainText(messages.get(5)).contains("Sleeping")); // s-stopped
        assertTrue(plainText(messages.get(6)).contains("Stopping")); // s-stopping
        assertTrue(plainText(messages.get(7)).contains("Running · readiness unverified")); // s-unverified
        assertTrue(plainText(messages.get(8)).contains("Get help:"));
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

    private static ClickEvent<?> clickEvent(Component message, String label) {
        TextComponent text = findText(message, label);
        assertNotNull(text, label);
        return text.clickEvent();
    }

    private static TextComponent findText(Component component, String content) {
        if (component instanceof TextComponent text && text.content().equals(content)) {
            return text;
        }
        for (Component child : component.children()) {
            TextComponent found = findText(child, content);
            if (found != null) {
                return found;
            }
        }
        return null;
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
        verify(source, times(3)).sendMessage(messages.capture());
        String status = plainText(messages.getAllValues().get(1));
        assertTrue(plainText(messages.getAllValues().get(2)).contains("Troubleshooting:"));
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
    public void testExecuteStart_ProxyShutdownSendsNothingMore() {
        grant(AutoStopperCommand.START_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        when(lifecycleCoordinator.requestManualStart(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.completedFuture(me.criseda.autostopper.lifecycle.ManualStartOutcome.PROXY_SHUTDOWN));

        command.execute(mockInvocation(source, new String[]{"start", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(captor.capture());
        assertTrue(plainText(captor.getValue()).contains("Waking survival"));
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
    public void testExecuteStop_ProxyShutdownSendsNothingMore() {
        grant(AutoStopperCommand.STOP_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        when(lifecycleCoordinator.requestManualStop(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.completedFuture(me.criseda.autostopper.lifecycle.ManualStopOutcome.PROXY_SHUTDOWN));

        command.execute(mockInvocation(source, new String[]{"stop", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(captor.capture());
        assertTrue(plainText(captor.getValue()).contains("Stopping survival"));
        verify(activityTracker, never()).removeActivity("survival");
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
    public void testExecuteRestart_ProxyShutdownSendsNothingMore() {
        grant(AutoStopperCommand.RESTART_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        when(lifecycleCoordinator.requestManualRestart(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.completedFuture(me.criseda.autostopper.lifecycle.ManualRestartOutcome.PROXY_SHUTDOWN));

        command.execute(mockInvocation(source, new String[]{"restart", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendMessage(captor.capture());
        assertTrue(plainText(captor.getValue()).contains("Restarting survival"));
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
            if (!outcome.name().equals("PROXY_SHUTDOWN")) {
                assertOperatorFailure(outcome.name());
            }
            clearInvocations(source);
        }

        // Also test exceptional completion
        when(lifecycleCoordinator.requestManualStart(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));
        command.execute(mockInvocation(source, new String[]{"start", "survival"}));
        assertOperatorFailure("START_FAILED");
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
            if (!outcome.name().equals("PROXY_SHUTDOWN")) {
                assertOperatorFailure(outcome.name());
            }
            clearInvocations(source);
        }

        // Exceptional completion
        when(lifecycleCoordinator.requestManualStop(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));
        command.execute(mockInvocation(source, new String[]{"stop", "survival"}));
        assertOperatorFailure("STOP_FAILED");
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
            if (!outcome.name().equals("PROXY_SHUTDOWN")) {
                assertOperatorFailure(outcome.name());
            }
            clearInvocations(source);
        }

        // Exceptional completion
        when(lifecycleCoordinator.requestManualRestart(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));
        command.execute(mockInvocation(source, new String[]{"restart", "survival"}));
        assertOperatorFailure("START_FAILED");
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

    @Test
    public void testExecuteStart_ConsoleFailureIncludesCauseRemediationAndLink() {
        grant(AutoStopperCommand.START_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        when(lifecycleCoordinator.requestManualStart(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.completedFuture(ManualStartOutcome.START_FAILED));

        command.execute(mockInvocation(source, new String[]{"start", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendMessage(captor.capture());
        String failure = plainText(captor.getAllValues().get(1));
        assertTrue(failure.contains("Failed to start server survival. Docker could not start the container."));
        assertTrue(failure.contains("Fix: Review the proxy log and Docker state, then retry."));
        assertTrue(failure.contains("Next: /autostopper status"));
        assertTrue(failure.contains("Outcome: START_FAILED"));
        assertTrue(failure.contains(
                "https://github.com/Criseda/AutoStopper/blob/1.1.2/docs/troubleshooting.md#start_failed"));
    }

    @Test
    public void testExecuteStart_PlayerFailureUsesHoverAndClickAffordances() {
        Player player = mock(Player.class);
        when(player.getPermissionValue(anyString())).thenReturn(Tristate.UNDEFINED);
        when(player.getPermissionValue(AutoStopperCommand.START_PERMISSION)).thenReturn(Tristate.TRUE);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        when(lifecycleCoordinator.requestManualStart(snapshot.server("survival").orElseThrow()))
                .thenReturn(CompletableFuture.completedFuture(ManualStartOutcome.CANCELLED));

        command.execute(mockInvocation(player, new String[]{"start", "survival"}));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, times(2)).sendMessage(captor.capture());
        Component failure = captor.getAllValues().get(1);
        String text = plainText(failure);
        assertTrue(text.contains("Starting server survival was cancelled. Try again."));
        assertTrue(text.contains("A reload, shutdown, or conflicting operation cancelled this one."));
        assertFalse(text.contains("Outcome:"));
        assertFalse(text.contains("https://"));

        Component reason = failure.children().get(0);
        assertEquals(net.kyori.adventure.text.event.ClickEvent.Action.OPEN_URL, reason.clickEvent().action());
        assertTrue(reason.clickEvent().toString().contains("troubleshooting.md#cancelled"));
        Component hover = (Component) reason.hoverEvent().value();
        assertTrue(plainText(hover).contains("Outcome: CANCELLED"));
        assertTrue(plainText(hover).contains("Click to open troubleshooting"));

        Component next = failure.children().get(failure.children().size() - 1);
        assertEquals("/autostopper start survival", plainText(next));
        assertEquals(net.kyori.adventure.text.event.ClickEvent.Action.SUGGEST_COMMAND, next.clickEvent().action());
    }

    @Test
    public void testExecuteStatusCommand_NoFooterWithoutFailures() {
        grant(AutoStopperCommand.STATUS_PERMISSION);
        ConfigSnapshot snapshot = snapshot("survival");
        when(config.snapshot()).thenReturn(snapshot);
        when(operationalStatus.collectStatuses(snapshot)).thenReturn(CompletableFuture.completedFuture(
                Map.of("survival", operational(OperationalState.READY))));

        command.execute(mockInvocation(source, new String[]{"status"}));

        verify(source, times(2)).sendMessage(any(Component.class));
    }

    private void assertOperatorFailure(String outcomeCode) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source, atLeastOnce()).sendMessage(captor.capture());
        String failure = plainText(captor.getValue());
        assertTrue(failure.contains("Outcome: " + outcomeCode), outcomeCode + " -> " + failure);
        assertTrue(failure.contains("Fix: "), failure);
        assertTrue(failure.contains("troubleshooting.md#" + outcomeCode.toLowerCase(java.util.Locale.ROOT)), failure);
        clearInvocations(source);
    }

    private OperationalServerStatus operational(OperationalState state) {
        return new OperationalServerStatus(state, 0, Optional.empty());
    }
}
