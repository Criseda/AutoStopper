package me.criseda.autostopper.docker;

import java.time.Duration;
import java.util.OptionalLong;

/**
 * One way of reaching Docker. Implementations classify failures into safe statuses and keep raw
 * Docker output in operator logs. Every call is blocking and must finish within its deadline.
 */
interface DockerBackend {
    ContainerInspection inspect(String containerName, Duration timeout);

    ContainerHealth health(String containerName, Duration timeout);

    /** Starts a container that was just observed as stopped. */
    ContainerStatus start(String containerName, Duration timeout);

    /** The container's configured stop grace period in seconds, or empty when unset or unreadable. */
    OptionalLong stopTimeoutSeconds(String containerName, Duration timeout);

    /**
     * Stops a container, waiting for its own grace period. Returns {@link ContainerStatus#TIMED_OUT}
     * when the deadline expires or the thread is interrupted, without re-checking the container.
     */
    ContainerStatus stop(String containerName, Duration deadline);

    /** A short operator-facing description, for example {@code Docker CLI}. */
    String describe();
}
