package me.criseda.autostopper.operational;

public enum OperationalState {
    STOPPED,
    STARTING,
    READY,
    RUNNING_UNVERIFIED,
    STOPPING,
    FAILED,
    DOCKER_UNAVAILABLE
}
