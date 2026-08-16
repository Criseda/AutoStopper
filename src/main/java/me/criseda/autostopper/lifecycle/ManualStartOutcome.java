package me.criseda.autostopper.lifecycle;

public enum ManualStartOutcome {
    ALREADY_READY(true),
    READY(true),
    SERVER_STOPPING(false),
    MAPPING_CHANGED(false),
    CONTAINER_MISSING(false),
    DOCKER_INACCESSIBLE(false),
    STATUS_TIMED_OUT(false),
    STATUS_FAILED(false),
    START_TIMED_OUT(false),
    START_FAILED(false),
    SERVER_NOT_READY(false),
    OVERLOADED(false),
    CANCELLED(false),
    PROXY_SHUTDOWN(false);

    private final boolean successful;

    ManualStartOutcome(boolean successful) {
        this.successful = successful;
    }

    public boolean isSuccessful() {
        return successful;
    }
}
