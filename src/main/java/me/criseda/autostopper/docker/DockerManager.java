package me.criseda.autostopper.docker;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

public final class DockerManager {
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private final Logger logger;
    private final CommandRunner commandRunner;
    private final Duration commandTimeout;

    public DockerManager(Logger logger, CommandRunner commandRunner) {
        this(logger, commandRunner, DEFAULT_COMMAND_TIMEOUT);
    }

    public DockerManager(Logger logger, CommandRunner commandRunner, Duration commandTimeout) {
        if (commandTimeout.isNegative() || commandTimeout.isZero()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
        this.logger = logger;
        this.commandRunner = commandRunner;
        this.commandTimeout = commandTimeout;
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
        Duration effectiveTimeout = boundedCommandTimeout(timeout);
        CommandOutput output = commandRunner.run(List.of(
                "docker", "inspect", "-f", "{{.State.Running}}", containerName), effectiveTimeout);

        switch (output.outcome()) {
            case TIMED_OUT:
                logger.warn("Timed out after {}ms checking status for container {}: {}",
                        effectiveTimeout.toMillis(), containerName, output.stderr().trim());
                return diagnostic(ContainerStatus.TIMED_OUT, DockerDiagnostic.TIMED_OUT,
                        "Docker status check timed out",
                        "Check Docker daemon responsiveness and host load, then retry.");
            case SPAWN_FAILED:
                logger.error("Could not execute docker inspect for container {}: {}",
                        containerName, output.stderr());
                if (isCliMissingError(output.stderr().toLowerCase(Locale.ROOT))) {
                    return diagnostic(ContainerStatus.FAILED, DockerDiagnostic.CLI_MISSING,
                            "Docker CLI could not be found",
                            "Install the Docker CLI and ensure the proxy process can find it on PATH.");
                }
                return diagnostic(ContainerStatus.FAILED, DockerDiagnostic.INDETERMINATE,
                        "Docker CLI could not be started",
                        "Verify the Docker executable and proxy process permissions, then retry.");
            default:
                break;
        }

        if (output.exitCode() == 0) {
            String state = output.stdout().trim();
            if ("true".equalsIgnoreCase(state)) {
                return ContainerInspection.healthy(ContainerStatus.RUNNING);
            }
            if ("false".equalsIgnoreCase(state)) {
                return ContainerInspection.healthy(ContainerStatus.STOPPED);
            }
            logger.warn("Unexpected output from docker inspect for container {}: {} (Exit Code: {})",
                    containerName, state, output.exitCode());
            return diagnostic(ContainerStatus.FAILED, DockerDiagnostic.INDETERMINATE,
                    "Docker returned an unexpected container state",
                    "Run docker inspect for the configured container and verify its state.");
        }

        String stderr = output.stderr().trim().toLowerCase(Locale.ROOT);
        if (stderr.contains("no such object") || stderr.contains("no such container")) {
            logger.warn("Container {} does not exist: {}", containerName, output.stderr().trim());
            return diagnostic(ContainerStatus.MISSING, DockerDiagnostic.CONTAINER_MISSING,
                    "configured container does not exist",
                    "Create the container or correct its container_name mapping.");
        }
        if (isPermissionDeniedError(stderr)) {
            logger.warn("Docker daemon inaccessible while checking container {}: {}",
                    containerName, output.stderr().trim());
            return diagnostic(ContainerStatus.INACCESSIBLE, DockerDiagnostic.PERMISSION_DENIED,
                    "permission denied accessing Docker",
                    "Grant the proxy process access to the Docker socket or endpoint.");
        }
        if (isDaemonUnavailableError(stderr)) {
            logger.warn("Docker daemon unavailable while checking container {}: {}",
                    containerName, output.stderr().trim());
            return diagnostic(ContainerStatus.INACCESSIBLE, DockerDiagnostic.DAEMON_UNAVAILABLE,
                    "Docker daemon is unavailable",
                    "Start Docker and verify the configured Docker endpoint is reachable.");
        }
        logger.warn("Could not check status for container {}: {} (Exit Code: {})",
                containerName, output.stderr().trim(), output.exitCode());
        return diagnostic(ContainerStatus.FAILED, DockerDiagnostic.INDETERMINATE,
                "Docker status check failed",
                "Run docker inspect for the configured container and review proxy logs.");
    }

    public ContainerHealth getContainerHealth(String containerName, Duration timeout) {
        requirePositive(timeout, "timeout");
        Duration effectiveTimeout = boundedCommandTimeout(timeout);
        CommandOutput output = commandRunner.run(List.of(
                "docker", "inspect", "-f",
                "{{if .State.Running}}{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}{{else}}stopped{{end}}",
                containerName), effectiveTimeout);

        switch (output.outcome()) {
            case TIMED_OUT:
                logger.warn("Timed out after {}ms checking health for container {}: {}",
                        effectiveTimeout.toMillis(), containerName, output.stderr().trim());
                return ContainerHealth.TIMED_OUT;
            case SPAWN_FAILED:
                logger.error("Could not execute docker health inspect for container {}: {}",
                        containerName, output.stderr());
                return ContainerHealth.FAILED;
            default:
                break;
        }

        if (output.exitCode() == 0) {
            return switch (output.stdout().trim().toLowerCase(Locale.ROOT)) {
                case "healthy" -> ContainerHealth.HEALTHY;
                case "starting" -> ContainerHealth.STARTING;
                case "unhealthy" -> ContainerHealth.UNHEALTHY;
                case "none", "<no value>" -> ContainerHealth.NO_HEALTHCHECK;
                case "stopped" -> ContainerHealth.STOPPED;
                default -> {
                    logger.warn("Unexpected health output for container {}: {}",
                            containerName, output.stdout().trim());
                    yield ContainerHealth.FAILED;
                }
            };
        }

        String stderr = output.stderr().trim().toLowerCase(Locale.ROOT);
        if (stderr.contains("no such object") || stderr.contains("no such container")) {
            return ContainerHealth.MISSING;
        }
        if (isInaccessibleError(stderr)) {
            return ContainerHealth.INACCESSIBLE;
        }
        logger.warn("Could not inspect health for container {}: {} (Exit Code: {})",
                containerName, output.stderr().trim(), output.exitCode());
        return ContainerHealth.FAILED;
    }

    public ContainerStatus startContainer(String containerName) {
        ContainerStatus status = getContainerStatus(containerName);

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
        CommandOutput output = commandRunner.run(List.of("docker", "start", containerName), commandTimeout);

        switch (output.outcome()) {
            case TIMED_OUT:
                logger.error("Timed out after {}ms starting container {}: {}",
                        commandTimeout.toMillis(), containerName, output.stderr().trim());
                return ContainerStatus.TIMED_OUT;
            case SPAWN_FAILED:
                logger.error("Could not execute docker start for container {}: {}",
                        containerName, output.stderr());
                return ContainerStatus.FAILED;
            default:
                break;
        }

        if (output.exitCode() == 0) {
            logger.info("Started container: {}", containerName);
            return ContainerStatus.RUNNING;
        }

        String stderr = output.stderr().trim().toLowerCase(Locale.ROOT);
        if (isInaccessibleError(stderr)) {
            logger.error("Permission denied starting container {}: {}", containerName, output.stderr().trim());
            return ContainerStatus.INACCESSIBLE;
        }
        logger.error("Failed to start container {}: {} (Exit Code: {})",
                containerName, output.stderr().trim(), output.exitCode());
        return ContainerStatus.FAILED;
    }

    public ContainerStatus stopContainer(String containerName) {
        CommandOutput output = commandRunner.run(List.of("docker", "stop", containerName), commandTimeout);

        switch (output.outcome()) {
            case TIMED_OUT:
                logger.error("Timed out after {}ms stopping container {}: {}",
                        commandTimeout.toMillis(), containerName, output.stderr().trim());
                return ContainerStatus.TIMED_OUT;
            case SPAWN_FAILED:
                logger.error("Could not execute docker stop for container {}: {}",
                        containerName, output.stderr());
                return ContainerStatus.FAILED;
            default:
                break;
        }

        if (output.exitCode() == 0) {
            logger.info("Stopped container: {}", containerName);
            return ContainerStatus.STOPPED;
        }

        String stderr = output.stderr().trim().toLowerCase(Locale.ROOT);
        if (isInaccessibleError(stderr)) {
            logger.error("Permission denied stopping container {}: {}", containerName, output.stderr().trim());
            return ContainerStatus.INACCESSIBLE;
        }
        logger.error("Failed to stop container {}: {} (Exit Code: {})",
                containerName, output.stderr().trim(), output.exitCode());
        return ContainerStatus.FAILED;
    }

    private boolean isInaccessibleError(String stderr) {
        return isPermissionDeniedError(stderr) || isDaemonUnavailableError(stderr);
    }

    private boolean isPermissionDeniedError(String stderr) {
        return stderr.contains("permission denied") || stderr.contains("access denied");
    }

    private boolean isDaemonUnavailableError(String stderr) {
        return stderr.contains("cannot connect to the docker daemon")
                || stderr.contains("is the docker daemon running")
                || stderr.contains("connection refused")
                || stderr.contains("dial unix")
                || stderr.contains("error during connect")
                || stderr.contains("dockerdesktoplinuxengine");
    }

    private boolean isCliMissingError(String error) {
        return error.contains("docker not found")
                || error.contains("no such file or directory")
                || error.contains("cannot find the file")
                || error.contains("createprocess error=2");
    }

    private ContainerInspection diagnostic(ContainerStatus status, DockerDiagnostic diagnostic,
            String detail, String remediation) {
        return new ContainerInspection(status, diagnostic, detail, remediation);
    }

    private void requirePositive(Duration timeout, String name) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private Duration boundedCommandTimeout(Duration requested) {
        return requested.compareTo(commandTimeout) < 0 ? requested : commandTimeout;
    }
}
