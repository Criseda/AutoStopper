package me.criseda.autostopper.lifecycle;

import me.criseda.autostopper.telemetry.TelemetryOutcome;

/**
 * Result of one shared startup operation, distinguishing the stage that failed so that player
 * messages, operator diagnostics, telemetry, and manual command outcomes can each be derived.
 */
enum StartupOutcome {
    READY_RUNNING(true, ConnectionOutcome.CONNECTED),
    READY_AFTER_START(true, ConnectionOutcome.CONNECTED),
    STATUS_NO_MAPPING(false, ConnectionOutcome.STATUS_FAILED),
    STATUS_MISSING(false, ConnectionOutcome.CONTAINER_MISSING),
    STATUS_INACCESSIBLE(false, ConnectionOutcome.DOCKER_INACCESSIBLE),
    STATUS_TIMED_OUT(false, ConnectionOutcome.STATUS_FAILED),
    STATUS_FAILED(false, ConnectionOutcome.STATUS_FAILED),
    STATUS_ERROR(false, ConnectionOutcome.STATUS_FAILED),
    START_MISSING(false, ConnectionOutcome.CONTAINER_MISSING),
    START_INACCESSIBLE(false, ConnectionOutcome.DOCKER_INACCESSIBLE),
    START_TIMED_OUT(false, ConnectionOutcome.START_TIMED_OUT),
    START_FAILED(false, ConnectionOutcome.START_FAILED),
    START_ERROR(false, ConnectionOutcome.START_FAILED),
    NOT_READY(false, ConnectionOutcome.SERVER_NOT_READY),
    READINESS_ERROR(false, ConnectionOutcome.SERVER_NOT_READY),
    CANCELLED(false, ConnectionOutcome.START_CANCELLED),
    OVERLOADED(false, ConnectionOutcome.OVERLOADED),
    /** The proxy shut down before the startup finished; set only by shutdown, never by the pipeline. */
    PROXY_SHUTDOWN(false, ConnectionOutcome.PROXY_SHUTDOWN);

    private final boolean ready;
    private final ConnectionOutcome connectionOutcome;

    StartupOutcome(boolean ready, ConnectionOutcome connectionOutcome) {
        this.ready = ready;
        this.connectionOutcome = connectionOutcome;
    }

    boolean isReady() {
        return ready;
    }

    ConnectionOutcome connectionOutcome() {
        return connectionOutcome;
    }

    TelemetryOutcome toTelemetryOutcome() {
        return switch (this) {
            case READY_RUNNING, READY_AFTER_START -> TelemetryOutcome.READY;
            case STATUS_NO_MAPPING -> TelemetryOutcome.NO_MAPPING;
            case STATUS_MISSING, START_MISSING -> TelemetryOutcome.CONTAINER_MISSING;
            case STATUS_INACCESSIBLE, START_INACCESSIBLE -> TelemetryOutcome.DOCKER_INACCESSIBLE;
            case STATUS_TIMED_OUT -> TelemetryOutcome.STATUS_TIMED_OUT;
            case STATUS_FAILED, STATUS_ERROR -> TelemetryOutcome.STATUS_FAILED;
            case START_TIMED_OUT -> TelemetryOutcome.START_TIMED_OUT;
            case START_FAILED, START_ERROR -> TelemetryOutcome.START_FAILED;
            case NOT_READY, READINESS_ERROR -> TelemetryOutcome.SERVER_NOT_READY;
            case CANCELLED -> TelemetryOutcome.CANCELLED;
            case OVERLOADED -> TelemetryOutcome.OVERLOADED;
            case PROXY_SHUTDOWN -> TelemetryOutcome.PROXY_SHUTDOWN;
        };
    }

    ManualStartOutcome toManualStartOutcome() {
        return switch (this) {
            case READY_RUNNING, READY_AFTER_START -> ManualStartOutcome.READY;
            case STATUS_NO_MAPPING -> ManualStartOutcome.MAPPING_CHANGED;
            case CANCELLED -> ManualStartOutcome.CANCELLED;
            case STATUS_MISSING, START_MISSING -> ManualStartOutcome.CONTAINER_MISSING;
            case STATUS_INACCESSIBLE, START_INACCESSIBLE -> ManualStartOutcome.DOCKER_INACCESSIBLE;
            case STATUS_TIMED_OUT -> ManualStartOutcome.STATUS_TIMED_OUT;
            case STATUS_FAILED, STATUS_ERROR -> ManualStartOutcome.STATUS_FAILED;
            case START_TIMED_OUT -> ManualStartOutcome.START_TIMED_OUT;
            case START_FAILED, START_ERROR -> ManualStartOutcome.START_FAILED;
            case NOT_READY, READINESS_ERROR -> ManualStartOutcome.SERVER_NOT_READY;
            case OVERLOADED -> ManualStartOutcome.OVERLOADED;
            case PROXY_SHUTDOWN -> ManualStartOutcome.PROXY_SHUTDOWN;
        };
    }

    ManualRestartOutcome toManualRestartOutcome() {
        return switch (this) {
            case READY_RUNNING, READY_AFTER_START -> ManualRestartOutcome.RESTARTED_AND_READY;
            case STATUS_NO_MAPPING -> ManualRestartOutcome.MAPPING_CHANGED;
            case CANCELLED -> ManualRestartOutcome.CANCELLED;
            case STATUS_MISSING, START_MISSING -> ManualRestartOutcome.CONTAINER_MISSING;
            case STATUS_INACCESSIBLE, START_INACCESSIBLE -> ManualRestartOutcome.DOCKER_INACCESSIBLE;
            case STATUS_TIMED_OUT, START_TIMED_OUT -> ManualRestartOutcome.START_TIMED_OUT;
            case STATUS_FAILED, STATUS_ERROR, START_FAILED, START_ERROR -> ManualRestartOutcome.START_FAILED;
            case NOT_READY, READINESS_ERROR -> ManualRestartOutcome.SERVER_NOT_READY;
            case OVERLOADED -> ManualRestartOutcome.OVERLOADED;
            case PROXY_SHUTDOWN -> ManualRestartOutcome.PROXY_SHUTDOWN;
        };
    }

    String failureDetail() {
        return switch (this) {
            case STATUS_NO_MAPPING -> "no active container mapping";
            case STATUS_MISSING, START_MISSING -> "configured container does not exist";
            case STATUS_INACCESSIBLE, START_INACCESSIBLE -> "Docker is unavailable";
            case STATUS_TIMED_OUT, START_TIMED_OUT -> "Docker operation timed out";
            case OVERLOADED -> "AutoStopper worker queue is saturated";
            case CANCELLED -> "startup was cancelled";
            case STATUS_FAILED, STATUS_ERROR, START_FAILED, START_ERROR -> "Docker operation failed";
            case NOT_READY, READINESS_ERROR -> "server readiness check failed";
            case READY_RUNNING, READY_AFTER_START -> throw new IllegalArgumentException("ready outcome is not a failure");
            case PROXY_SHUTDOWN -> throw new IllegalArgumentException("shutdown outcome is not a startup failure");
        };
    }

    String remediation() {
        return switch (this) {
            case STATUS_NO_MAPPING -> "Reload a valid monitored server mapping.";
            case STATUS_MISSING, START_MISSING -> "Create the container or correct container_name, then retry.";
            case STATUS_INACCESSIBLE, START_INACCESSIBLE -> "Restore Docker daemon and socket access, then retry.";
            case STATUS_TIMED_OUT, START_TIMED_OUT -> "Check Docker daemon responsiveness and host load, then retry.";
            case OVERLOADED -> "Wait for current AutoStopper operations to finish, then retry.";
            case CANCELLED -> "Retry after the current reload or shutdown completes.";
            case STATUS_FAILED, STATUS_ERROR, START_FAILED, START_ERROR -> "Review proxy logs and Docker state, then retry.";
            case NOT_READY, READINESS_ERROR -> "Verify the configured readiness strategy and backend endpoint, then retry.";
            case READY_RUNNING, READY_AFTER_START -> throw new IllegalArgumentException("ready outcome is not a failure");
            case PROXY_SHUTDOWN -> throw new IllegalArgumentException("shutdown outcome is not a startup failure");
        };
    }
}
