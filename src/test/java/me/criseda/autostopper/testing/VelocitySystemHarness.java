package me.criseda.autostopper.testing;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import me.criseda.autostopper.testing.SystemTestSupport.CommandResult;

import static me.criseda.autostopper.testing.SystemTestSupport.deleteRecursively;
import static me.criseda.autostopper.testing.SystemTestSupport.requireSuccess;
import static me.criseda.autostopper.testing.SystemTestSupport.runCommand;
import static me.criseda.autostopper.testing.SystemTestSupport.sha256;

/**
 * Runs the packaged plugin in pinned real Velocity runtimes inside disposable containers.
 * This intentionally tests no live Docker socket or Minecraft backend behavior.
 */
public final class VelocitySystemHarness {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration IMAGE_PULL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration SHUTDOWN_COMMAND_TIMEOUT = Duration.ofSeconds(40);
    private static final String USER_AGENT = "AutoStopper system harness (https://github.com/Criseda/AutoStopper)";
    private static final String VALID_CONFIG = """
            inactivity_timeout_seconds: 300
            shutdown_timeout_seconds: 10
            monitored_servers: []
            """;
    private static final String INVALID_CONFIG = """
            inactivity_timeout_seconds: invalid
            monitored_servers: []
            """;
    private static final List<RuntimeProfile> PROFILES = List.of(
            new RuntimeProfile(
                    "legacy",
                    "Java 21",
                    "eclipse-temurin:21-jre",
                    "Velocity 3.5.1 build 615",
                    URI.create("https://fill-data.papermc.io/v1/objects/"
                            + "b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3/"
                            + "velocity-3.5.1-615.jar"),
                    "b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3"),
            new RuntimeProfile(
                    "current",
                    "Java 25",
                    "eclipse-temurin:25-jre",
                    "Velocity 4.1.0-SNAPSHOT build 16",
                    URI.create("https://fill-data.papermc.io/v1/objects/"
                            + "aebade8be3b15d7c3c61514a50ce857cbf78ee87bd32e8d16d2352c6ca3e472f/"
                            + "velocity-4.1.0-SNAPSHOT-16.jar"),
                    "aebade8be3b15d7c3c61514a50ce857cbf78ee87bd32e8d16d2352c6ca3e472f"));

    private final Path pluginArtifact;
    private final Path outputDirectory;
    private final Path workDirectory;
    private final Path downloadDirectory;
    private final Path logDirectory;
    private final String projectVersion;

    private VelocitySystemHarness(Path pluginArtifact, Path outputDirectory, String projectVersion) {
        this.pluginArtifact = pluginArtifact.toAbsolutePath().normalize();
        this.outputDirectory = outputDirectory.toAbsolutePath().normalize();
        this.workDirectory = this.outputDirectory.resolve("work");
        this.downloadDirectory = this.outputDirectory.resolve("downloads");
        this.logDirectory = this.outputDirectory.resolve("logs");
        this.projectVersion = projectVersion;
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                    "Expected arguments: <packaged-plugin-jar> <output-directory> <project-version> <profiles>");
        }

        VelocitySystemHarness harness = new VelocitySystemHarness(
                Path.of(arguments[0]), Path.of(arguments[1]), arguments[2]);
        harness.run(arguments[3]);
    }

    private void run(String selectedProfiles) throws Exception {
        requirePackagedArtifact();
        Files.createDirectories(outputDirectory);
        deleteRecursively(workDirectory);
        Files.createDirectories(workDirectory);
        Files.createDirectories(downloadDirectory);
        Files.createDirectories(logDirectory);

        CommandResult dockerVersion = runCommand(COMMAND_TIMEOUT, "docker", "version", "--format", "{{.Server.Version}}");
        requireSuccess(dockerVersion, "Docker daemon is unavailable");
        System.out.println("Docker daemon " + dockerVersion.output().trim() + " is available.");

        List<RuntimeProfile> selected = selectProfiles(selectedProfiles);
        try {
            for (RuntimeProfile profile : selected) {
                runProfile(profile);
            }
        } finally {
            deleteRecursively(workDirectory);
        }
        System.out.println("All requested Velocity system tests passed.");
    }

    private void requirePackagedArtifact() throws IOException {
        if (!Files.isRegularFile(pluginArtifact)) {
            throw new IllegalStateException("Packaged plugin JAR does not exist: " + pluginArtifact);
        }
        Path expectedDirectory = Path.of("target").toAbsolutePath().normalize();
        if (!pluginArtifact.getParent().equals(expectedDirectory)) {
            throw new IllegalStateException(
                    "System harness must use the packaged JAR from target, got " + pluginArtifact);
        }
        if (pluginArtifact.getFileName().toString().startsWith("original-")) {
            throw new IllegalStateException("System harness must use the shaded plugin JAR, not " + pluginArtifact);
        }
    }

    private List<RuntimeProfile> selectProfiles(String selection) {
        if (selection.equalsIgnoreCase("all")) {
            return PROFILES;
        }
        Set<String> requested = Set.of(selection.toLowerCase(Locale.ROOT).split(","));
        List<RuntimeProfile> selected = PROFILES.stream()
                .filter(profile -> requested.contains(profile.name()))
                .toList();
        if (selected.size() != requested.size()) {
            throw new IllegalArgumentException("Unknown Velocity system-test profile selection: " + selection);
        }
        return selected;
    }

    private void runProfile(RuntimeProfile profile) throws Exception {
        System.out.println("Preparing " + profile.javaLabel() + " / " + profile.velocityLabel() + "...");
        ensureImage(profile.image());
        Path velocityJar = downloadVelocity(profile);
        runScenario(profile, velocityJar, "success", VALID_CONFIG, true);
        runScenario(profile, velocityJar, "invalid-config", INVALID_CONFIG, false);
    }

    private void ensureImage(String image) throws Exception {
        CommandResult inspect = runCommand(COMMAND_TIMEOUT, "docker", "image", "inspect", image);
        if (inspect.exitCode() == 0) {
            return;
        }
        requireSuccess(runCommand(IMAGE_PULL_TIMEOUT, "docker", "pull", image),
                "Could not pull pinned runtime image " + image);
    }

    private Path downloadVelocity(RuntimeProfile profile) throws Exception {
        Path destination = downloadDirectory.resolve(profile.name() + "-velocity.jar");
        if (Files.isRegularFile(destination) && sha256(destination).equals(profile.sha256())) {
            return destination;
        }
        Files.deleteIfExists(destination);
        Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
        Files.deleteIfExists(temporary);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(profile.downloadUri())
                .timeout(Duration.ofMinutes(3))
                .header("User-Agent", USER_AGENT)
                .build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(temporary);
            throw new IllegalStateException("Velocity download failed with HTTP " + response.statusCode()
                    + " for " + profile.downloadUri());
        }
        String actualHash = sha256(temporary);
        if (!actualHash.equals(profile.sha256())) {
            Files.deleteIfExists(temporary);
            throw new IllegalStateException("Velocity download SHA-256 mismatch for " + profile.name()
                    + ": got " + actualHash + ", expected " + profile.sha256());
        }
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        return destination;
    }

    private void runScenario(
            RuntimeProfile profile, Path velocityJar, String scenario, String config, boolean expectEnablement)
            throws Exception {
        String container = containerName(profile.name(), scenario);
        Path fixture = workDirectory.resolve(profile.name() + "-" + scenario);
        Path pluginData = fixture.resolve("plugins/autostopper");
        Path logFile = logDirectory.resolve(profile.name() + "-" + scenario + ".log");
        Files.createDirectories(pluginData);
        Files.copy(velocityJar, fixture.resolve("velocity.jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(pluginArtifact, fixture.resolve("plugins/AutoStopper.jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("smoke", profile.name(), "velocity.toml"), fixture.resolve("velocity.toml"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(pluginData.resolve("config.yml"), config, StandardCharsets.UTF_8);

        boolean created = false;
        String capturedLogs = "";
        try {
            requireSuccess(runCommand(COMMAND_TIMEOUT,
                    "docker", "create",
                    "--name", container,
                    "--workdir", "/proxy",
                    profile.image(),
                    "java", "-Xms128m", "-Xmx256m", "-jar", "/proxy/velocity.jar"),
                    "Could not create system-test container " + container);
            created = true;
            requireSuccess(runCommand(COMMAND_TIMEOUT,
                    "docker", "cp", fixture.toAbsolutePath() + java.io.File.separator + ".", container + ":/proxy"),
                    "Could not copy the isolated fixture into " + container);
            requireSuccess(runCommand(COMMAND_TIMEOUT, "docker", "start", container),
                    "Could not start system-test container " + container);

            String packagedHash = sha256(pluginArtifact);
            CommandResult containerHash = runCommand(COMMAND_TIMEOUT,
                    "docker", "exec", container, "sha256sum", "/proxy/plugins/AutoStopper.jar");
            requireSuccess(containerHash, "Could not verify the packaged plugin copied into " + container);
            if (!containerHash.output().startsWith(packagedHash + " ")) {
                throw new IllegalStateException("Container plugin hash differs from packaged artifact " + pluginArtifact);
            }

            List<String> startupMarkers = new ArrayList<>();
            startupMarkers.add("Loaded plugin autostopper " + projectVersion + " by criseda");
            startupMarkers.add("Done (");
            if (expectEnablement) {
                startupMarkers.add("AutoStopper plugin initialized!");
            } else {
                startupMarkers.add(
                        "Configuration validation error: inactivity_timeout_seconds: expected a positive integer");
                startupMarkers.add(
                        "AutoStopper initialization aborted: inactivity_timeout_seconds: expected a positive integer");
            }
            capturedLogs = waitForStartup(container, startupMarkers);
            rejectFailureMarkers(capturedLogs, expectEnablement);

            long shutdownStarted = System.nanoTime();
            requireSuccess(runCommand(SHUTDOWN_COMMAND_TIMEOUT,
                    "docker", "stop", "--timeout", "30", container),
                    "Velocity did not stop within its bounded shutdown deadline for " + container);
            Duration shutdownDuration = Duration.ofNanos(System.nanoTime() - shutdownStarted);
            capturedLogs = readContainerLogs(container);
            if (!capturedLogs.contains("Shutting down the proxy")) {
                throw new IllegalStateException("Velocity did not report a clean shutdown for " + container);
            }
            if (expectEnablement
                    && !capturedLogs.contains("AutoStopper shutdown completed within 10 seconds.")) {
                throw new IllegalStateException("AutoStopper did not report bounded shutdown completion for " + container);
            }
            Files.writeString(logFile, capturedLogs, StandardCharsets.UTF_8);
            System.out.println("PASS " + profile.name() + "/" + scenario + " (shutdown "
                    + shutdownDuration.toMillis() + " ms, log " + logFile + ")");
        } finally {
            if (created) {
                capturedLogs = readContainerLogsBestEffort(container, capturedLogs);
                Files.writeString(logFile, capturedLogs, StandardCharsets.UTF_8);
                runCommand(COMMAND_TIMEOUT, "docker", "rm", "--force", container);
            }
            deleteRecursively(fixture);
        }
    }

    private String waitForStartup(String container, List<String> markers) throws Exception {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        String logs = "";
        while (System.nanoTime() < deadline) {
            logs = readContainerLogs(container);
            if (markers.stream().allMatch(logs::contains)) {
                return logs;
            }
            CommandResult running = runCommand(COMMAND_TIMEOUT,
                    "docker", "inspect", "--format", "{{.State.Running}}", container);
            if (running.exitCode() != 0 || !running.output().trim().equals("true")) {
                throw new IllegalStateException("Velocity exited before reaching startup assertions for "
                        + container + System.lineSeparator() + logs);
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Timed out waiting for startup markers " + markers + " in "
                + container + System.lineSeparator() + logs);
    }

    private void rejectFailureMarkers(String logs, boolean expectEnablement) {
        List<String> fatalMarkers = List.of(
                "Unable to load plugin",
                "Exception encountered when loading plugin",
                "Could not create plugin");
        fatalMarkers.stream()
                .filter(logs::contains)
                .findFirst()
                .ifPresent(marker -> {
                    throw new IllegalStateException("Velocity reported plugin load failure: " + marker);
                });
        if (!expectEnablement && logs.contains("AutoStopper plugin initialized!")) {
            throw new IllegalStateException("Invalid configuration unexpectedly enabled AutoStopper");
        }
    }

    private String readContainerLogs(String container) throws Exception {
        CommandResult logs = runCommand(COMMAND_TIMEOUT, "docker", "logs", container);
        requireSuccess(logs, "Could not read captured logs from " + container);
        return logs.output();
    }

    private String readContainerLogsBestEffort(String container, String fallback) {
        try {
            CommandResult logs = runCommand(COMMAND_TIMEOUT, "docker", "logs", container);
            return logs.exitCode() == 0 ? logs.output() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String containerName(String profile, String scenario) {
        return "autostopper-system-" + profile + "-" + scenario + "-"
                + ProcessHandle.current().pid() + "-" + Long.toUnsignedString(System.nanoTime(), 36);
    }

    private record RuntimeProfile(
            String name,
            String javaLabel,
            String image,
            String velocityLabel,
            URI downloadUri,
            String sha256) {
    }
}
