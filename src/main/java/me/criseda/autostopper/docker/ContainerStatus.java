package me.criseda.autostopper.docker;

public enum ContainerStatus {
    RUNNING,
    STOPPED,
    MISSING,
    INACCESSIBLE,
    FAILED,
    TIMED_OUT;
}