package me.criseda.autostopper.docker;

public enum ContainerHealth {
    HEALTHY,
    STARTING,
    UNHEALTHY,
    NO_HEALTHCHECK,
    STOPPED,
    MISSING,
    INACCESSIBLE,
    TIMED_OUT,
    FAILED
}
