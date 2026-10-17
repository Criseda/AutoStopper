package me.criseda.autostopper.messages;

import me.criseda.autostopper.lifecycle.ManualRestartOutcome;
import me.criseda.autostopper.lifecycle.ManualStartOutcome;
import me.criseda.autostopper.lifecycle.ManualStopOutcome;
import me.criseda.autostopper.telemetry.TelemetryOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorFailureGuideTest {

    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+(.+?)\\s*$");
    private static final Pattern OUTCOME_ROW = Pattern.compile("(?m)^\\|\\s*`([A-Z_]+)`\\s*\\|");

    @Test
    void everyManualFailureOutcomeHasAGuide() {
        for (ManualStartOutcome outcome : ManualStartOutcome.values()) {
            if (!outcome.isSuccessful() && outcome != ManualStartOutcome.PROXY_SHUTDOWN) {
                assertEquals(outcome.name(), OperatorFailureGuide.of(outcome).outcomeCode());
            }
        }
        for (ManualStopOutcome outcome : ManualStopOutcome.values()) {
            if (!outcome.isSuccessful() && outcome != ManualStopOutcome.PROXY_SHUTDOWN) {
                assertEquals(outcome.name(), OperatorFailureGuide.of(outcome).outcomeCode());
            }
        }
        for (ManualRestartOutcome outcome : ManualRestartOutcome.values()) {
            if (!outcome.isSuccessful() && outcome != ManualRestartOutcome.PROXY_SHUTDOWN) {
                assertEquals(outcome.name(), OperatorFailureGuide.of(outcome).outcomeCode());
            }
        }
    }

    @Test
    void successfulOutcomesAreNotFailures() {
        assertThrows(IllegalArgumentException.class, () -> OperatorFailureGuide.of(ManualStartOutcome.READY));
        assertThrows(IllegalArgumentException.class, () -> OperatorFailureGuide.of(ManualStopOutcome.STOPPED));
        assertThrows(IllegalArgumentException.class,
                () -> OperatorFailureGuide.of(ManualRestartOutcome.RESTARTED_AND_READY));
    }

    @Test
    void outcomeCodesMatchTelemetryOutcomes() {
        for (OperatorFailureGuide guide : OperatorFailureGuide.values()) {
            assertEquals(guide.outcomeCode(), TelemetryOutcome.valueOf(guide.outcomeCode()).name());
        }
    }

    @Test
    void guideTextIsCompleteAndSanitized() {
        for (OperatorFailureGuide guide : OperatorFailureGuide.values()) {
            assertFalse(guide.cause().isBlank(), guide.name());
            assertFalse(guide.remediation().isBlank(), guide.name());
            assertFalse(guide.cause().contains("docker.sock"), guide.name());
            assertFalse(guide.remediation().contains("docker.sock"), guide.name());
        }
    }

    @Test
    void everyTroubleshootingAnchorExists() throws IOException {
        Set<String> anchors = troubleshootingAnchors();
        for (OperatorFailureGuide guide : OperatorFailureGuide.values()) {
            assertTrue(anchors.contains(guide.troubleshootingAnchor()),
                    "docs/troubleshooting.md has no section for #" + guide.troubleshootingAnchor());
        }
    }

    @Test
    void everyTelemetryOutcomeIsDocumented() throws IOException {
        Set<String> anchors = troubleshootingAnchors();
        Set<String> rows = OUTCOME_ROW.matcher(troubleshootingMarkdown()).results()
                .map(result -> result.group(1))
                .collect(Collectors.toSet());
        for (TelemetryOutcome outcome : TelemetryOutcome.values()) {
            assertTrue(anchors.contains(outcome.name().toLowerCase(Locale.ROOT)) || rows.contains(outcome.name()),
                    "docs/troubleshooting.md has no section or table row for " + outcome);
        }
    }

    private static String troubleshootingMarkdown() throws IOException {
        return Files.readString(Path.of(ProjectLinks.TROUBLESHOOTING_PATH), StandardCharsets.UTF_8);
    }

    private static Set<String> troubleshootingAnchors() throws IOException {
        Matcher matcher = HEADING.matcher(troubleshootingMarkdown());
        return matcher.results()
                .map(result -> githubSlug(result.group(1)))
                .collect(Collectors.toSet());
    }

    /** GitHub's heading anchor: lowercase, punctuation other than '-' and '_' removed, spaces to '-'. */
    private static String githubSlug(String heading) {
        String lower = heading.toLowerCase(Locale.ROOT);
        StringBuilder slug = new StringBuilder();
        Arrays.stream(lower.split("")).forEach(character -> {
            char c = character.charAt(0);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                slug.append(c);
            } else if (c == ' ') {
                slug.append('-');
            }
        });
        return slug.toString();
    }
}
