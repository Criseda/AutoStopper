package me.criseda.autostopper.testing;

import me.criseda.autostopper.testing.SystemTestSupport.CommandResult;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static me.criseda.autostopper.testing.SystemTestSupport.deleteRecursively;
import static me.criseda.autostopper.testing.SystemTestSupport.requireSuccess;
import static me.criseda.autostopper.testing.SystemTestSupport.runCommand;
import static me.criseda.autostopper.testing.SystemTestSupport.sha256;

/** Runs the exact packaged candidate through a real Docker, Compose, Velocity, and Minecraft stack. */
public final class ReleaseCandidateE2EHarness {
    private static final Duration GLOBAL_TIMEOUT = Duration.ofMinutes(25);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration IMAGE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration PROXY_STARTUP_TIMEOUT = Duration.ofMinutes(4);
    private static final Duration CLIENT_TIMEOUT = Duration.ofMinutes(4);
    private static final Duration IDLE_STOP_TIMEOUT = Duration.ofSeconds(100);
    private static final Duration RETRY_STOP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(750);
    private static final String VELOCITY_IMAGE = "itzg/mc-proxy:2026.8.0-java25";
    private static final String BACKEND_IMAGE = "itzg/minecraft-server:java21";
    private static final String CLIENT_IMAGE_PREFIX = "autostopper-release-candidate-client";

    private final Path pluginArtifact;
    private final Path outputDirectory;
    private final Path workDirectory;
    private final Path evidenceDirectory;
    private final Path repositoryDirectory;
    private final Path composeFile;
    private final String projectVersion;
    private final Deadline deadline = new Deadline(GLOBAL_TIMEOUT);
    private String candidateHash;
    private String clientImage;

    private ReleaseCandidateE2EHarness(
            Path pluginArtifact, Path outputDirectory, String projectVersion, Path repositoryDirectory) {
        this.pluginArtifact = pluginArtifact.toAbsolutePath().normalize();
        this.outputDirectory = outputDirectory.toAbsolutePath().normalize();
        this.workDirectory = this.outputDirectory.resolve("work");
        this.evidenceDirectory = this.outputDirectory.resolve("evidence");
        this.repositoryDirectory = repositoryDirectory.toAbsolutePath().normalize();
        this.composeFile = this.repositoryDirectory.resolve("e2e/docker-compose.yml");
        this.projectVersion = projectVersion;
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) {
            throw new IllegalArgumentException("Expected arguments: <packaged-plugin-jar> <output-directory> "
                    + "<project-version> <repository-directory>");
        }
        ReleaseCandidateE2EHarness harness = new ReleaseCandidateE2EHarness(
                Path.of(arguments[0]), Path.of(arguments[1]), arguments[2], Path.of(arguments[3]));
        harness.run();
    }

    private void run() throws Exception {
        requirePackagedArtifact();
        requireFixture();
        Files.createDirectories(outputDirectory);
        deleteRecursively(workDirectory);
        deleteRecursively(evidenceDirectory);
        Files.createDirectories(workDirectory);
        Files.createDirectories(evidenceDirectory);
        candidateHash = sha256(pluginArtifact);
        writeCandidateManifest();

        requireSuccess(command(COMMAND_TIMEOUT, "docker", "version", "--format", "{{.Server.Version}}"),
                "Docker daemon is unavailable");
        requireSuccess(command(COMMAND_TIMEOUT, "docker", "compose", "version"),
                "Docker Compose v2 is unavailable");
        pullImages();
        buildClientImage();
        captureImageEvidence();

        List<ScenarioResult> results = new ArrayList<>();
        try {
            runScenario(Scenario.HAPPY_PATH, results);
            runScenario(Scenario.NEVER_READY, results);
            runScenario(Scenario.FAILED_STOP, results);
        } finally {
            writeSummary(results);
            cleanupWorkDirectoryBestEffort();
        }
        System.out.println("All release-candidate Docker/Minecraft E2E scenarios passed for candidate SHA-256 "
                + candidateHash + ". Evidence: " + evidenceDirectory);
    }

    private void requirePackagedArtifact() throws IOException {
        if (!Files.isRegularFile(pluginArtifact)) {
            throw new IllegalStateException("Packaged candidate JAR does not exist: " + pluginArtifact);
        }
        if (!pluginArtifact.getFileName().toString().endsWith(".jar")
                || pluginArtifact.getFileName().toString().startsWith("original-")) {
            throw new IllegalStateException("Release-candidate E2E requires the final shaded JAR: " + pluginArtifact);
        }
    }

    private void requireFixture() {
        List<Path> required = List.of(
                composeFile,
                repositoryDirectory.resolve("e2e/docker-entrypoint.sh"),
                repositoryDirectory.resolve("e2e/docker-cli-wrapper.sh"),
                repositoryDirectory.resolve("e2e/client/Dockerfile"),
                repositoryDirectory.resolve("e2e/client/package.json"),
                repositoryDirectory.resolve("e2e/client/package-lock.json"),
                repositoryDirectory.resolve("e2e/client/client.js"));
        for (Path path : required) {
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Release-candidate fixture file is missing: " + path);
            }
        }
    }

    private void pullImages() throws Exception {
        requireSuccess(command(IMAGE_TIMEOUT, "docker", "pull", VELOCITY_IMAGE),
                "Could not pull the pinned current proxy image");
        requireSuccess(command(IMAGE_TIMEOUT, "docker", "pull", BACKEND_IMAGE),
                "Could not pull the pinned Minecraft backend image");
    }

    private void buildClientImage() throws Exception {
        Path clientDirectory = repositoryDirectory.resolve("e2e/client");
        String clientHash = sha256(clientDirectory.resolve("package-lock.json")).substring(0, 16);
        clientImage = CLIENT_IMAGE_PREFIX + ":" + clientHash;
        requireSuccess(command(IMAGE_TIMEOUT, repositoryDirectory, Map.of(),
                        "docker", "build", "--tag", clientImage, clientDirectory.toString()),
                "Could not build the pinned headless Minecraft client image");
    }

    private void runScenario(Scenario scenario, List<ScenarioResult> results) throws Exception {
        ScenarioContext context = createScenario(scenario);
        Instant startedAt = Instant.now();
        Throwable failure = null;
        System.out.println("Running release-candidate scenario " + scenario.id + " in " + context.project + "...");
        try {
            CommandResult rendered = compose(context, COMMAND_TIMEOUT, "config");
            requireSuccess(rendered, "Compose fixture is invalid for " + scenario.id);
            Files.writeString(context.evidence.resolve("compose-config.yml"), rendered.output(),
                    StandardCharsets.UTF_8);

            requireSuccess(compose(context, IMAGE_TIMEOUT, "create", "backend"),
                    "Could not create the stopped Minecraft backend for " + scenario.id);
            assertContainerRunning(context.backendContainer, false,
                    "Backend must be stopped before the first client request");
            requireSuccess(compose(context, IMAGE_TIMEOUT, "up", "--detach", "--no-deps", "velocity"),
                    "Could not start the Velocity proxy for " + scenario.id);
            waitForProxyStartup(context);
            assertCandidateIdentity(context);

            switch (scenario) {
                case HAPPY_PATH -> runHappyPath(context);
                case NEVER_READY -> runNeverReady(context);
                case FAILED_STOP -> runFailedStop(context);
            }
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            captureScenarioEvidence(context, failure);
            List<String> cleanupErrors = cleanupScenario(context);
            IllegalStateException cleanupFailure = null;
            if (!cleanupErrors.isEmpty()) {
                cleanupFailure = new IllegalStateException("Cleanup remained incomplete for " + scenario.id + ": "
                        + String.join("; ", cleanupErrors));
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            Duration duration = Duration.between(startedAt, Instant.now());
            boolean passed = failure == null;
            results.add(new ScenarioResult(scenario.id, passed, duration,
                    failure == null ? null : failure.toString()));
            Files.writeString(context.evidence.resolve("result.txt"),
                    (passed ? "PASS" : "FAIL") + " " + scenario.id + " after " + duration + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            System.out.println((passed ? "PASS " : "FAIL ") + scenario.id + " after " + duration);
            if (cleanupFailure != null && failure == cleanupFailure) {
                throw cleanupFailure;
            }
        }
    }

    private void runHappyPath(ScenarioContext context) throws Exception {
        List<String> names = List.of("E2EAlpha", "E2EBeta", "E2EGamma");
        String output = runClients(context, "simultaneous", names, "spawn", null, Duration.ofMinutes(3));
        for (String name : names) {
            requireContains(output, "\"event\":\"spawn\",\"name\":\"" + name + "\"",
                    "Headless client did not reach the backend: " + name);
        }
        waitForBackendPlayers(context, names);
        assertAuditCount(context, "start " + context.backendContainer, 1,
                "Simultaneous clients must execute exactly one Docker start");

        waitForContainerRunning(context.backendContainer, false, IDLE_STOP_TIMEOUT,
                "Backend did not stop after all clients became idle");
        waitForProxyLog(context, "Stopped server: backend", COMMAND_TIMEOUT);

        String restartName = "E2ERestart";
        String restart = runClients(context, "restart", List.of(restartName), "spawn", null,
                Duration.ofMinutes(3));
        requireContains(restart, "\"event\":\"spawn\",\"name\":\"" + restartName + "\"",
                "Client did not reach the backend after idle restart");
        waitForBackendPlayers(context, List.of(restartName));
        assertAuditCount(context, "start " + context.backendContainer, 2,
                "Idle restart must execute one additional Docker start");
    }

    private void runNeverReady(ScenarioContext context) throws Exception {
        String output = runClients(context, "never-ready", List.of("E2ENotReady"), "message",
                "remained unreachable", Duration.ofSeconds(50));
        requireContains(output, "remained unreachable",
                "Never-ready client did not receive the typed readiness error");
        assertContainerRunning(context.backendContainer, true,
                "Never-ready injection must leave the started backend running");
        assertAuditCount(context, "start " + context.backendContainer, 1,
                "Never-ready scenario must start the backend once");
        String logs = proxyLogs(context);
        requireContains(logs, "failed minecraft_status readiness",
                "Proxy did not report the typed readiness failure");
        requireContains(logs, "remained unreachable",
                "Proxy readiness failure did not retain the expected diagnostic");
    }

    private void runFailedStop(ScenarioContext context) throws Exception {
        String output = runClients(context, "failed-stop", List.of("E2EStopFail"), "spawn", null,
                Duration.ofMinutes(3));
        requireContains(output, "\"event\":\"spawn\",\"name\":\"E2EStopFail\"",
                "Failed-stop scenario client did not reach the backend");

        waitForAuditCount(context, "stop " + context.backendContainer, 1, IDLE_STOP_TIMEOUT);
        assertContainerRunning(context.backendContainer, true,
                "Injected first stop failure must leave the backend running");
        waitForProxyLog(context, "failed for server backend with FAILED; retrying", COMMAND_TIMEOUT);

        waitForAuditCount(context, "stop " + context.backendContainer, 2, RETRY_STOP_TIMEOUT);
        waitForContainerRunning(context.backendContainer, false, COMMAND_TIMEOUT,
                "Backend did not stop after the bounded retry");
        waitForProxyLog(context, "Stopped server: backend", COMMAND_TIMEOUT);
    }

    private String runClients(ScenarioContext context, String label, List<String> names,
            String expectation, String expectedMessage, Duration clientDeadline) throws Exception {
        List<String> arguments = new ArrayList<>(List.of(
                "--profile", "clients", "run", "--rm", "--no-deps",
                "--env", "E2E_NAMES=" + String.join(",", names),
                "--env", "E2E_EXPECT=" + expectation,
                "--env", "E2E_TIMEOUT_MILLIS=" + clientDeadline.toMillis()));
        if (expectedMessage != null) {
            arguments.add("--env");
            arguments.add("E2E_MESSAGE=" + expectedMessage);
        }
        arguments.add("client");
        CommandResult result = compose(context, CLIENT_TIMEOUT, arguments.toArray(String[]::new));
        Files.writeString(context.evidence.resolve("client-" + label + ".log"), result.output(),
                StandardCharsets.UTF_8);
        requireSuccess(result, "Headless Minecraft client batch failed for " + context.scenario.id + "/" + label);
        requireContains(result.output(), "\"event\":\"batch-passed\"",
                "Headless client batch did not report success");
        return result.output();
    }

    private void waitForBackendPlayers(ScenarioContext context, List<String> names) throws Exception {
        waitUntil(COMMAND_TIMEOUT, "backend join markers for " + names, () -> {
            try {
                String logs = containerLogs(context.backendContainer);
                return names.stream().allMatch(name -> logs.contains(name + " joined the game"));
            } catch (Exception error) {
                return false;
            }
        });
    }

    private void waitForProxyStartup(ScenarioContext context) throws Exception {
        waitUntil(PROXY_STARTUP_TIMEOUT, "Velocity and AutoStopper startup", () -> {
            try {
                String logs = proxyLogs(context);
                return logs.contains("Loaded plugin autostopper " + projectVersion + " by criseda")
                        && logs.contains("AutoStopper plugin initialized!")
                        && logs.contains("Done (");
            } catch (Exception error) {
                return false;
            }
        });
    }

    private void assertCandidateIdentity(ScenarioContext context) throws Exception {
        CommandResult hash = command(COMMAND_TIMEOUT, "docker", "exec", context.velocityContainer,
                "sha256sum", "/server/plugins/AutoStopper.jar");
        requireSuccess(hash, "Could not hash the candidate JAR inside the proxy");
        if (!hash.output().startsWith(candidateHash + " ")) {
            throw new IllegalStateException("Proxy candidate hash differs from packaged artifact: " + hash.output());
        }
        Files.writeString(context.evidence.resolve("candidate-sha256.txt"), hash.output(), StandardCharsets.UTF_8);
    }

    private void waitForProxyLog(ScenarioContext context, String marker, Duration timeout) throws Exception {
        waitUntil(timeout, "proxy log marker: " + marker, () -> {
            try {
                return proxyLogs(context).contains(marker);
            } catch (Exception error) {
                return false;
            }
        });
    }

    private void waitForAuditCount(ScenarioContext context, String command, int expected, Duration timeout)
            throws Exception {
        waitUntil(timeout, "Docker audit count " + expected + " for " + command, () -> {
            try {
                return auditCount(context, command) >= expected;
            } catch (Exception error) {
                return false;
            }
        });
    }

    private void assertAuditCount(ScenarioContext context, String command, int expected, String message)
            throws Exception {
        int actual = auditCount(context, command);
        if (actual != expected) {
            throw new IllegalStateException(message + ": expected " + expected + ", got " + actual
                    + System.lineSeparator() + dockerAudit(context));
        }
    }

    private int auditCount(ScenarioContext context, String expectedCommand) throws Exception {
        return (int) dockerAudit(context).lines().filter(expectedCommand::equals).count();
    }

    private String dockerAudit(ScenarioContext context) throws Exception {
        CommandResult audit = command(COMMAND_TIMEOUT, "docker", "exec", context.velocityContainer,
                "sh", "-c", "test ! -f /tmp/autostopper-e2e-docker-commands.log "
                        + "|| cat /tmp/autostopper-e2e-docker-commands.log");
        requireSuccess(audit, "Could not read the proxy Docker command audit");
        return audit.output();
    }

    private String proxyLogs(ScenarioContext context) throws Exception {
        return containerLogs(context.velocityContainer);
    }

    private String containerLogs(String container) throws Exception {
        CommandResult logs = command(COMMAND_TIMEOUT, "docker", "logs", container);
        requireSuccess(logs, "Could not read logs for " + container);
        return logs.output();
    }

    private void waitForContainerRunning(String container, boolean running, Duration timeout, String message)
            throws Exception {
        try {
            waitUntil(timeout, message, () -> {
                try {
                    return containerRunning(container) == running;
                } catch (Exception error) {
                    return false;
                }
            });
        } catch (IllegalStateException error) {
            throw new IllegalStateException(message, error);
        }
    }

    private void assertContainerRunning(String container, boolean expected, String message) throws Exception {
        boolean actual = containerRunning(container);
        if (actual != expected) {
            throw new IllegalStateException(message + ": expected running=" + expected + ", got " + actual);
        }
    }

    private boolean containerRunning(String container) throws Exception {
        CommandResult state = command(COMMAND_TIMEOUT, "docker", "inspect", "--format",
                "{{.State.Running}}", container);
        requireSuccess(state, "Could not inspect container state for " + container);
        return Boolean.parseBoolean(state.output().trim());
    }

    private void waitUntil(Duration timeout, String description, Supplier<Boolean> condition) throws Exception {
        Duration bounded = deadline.bound(timeout);
        long finishAt = System.nanoTime() + bounded.toNanos();
        while (System.nanoTime() < finishAt) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(Math.min(POLL_INTERVAL.toMillis(),
                    Math.max(1, Duration.ofNanos(finishAt - System.nanoTime()).toMillis())));
        }
        throw new IllegalStateException("Timed out after " + bounded + " waiting for " + description);
    }

    private ScenarioContext createScenario(Scenario scenario) throws Exception {
        String nonce = Long.toUnsignedString(System.nanoTime(), 36).toLowerCase(Locale.ROOT);
        String project = "autostopper-e2e-" + scenario.id + "-" + ProcessHandle.current().pid() + "-" + nonce;
        Path scenarioDirectory = workDirectory.resolve(scenario.id);
        Path velocityData = scenarioDirectory.resolve("velocity");
        Path backendData = scenarioDirectory.resolve("backend-data");
        Path scenarioEvidence = evidenceDirectory.resolve(scenario.id);
        deleteRecursively(scenarioDirectory);
        Files.createDirectories(velocityData.resolve("plugins/autostopper"));
        Files.createDirectories(backendData);
        Files.createDirectories(scenarioEvidence);
        makeWorldWritable(velocityData);
        makeWorldWritable(backendData);

        Files.copy(pluginArtifact, velocityData.resolve("plugins/AutoStopper.jar"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(velocityData.resolve("velocity.toml"), velocityConfig(), StandardCharsets.UTF_8);
        Files.writeString(velocityData.resolve("plugins/autostopper/config.yml"),
                pluginConfig(scenario, project + "-backend"), StandardCharsets.UTF_8);
        Files.writeString(backendData.resolve("bukkit.yml"), """
                settings:
                  connection-throttle: -1
                """, StandardCharsets.UTF_8);
        Path entrypoint = normalizedScript("e2e/docker-entrypoint.sh", scenarioDirectory.resolve("entrypoint.sh"));
        Path wrapper = normalizedScript("e2e/docker-cli-wrapper.sh", scenarioDirectory.resolve("docker-wrapper.sh"));

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("E2E_BACKEND_CONTAINER", project + "-backend");
        environment.put("E2E_VELOCITY_CONTAINER", project + "-velocity");
        environment.put("E2E_VELOCITY_PORT", Integer.toString(availablePort()));
        environment.put("E2E_BACKEND_DATA", composePath(backendData));
        environment.put("E2E_VELOCITY_DATA", composePath(velocityData));
        environment.put("E2E_ENTRYPOINT", composePath(entrypoint));
        environment.put("E2E_DOCKER_WRAPPER", composePath(wrapper));
        environment.put("E2E_CLIENT_IMAGE", clientImage);
        environment.put("E2E_FAIL_STOP_ONCE", Boolean.toString(scenario == Scenario.FAILED_STOP));
        environment.put("E2E_HOST_UID", hostIdentity("-u"));
        environment.put("E2E_HOST_GID", hostIdentity("-g"));
        return new ScenarioContext(scenario, project, project + "-velocity", project + "-backend",
                scenarioDirectory, scenarioEvidence, environment);
    }

    private String pluginConfig(Scenario scenario, String backendContainer) {
        int readinessPort = scenario == Scenario.NEVER_READY ? 25_566 : 25_565;
        int readinessTimeout = scenario == Scenario.NEVER_READY ? 10 : 180;
        return """
                inactivity_timeout_seconds: 1
                shutdown_timeout_seconds: 10
                stop_retry:
                  max_attempts: 2
                  initial_backoff_seconds: 1
                  max_backoff_seconds: 1
                monitored_servers:
                  - server_name: backend
                    container_name: %s
                    readiness:
                      strategy: minecraft_status
                      target_host: backend
                      target_port: %d
                      probe_interval_millis: 500
                      timeout_seconds: %d
                      connect_timeout_millis: 500
                      read_timeout_millis: 1000
                """.formatted(backendContainer, readinessPort, readinessTimeout);
    }

    private String velocityConfig() {
        return """
                config-version = "2.8"
                bind = "0.0.0.0:25577"
                motd = "AutoStopper release-candidate E2E"
                show-max-players = 10
                online-mode = false
                force-key-authentication = false
                player-info-forwarding-mode = "none"
                ping-passthrough = "disabled"

                [advanced]
                login-ratelimit = 0
                read-timeout = 240000

                [servers]
                backend = "backend:25565"
                try = ["backend"]

                [forced-hosts]
                """;
    }

    private Path normalizedScript(String repositoryPath, Path destination) throws IOException {
        String content = Files.readString(repositoryDirectory.resolve(repositoryPath), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        Files.writeString(destination, content, StandardCharsets.UTF_8);
        makeExecutable(destination);
        return destination.toAbsolutePath().normalize();
    }

    private void makeWorldWritable(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, EnumSet.allOf(PosixFilePermission.class));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Docker Desktop bind mounts do not expose POSIX permissions to the Windows host.
        }
    }

    private void makeExecutable(Path path) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // The entrypoint invokes the normalized scripts explicitly on Docker Desktop.
        }
    }

    private String hostIdentity(String option) {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
            return "1000";
        }
        try {
            CommandResult result = runCommand(Duration.ofSeconds(5), "id", option);
            return result.exitCode() == 0 ? result.output().trim() : "1000";
        } catch (Exception ignored) {
            return "1000";
        }
    }

    private int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private String composePath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private CommandResult compose(ScenarioContext context, Duration timeout, String... arguments) throws Exception {
        return command(timeout, repositoryDirectory, context.environment, composeCommand(context, arguments));
    }

    private CommandResult rawCompose(ScenarioContext context, Duration timeout, String... arguments) throws Exception {
        return runCommand(timeout, repositoryDirectory, context.environment, composeCommand(context, arguments));
    }

    private List<String> composeCommand(ScenarioContext context, String... arguments) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "compose", "--ansi", "never", "--project-name", context.project,
                "--file", composeFile.toString()));
        command.addAll(List.of(arguments));
        return command;
    }

    private CommandResult command(Duration timeout, String... arguments) throws Exception {
        return command(timeout, repositoryDirectory, Map.of(), arguments);
    }

    private CommandResult rawCommand(Duration timeout, String... arguments) throws Exception {
        return runCommand(timeout, repositoryDirectory, Map.of(), List.of(arguments));
    }

    private CommandResult command(Duration timeout, Path directory, Map<String, String> environment,
            String... arguments) throws Exception {
        return command(timeout, directory, environment, List.of(arguments));
    }

    private CommandResult command(Duration timeout, Path directory, Map<String, String> environment,
            List<String> arguments) throws Exception {
        return runCommand(deadline.bound(timeout), directory, environment, arguments);
    }

    private void captureScenarioEvidence(ScenarioContext context, Throwable failure) {
        if (failure != null) {
            writeBestEffort(context.evidence.resolve("failure.txt"), failure.toString());
        }
        captureBestEffort(context.evidence.resolve("compose-ps.json"),
                () -> rawCompose(context, COMMAND_TIMEOUT, "ps", "--all", "--format", "json"));
        captureBestEffort(context.evidence.resolve("compose.log"),
                () -> rawCompose(context, COMMAND_TIMEOUT, "logs", "--no-color", "--timestamps"));
        captureBestEffort(context.evidence.resolve("velocity-inspect.json"),
                () -> rawCommand(COMMAND_TIMEOUT, "docker", "inspect", context.velocityContainer));
        captureBestEffort(context.evidence.resolve("backend-inspect.json"),
                () -> rawCommand(COMMAND_TIMEOUT, "docker", "inspect", context.backendContainer));
        captureBestEffort(context.evidence.resolve("docker-commands.log"),
                () -> rawCommand(COMMAND_TIMEOUT, "docker", "exec", context.velocityContainer,
                        "sh", "-c", "test ! -f /tmp/autostopper-e2e-docker-commands.log "
                                + "|| cat /tmp/autostopper-e2e-docker-commands.log"));
        captureBestEffort(context.evidence.resolve("project-containers.txt"),
                () -> rawCommand(COMMAND_TIMEOUT, "docker", "ps", "--all", "--no-trunc", "--filter",
                        "label=com.docker.compose.project=" + context.project));
        captureBestEffort(context.evidence.resolve("project-networks.txt"),
                () -> rawCommand(COMMAND_TIMEOUT, "docker", "network", "ls", "--no-trunc", "--filter",
                        "label=com.docker.compose.project=" + context.project));
        captureBestEffort(context.evidence.resolve("project-volumes.txt"),
                () -> rawCommand(COMMAND_TIMEOUT, "docker", "volume", "ls", "--filter",
                        "label=com.docker.compose.project=" + context.project));
    }

    private void captureBestEffort(Path destination, ThrowingSupplier<CommandResult> action) {
        try {
            CommandResult result = action.get();
            Files.writeString(destination, "exit=" + result.exitCode() + System.lineSeparator() + result.output(),
                    StandardCharsets.UTF_8);
        } catch (Exception error) {
            writeBestEffort(destination, "capture failed: " + error);
        }
    }

    private List<String> cleanupScenario(ScenarioContext context) {
        List<String> errors = new ArrayList<>();
        try {
            CommandResult result = rawCompose(
                    context, COMMAND_TIMEOUT, "down", "--volumes", "--remove-orphans", "--timeout", "10");
            if (result.exitCode() != 0) {
                writeBestEffort(context.evidence.resolve("cleanup-errors.txt"),
                        "Compose cleanup exited " + result.exitCode() + ": " + result.output());
            }
        } catch (Exception error) {
            writeBestEffort(context.evidence.resolve("cleanup-errors.txt"), "Compose cleanup failed: " + error);
        }
        removeContainerBestEffort(context.velocityContainer, context.evidence);
        removeContainerBestEffort(context.backendContainer, context.evidence);
        try {
            rawCommand(COMMAND_TIMEOUT, "docker", "network", "rm", context.project + "_minecraft");
        } catch (Exception ignored) {
            // Compose down normally removes the project network; the fallback is best effort.
        }
        try {
            deleteRecursively(context.work);
        } catch (IOException error) {
            cleanupBindMountBestEffort(context.work, context.evidence);
        }
        verifyResourceAbsent("container", context.velocityContainer, errors);
        verifyResourceAbsent("container", context.backendContainer, errors);
        verifyResourceAbsent("network", context.project + "_minecraft", errors);
        if (Files.exists(context.work)) {
            errors.add("temporary directory remains: " + context.work);
        }
        for (String error : errors) {
            writeBestEffort(context.evidence.resolve("cleanup-errors.txt"), error);
        }
        return errors;
    }

    private void removeContainerBestEffort(String container, Path evidence) {
        try {
            rawCommand(COMMAND_TIMEOUT, "docker", "rm", "--force", container);
        } catch (Exception error) {
            writeBestEffort(evidence.resolve("cleanup-errors.txt"),
                    "Container cleanup failed for " + container + ": " + error);
        }
    }

    private void verifyResourceAbsent(String type, String name, List<String> errors) {
        try {
            CommandResult result = rawCommand(COMMAND_TIMEOUT, "docker", type, "inspect", name);
            if (result.exitCode() == 0) {
                errors.add(type + " remains: " + name);
            } else {
                String output = result.output().toLowerCase(Locale.ROOT);
                if (!output.contains("no such") && !output.contains("not found")) {
                    errors.add("could not verify absent " + type + " " + name + " (exit "
                            + result.exitCode() + "): " + result.output());
                }
            }
        } catch (Exception error) {
            errors.add("could not verify absent " + type + " " + name + ": " + error);
        }
    }

    private void cleanupBindMountBestEffort(Path target, Path evidence) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(workDirectory) || normalized.equals(workDirectory)) {
            writeBestEffort(evidence.resolve("cleanup-errors.txt"),
                    "Refused cleanup outside a scenario work directory: " + normalized);
            return;
        }
        try {
            rawCommand(COMMAND_TIMEOUT, "docker", "run", "--rm", "--user", "0", "--entrypoint", "/bin/sh",
                    "--volume", composePath(normalized) + ":/cleanup", BACKEND_IMAGE,
                    "-c", "find /cleanup -mindepth 1 -delete");
            deleteRecursively(normalized);
        } catch (Exception error) {
            writeBestEffort(evidence.resolve("cleanup-errors.txt"),
                    "Bind-mount cleanup failed for " + normalized + ": " + error);
        }
    }

    private void cleanupWorkDirectoryBestEffort() {
        try {
            deleteRecursively(workDirectory);
        } catch (IOException ignored) {
            // Individual scenario cleanup records actionable failures with the scenario evidence.
        }
    }

    private void captureImageEvidence() {
        captureBestEffort(evidenceDirectory.resolve("images.json"), () -> command(COMMAND_TIMEOUT,
                "docker", "image", "inspect", VELOCITY_IMAGE, BACKEND_IMAGE, clientImage));
    }

    private void writeCandidateManifest() throws Exception {
        String commit = System.getenv("GITHUB_SHA");
        if (commit == null || commit.isBlank()) {
            CommandResult result = command(COMMAND_TIMEOUT, "git", "rev-parse", "HEAD");
            commit = result.exitCode() == 0 ? result.output().trim() : "unknown";
        }
        String manifest = """
                {
                  "commit": "%s",
                  "projectVersion": "%s",
                  "artifact": "%s",
                  "size": %d,
                  "sha256": "%s",
                  "velocityImage": "%s",
                  "velocityVersion": "4.0.0-6",
                  "backendImage": "%s",
                  "minecraftVersion": "1.21.4",
                  "purpurBuild": "2416"
                }
                """.formatted(json(commit), json(projectVersion), json(pluginArtifact.getFileName().toString()),
                Files.size(pluginArtifact), candidateHash, VELOCITY_IMAGE, BACKEND_IMAGE);
        Files.writeString(evidenceDirectory.resolve("candidate-manifest.json"), manifest, StandardCharsets.UTF_8);
    }

    private void writeSummary(List<ScenarioResult> results) {
        StringBuilder summary = new StringBuilder();
        summary.append("candidate_sha256=").append(candidateHash).append(System.lineSeparator());
        for (ScenarioResult result : results) {
            summary.append(result.scenario).append('=').append(result.passed ? "PASS" : "FAIL")
                    .append(" duration=").append(result.duration);
            if (result.detail != null) {
                summary.append(" detail=").append(result.detail);
            }
            summary.append(System.lineSeparator());
        }
        writeBestEffort(evidenceDirectory.resolve("summary.txt"), summary.toString());
    }

    private void writeBestEffort(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content + (content.endsWith(System.lineSeparator()) ? "" : System.lineSeparator()),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Cleanup and evidence collection must not mask the primary failure.
        }
    }

    private void requireContains(String content, String expected, String message) {
        if (!content.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(message + "; missing marker: " + expected
                    + System.lineSeparator() + content);
        }
    }

    private String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private enum Scenario {
        HAPPY_PATH("happy-path"),
        NEVER_READY("never-ready"),
        FAILED_STOP("failed-stop");

        private final String id;

        Scenario(String id) {
            this.id = id;
        }
    }

    private record ScenarioContext(
            Scenario scenario,
            String project,
            String velocityContainer,
            String backendContainer,
            Path work,
            Path evidence,
            Map<String, String> environment) {
    }

    private record ScenarioResult(String scenario, boolean passed, Duration duration, String detail) {
    }

    private static final class Deadline {
        private final long finishAt;

        private Deadline(Duration duration) {
            this.finishAt = System.nanoTime() + duration.toNanos();
        }

        private Duration bound(Duration requested) {
            long remaining = finishAt - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("Release-candidate E2E exceeded global timeout " + GLOBAL_TIMEOUT);
            }
            Duration remainingDuration = Duration.ofNanos(remaining);
            return requested.compareTo(remainingDuration) < 0 ? requested : remainingDuration;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
