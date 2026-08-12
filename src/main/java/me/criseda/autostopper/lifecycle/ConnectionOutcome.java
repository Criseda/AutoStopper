package me.criseda.autostopper.lifecycle;

public enum ConnectionOutcome {
    CONNECTED(true),
    ALREADY_CONNECTED(true),
    CONNECTION_IN_PROGRESS(false),
    CONNECTION_CANCELLED(false),
    SERVER_DISCONNECTED(false),
    CONNECTION_FAILED(false),
    PLAYER_DISCONNECTED(false),
    SERVER_STOPPING(false),
    MAPPING_CHANGED(false),
    STATUS_FAILED(false),
    START_FAILED(false),
    START_TIMED_OUT(false),
    START_CANCELLED(false),
    CONTAINER_MISSING(false),
    DOCKER_INACCESSIBLE(false),
    SERVER_NOT_READY(false),
    OVERLOADED(false),
    PROXY_SHUTDOWN(false);

    private final boolean successful;

    ConnectionOutcome(boolean successful) {
        this.successful = successful;
    }

    public boolean isSuccessful() {
        return successful;
    }
}
