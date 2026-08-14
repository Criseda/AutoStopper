package me.criseda.autostopper.messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.operational.OperationalServerStatus;
import me.criseda.autostopper.operational.OperationalState;

public final class AutoStopperMessages {
    static final NamedTextColor BRAND_COLOR = NamedTextColor.GOLD;
    static final NamedTextColor COMMAND_COLOR = NamedTextColor.AQUA;
    static final NamedTextColor ARGUMENT_COLOR = NamedTextColor.YELLOW;
    static final NamedTextColor NEUTRAL_COLOR = NamedTextColor.GRAY;
    static final NamedTextColor SUCCESS_COLOR = NamedTextColor.GREEN;
    static final NamedTextColor WARNING_COLOR = NamedTextColor.YELLOW;
    static final NamedTextColor ERROR_COLOR = NamedTextColor.RED;

    private static final Component PREFIX = Component.text("[AutoStopper] ", BRAND_COLOR);

    private AutoStopperMessages() {
    }

    public static Component pluginInfo(String version) {
        return Component.text()
                .append(Component.text("AutoStopper ", BRAND_COLOR))
                .append(argument(version))
                .append(Component.text(" - ", NEUTRAL_COLOR))
                .append(Component.text("Server Auto-Stop Plugin", ARGUMENT_COLOR))
                .build();
    }

    public static Component helpHint() {
        return Component.text()
                .append(Component.text("Use ", NEUTRAL_COLOR))
                .append(command("/autostopper help"))
                .append(Component.text(" for more information", NEUTRAL_COLOR))
                .build();
    }

    public static Component unknownCommand() {
        return prefixed()
                .append(Component.text("Unknown command. Use ", ERROR_COLOR))
                .append(command("/autostopper help"))
                .append(Component.text(" for help.", ERROR_COLOR))
                .build();
    }

    public static Component helpHeader() {
        return Component.text("AutoStopper Help:", BRAND_COLOR).decorate(TextDecoration.BOLD);
    }

    public static Component helpEntry(String commandText, String description) {
        return Component.text()
                .append(command(commandText))
                .append(Component.text(" - " + description, NEUTRAL_COLOR))
                .build();
    }

    public static Component statusHeader() {
        return Component.text("AutoStopper Server Status:", BRAND_COLOR).decorate(TextDecoration.BOLD);
    }

    public static Component statusCollectionFailed() {
        return error("Could not collect server statuses.");
    }

    public static Component statusNoMapping(String serverName) {
        return statusLine(serverName, "No container mapping", ERROR_COLOR, null);
    }

    public static Component statusRunning(String serverName, Long minutesSinceActivity) {
        String detail = minutesSinceActivity == null
                ? "No activity recorded"
                : minutesSinceActivity + " minutes since last activity";
        return statusLine(serverName, "Running", SUCCESS_COLOR, detail);
    }

    public static Component statusStopped(String serverName) {
        return statusLine(serverName, "Stopped", ERROR_COLOR, null);
    }

    public static Component statusMissing(String serverName) {
        return statusLine(serverName, "Missing", ERROR_COLOR, "container does not exist");
    }

    public static Component statusInaccessible(String serverName) {
        return statusLine(serverName, "Inaccessible", ERROR_COLOR, "Docker daemon unreachable");
    }

    public static Component statusTimedOut(String serverName) {
        return statusLine(serverName, "Timed out", WARNING_COLOR,
                "status check did not respond in time");
    }

    public static Component statusFailed(String serverName) {
        return statusLine(serverName, "Failed", ERROR_COLOR, "status check failed");
    }

    public static Component operationalStatus(String serverName, OperationalServerStatus status,
            Long minutesSinceActivity) {
        StringBuilder detail = new StringBuilder();
        if (status.waitingPlayers() > 0) {
            detail.append(status.waitingPlayers()).append(" player(s) waiting");
        }
        if (status.state() == OperationalState.READY || status.state() == OperationalState.RUNNING_UNVERIFIED) {
            appendDetail(detail, minutesSinceActivity == null
                    ? "No activity recorded"
                    : minutesSinceActivity + " minutes since last activity");
        }
        status.lastFailure().ifPresent(failure -> appendDetail(detail, failureDetail(failure)));
        NamedTextColor color = switch (status.state()) {
            case READY -> SUCCESS_COLOR;
            case STARTING, STOPPING, RUNNING_UNVERIFIED -> WARNING_COLOR;
            case STOPPED -> NEUTRAL_COLOR;
            case FAILED, DOCKER_UNAVAILABLE -> ERROR_COLOR;
        };
        return statusLine(serverName, status.state().name(), color,
                detail.isEmpty() ? null : detail.toString());
    }

    public static Component reloadStarted() {
        return progress("Reloading AutoStopper configuration...");
    }

    public static Component reloadFailed(String errorSummary) {
        return prefixed()
                .append(Component.text("Configuration reload failed: ", ERROR_COLOR))
                .append(argument(errorSummary))
                .build();
    }

    public static Component reloadSucceeded() {
        return success("Configuration reloaded successfully!");
    }

    public static Component preflightCompleted(int healthy, int degraded) {
        if (degraded == 0) {
            return success("Operational preflight passed for " + healthy + " mapping(s).");
        }
        return warning("Operational preflight found " + degraded + " degraded mapping(s); "
                + "use /autostopper status for remediation.");
    }

    public static Component permissionDenied(String action) {
        return prefixed()
                .append(Component.text("You do not have permission to ", ERROR_COLOR))
                .append(Component.text(action, ERROR_COLOR))
                .append(Component.text(".", ERROR_COLOR))
                .build();
    }

    public static Component serverAlreadyStarting() {
        return progress("Server is already being started, please wait...");
    }

    public static Component serverStopping(String serverName) {
        return serverMessage(WARNING_COLOR, "Server ", serverName,
                " is currently stopping. Please try again shortly.");
    }

    public static Component mappingChanged(String serverName) {
        return serverMessage(WARNING_COLOR, "The container mapping for server ", serverName,
                " changed while another operation was running. Please try again.");
    }

    public static Component overloaded() {
        return warning("AutoStopper is overloaded right now; please try again in a moment.");
    }

    public static Component statusCheckError(String serverName) {
        return serverMessage(ERROR_COLOR, "Error checking status of server ", serverName, ".");
    }

    public static Component noContainerMapping(String serverName) {
        return serverMessage(ERROR_COLOR, "Server ", serverName, " has no container mapping.");
    }

    public static Component containerMissing(String serverName) {
        return serverMessage(ERROR_COLOR, "The container for server ", serverName, " does not exist.");
    }

    public static Component dockerUnavailable(String operation, String serverName) {
        return serverMessage(ERROR_COLOR, "Cannot reach the Docker daemon to " + operation + " server ",
                serverName, ".");
    }

    public static Component statusCheckTimedOut(String serverName) {
        return serverMessage(WARNING_COLOR, "Could not check the status of server ", serverName,
                " in time. Try again.");
    }

    public static Component statusCheckFailed(String serverName) {
        return serverMessage(ERROR_COLOR, "Could not check the status of server ", serverName, ".");
    }

    public static Component serverOfflineStarting() {
        return progress("Server is currently offline. Starting it up for you...");
    }

    public static Component startError(String serverName) {
        return serverMessage(ERROR_COLOR, "Error starting server ", serverName, ".");
    }

    public static Component startTimedOut(String serverName) {
        return serverMessage(WARNING_COLOR, "Timed out starting server ", serverName, ". Try again.");
    }

    public static Component startFailed(String serverName) {
        return serverMessage(ERROR_COLOR, "Failed to start server ", serverName, ".");
    }

    public static Component startCancelled(String serverName) {
        return serverMessage(WARNING_COLOR, "Starting server ", serverName,
                " was cancelled. Please try again.");
    }

    public static Component serverNotReady(String serverName) {
        return serverMessage(WARNING_COLOR, "Server ", serverName, " is not ready.");
    }

    public static Component serverNotReady(String serverName, String detail) {
        return prefixed()
                .append(Component.text("Server ", WARNING_COLOR))
                .append(argument(serverName))
                .append(Component.text(" is not ready. " + detail, WARNING_COLOR))
                .build();
    }

    public static Component serverReady(String serverName) {
        return serverMessage(SUCCESS_COLOR, "Server ", serverName, " is now ready!");
    }

    public static Component retryServerCommand(String serverName) {
        return prefixed()
                .append(Component.text("Try again in a moment with ", WARNING_COLOR))
                .append(command("/server "))
                .append(argument(serverName))
                .build();
    }

    public static Component connectionFailed(String serverName) {
        return serverMessage(ERROR_COLOR, "Could not connect you to server ", serverName, ".");
    }

    public static Component connectionCancelled(String serverName) {
        return serverMessage(WARNING_COLOR, "Your connection to server ", serverName,
                " was cancelled. Please try again.");
    }

    public static Component connectionInProgress(String serverName) {
        return serverMessage(WARNING_COLOR, "A connection to server ", serverName,
                " is already in progress.");
    }

    public static Component connectionRefused(String serverName) {
        return serverMessage(ERROR_COLOR, "Server ", serverName,
                " refused the connection. Please try again.");
    }

    private static Component statusLine(String serverName, String status,
            NamedTextColor statusColor, String detail) {
        TextComponent.Builder message = Component.text()
                .append(argument(serverName))
                .append(Component.text(": ", NEUTRAL_COLOR))
                .append(Component.text(status, statusColor).decorate(TextDecoration.BOLD));
        if (detail != null) {
            message.append(Component.text(" - " + detail, NEUTRAL_COLOR));
        }
        return message.build();
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

    private static Component serverMessage(NamedTextColor color, String before,
            String serverName, String after) {
        return prefixed()
                .append(Component.text(before, color))
                .append(argument(serverName))
                .append(Component.text(after, color))
                .build();
    }

    private static Component progress(String content) {
        return message(content, WARNING_COLOR);
    }

    private static Component warning(String content) {
        return message(content, WARNING_COLOR);
    }

    private static Component success(String content) {
        return message(content, SUCCESS_COLOR);
    }

    private static Component error(String content) {
        return message(content, ERROR_COLOR);
    }

    private static Component message(String content, NamedTextColor color) {
        return prefixed().append(Component.text(content, color)).build();
    }

    private static TextComponent.Builder prefixed() {
        return Component.text().append(PREFIX);
    }

    private static Component command(String content) {
        return Component.text(content, COMMAND_COLOR);
    }

    private static Component argument(String content) {
        return Component.text(content, ARGUMENT_COLOR);
    }
}
