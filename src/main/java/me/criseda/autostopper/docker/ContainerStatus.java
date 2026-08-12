package me.criseda.autostopper.docker;

public enum ContainerStatus {
    RUNNING,
    STOPPED,
    MISSING,
    INACCESSIBLE,
    FAILED,
    TIMED_OUT;

    public boolean isIndeterminate() {
        return this != RUNNING && this != STOPPED;
    }
}