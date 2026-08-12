package me.criseda.autostopper.docker;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

public class DockerManager {
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
        return getContainerStatus(containerName, commandTimeout);
    }

    public ContainerStatus getContainerStatus(String containerName, Duration timeout) {
        requirePositive(timeout, "timeout");
        Duration effectiveTimeout = boundedCommandTimeout(timeout);
        CommandOutput output = commandRunner.run(List.of(
                "docker", "inspect", "-f", "{{.State.Running}}", containerName), effectiveTimeout);

        switch (output.outcome()) {
            case TIMED_OUT:
                logger.warn("Timed out after {}ms checking status for container {}: {}",
                        effectiveTimeout.toMillis(), containerName, output.stderr().trim());
                return ContainerStatus.TIMED_OUT;
            case SPAWN_FAILED:
                logger.error("Could not execute docker inspect for container {}: {}",
                        containerName, output.stderr());
                return ContainerStatus.FAILED;
            default:
                break;
        }

        if (output.exitCode() == 0) {
            String state = output.stdout().trim();
            if ("true".equalsIgnoreCase(state)) {
                return ContainerStatus.RUNNING;
            }
            if ("false".equalsIgnoreCase(state)) {
                return ContainerStatus.STOPPED;
            }
            logger.warn("Unexpected output from docker inspect for container {}: {} (Exit Code: {})",
                    containerName, state, output.exitCode());
            return ContainerStatus.FAILED;
        }

        String stderr = output.stderr().trim().toLowerCase(Locale.ROOT);
        if (stderr.contains("no such object") || stderr.contains("no such container")) {
            logger.warn("Container {} does not exist: {}", containerName, output.stderr().trim());
            return ContainerStatus.MISSING;
        }
        if (isInaccessibleError(stderr)) {
            logger.warn("Docker daemon inaccessible while checking container {}: {}",
                    containerName, output.stderr().trim());
            return ContainerStatus.INACCESSIBLE;
        }
        logger.warn("Could not check status for container {}: {} (Exit Code: {})",
                containerName, output.stderr().trim(), output.exitCode());
        return ContainerStatus.FAILED;
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
        return stderr.contains("permission denied")
                || stderr.contains("access denied")
                || stderr.contains("cannot connect to the docker daemon")
                || stderr.contains("is the docker daemon running")
                || stderr.contains("connection refused")
                || stderr.contains("dial unix");
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
