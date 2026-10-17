package me.criseda.autostopper.messages;

import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.operational.OperationalServerStatus;
import me.criseda.autostopper.operational.OperationalState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;

import java.time.Duration;
import java.util.Objects;

/**
 * Message factories for AutoStopper's chat, command, and lifecycle presentation.
 * All methods construct Adventure components using the unified semantic tokens.
 */
public final class AutoStopperMessages {

    private AutoStopperMessages() {
    }

    // --- Brand Markings & Prefixes ---

    public static Component brandPrefix() {
        return finish(Component.text()
                .append(Component.text("AutoStopper", MessageTokens.BRAND))
                .append(Component.text(" " + MessageTokens.PREFIX_DIVIDER + " ", MessageTokens.TEXT_MUTED)));
    }

    public static Component brandSuccess() {
        return finish(Component.text()
                .append(Component.text("AutoStopper", MessageTokens.BRAND))
                .append(Component.text(" " + MessageTokens.MARK_SUCCESS + " ", MessageTokens.SUCCESS)));
    }

    public static Component brandAttention() {
        return finish(Component.text()
                .append(Component.text("AutoStopper", MessageTokens.BRAND))
                .append(Component.text(" " + MessageTokens.MARK_ATTENTION + " ", MessageTokens.FAILURE)));
    }

    // --- Root Overview & Help ---

    public static Component pluginHeader(String version) {
        return pluginHeader(version, false);
    }

    public static Component pluginHeader(String version, boolean isPlayer) {
        if (!isPlayer) {
            return finish(Component.text()
                    .append(Component.text("AutoStopper", MessageTokens.BRAND))
                    .append(Component.text(" " + version, MessageTokens.TEXT_MUTED)));
        }
        return finish(Component.text()
                .append(Component.text("AutoStopper", MessageTokens.BRAND))
                .append(Component.text(" ", MessageTokens.TEXT_MUTED))
                .append(link(version, ProjectLinks.releaseNotes(version), "Open the release notes")));
    }

    public static Component pluginTagline() {
        return finish(Component.text("Empty servers sleep. Players wake them.", MessageTokens.TEXT_PRIMARY));
    }

    public static Component pluginInfo(String version) {
        return finish(Component.text()
                .append(Component.text("AutoStopper", MessageTokens.BRAND))
                .append(Component.text(" " + version, MessageTokens.TEXT_MUTED))
                .append(Component.text(" " + MessageTokens.SEPARATOR + " ", MessageTokens.TEXT_MUTED))
                .append(Component.text("Empty servers sleep. Players wake them.", MessageTokens.TEXT_PRIMARY)));
    }

    public static Component helpHint() {
        return helpHint(false);
    }

    public static Component helpHint(boolean isPlayer) {
        return finish(Component.text()
                .append(Component.text("Use ", MessageTokens.TEXT_MUTED))
                .append(command("/autostopper help", "Show available commands", isPlayer))
                .append(Component.text(" for commands.", MessageTokens.TEXT_MUTED)));
    }

    public static Component helpHeader() {
        return finish(Component.text("AutoStopper Commands", MessageTokens.BRAND));
    }

    public static Component commandsHeader() {
        return finish(Component.text("Commands:", MessageTokens.BRAND));
    }

    /**
     * Public project links: docs, GitHub, Modrinth, and changelog. Players get clickable labels; the
     * console gets one copyable {@code Label: url} line per link.
     */
    public static Component projectLinks(String pluginVersion, boolean isPlayer) {
        String[][] links = {
                {"Docs", ProjectLinks.documentation(pluginVersion), "Open the AutoStopper documentation"},
                {"GitHub", ProjectLinks.repository(), "Open the AutoStopper repository"},
                {"Modrinth", ProjectLinks.modrinth(), "Open AutoStopper on Modrinth"},
                {"Changelog", ProjectLinks.changelog(pluginVersion), "Open the changelog"}};
        TextComponent.Builder builder = Component.text();
        for (int i = 0; i < links.length; i++) {
            String[] entry = links[i];
            if (isPlayer) {
                if (i > 0) {
                    builder.append(Component.text(" " + MessageTokens.SEPARATOR + " ", MessageTokens.TEXT_MUTED));
                }
                builder.append(link(entry[0], entry[1], entry[2]));
            } else {
                if (i > 0) {
                    builder.append(Component.newline());
                }
                builder.append(Component.text(entry[0] + ": ", MessageTokens.TEXT_MUTED))
                        .append(Component.text(entry[1], MessageTokens.ACTION));
            }
        }
        return finish(builder);
    }

    public static Component helpGroup(String label) {
        return finish(Component.text(label, MessageTokens.TEXT_MUTED));
    }

    public static Component helpNoOperatorCommands() {
        return finish(Component.text("You have no AutoStopper operator commands.", MessageTokens.TEXT_PRIMARY));
    }

    /**
     * One help line. Players hover for the description and the permission that grants the command, and
     * click to suggest it; the console gets the same line as plain text.
     */
    public static Component helpEntry(String commandText, String description, String permission,
            String adminPermission, boolean isPlayer) {
        TextComponent.Builder commandBuilder = Component.text()
                .content(commandText)
                .color(MessageTokens.ACTION);
        if (isPlayer) {
            commandBuilder.clickEvent(ClickEvent.suggestCommand(commandText))
                    .hoverEvent(HoverEvent.showText(Component.text()
                            .append(Component.text(description, MessageTokens.TEXT_PRIMARY))
                            .append(Component.newline())
                            .append(Component.text("Permission: ", MessageTokens.TEXT_MUTED))
                            .append(Component.text(permission, MessageTokens.ACTION))
                            .append(Component.text(" or ", MessageTokens.TEXT_MUTED))
                            .append(Component.text(adminPermission, MessageTokens.ACTION))
                            .append(Component.newline())
                            .append(Component.text("Click to suggest this command", MessageTokens.TEXT_MUTED))
                            .build()));
        }
        return finish(Component.text()
                .append(Component.text("  ", MessageTokens.TEXT_MUTED))
                .append(commandBuilder.build())
                .append(Component.text(" " + MessageTokens.SEPARATOR + " ", MessageTokens.TEXT_MUTED))
                .append(Component.text(description, MessageTokens.TEXT_PRIMARY)));
    }

    // --- Command Errors & Fuzzy Suggestions ---

    public static Component unknownCommand() {
        return unknownCommand(false);
    }

    public static Component unknownCommand(boolean isPlayer) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Unknown command. Use ", MessageTokens.FAILURE))
                .append(command("/autostopper help", "Show available commands", isPlayer))
                .append(Component.text(" to see available commands.", MessageTokens.FAILURE)));
    }

    public static Component unknownSubcommand(String entered, String suggestion, boolean isPlayer) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Unknown command '", MessageTokens.FAILURE))
                .append(Component.text(entered, MessageTokens.ACTION))
                .append(Component.text("'. Did you mean ", MessageTokens.FAILURE))
                .append(command("/autostopper " + suggestion, "Click to suggest /autostopper " + suggestion, isPlayer))
                .append(Component.text("?", MessageTokens.FAILURE)));
    }

    public static Component unknownSubcommandNoMatch(String entered, boolean isPlayer) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Unknown command '", MessageTokens.FAILURE))
                .append(Component.text(entered, MessageTokens.ACTION))
                .append(Component.text("'. Use ", MessageTokens.FAILURE))
                .append(command("/autostopper help", "Show available commands", isPlayer))
                .append(Component.text(" to see available commands.", MessageTokens.FAILURE)));
    }

    public static Component commandUsage(String usage, String description, boolean isPlayer) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Usage: ", MessageTokens.TEXT_MUTED))
                .append(command(usage, description, isPlayer))
                .append(Component.text(" " + MessageTokens.SEPARATOR + " ", MessageTokens.TEXT_MUTED))
                .append(Component.text(description, MessageTokens.TEXT_PRIMARY)));
    }

    // --- Operational Status ---

    public static Component statusHeader() {
        return finish(Component.text("Server status", MessageTokens.BRAND));
    }

    public static Component statusCollectionFailed() {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Could not collect server statuses.", MessageTokens.FAILURE)));
    }

    public static Component statusNoMapping(String serverName) {
        return formatStatusRow(serverName, MessageTokens.MARK_ATTENTION, MessageTokens.FAILURE,
                "No container mapping", MessageTokens.FAILURE, null);
    }

    public static Component statusRunning(String serverName, Long minutesSinceActivity) {
        String activityDetail = formatActivity(minutesSinceActivity);
        return formatStatusRow(serverName, MessageTokens.DOT_READY, MessageTokens.SUCCESS,
                "Ready", MessageTokens.SUCCESS, activityDetail);
    }

    public static Component statusStopped(String serverName) {
        return formatStatusRow(serverName, MessageTokens.DOT_STOPPED, MessageTokens.TEXT_MUTED,
                "Sleeping", MessageTokens.TEXT_MUTED, null);
    }

    public static Component statusMissing(String serverName) {
        return formatStatusRow(serverName, MessageTokens.MARK_ATTENTION, MessageTokens.FAILURE,
                "Missing", MessageTokens.FAILURE, "container does not exist");
    }

    public static Component statusInaccessible(String serverName) {
        return formatStatusRow(serverName, MessageTokens.MARK_ATTENTION, MessageTokens.FAILURE,
                "Inaccessible", MessageTokens.FAILURE, "Docker daemon unreachable");
    }

    public static Component statusTimedOut(String serverName) {
        return formatStatusRow(serverName, MessageTokens.MARK_ATTENTION, MessageTokens.PROGRESS_WARNING,
                "Timed out", MessageTokens.PROGRESS_WARNING, "status check did not respond in time");
    }

    public static Component statusFailed(String serverName) {
        return formatStatusRow(serverName, MessageTokens.MARK_ATTENTION, MessageTokens.FAILURE,
                "Failed", MessageTokens.FAILURE, "status check failed");
    }

    public static Component operationalStatus(String serverName, OperationalServerStatus status,
            Long minutesSinceActivity) {
        Objects.requireNonNull(status, "status");
        String glyph;
        TextColor glyphColor;
        String stateLabel;
        TextColor stateColor;

        switch (status.state()) {
            case READY -> {
                glyph = MessageTokens.DOT_READY;
                glyphColor = MessageTokens.SUCCESS;
                stateLabel = "Ready";
                stateColor = MessageTokens.SUCCESS;
            }
            case STARTING -> {
                glyph = MessageTokens.DOT_PROGRESS;
                glyphColor = MessageTokens.PROGRESS_WARNING;
                stateLabel = "Waking";
                stateColor = MessageTokens.PROGRESS_WARNING;
            }
            case RUNNING_UNVERIFIED -> {
                glyph = MessageTokens.DOT_PROGRESS;
                glyphColor = MessageTokens.PROGRESS_WARNING;
                stateLabel = "Running · readiness unverified";
                stateColor = MessageTokens.PROGRESS_WARNING;
            }
            case STOPPING -> {
                glyph = MessageTokens.DOT_PROGRESS;
                glyphColor = MessageTokens.PROGRESS_WARNING;
                stateLabel = "Stopping";
                stateColor = MessageTokens.PROGRESS_WARNING;
            }
            case STOPPED -> {
                glyph = MessageTokens.DOT_STOPPED;
                glyphColor = MessageTokens.TEXT_MUTED;
                stateLabel = "Sleeping";
                stateColor = MessageTokens.TEXT_MUTED;
            }
            case FAILED -> {
                glyph = MessageTokens.MARK_ATTENTION;
                glyphColor = MessageTokens.FAILURE;
                stateLabel = "Failed";
                stateColor = MessageTokens.FAILURE;
            }
            case DOCKER_UNAVAILABLE -> {
                glyph = MessageTokens.MARK_ATTENTION;
                glyphColor = MessageTokens.FAILURE;
                stateLabel = "Unavailable · Docker cannot be reached";
                stateColor = MessageTokens.FAILURE;
            }
            default -> {
                glyph = MessageTokens.MARK_ATTENTION;
                glyphColor = MessageTokens.FAILURE;
                stateLabel = status.state().name();
                stateColor = MessageTokens.FAILURE;
            }
        }

        StringBuilder detailBuilder = new StringBuilder();
        if (status.held()) {
            detailBuilder.append("held");
        }
        if (status.waitingPlayers() > 0) {
            appendDetail(detailBuilder, formatWaiters(status.waitingPlayers()));
        }
        if (status.state() == OperationalState.READY || status.state() == OperationalState.RUNNING_UNVERIFIED) {
            appendDetail(detailBuilder, formatActivity(minutesSinceActivity));
        }
        status.lastFailure().ifPresent(failure -> appendDetail(detailBuilder, failureDetail(failure)));

        String detail = detailBuilder.isEmpty() ? null : detailBuilder.toString();
        return formatStatusRow(serverName, glyph, glyphColor, stateLabel, stateColor, detail);
    }

    private static Component formatStatusRow(String serverName, String glyph, TextColor glyphColor,
            String stateLabel, TextColor stateColor, String detail) {
        TextComponent.Builder row = Component.text()
                .append(Component.text(glyph + " ", glyphColor))
                .append(Component.text(serverName, MessageTokens.ACTION))
                .append(Component.text("   ", MessageTokens.TEXT_MUTED))
                .append(Component.text(stateLabel, stateColor));
        if (detail != null && !detail.isEmpty()) {
            row.append(Component.text(" " + MessageTokens.SEPARATOR + " ", MessageTokens.TEXT_MUTED))
               .append(Component.text(detail, MessageTokens.TEXT_MUTED));
        }
        return finish(row);
    }

    // --- Configuration Reload & Preflight ---

    public static Component reloadStarted() {
        return finish(Component.text()
                .append(brandPrefix())
                .append(Component.text("Reloading AutoStopper configuration…", MessageTokens.PROGRESS_WARNING)));
    }

    public static Component reloadFailed(String errorSummary) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Configuration reload failed: ", MessageTokens.FAILURE))
                .append(Component.text(errorSummary, MessageTokens.ACTION)));
    }

    public static Component reloadSucceeded() {
        return finish(Component.text()
                .append(brandSuccess())
                .append(Component.text("Configuration reloaded successfully!", MessageTokens.SUCCESS)));
    }

    public static Component preflightCompleted(int healthy, int degraded) {
        if (degraded == 0) {
            return finish(Component.text()
                    .append(brandSuccess())
                    .append(Component.text("Operational preflight passed for " + healthy + " mapping(s).",
                            MessageTokens.SUCCESS)));
        }
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Operational preflight found " + degraded + " degraded mapping(s); use ",
                        MessageTokens.PROGRESS_WARNING))
                .append(command("/autostopper status", "View server status", true))
                .append(Component.text(" for remediation.", MessageTokens.PROGRESS_WARNING)));
    }

    public static Component permissionDenied(String action) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("You do not have permission to " + action + ".", MessageTokens.FAILURE)));
    }

    // --- Lifecycle Progression & Stage Feedback ---

    public static Component serverAlreadyStarting() {
        return finish(Component.text()
                .append(brandPrefix())
                .append(Component.text("Server is already being started, please wait…", MessageTokens.PROGRESS_WARNING)));
    }

    public static Component serverOfflineStarting() {
        return finish(Component.text()
                .append(brandPrefix())
                .append(Component.text("Server is offline. Starting it up…", MessageTokens.PROGRESS_WARNING)));
    }

    public static Component lifecycleInspecting(String serverName) {
        return finish(Component.text()
                .append(brandPrefix())
                .append(Component.text("Checking server ", MessageTokens.TEXT_PRIMARY))
                .append(argument(serverName))
                .append(Component.text("…", MessageTokens.TEXT_MUTED)));
    }

    public static Component lifecycleStarting(String serverName) {
        return finish(Component.text()
                .append(brandPrefix())
                .append(Component.text("Waking ", MessageTokens.PROGRESS_WARNING))
                .append(argument(serverName))
                .append(Component.text("…", MessageTokens.PROGRESS_WARNING)));
    }

    public static Component lifecycleWaitingForReadiness(String serverName) {
        return finish(Component.text()
                .append(brandPrefix())
                .append(Component.text("Waiting for server ", MessageTokens.TEXT_PRIMARY))
                .append(argument(serverName))
                .append(Component.text(" to become ready…", MessageTokens.TEXT_PRIMARY)));
    }

    public static Component lifecycleConnecting(String serverName) {
        return finish(Component.text()
                .append(brandPrefix())
                .append(Component.text("Connecting you to server ", MessageTokens.TEXT_PRIMARY))
                .append(argument(serverName))
                .append(Component.text("…", MessageTokens.TEXT_PRIMARY)));
    }

    public static Component playersWaiting(int count) {
        return finish(Component.text()
                .append(brandPrefix())
                .append(Component.text(formatWaiters(count), MessageTokens.PROGRESS_WARNING)));
    }

    public static Component lifecycleSucceeded(String serverName, Duration elapsed) {
        return finish(Component.text()
                .append(brandSuccess())
                .append(Component.text("Connected to ", MessageTokens.SUCCESS))
                .append(argument(serverName))
                .append(Component.text(" " + MessageTokens.SEPARATOR + " " + formatElapsed(elapsed), MessageTokens.TEXT_MUTED)));
    }

    public static Component lifecycleFailed(Component reason, Duration elapsed) {
        return finish(Component.text()
                .append(reason)
                .append(Component.text(" " + MessageTokens.SEPARATOR + " Waited " + formatElapsed(elapsed) + ".",
                        MessageTokens.TEXT_MUTED)));
    }

    public static Component retryServerCommand(String serverName) {
        return retryServerCommand(serverName, true);
    }

    public static Component retryServerCommand(String serverName, boolean isPlayer) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Try again in a moment with ", MessageTokens.PROGRESS_WARNING))
                .append(command("/server " + serverName, "Connect to " + serverName, isPlayer)));
    }

    public static Component serverReady(String serverName) {
        return finish(Component.text()
                .append(brandSuccess())
                .append(Component.text("Server ", MessageTokens.SUCCESS))
                .append(argument(serverName))
                .append(Component.text(" is now ready!", MessageTokens.SUCCESS)));
    }

    // --- Terminal Failure Outcomes ---

    public static Component serverStopping(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(" is currently stopping. Try again shortly.", MessageTokens.FAILURE)));
    }

    public static Component mappingChanged(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("The container mapping for server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(" changed while starting. Try again.", MessageTokens.FAILURE)));
    }

    public static Component overloaded() {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("AutoStopper is overloaded right now; try again in a moment.", MessageTokens.FAILURE)));
    }

    public static Component noContainerMapping(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(" has no container mapping.", MessageTokens.FAILURE)));
    }

    public static Component containerMissing(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("The container for server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(" does not exist.", MessageTokens.FAILURE)));
    }

    public static Component dockerUnavailable(String operation, String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Cannot reach the Docker daemon to " + operation + " server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(".", MessageTokens.FAILURE)));
    }

    public static Component statusCheckTimedOut(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Could not check status of server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(" in time. Try again.", MessageTokens.FAILURE)));
    }

    public static Component statusCheckFailed(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Could not check status of server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(".", MessageTokens.FAILURE)));
    }

    public static Component statusCheckError(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Error checking status of server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(".", MessageTokens.FAILURE)));
    }

    public static Component startTimedOut(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Timed out starting server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(". Try again.", MessageTokens.FAILURE)));
    }

    public static Component startFailed(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Failed to start server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(".", MessageTokens.FAILURE)));
    }

    public static Component startError(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Error starting server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(".", MessageTokens.FAILURE)));
    }

    public static Component startCancelled(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Starting server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(" was cancelled. Try again.", MessageTokens.FAILURE)));
    }

    public static Component serverNotReady(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(" is not ready.", MessageTokens.FAILURE)));
    }

    public static Component serverNotReady(String serverName, String detail) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(" is not ready. " + detail, MessageTokens.FAILURE)));
    }

    public static Component connectionInProgress(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("A connection to server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(" is already in progress.", MessageTokens.FAILURE)));
    }

    public static Component connectionCancelled(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Your connection to server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(" was cancelled. Try again.", MessageTokens.FAILURE)));
    }

    public static Component connectionRefused(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(" refused the connection. Try again.", MessageTokens.FAILURE)));
    }

    public static Component connectionFailed(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Could not connect you to server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(".", MessageTokens.FAILURE)));
    }

    // --- Manual Lifecycle & Hold Commands ---

    public static Component manualStartStarting(String serverName) {
        return finish(Component.text()
                .append(brandPrefix())
                .append(Component.text("Waking ", MessageTokens.PROGRESS_WARNING))
                .append(argument(serverName))
                .append(Component.text("…", MessageTokens.PROGRESS_WARNING)));
    }

    public static Component manualStartAlreadyReady(String serverName) {
        return finish(Component.text()
                .append(brandSuccess())
                .append(Component.text("Server ", MessageTokens.SUCCESS))
                .append(argument(serverName))
                .append(Component.text(" is already ready.", MessageTokens.SUCCESS)));
    }

    public static Component manualStartSucceeded(String serverName, Duration elapsed) {
        return finish(Component.text()
                .append(brandSuccess())
                .append(Component.text("Server ", MessageTokens.SUCCESS))
                .append(argument(serverName))
                .append(Component.text(" is now ready!", MessageTokens.SUCCESS))
                .append(Component.text(" " + MessageTokens.SEPARATOR + " " + formatElapsed(elapsed), MessageTokens.TEXT_MUTED)));
    }

    public static Component manualStartFailed(String serverName, Component reason, Duration elapsed) {
        return finish(Component.text()
                .append(reason)
                .append(Component.text(" " + MessageTokens.SEPARATOR + " Waited " + formatElapsed(elapsed) + ".", MessageTokens.TEXT_MUTED)));
    }

    public static Component manualStopStopping(String serverName) {
        return finish(Component.text()
                .append(brandPrefix())
                .append(Component.text("Stopping ", MessageTokens.PROGRESS_WARNING))
                .append(argument(serverName))
                .append(Component.text("…", MessageTokens.PROGRESS_WARNING)));
    }

    public static Component manualStopAlreadyStopped(String serverName) {
        return finish(Component.text()
                .append(brandSuccess())
                .append(Component.text("Server ", MessageTokens.SUCCESS))
                .append(argument(serverName))
                .append(Component.text(" is already sleeping.", MessageTokens.SUCCESS)));
    }

    public static Component manualStopSucceeded(String serverName, Duration elapsed) {
        return finish(Component.text()
                .append(brandSuccess())
                .append(Component.text("Stopped server ", MessageTokens.SUCCESS))
                .append(argument(serverName))
                .append(Component.text(" " + MessageTokens.SEPARATOR + " " + formatElapsed(elapsed), MessageTokens.TEXT_MUTED)));
    }

    public static Component manualStopRefusedPlayers(String serverName, int count) {
        String players = count == 1 ? "1 player is" : count + " players are";
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Cannot stop server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(": " + players + " currently connected.", MessageTokens.FAILURE)));
    }

    public static Component manualStopRefusedWaiters(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Cannot stop server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(": players are waiting to connect.", MessageTokens.FAILURE)));
    }

    public static Component manualStopFailed(String serverName, Component reason) {
        return finish(Component.text()
                .append(reason));
    }

    public static Component manualRestartRestarting(String serverName) {
        return finish(Component.text()
                .append(brandPrefix())
                .append(Component.text("Restarting ", MessageTokens.PROGRESS_WARNING))
                .append(argument(serverName))
                .append(Component.text("…", MessageTokens.PROGRESS_WARNING)));
    }

    public static Component manualRestartSucceeded(String serverName, Duration elapsed) {
        return finish(Component.text()
                .append(brandSuccess())
                .append(Component.text("Restarted server ", MessageTokens.SUCCESS))
                .append(argument(serverName))
                .append(Component.text(" " + MessageTokens.SEPARATOR + " " + formatElapsed(elapsed), MessageTokens.TEXT_MUTED)));
    }

    public static Component manualRestartRefusedPlayers(String serverName, int count) {
        String players = count == 1 ? "1 player is" : count + " players are";
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Cannot restart server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(": " + players + " currently connected.", MessageTokens.FAILURE)));
    }

    public static Component manualRestartRefusedWaiters(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Cannot restart server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(": players are waiting to connect.", MessageTokens.FAILURE)));
    }

    public static Component manualRestartFailed(String serverName, Component reason, Duration elapsed) {
        return finish(Component.text()
                .append(reason)
                .append(Component.text(" " + MessageTokens.SEPARATOR + " Waited " + formatElapsed(elapsed) + ".", MessageTokens.TEXT_MUTED)));
    }

    // --- Operator Failure Detail & Help Links ---

    /**
     * A failed manual command for an operator. The chat line keeps the reason and cause; players get
     * the remediation and typed outcome code on hover, a troubleshooting link on click, and the next
     * command as a click-to-suggest affordance. Console senders get the same detail as plain text.
     */
    public static Component operatorFailure(Component reason, OperatorFailureGuide guide, String retryCommand,
            Duration elapsed, String pluginVersion, boolean isPlayer) {
        Objects.requireNonNull(guide, "guide");
        String troubleshootingUrl = ProjectLinks.troubleshooting(pluginVersion, guide.troubleshootingAnchor());
        String nextCommand = switch (guide.nextStep()) {
            case RETRY -> retryCommand;
            case STATUS -> "/autostopper status";
            case NONE -> null;
        };

        TextComponent.Builder message = Component.text();
        if (isPlayer) {
            message.append(Component.text()
                    .append(reason)
                    .hoverEvent(HoverEvent.showText(failureHover(guide)))
                    .clickEvent(ClickEvent.openUrl(troubleshootingUrl)));
        } else {
            message.append(reason);
        }
        message.append(Component.text(" " + guide.cause(), MessageTokens.TEXT_PRIMARY));
        if (elapsed != null) {
            message.append(Component.text(" " + MessageTokens.SEPARATOR + " Waited " + formatElapsed(elapsed) + ".",
                    MessageTokens.TEXT_MUTED));
        }

        if (isPlayer) {
            if (nextCommand != null) {
                message.append(Component.text(" " + MessageTokens.SEPARATOR + " ", MessageTokens.TEXT_MUTED))
                        .append(command(nextCommand, "Click to suggest " + nextCommand, true));
            }
            return finish(message);
        }

        message.append(Component.newline())
                .append(Component.text("Fix: ", MessageTokens.TEXT_MUTED))
                .append(Component.text(guide.remediation(), MessageTokens.TEXT_PRIMARY));
        if (nextCommand != null) {
            message.append(Component.newline())
                    .append(Component.text("Next: ", MessageTokens.TEXT_MUTED))
                    .append(Component.text(nextCommand, MessageTokens.ACTION));
        }
        message.append(Component.newline())
                .append(Component.text("Outcome: ", MessageTokens.TEXT_MUTED))
                .append(Component.text(guide.outcomeCode(), MessageTokens.TEXT_PRIMARY))
                .append(Component.text(" " + MessageTokens.SEPARATOR + " Troubleshooting: ", MessageTokens.TEXT_MUTED))
                .append(Component.text(troubleshootingUrl, MessageTokens.ACTION));
        return finish(message);
    }

    /** The {@code Troubleshooting · Get help} footer shown to operators under help and failed status. */
    public static Component operatorHelpFooter(String pluginVersion, boolean isPlayer) {
        String troubleshootingUrl = ProjectLinks.troubleshooting(pluginVersion);
        String helpUrl = ProjectLinks.getHelp();
        if (isPlayer) {
            return finish(Component.text()
                    .append(link("Troubleshooting", troubleshootingUrl, "Open the troubleshooting guide"))
                    .append(Component.text(" " + MessageTokens.SEPARATOR + " ", MessageTokens.TEXT_MUTED))
                    .append(link("Get help", helpUrl, "Ask for support or report a bug")));
        }
        return finish(Component.text()
                .append(Component.text("Troubleshooting: ", MessageTokens.TEXT_MUTED))
                .append(Component.text(troubleshootingUrl, MessageTokens.ACTION))
                .append(Component.text(" " + MessageTokens.SEPARATOR + " Get help: ", MessageTokens.TEXT_MUTED))
                .append(Component.text(helpUrl, MessageTokens.ACTION)));
    }

    public static Component holdApplied(String serverName) {
        return finish(Component.text()
                .append(brandSuccess())
                .append(Component.text("Automatic shutdown held for server ", MessageTokens.SUCCESS))
                .append(argument(serverName))
                .append(Component.text(".", MessageTokens.SUCCESS)));
    }

    public static Component holdAlreadyActive(String serverName) {
        return finish(Component.text()
                .append(brandPrefix())
                .append(Component.text("Automatic shutdown is already held for server ", MessageTokens.TEXT_PRIMARY))
                .append(argument(serverName))
                .append(Component.text(".", MessageTokens.TEXT_PRIMARY)));
    }

    public static Component holdReleased(String serverName) {
        return finish(Component.text()
                .append(brandSuccess())
                .append(Component.text("Automatic shutdown hold released for server ", MessageTokens.SUCCESS))
                .append(argument(serverName))
                .append(Component.text(".", MessageTokens.SUCCESS)));
    }

    public static Component holdNotActive(String serverName) {
        return finish(Component.text()
                .append(brandPrefix())
                .append(Component.text("Server ", MessageTokens.TEXT_PRIMARY))
                .append(argument(serverName))
                .append(Component.text(" does not have an active hold.", MessageTokens.TEXT_PRIMARY)));
    }

    public static Component unmappedServer(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(" is not mapped in AutoStopper.", MessageTokens.FAILURE)));
    }

    public static Component stopTimedOut(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Timed out stopping server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(". Try again.", MessageTokens.FAILURE)));
    }

    public static Component stopFailed(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Failed to stop server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(".", MessageTokens.FAILURE)));
    }

    public static Component stopCancelled(String serverName) {
        return finish(Component.text()
                .append(brandAttention())
                .append(Component.text("Stopping server ", MessageTokens.FAILURE))
                .append(argument(serverName))
                .append(Component.text(" was cancelled.", MessageTokens.FAILURE)));
    }

    // --- Humanization Utilities ---

    public static String formatElapsed(Duration elapsed) {
        long millis = Math.max(0, elapsed.toMillis());
        if (millis < 1_000) {
            return millis + " ms";
        }
        if (millis < 60_000) {
            long seconds = millis / 1_000;
            long tenths = (millis % 1_000) / 100;
            if (tenths != 0) {
                return seconds + "." + tenths + "s";
            }
            return seconds + "s";
        }
        long minutes = millis / 60_000;
        long remainingSeconds = (millis % 60_000) / 1_000;
        if (remainingSeconds > 0) {
            return minutes + "m " + remainingSeconds + "s";
        }
        return minutes + "m";
    }

    public static String formatActivity(Long minutesSinceActivity) {
        if (minutesSinceActivity == null) {
            return "no activity recorded";
        }
        if (minutesSinceActivity == 0) {
            return "active just now";
        }
        if (minutesSinceActivity < 60) {
            return "active " + minutesSinceActivity + "m ago";
        }
        long hours = minutesSinceActivity / 60;
        long remainingMinutes = minutesSinceActivity % 60;
        if (remainingMinutes > 0) {
            return "active " + hours + "h " + remainingMinutes + "m ago";
        }
        return "active " + hours + "h ago";
    }

    public static String formatWaiters(int count) {
        return count == 1 ? "1 player waiting" : count + " players waiting";
    }

    private static String failureDetail(OperationalFailure failure) {
        return "last failure " + failure.timestamp() + " during " + failure.context()
                + ": " + failure.detail() + " Remediation: " + failure.remediation();
    }

    private static void appendDetail(StringBuilder target, String detail) {
        if (!target.isEmpty()) {
            target.append("; ");
        }
        target.append(detail);
    }

    private static Component argument(String content) {
        return Component.text(content, MessageTokens.ACTION);
    }

    private static Component serverNameComponent(String serverName) {
        return Component.text(serverName, MessageTokens.ACTION);
    }

    private static Component command(String commandText, String hoverDescription, boolean isPlayer) {
        TextComponent.Builder builder = Component.text()
                .content(commandText)
                .color(MessageTokens.ACTION);
        if (isPlayer) {
            builder.clickEvent(ClickEvent.suggestCommand(commandText));
            if (hoverDescription != null && !hoverDescription.isEmpty()) {
                builder.hoverEvent(HoverEvent.showText(Component.text(hoverDescription, MessageTokens.TEXT_PRIMARY)));
            }
        }
        return finish(builder);
    }

    private static Component failureHover(OperatorFailureGuide guide) {
        return finish(Component.text()
                .append(Component.text("Cause: ", MessageTokens.TEXT_MUTED))
                .append(Component.text(guide.cause(), MessageTokens.TEXT_PRIMARY))
                .append(Component.newline())
                .append(Component.text("Fix: ", MessageTokens.TEXT_MUTED))
                .append(Component.text(guide.remediation(), MessageTokens.TEXT_PRIMARY))
                .append(Component.newline())
                .append(Component.text("Outcome: ", MessageTokens.TEXT_MUTED))
                .append(Component.text(guide.outcomeCode(), MessageTokens.TEXT_PRIMARY))
                .append(Component.newline())
                .append(Component.text("Click to open troubleshooting", MessageTokens.ACTION)));
    }

    private static Component link(String label, String url, String hoverDescription) {
        return finish(Component.text()
                .content(label)
                .color(MessageTokens.ACTION)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(hoverDescription, MessageTokens.TEXT_PRIMARY))));
    }

    private static Component finish(ComponentLike componentLike) {
        return componentLike.asComponent();
    }
}
