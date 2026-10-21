package me.criseda.autostopper.docker;

import me.criseda.autostopper.config.DockerSettings;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Container operations with AutoStopper's deadline and stop-grace semantics. The transport is a
 * {@link DockerBackend} chosen from the current {@link DockerSettings}; a reload that changes them
 * takes effect on the next operation, while operations already running keep their backend.
 */
public final class DockerManager {
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(10);
    /** Docker's stop grace period when a container does not configure StopTimeout. */
    static final Duration DEFAULT_STOP_GRACE_PERIOD = Duration.ofSeconds(10);
    /** Upper bound on how long AutoStopper waits for a container's own stop grace period. */
    static final Duration MAX_STOP_GRACE_PERIOD = Duration.ofMinutes(10);
    private final Logger logger;
    private final Duration commandTimeout;
    private final Supplier<DockerSettings> settings;
    private final Function<DockerSettings, DockerBackend> backendFactory;
    private final Object selectionLock = new Object();
    private volatile Selection selection;

    /** Uses the Docker CLI only, regardless of configuration. */
    public DockerManager(Logger logger, CommandRunner commandRunner) {
        this(logger, commandRunner, DEFAULT_COMMAND_TIMEOUT);
    }

    /** Uses the Docker CLI only, regardless of configuration. */
    public DockerManager(Logger logger, CommandRunner commandRunner, Duration commandTimeout) {
        this(logger, new DockerCliBackend(logger, commandRunner, "Docker CLI"), commandTimeout);
    }

    /** Selects the backend and endpoint from {@code settings} on first use and after each change. */
    public DockerManager(Logger logger, CommandRunner commandRunner, Supplier<DockerSettings> settings) {
        this(logger, DEFAULT_COMMAND_TIMEOUT, settings,
                new DockerBackendSelector(logger, commandRunner)::select);
    }

    DockerManager(Logger logger, DockerBackend backend, Duration commandTimeout) {
        this(logger, commandTimeout, DockerSettings::defaults, ignored -> backend);
    }

    DockerManager(Logger logger, Duration commandTimeout, Supplier<DockerSettings> settings,
            Function<DockerSettings, DockerBackend> backendFactory) {
        if (commandTimeout.isNegative() || commandTimeout.isZero()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
        this.logger = logger;
        this.commandTimeout = commandTimeout;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.backendFactory = Objects.requireNonNull(backendFactory, "backendFactory");
    }

    /**
     * Describes how Docker is reached, for operator output. Never blocks; before the first Docker
     * operation no backend has been selected yet.
     */
    public String describeAccess() {
        Selection current = selection;
        return current == null ? "not selected yet (no Docker operation has run)" : current.backend().describe();
    }

    public ContainerStatus getContainerStatus(String containerName) {
        return inspectContainer(containerName, commandTimeout).status();
    }

    public ContainerStatus getContainerStatus(String containerName, Duration timeout) {
        return inspectContainer(containerName, timeout).status();
    }

    public ContainerInspection inspectContainer(String containerName) {
        return inspectContainer(containerName, commandTimeout);
    }

    public ContainerInspection inspectContainer(String containerName, Duration timeout) {
        requirePositive(timeout, "timeout");
        return backend().inspect(containerName, boundedCommandTimeout(timeout));
    }

    public ContainerHealth getContainerHealth(String containerName, Duration timeout) {
        requirePositive(timeout, "timeout");
        return backend().health(containerName, boundedCommandTimeout(timeout));
    }

    public ContainerStatus startContainer(String containerName) {
        DockerBackend backend = backend();
        ContainerStatus status = backend.inspect(containerName, commandTimeout).status();

        switch (status) {
            case RUNNING:
                logger.info("Container {} is already running.", containerName);
                return ContainerStatus.RUNNING;
            case STOPPED:
                break;
            case MISSING:
                logger.error("Cannot start container {}: container does not exist.", containerName);
                return status;
            case INACCESSIBLE:
                logger.error("Cannot start container {}: Docker daemon is inaccessible.", containerName);
                return status;
            case TIMED_OUT:
                logger.error("Cannot start container {}: status check timed out.", containerName);
                return status;
            case FAILED:
                logger.error("Cannot start container {}: status check failed.", containerName);
                return status;
        }

        logger.info("Starting container: {}", containerName);
        return backend.start(containerName, commandTimeout);
    }

    public ContainerStatus stopContainer(String containerName) {
        DockerBackend backend = backend();
        // Docker waits for the container's own StopTimeout before SIGKILL, so the stop deadline
        // must cover that grace period rather than cutting a graceful save short.
        Duration gracePeriod = stopGracePeriod(backend, containerName);
        Duration stopDeadline = gracePeriod.plus(commandTimeout);
        ContainerStatus result = backend.stop(containerName, stopDeadline);
        if (result != ContainerStatus.TIMED_OUT) {
            return result;
        }
        if (!Thread.currentThread().isInterrupted()
                && backend.inspect(containerName, commandTimeout).status() == ContainerStatus.STOPPED) {
            logger.warn("docker stop for container {} exceeded {}ms, but the container is stopped",
                    containerName, stopDeadline.toMillis());
            return ContainerStatus.STOPPED;
        }
        logger.error("Timed out after {}ms stopping container {} (stop grace period {}s)",
                stopDeadline.toMillis(), containerName, gracePeriod.toSeconds());
        return ContainerStatus.TIMED_OUT;
    }

    private Duration stopGracePeriod(DockerBackend backend, String containerName) {
        OptionalLong configured = backend.stopTimeoutSeconds(containerName, commandTimeout);
        if (configured.isEmpty()) {
            return DEFAULT_STOP_GRACE_PERIOD;
        }
        long seconds = configured.getAsLong();
        if (seconds < 0 || seconds > MAX_STOP_GRACE_PERIOD.toSeconds()) {
            logger.warn("Stop grace period {}s for container {} is unbounded or too long; waiting at most {}s",
                    seconds, containerName, MAX_STOP_GRACE_PERIOD.toSeconds());
            return MAX_STOP_GRACE_PERIOD;
        }
        return Duration.ofSeconds(seconds);
    }

    private DockerBackend backend() {
        DockerSettings wanted = settings.get();
        Selection current = selection;
        if (current != null && current.settings().equals(wanted)) {
            return current.backend();
        }
        synchronized (selectionLock) {
            current = selection;
            if (current == null || !current.settings().equals(wanted)) {
                DockerBackend backend = Objects.requireNonNull(backendFactory.apply(wanted), "backend");
                current = new Selection(wanted, backend);
                selection = current;
                logger.info("Docker access: {}", backend.describe());
            }
            return current.backend();
        }
    }

    static ContainerInspection diagnostic(ContainerStatus status, DockerDiagnostic diagnostic,
            String detail, String remediation) {
        return new ContainerInspection(status, diagnostic, detail, remediation);
    }

    static ContainerInspection containerMissing() {
        return diagnostic(ContainerStatus.MISSING, DockerDiagnostic.CONTAINER_MISSING,
                "configured container does not exist",
                "Create the container or correct its container_name mapping.");
    }

    static ContainerInspection permissionDenied() {
        return diagnostic(ContainerStatus.INACCESSIBLE, DockerDiagnostic.PERMISSION_DENIED,
                "permission denied accessing Docker",
                "Grant the proxy process access to the Docker socket or endpoint.");
    }

    static ContainerInspection daemonUnavailable() {
        return diagnostic(ContainerStatus.INACCESSIBLE, DockerDiagnostic.DAEMON_UNAVAILABLE,
                "Docker daemon is unavailable",
                "Start Docker and verify the configured Docker endpoint is reachable.");
    }

    private void requirePositive(Duration timeout, String name) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private Duration boundedCommandTimeout(Duration requested) {
        return requested.compareTo(commandTimeout) < 0 ? requested : commandTimeout;
    }

    private record Selection(DockerSettings settings, DockerBackend backend) {
    }
}
