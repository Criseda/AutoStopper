package me.criseda.autostopper.telemetry;

/**
 * Identifies the initiator of a lifecycle operation.
 */
public enum TelemetryOrigin {
    /**
     * Initiated by a player connection attempt via Velocity.
     */
    PLAYER_CONNECTION,

    /**
     * Initiated by an operator executing an /autostopper command.
     */
    MANUAL_COMMAND,

    /**
     * Initiated by background inactivity tracking.
     */
    ACTIVITY_TRACKER,

    /**
     * Initiated by operational status polling or preflight diagnostics.
     */
    STATUS_POLL,

    /**
     * Initiated by internal proxy lifecycle events such as reload or shutdown.
     */
    INTERNAL
}
