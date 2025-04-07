package me.criseda.autostopper.docker;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class DockerManager {
    private final Logger logger;

    public DockerManager(Logger logger) {
        this.logger = logger;
    }

    public boolean isContainerRunning(String containerName) {
        try {
            Process process = Runtime.getRuntime()
                    .exec(new String[] { "docker", "inspect", "-f", "{{.State.Running}}", containerName });
            process.waitFor();

            try (java.util.Scanner scanner = new java.util.Scanner(process.getInputStream()).useDelimiter("\\A")) {
                String result = scanner.hasNext() ? scanner.next().trim() : "";
                return "true".equals(result);
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Error checking if container is running: " + containerName, e);
            return false;
        }
    }

    public boolean startContainer(String containerName) {
        try {
            logger.info("Starting container: " + containerName);
            Process process = Runtime.getRuntime().exec(new String[] { "docker", "start", containerName });
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                logger.info("Started container: " + containerName);
                return true;
            } else {
                logger.error("Failed to start container: " + containerName + ", exit code: " + exitCode);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Error starting container: " + containerName, e);
            return false;
        }
    }

    public boolean stopContainer(String containerName) {
        try {
            Process process = Runtime.getRuntime().exec(new String[] { "docker", "stop", containerName });
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                logger.info("Stopped container: " + containerName);
                return true;
            } else {
                logger.error("Failed to stop container: " + containerName + ", exit code: " + exitCode);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Error stopping container: " + containerName, e);
            return false;
        }
    }

    public boolean waitForContainerReady(String containerName, int timeoutSeconds, String... readyPatterns) {
        logger.info("Waiting for container " + containerName + " to fully initialize...");
        final long startTime = System.currentTimeMillis();
        final long timeoutMillis = timeoutSeconds * 1000L;

        try {
            // Use --tail=0 to only show NEW logs generated after this command starts
            Process logProcess = Runtime.getRuntime().exec(
                    new String[] { "docker", "logs", "--follow", "--tail=0", containerName });

            // Read the output asynchronously
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(logProcess.getInputStream()))) {
                String line;
                while ((System.currentTimeMillis() - startTime) < timeoutMillis &&
                        (line = reader.readLine()) != null) {

                    // Check for any of the ready patterns
                    for (String pattern : readyPatterns) {
                        if (line.contains(pattern)) {
                            // Container is ready!
                            logger.info("Container " + containerName + " is ready (found: " + pattern + ")");
                            logProcess.destroy(); // Stop following logs
                            return true;
                        }
                    }
                }
            }

            // Make sure to stop the log process if we exit the loop
            logProcess.destroy();

            // If we got here without returning, we timed out
            logger.warn("Timed out waiting for container " + containerName + " to initialize");
            return false;

        } catch (IOException e) {
            logger.error("Error monitoring docker logs for " + containerName, e);
            return false;
        }
    }
}