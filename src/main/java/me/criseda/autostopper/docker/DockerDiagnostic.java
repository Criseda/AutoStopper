package me.criseda.autostopper.docker;

/** Safe, operator-facing classification of a Docker inspect result. */
public enum DockerDiagnostic {
    HEALTHY,
    CLI_MISSING,
    DAEMON_UNAVAILABLE,
    PERMISSION_DENIED,
    CONTAINER_MISSING,
    TIMED_OUT,
    INDETERMINATE;

    public boolean dockerUnavailable() {
        return this == CLI_MISSING || this == DAEMON_UNAVAILABLE || this == PERMISSION_DENIED;
    }
}
