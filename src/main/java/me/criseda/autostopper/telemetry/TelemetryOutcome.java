package me.criseda.autostopper.telemetry;

import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.lifecycle.ConnectionOutcome;
import me.criseda.autostopper.lifecycle.ManualRestartOutcome;
import me.criseda.autostopper.lifecycle.ManualStartOutcome;
import me.criseda.autostopper.lifecycle.ManualStopOutcome;
import me.criseda.autostopper.readiness.ReadinessResult;

import java.util.Objects;

/**
 * Standardized terminal outcomes and stage results for AutoStopper lifecycle telemetry.
 */
public enum TelemetryOutcome {
    // Success & Ready outcomes
    READY,
    RUNNING,
    STOPPED,
    RESTARTED_AND_READY,
    CONNECTED,

    // Idempotency & already-in-desired-state outcomes
    ALREADY_READY,
    ALREADY_STOPPED,
    ALREADY_CONNECTED,

    // Preconditions & active state refusals
    PLAYERS_CONNECTED,
    WAITERS_PRESENT,
    SERVER_STARTING,
    SERVER_STOPPING,

    // Configuration & container mapping issues
    MAPPING_CHANGED,
    NO_MAPPING,
    CONTAINER_MISSING,

    // Docker daemon & infrastructure failures
    DOCKER_INACCESSIBLE,
    STATUS_TIMED_OUT,
    STATUS_FAILED,
    START_TIMED_OUT,
    START_FAILED,
    STOP_TIMED_OUT,
    STOP_FAILED,

    // Readiness check failures
    SERVER_NOT_READY,

    // Velocity player connection outcomes
    CONNECTION_FAILED,
    SERVER_DISCONNECTED,
    CONNECTION_CANCELLED,
    CONNECTION_IN_PROGRESS,
    PLAYER_DISCONNECTED,

    // Concurrency, rate limiting, and lifecycle cancellation
    OVERLOADED,
    CANCELLED,
    PROXY_SHUTDOWN;

    public static TelemetryOutcome from(ConnectionOutcome outcome) {
        if (outcome == null) {
            return CONNECTION_FAILED;
        }
        return switch (outcome) {
            case CONNECTED -> CONNECTED;
            case ALREADY_CONNECTED -> ALREADY_CONNECTED;
            case CONNECTION_IN_PROGRESS -> CONNECTION_IN_PROGRESS;
            case PLAYER_DISCONNECTED -> PLAYER_DISCONNECTED;
            case SERVER_STOPPING -> SERVER_STOPPING;
            case SERVER_DISCONNECTED -> SERVER_DISCONNECTED;
            case CONNECTION_FAILED -> CONNECTION_FAILED;
            case CONNECTION_CANCELLED -> CONNECTION_CANCELLED;
            case MAPPING_CHANGED -> MAPPING_CHANGED;
            case STATUS_FAILED -> STATUS_FAILED;
            case START_FAILED -> START_FAILED;
            case START_TIMED_OUT -> START_TIMED_OUT;
            case START_CANCELLED -> CANCELLED;
            case CONTAINER_MISSING -> CONTAINER_MISSING;
            case DOCKER_INACCESSIBLE -> DOCKER_INACCESSIBLE;
            case SERVER_NOT_READY -> SERVER_NOT_READY;
            case OVERLOADED -> OVERLOADED;
            case PROXY_SHUTDOWN -> PROXY_SHUTDOWN;
        };
    }

    public static TelemetryOutcome from(ManualStartOutcome outcome) {
        if (outcome == null) {
            return START_FAILED;
        }
        return switch (outcome) {
            case ALREADY_READY -> ALREADY_READY;
            case READY -> READY;
            case SERVER_STOPPING -> SERVER_STOPPING;
            case MAPPING_CHANGED -> MAPPING_CHANGED;
            case CONTAINER_MISSING -> CONTAINER_MISSING;
            case DOCKER_INACCESSIBLE -> DOCKER_INACCESSIBLE;
            case STATUS_TIMED_OUT -> STATUS_TIMED_OUT;
            case STATUS_FAILED -> STATUS_FAILED;
            case START_TIMED_OUT -> START_TIMED_OUT;
            case START_FAILED -> START_FAILED;
            case SERVER_NOT_READY -> SERVER_NOT_READY;
            case OVERLOADED -> OVERLOADED;
            case CANCELLED -> CANCELLED;
            case PROXY_SHUTDOWN -> PROXY_SHUTDOWN;
        };
    }

    public static TelemetryOutcome from(ManualStopOutcome outcome) {
        if (outcome == null) {
            return STOP_FAILED;
        }
        return switch (outcome) {
            case STOPPED -> STOPPED;
            case ALREADY_STOPPED -> ALREADY_STOPPED;
            case PLAYERS_CONNECTED -> PLAYERS_CONNECTED;
            case WAITERS_PRESENT -> WAITERS_PRESENT;
            case SERVER_STARTING -> SERVER_STARTING;
            case SERVER_STOPPING -> SERVER_STOPPING;
            case MAPPING_CHANGED -> MAPPING_CHANGED;
            case CONTAINER_MISSING -> CONTAINER_MISSING;
            case DOCKER_INACCESSIBLE -> DOCKER_INACCESSIBLE;
            case STOP_TIMED_OUT -> STOP_TIMED_OUT;
            case STOP_FAILED -> STOP_FAILED;
            case OVERLOADED -> OVERLOADED;
            case CANCELLED -> CANCELLED;
            case PROXY_SHUTDOWN -> PROXY_SHUTDOWN;
        };
    }

    public static TelemetryOutcome from(ManualRestartOutcome outcome) {
        if (outcome == null) {
            return STOP_FAILED;
        }
        return switch (outcome) {
            case RESTARTED_AND_READY -> RESTARTED_AND_READY;
            case PLAYERS_CONNECTED -> PLAYERS_CONNECTED;
            case WAITERS_PRESENT -> WAITERS_PRESENT;
            case SERVER_STARTING -> SERVER_STARTING;
            case SERVER_STOPPING -> SERVER_STOPPING;
            case MAPPING_CHANGED -> MAPPING_CHANGED;
            case CONTAINER_MISSING -> CONTAINER_MISSING;
            case DOCKER_INACCESSIBLE -> DOCKER_INACCESSIBLE;
            case STOP_TIMED_OUT -> STOP_TIMED_OUT;
            case STOP_FAILED -> STOP_FAILED;
            case START_TIMED_OUT -> START_TIMED_OUT;
            case START_FAILED -> START_FAILED;
            case SERVER_NOT_READY -> SERVER_NOT_READY;
            case OVERLOADED -> OVERLOADED;
            case CANCELLED -> CANCELLED;
            case PROXY_SHUTDOWN -> PROXY_SHUTDOWN;
        };
    }

    public static TelemetryOutcome from(ContainerStatus status) {
        if (status == null) {
            return STATUS_FAILED;
        }
        return switch (status) {
            case RUNNING -> RUNNING;
            case STOPPED -> STOPPED;
            case MISSING -> CONTAINER_MISSING;
            case INACCESSIBLE -> DOCKER_INACCESSIBLE;
            case TIMED_OUT -> STATUS_TIMED_OUT;
            case FAILED -> STATUS_FAILED;
        };
    }

    public static TelemetryOutcome from(ReadinessResult.Outcome outcome) {
        if (outcome == null) {
            return SERVER_NOT_READY;
        }
        return switch (outcome) {
            case READY -> READY;
            case TIMED_OUT -> STATUS_TIMED_OUT;
            case CONTAINER_STOPPED -> STOPPED;
            case CONTAINER_MISSING -> CONTAINER_MISSING;
            case DOCKER_INACCESSIBLE -> DOCKER_INACCESSIBLE;
            case DOCKER_FAILED, NO_HEALTHCHECK, INVALID_TARGET -> SERVER_NOT_READY;
            case INTERRUPTED -> CANCELLED;
        };
    }
}
