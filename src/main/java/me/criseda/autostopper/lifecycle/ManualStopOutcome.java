package me.criseda.autostopper.lifecycle;

public enum ManualStopOutcome {
    STOPPED(true),
    ALREADY_STOPPED(true),
    PLAYERS_CONNECTED(false),
    WAITERS_PRESENT(false),
    SERVER_STARTING(false),
    SERVER_STOPPING(false),
    MAPPING_CHANGED(false),
    CONTAINER_MISSING(false),
    DOCKER_INACCESSIBLE(false),
    STOP_TIMED_OUT(false),
    STOP_FAILED(false),
    OVERLOADED(false),
    CANCELLED(false),
    PROXY_SHUTDOWN(false);

    private final boolean successful;

    ManualStopOutcome(boolean successful) {
        this.successful = successful;
    }

    public boolean isSuccessful() {
        return successful;
    }
}
