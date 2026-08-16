package me.criseda.autostopper.telemetry;

/**
 * Categorizes lifecycle operations and intermediate stages monitored by AutoStopper telemetry.
 */
public enum TelemetryOperationType {
    /**
     * Authoritative shared container startup and readiness sequence.
     */
    STARTUP,

    /**
     * Command-initiated manual start sequence.
     */
    MANUAL_START,

    /**
     * Command-initiated manual stop sequence.
     */
    MANUAL_STOP,

    /**
     * Inactivity-driven background automatic stop sequence.
     */
    AUTOMATIC_STOP,

    /**
     * Command-initiated manual restart sequence.
     */
    MANUAL_RESTART,

    /**
     * Individual player connection waiter lifecycle from request to connect or failure.
     */
    CONNECTION_WAIT,

    /**
     * Intermediate container status/inspection check stage.
     */
    STATUS_CHECK,

    /**
     * Intermediate Docker container start stage.
     */
    CONTAINER_START,

    /**
     * Intermediate backend readiness verification stage.
     */
    READINESS_CHECK
}
