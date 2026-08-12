package me.criseda.autostopper.docker;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class DockerManager {
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final long READINESS_CLEANUP_GRACE_MILLIS = 250;
    private static final int READINESS_EVENT_CAPACITY = 256;

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
        CommandOutput output = commandRunner.run(List.of(
                "docker", "inspect", "-f", "{{.State.Running}}", containerName), commandTimeout);

        switch (output.outcome()) {
            case TIMED_OUT:
                logger.warn("Timed out after {}ms checking status for container {}: {}",
                        commandTimeout.toMillis(), containerName, output.stderr().trim());
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

    public boolean waitForContainerReady(String containerName, int timeoutSeconds, String... readyPatterns) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        logger.info("Waiting for container {} to fully initialize...", containerName);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        boolean interrupted = false;
        Process process = null;
        Thread readerThread = null;

        try {
            process = new ProcessBuilder("docker", "logs", "--follow", "--tail=0", containerName)
                    .redirectErrorStream(true)
                    .start();

            BlockingQueue<LogEvent> events = new ArrayBlockingQueue<>(READINESS_EVENT_CAPACITY);
            Process ownedProcess = process;
            readerThread = new Thread(() -> readLogEvents(ownedProcess, events),
                    "autostopper-readiness-" + containerName);
            readerThread.setDaemon(true);
            readerThread.start();

            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                LogEvent event = events.poll(remaining, TimeUnit.NANOSECONDS);
                if (event == null) {
                    break;
                }
                if (event.failure() != null) {
                    logger.error("Error reading readiness logs for container {}", containerName, event.failure());
                    return false;
                }
                if (event.endOfStream()) {
                    logger.warn("Container {} stopped producing logs before it became ready", containerName);
                    return false;
                }
                for (String pattern : readyPatterns) {
                    if (event.line().contains(pattern)) {
                        logger.info("Container {} is ready (found: {})", containerName, pattern);
                        return true;
                    }
                }
            }
            logger.warn("Timeout waiting for container {}", containerName);
            return false;
        } catch (InterruptedException e) {
            interrupted = true;
            logger.warn("Interrupted while waiting for container {} to become ready", containerName);
            return false;
        } catch (IOException e) {
            logger.error("Error waiting for container {} ready", containerName, e);
            return false;
        } finally {
            if (process != null) {
                terminateQuietly(process);
                closeProcessOutput(process);
            }
            joinReaderQuietly(readerThread);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void readLogEvents(Process process, BlockingQueue<LogEvent> events) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                events.put(LogEvent.line(line));
            }
            events.put(LogEvent.end());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            events.offer(LogEvent.failure(e));
        }
    }

    private boolean isInaccessibleError(String stderr) {
        return stderr.contains("permission denied")
                || stderr.contains("access denied")
                || stderr.contains("cannot connect to the docker daemon")
                || stderr.contains("is the docker daemon running")
                || stderr.contains("connection refused")
                || stderr.contains("dial unix");
    }

    private void terminateQuietly(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(READINESS_CLEANUP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void closeProcessOutput(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // Process teardown closes this stream during normal operation.
        }
    }

    private void joinReaderQuietly(Thread readerThread) {
        if (readerThread == null) {
            return;
        }
        readerThread.interrupt();
        try {
            readerThread.join(READINESS_CLEANUP_GRACE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record LogEvent(String line, boolean endOfStream, IOException failure) {
        private static LogEvent line(String line) {
            return new LogEvent(line, false, null);
        }

        private static LogEvent end() {
            return new LogEvent("", true, null);
        }

        private static LogEvent failure(IOException failure) {
            return new LogEvent("", false, failure);
        }
    }
}
