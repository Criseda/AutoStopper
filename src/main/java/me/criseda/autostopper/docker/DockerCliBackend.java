package me.criseda.autostopper.docker;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/** Runs the {@code docker} CLI and classifies its stderr. Deprecated in favor of the Engine API backend. */
final class DockerCliBackend implements DockerBackend {
    private final Logger logger;
    private final CommandRunner commandRunner;
    private final String description;

    DockerCliBackend(Logger logger, CommandRunner commandRunner, String description) {
        this.logger = logger;
        this.commandRunner = commandRunner;
        this.description = description;
    }

    @Override
    public ContainerInspection inspect(String containerName, Duration timeout) {
        CommandOutput output = commandRunner.run(List.of(
                "docker", "inspect", "-f", "{{.State.Running}}", containerName), timeout);

        switch (output.outcome()) {
            case TIMED_OUT:
                logger.warn("Timed out after {}ms checking status for container {}: {}",
                        timeout.toMillis(), containerName, output.stderr().trim());
                return DockerManager.diagnostic(ContainerStatus.TIMED_OUT, DockerDiagnostic.TIMED_OUT,
                        "Docker status check timed out",
                        "Check Docker daemon responsiveness and host load, then retry.");
            case SPAWN_FAILED:
                logger.error("Could not execute docker inspect for container {}: {}",
                        containerName, output.stderr());
                if (isCliMissingError(output.stderr().toLowerCase(Locale.ROOT))) {
                    return DockerManager.diagnostic(ContainerStatus.FAILED, DockerDiagnostic.CLI_MISSING,
                            "Docker CLI could not be found",
                            "Install the Docker CLI and ensure the proxy process can find it on PATH.");
                }
                return DockerManager.diagnostic(ContainerStatus.FAILED, DockerDiagnostic.INDETERMINATE,
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
            return DockerManager.diagnostic(ContainerStatus.FAILED, DockerDiagnostic.INDETERMINATE,
                    "Docker returned an unexpected container state",
                    "Run docker inspect for the configured container and verify its state.");
        }

        String stderr = output.stderr().trim().toLowerCase(Locale.ROOT);
        if (stderr.contains("no such object") || stderr.contains("no such container")) {
            logger.warn("Container {} does not exist: {}", containerName, output.stderr().trim());
            return DockerManager.containerMissing();
        }
        if (isPermissionDeniedError(stderr)) {
            logger.warn("Docker daemon inaccessible while checking container {}: {}",
                    containerName, output.stderr().trim());
            return DockerManager.permissionDenied();
        }
        if (isDaemonUnavailableError(stderr)) {
            logger.warn("Docker daemon unavailable while checking container {}: {}",
                    containerName, output.stderr().trim());
            return DockerManager.daemonUnavailable();
        }
        logger.warn("Could not check status for container {}: {} (Exit Code: {})",
                containerName, output.stderr().trim(), output.exitCode());
        return DockerManager.diagnostic(ContainerStatus.FAILED, DockerDiagnostic.INDETERMINATE,
                "Docker status check failed",
                "Run docker inspect for the configured container and review proxy logs.");
    }

    @Override
    public ContainerHealth health(String containerName, Duration timeout) {
        CommandOutput output = commandRunner.run(List.of(
                "docker", "inspect", "-f",
                "{{if .State.Running}}{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}{{else}}stopped{{end}}",
                containerName), timeout);

        switch (output.outcome()) {
            case TIMED_OUT:
                logger.warn("Timed out after {}ms checking health for container {}: {}",
                        timeout.toMillis(), containerName, output.stderr().trim());
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

    @Override
    public ContainerStatus start(String containerName, Duration timeout) {
        CommandOutput output = commandRunner.run(List.of("docker", "start", containerName), timeout);

        switch (output.outcome()) {
            case TIMED_OUT:
                logger.error("Timed out after {}ms starting container {}: {}",
                        timeout.toMillis(), containerName, output.stderr().trim());
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

    @Override
    public OptionalLong stopTimeoutSeconds(String containerName, Duration timeout) {
        CommandOutput output = commandRunner.run(List.of(
                "docker", "inspect", "-f", "{{.Config.StopTimeout}}", containerName), timeout);
        if (output.outcome() != CommandOutput.Outcome.COMPLETED || output.exitCode() != 0) {
            logger.debug("Could not read the stop grace period for container {}: {}",
                    containerName, output.stderr().trim());
            return OptionalLong.empty();
        }
        String value = output.stdout().trim();
        if (value.isEmpty() || "<nil>".equals(value) || "<no value>".equals(value)) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            logger.warn("Unexpected stop grace period for container {}: {}", containerName, value);
            return OptionalLong.empty();
        }
    }

    @Override
    public ContainerStatus stop(String containerName, Duration deadline) {
        CommandOutput output = commandRunner.run(List.of("docker", "stop", containerName), deadline);

        switch (output.outcome()) {
            case TIMED_OUT:
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

    @Override
    public String describe() {
        return description;
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
}
