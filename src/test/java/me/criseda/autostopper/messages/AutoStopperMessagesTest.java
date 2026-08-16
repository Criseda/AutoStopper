package me.criseda.autostopper.messages;

import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.operational.OperationalServerStatus;
import me.criseda.autostopper.operational.OperationalState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoStopperMessagesTest {

    @Test
    void pluginHeaderAndTaglineUseBrandTokens() {
        Component header = AutoStopperMessages.pluginHeader("2.1.0");
        Component tagline = AutoStopperMessages.pluginTagline();

        assertEquals("AutoStopper 2.1.0", plainText(header));
        assertColor(header, "AutoStopper", MessageTokens.BRAND);
        assertColor(header, " 2.1.0", MessageTokens.TEXT_MUTED);

        assertEquals("Empty servers sleep. Players wake them.", plainText(tagline));
        assertColor(tagline, "Empty servers sleep. Players wake them.", MessageTokens.TEXT_PRIMARY);
    }

    @Test
    void helpHintOffersProgressiveAffordances() {
        Component playerHint = AutoStopperMessages.helpHint(true);
        Component consoleHint = AutoStopperMessages.helpHint(false);

        assertEquals("Use /autostopper help for commands.", plainText(playerHint));
        assertEquals("Use /autostopper help for commands.", plainText(consoleHint));

        TextComponent playerCmd = findText(playerHint, "/autostopper help");
        assertNotNull(playerCmd);
        assertNotNull(playerCmd.clickEvent());
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, playerCmd.clickEvent().action());
        assertTrue(playerCmd.clickEvent().toString().contains("/autostopper help"));
        assertNotNull(playerCmd.hoverEvent());

        TextComponent consoleCmd = findText(consoleHint, "/autostopper help");
        assertNotNull(consoleCmd);
        assertNull(consoleCmd.clickEvent());
        assertNull(consoleCmd.hoverEvent());
    }

    @Test
    void operationalStatusForRunningUnverifiedUsesWarningColorAndMutedActivity() {
        OperationalServerStatus status = new OperationalServerStatus(
                OperationalState.RUNNING_UNVERIFIED, 0, Optional.empty());
        Component message = AutoStopperMessages.operationalStatus("survival", status, 3L);

        assertEquals("◐ survival   Running · readiness unverified · active 3m ago", plainText(message));
        assertColor(message, "◐ ", MessageTokens.PROGRESS_WARNING);
        assertColor(message, "survival", MessageTokens.ACTION);
        assertColor(message, "Running · readiness unverified", MessageTokens.PROGRESS_WARNING);
        assertColor(message, "active 3m ago", MessageTokens.TEXT_MUTED);
    }

    @Test
    void operationalStatusForAllStatesRendersExpectedGlyphsAndLabels() {
        OperationalServerStatus ready = new OperationalServerStatus(OperationalState.READY, 0, Optional.empty());
        OperationalServerStatus waking = new OperationalServerStatus(OperationalState.STARTING, 2, Optional.empty());
        OperationalServerStatus sleeping = new OperationalServerStatus(OperationalState.STOPPED, 0, Optional.empty());
        OperationalServerStatus stopping = new OperationalServerStatus(OperationalState.STOPPING, 0, Optional.empty());
        OperationalServerStatus unverified = new OperationalServerStatus(OperationalState.RUNNING_UNVERIFIED, 0, Optional.empty());
        OperationalServerStatus failed = new OperationalServerStatus(OperationalState.FAILED, 0, Optional.empty());
        OperationalServerStatus unavailable = new OperationalServerStatus(OperationalState.DOCKER_UNAVAILABLE, 0, Optional.empty());

        assertEquals("● survival   Ready · active just now",
                plainText(AutoStopperMessages.operationalStatus("survival", ready, 0L)));
        assertEquals("◐ creative   Waking · 2 players waiting",
                plainText(AutoStopperMessages.operationalStatus("creative", waking, null)));
        assertEquals("○ events   Sleeping",
                plainText(AutoStopperMessages.operationalStatus("events", sleeping, null)));
        assertEquals("◐ hub   Stopping",
                plainText(AutoStopperMessages.operationalStatus("hub", stopping, null)));
        assertEquals("◐ lobby   Running · readiness unverified · no activity recorded",
                plainText(AutoStopperMessages.operationalStatus("lobby", unverified, null)));
        assertEquals("! mini   Failed",
                plainText(AutoStopperMessages.operationalStatus("mini", failed, null)));
        assertEquals("! modded   Unavailable · Docker cannot be reached",
                plainText(AutoStopperMessages.operationalStatus("modded", unavailable, null)));
    }

    @Test
    void operationalStatusIncludesSanitizedFailureDetail() {
        OperationalFailure failure = new OperationalFailure(
                Instant.parse("2026-08-15T12:00:00Z"),
                "startup probe",
                "daemon unreachable",
                "Start Docker daemon");
        OperationalServerStatus status = new OperationalServerStatus(
                OperationalState.DOCKER_UNAVAILABLE, 0, Optional.of(failure));
        Component message = AutoStopperMessages.operationalStatus("survival", status, null);

        String text = plainText(message);
        assertTrue(text.contains("! survival   Unavailable · Docker cannot be reached"));
        assertTrue(text.contains("last failure 2026-08-15T12:00:00Z during startup probe: daemon unreachable"));
        assertTrue(text.contains("Remediation: Start Docker daemon"));
    }

    @Test
    void lifecycleMessagesUseCleanBrandMarkingsAndMonotonicElapsedValue() {
        List<Component> stages = List.of(
                AutoStopperMessages.lifecycleInspecting("survival"),
                AutoStopperMessages.lifecycleStarting("survival"),
                AutoStopperMessages.lifecycleWaitingForReadiness("survival"),
                AutoStopperMessages.lifecycleConnecting("survival"),
                AutoStopperMessages.playersWaiting(2),
                AutoStopperMessages.lifecycleSucceeded("survival", Duration.ofMillis(2_500)));
        Component failure = AutoStopperMessages.lifecycleFailed(
                AutoStopperMessages.serverNotReady("survival"), Duration.ofMillis(850));

        assertEquals(List.of(
                "AutoStopper › Checking server survival…",
                "AutoStopper › Waking survival…",
                "AutoStopper › Waiting for server survival to become ready…",
                "AutoStopper › Connecting you to server survival…",
                "AutoStopper › 2 players waiting",
                "AutoStopper ✓ Connected to survival · 2.5s"),
                stages.stream().map(AutoStopperMessagesTest::plainText).toList());
        assertEquals("AutoStopper ! Server survival is not ready. · Waited 850 ms.", plainText(failure));
    }

    @Test
    void fuzzySubcommandSuggestionsOfferClickAffordance() {
        Component match = AutoStopperMessages.unknownSubcommand("statsu", "status", true);
        Component noMatch = AutoStopperMessages.unknownSubcommandNoMatch("xyz", true);

        assertEquals("AutoStopper ! Unknown command 'statsu'. Did you mean /autostopper status?", plainText(match));
        assertEquals("AutoStopper ! Unknown command 'xyz'. Use /autostopper help to see available commands.", plainText(noMatch));

        TextComponent suggested = findText(match, "/autostopper status");
        assertNotNull(suggested);
        assertNotNull(suggested.clickEvent());
        assertTrue(suggested.clickEvent().toString().contains("/autostopper status"));
    }

    @Test
    void humanizationHelpersFormatCorrectly() {
        assertEquals("500 ms", AutoStopperMessages.formatElapsed(Duration.ofMillis(500)));
        assertEquals("8.4s", AutoStopperMessages.formatElapsed(Duration.ofMillis(8_400)));
        assertEquals("8s", AutoStopperMessages.formatElapsed(Duration.ofMillis(8_000)));
        assertEquals("1m 24s", AutoStopperMessages.formatElapsed(Duration.ofSeconds(84)));

        assertEquals("no activity recorded", AutoStopperMessages.formatActivity(null));
        assertEquals("active just now", AutoStopperMessages.formatActivity(0L));
        assertEquals("active 5m ago", AutoStopperMessages.formatActivity(5L));
        assertEquals("active 2h ago", AutoStopperMessages.formatActivity(120L));
        assertEquals("active 1h 15m ago", AutoStopperMessages.formatActivity(75L));

        assertEquals("1 player waiting", AutoStopperMessages.formatWaiters(1));
        assertEquals("3 players waiting", AutoStopperMessages.formatWaiters(3));
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
        assertEquals(MessageTokens.ACTION, serverArgument.color());
        assertEquals(MessageTokens.ACTION, errorArgument.color());
        assertTrue(serverArgument.children().isEmpty());
        assertTrue(errorArgument.children().isEmpty());
        assertTrue(plainText(ready).contains(untrustedServerName));
        assertTrue(plainText(reloadFailure).contains(untrustedError));
    }

    @Test
    void fixedMessageCatalogContainsNoLegacyFormattingCodes() {
        List<Component> messages = List.of(
                AutoStopperMessages.pluginHeader("2.1.0"),
                AutoStopperMessages.pluginTagline(),
                AutoStopperMessages.pluginInfo("2.1.0"),
                AutoStopperMessages.helpHint(),
                AutoStopperMessages.helpHeader(),
                AutoStopperMessages.commandsHeader(),
                AutoStopperMessages.helpEntry("/autostopper", "Shows plugin overview"),
                AutoStopperMessages.unknownCommand(),
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
            assertFalse(plainText(message).startsWith("[AutoStopper]"), plainText(message));
        }
    }

    private static void assertColor(Component component, String content, TextColor expected) {
        TextComponent text = findText(component, content);
        assertNotNull(text, "Missing text component containing: " + content);
        assertEquals(expected, text.color(), content);
    }

    private static TextComponent findText(Component component, String content) {
        return textComponents(component).stream()
                .filter(text -> text.content().contains(content))
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
