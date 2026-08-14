package me.criseda.autostopper.messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import me.criseda.autostopper.operational.OperationalServerStatus;
import me.criseda.autostopper.operational.OperationalState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoStopperMessagesTest {

    @Test
    void pluginInformationUsesStructuredBrandAndArgumentColors() {
        Component message = AutoStopperMessages.pluginInfo("1.2.3");

        assertEquals("AutoStopper 1.2.3 - Server Auto-Stop Plugin", plainText(message));
        assertColor(message, "AutoStopper ", NamedTextColor.GOLD);
        assertColor(message, "1.2.3", NamedTextColor.YELLOW);
        assertColor(message, " - ", NamedTextColor.GRAY);
    }

    @Test
    void runningStatusUsesAColoredBoldLabel() {
        Component message = AutoStopperMessages.statusRunning("survival", 5L);

        assertEquals("survival: Running - 5 minutes since last activity", plainText(message));
        assertColor(message, "survival", NamedTextColor.YELLOW);
        TextComponent running = findText(message, "Running");
        assertNotNull(running);
        assertEquals(NamedTextColor.GREEN, running.color());
        assertEquals(TextDecoration.State.TRUE, running.decoration(TextDecoration.BOLD));
    }

    @Test
    void operationalStatusForRunningUnverifiedUsesWarningColorAndDisplaysActivity() {
        OperationalServerStatus status = new OperationalServerStatus(
                OperationalState.RUNNING_UNVERIFIED, 0, Optional.empty());
        Component message = AutoStopperMessages.operationalStatus("survival", status, 3L);

        assertEquals("survival: RUNNING_UNVERIFIED - 3 minutes since last activity", plainText(message));
        assertColor(message, "survival", NamedTextColor.YELLOW);
        TextComponent stateText = findText(message, "RUNNING_UNVERIFIED");
        assertNotNull(stateText);
        assertEquals(NamedTextColor.YELLOW, stateText.color());
        assertEquals(TextDecoration.State.TRUE, stateText.decoration(TextDecoration.BOLD));
    }

    @Test
    void semanticOutcomesHaveConsistentColorsAndPrefix() {
        Component progress = AutoStopperMessages.reloadStarted();
        Component success = AutoStopperMessages.serverReady("survival");
        Component timeout = AutoStopperMessages.startTimedOut("survival");
        Component denied = AutoStopperMessages.permissionDenied("reload configuration");
        Component failure = AutoStopperMessages.startFailed("survival");

        for (Component message : List.of(progress, success, timeout, denied, failure)) {
            assertTrue(plainText(message).startsWith("[AutoStopper] "));
            assertColor(message, "[AutoStopper] ", NamedTextColor.GOLD);
        }
        assertColor(progress, "Reloading AutoStopper configuration...", NamedTextColor.YELLOW);
        assertColor(success, "Server ", NamedTextColor.GREEN);
        assertColor(timeout, "Timed out starting server ", NamedTextColor.YELLOW);
        assertColor(denied, "You do not have permission to ", NamedTextColor.RED);
        assertColor(failure, "Failed to start server ", NamedTextColor.RED);
    }

    @Test
    void lifecycleMessagesUseBackendLanguageAndMonotonicElapsedValue() {
        List<Component> stages = List.of(
                AutoStopperMessages.lifecycleInspecting("survival"),
                AutoStopperMessages.lifecycleStarting("survival"),
                AutoStopperMessages.lifecycleWaitingForReadiness("survival"),
                AutoStopperMessages.lifecycleConnecting("survival"),
                AutoStopperMessages.lifecycleSucceeded("survival", Duration.ofMillis(2_550)));
        Component failure = AutoStopperMessages.lifecycleFailed(
                AutoStopperMessages.serverNotReady("survival"), Duration.ofMillis(850));

        assertEquals(List.of(
                "[AutoStopper] Checking server survival...",
                "[AutoStopper] Starting server survival...",
                "[AutoStopper] Waiting for server survival to become ready...",
                "[AutoStopper] Connecting you to server survival...",
                "[AutoStopper] Connected to server survival after 2.5 seconds."),
                stages.stream().map(AutoStopperMessagesTest::plainText).toList());
        assertEquals("[AutoStopper] Server survival is not ready. Waited 850 ms.", plainText(failure));
    }

    @Test
    void dynamicValuesRemainLiteralTextComponents() {
        String untrustedServerName = "<red>survival</red>§l";
        String untrustedError = "bad <click:run_command:'/op me'>value</click>";

        Component ready = AutoStopperMessages.serverReady(untrustedServerName);
        Component reloadFailure = AutoStopperMessages.reloadFailed(untrustedError);

        TextComponent serverArgument = findText(ready, untrustedServerName);
        TextComponent errorArgument = findText(reloadFailure, untrustedError);
        assertNotNull(serverArgument);
        assertNotNull(errorArgument);
        assertEquals(NamedTextColor.YELLOW, serverArgument.color());
        assertEquals(NamedTextColor.YELLOW, errorArgument.color());
        assertTrue(serverArgument.children().isEmpty());
        assertTrue(errorArgument.children().isEmpty());
        assertTrue(plainText(ready).contains(untrustedServerName));
        assertTrue(plainText(reloadFailure).contains(untrustedError));
    }

    @Test
    void fixedMessageCatalogContainsNoLegacyFormattingCodes() {
        List<Component> messages = List.of(
                AutoStopperMessages.pluginInfo("1.2.3"),
                AutoStopperMessages.helpHint(),
                AutoStopperMessages.unknownCommand(),
                AutoStopperMessages.helpHeader(),
                AutoStopperMessages.helpEntry("/autostopper", "Shows plugin information"),
                AutoStopperMessages.statusHeader(),
                AutoStopperMessages.statusCollectionFailed(),
                AutoStopperMessages.statusNoMapping("survival"),
                AutoStopperMessages.statusRunning("survival", 5L),
                AutoStopperMessages.statusRunning("survival", null),
                AutoStopperMessages.statusStopped("survival"),
                AutoStopperMessages.statusMissing("survival"),
                AutoStopperMessages.statusInaccessible("survival"),
                AutoStopperMessages.statusTimedOut("survival"),
                AutoStopperMessages.statusFailed("survival"),
                AutoStopperMessages.reloadStarted(),
                AutoStopperMessages.reloadFailed("invalid configuration"),
                AutoStopperMessages.reloadSucceeded(),
                AutoStopperMessages.permissionDenied("reload configuration"),
                AutoStopperMessages.serverAlreadyStarting(),
                AutoStopperMessages.serverStopping("survival"),
                AutoStopperMessages.mappingChanged("survival"),
                AutoStopperMessages.overloaded(),
                AutoStopperMessages.statusCheckError("survival"),
                AutoStopperMessages.noContainerMapping("survival"),
                AutoStopperMessages.containerMissing("survival"),
                AutoStopperMessages.dockerUnavailable("manage", "survival"),
                AutoStopperMessages.statusCheckTimedOut("survival"),
                AutoStopperMessages.statusCheckFailed("survival"),
                AutoStopperMessages.serverOfflineStarting(),
                AutoStopperMessages.startError("survival"),
                AutoStopperMessages.startTimedOut("survival"),
                AutoStopperMessages.startFailed("survival"),
                AutoStopperMessages.startCancelled("survival"),
                AutoStopperMessages.serverNotReady("survival"),
                AutoStopperMessages.serverReady("survival"),
                AutoStopperMessages.retryServerCommand("survival"),
                AutoStopperMessages.connectionFailed("survival"),
                AutoStopperMessages.connectionCancelled("survival"),
                AutoStopperMessages.connectionInProgress("survival"),
                AutoStopperMessages.connectionRefused("survival"));

        for (Component message : messages) {
            assertFalse(plainText(message).contains("§"), plainText(message));
        }
    }

    private static void assertColor(Component component, String content, NamedTextColor expected) {
        TextComponent text = findText(component, content);
        assertNotNull(text, "Missing text component: " + content);
        assertEquals(expected, text.color(), content);
    }

    private static TextComponent findText(Component component, String content) {
        return textComponents(component).stream()
                .filter(text -> text.content().equals(content))
                .findFirst()
                .orElse(null);
    }

    private static String plainText(Component component) {
        StringBuilder result = new StringBuilder();
        for (TextComponent text : textComponents(component)) {
            result.append(text.content());
        }
        return result.toString();
    }

    private static List<TextComponent> textComponents(Component component) {
        List<TextComponent> result = new ArrayList<>();
        collectTextComponents(component, result);
        return result;
    }

    private static void collectTextComponents(Component component, List<TextComponent> result) {
        if (component instanceof TextComponent text) {
            result.add(text);
        }
        for (Component child : component.children()) {
            collectTextComponents(child, result);
        }
    }
}
