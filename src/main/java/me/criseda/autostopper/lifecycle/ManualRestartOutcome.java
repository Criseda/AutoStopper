package me.criseda.autostopper.lifecycle;

public enum ManualRestartOutcome {
    RESTARTED_AND_READY(true),
    PLAYERS_CONNECTED(false),
    WAITERS_PRESENT(false),
    SERVER_STARTING(false),
    SERVER_STOPPING(false),
    MAPPING_CHANGED(false),
    CONTAINER_MISSING(false),
    DOCKER_INACCESSIBLE(false),
    STOP_TIMED_OUT(false),
    STOP_FAILED(false),
    START_TIMED_OUT(false),
    START_FAILED(false),
    SERVER_NOT_READY(false),
    OVERLOADED(false),
    CANCELLED(false),
    PROXY_SHUTDOWN(false);

    private final boolean successful;

    ManualRestartOutcome(boolean successful) {
        this.successful = successful;
    }

    public boolean isSuccessful() {
        return successful;
    }
}
