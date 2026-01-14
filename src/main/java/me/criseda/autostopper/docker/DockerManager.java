package me.criseda.autostopper.docker;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class DockerManager {
    private final Logger logger;

    public DockerManager(Logger logger) {
        this.logger = logger;
    }

    public boolean isContainerRunning(String containerName) {
        CommandResult result = runCommand("docker", "inspect", "-f", "{{.State.Running}}", containerName);
        if (result.exitCode != 0) {
            logger.warn("Could not check status for container {}: {} (Exit Code: {})",
                    containerName, result.stderr.trim(), result.exitCode);
            return false;
        }
        return "true".equalsIgnoreCase(result.stdout.trim());
    }

    public boolean startContainer(String containerName) {
        logger.info("Starting container: " + containerName);

        if (isContainerRunning(containerName)) {
            logger.info("Container " + containerName + " is already running.");
            return true;
        }

        CommandResult result = runCommand("docker", "start", containerName);
        if (result.exitCode == 0) {
            logger.info("Started container: " + containerName);
            return true;
        } else {
            logger.error("Failed to start container: " + containerName + ", exit code: " + result.exitCode + ", error: "
                    + result.stderr.trim());
            return false;
        }
    }

    public boolean stopContainer(String containerName) {
        CommandResult result = runCommand("docker", "stop", containerName);
        if (result.exitCode == 0) {
            logger.info("Stopped container: " + containerName);
            return true;
        } else {
            logger.error("Failed to stop container: " + containerName + ", exit code: " + result.exitCode + ", error: "
                    + result.stderr.trim());
            return false;
        }
    }

    public boolean waitForContainerReady(String containerName, int timeoutSeconds, String... readyPatterns) {
        logger.info("Waiting for container " + containerName + " to fully initialize...");
        final long startTime = System.currentTimeMillis();
        final long timeoutMillis = timeoutSeconds * 1000L;

        try {
            Process process = new ProcessBuilder("docker", "logs", "--follow", "--tail=0", containerName)
                    .redirectErrorStream(true) // Merge stderr into stdout
                    .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((System.currentTimeMillis() - startTime) < timeoutMillis) {
                    if (reader.ready()) {
                        line = reader.readLine();
                        if (line == null)
                            break;

                        for (String pattern : readyPatterns) {
                            if (line.contains(pattern)) {
                                logger.info("Container " + containerName + " is ready (found: " + pattern + ")");
                                process.destroy();
                                return true;
                            }
                        }
                    } else {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        if (!process.isAlive()) {
                            break;
                        }
                    }
                }
            } finally {
                process.destroy();
            }

            logger.warn("Timeout waiting for container " + containerName);
            return false;
        } catch (IOException e) {
            logger.error("Error waiting for container ready: " + containerName, e);
            return false;
        }
    }

    private CommandResult runCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();

            StreamGobbler stdoutGobbler = new StreamGobbler(process.getInputStream());
            StreamGobbler stderrGobbler = new StreamGobbler(process.getErrorStream());
            stdoutGobbler.start();
            stderrGobbler.start();

            int exitCode = process.waitFor();
            stdoutGobbler.join();
            stderrGobbler.join();

            return new CommandResult(exitCode, stdoutGobbler.getOutput(), stderrGobbler.getOutput());

        } catch (IOException | InterruptedException e) {
            logger.error("Error executing command: " + String.join(" ", command), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new CommandResult(-1, "", e.getMessage());
        }
    }

    private static class CommandResult {
        final int exitCode;
        final String stdout;
        final String stderr;

        CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static class StreamGobbler extends Thread {
        private final InputStream stream;
        private final StringBuilder output = new StringBuilder();

        StreamGobbler(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            } catch (IOException e) {
                // Ignore
            }
        }

        public String getOutput() {
            return output.toString();
        }
    }
}