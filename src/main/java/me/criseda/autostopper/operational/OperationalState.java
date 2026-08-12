package me.criseda.autostopper.operational;

public enum OperationalState {
    STOPPED,
    STARTING,
    READY,
    STOPPING,
    FAILED,
    DOCKER_UNAVAILABLE
}
