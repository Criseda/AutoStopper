package me.criseda.autostopper.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public class AutoStopperConfig {
    private static final String TIMEOUT_KEY = "inactivity_timeout_seconds";
    private static final String SHUTDOWN_TIMEOUT_KEY = "shutdown_timeout_seconds";
    private static final String STOP_RETRY_KEY = "stop_retry";
    private static final String SERVERS_KEY = "monitored_servers";

    private final Path dataDirectory;
    private final Logger logger;
    private final Path configFile;
    private final Predicate<String> knownServer;
    private final AtomicReference<ConfigSnapshot> current =
            new AtomicReference<>(ConfigSnapshot.emptyDefault());

    public AutoStopperConfig(Path dataDirectory, Logger logger, Predicate<String> knownServer) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.configFile = dataDirectory.resolve("config.yml");
        this.knownServer = knownServer;
    }

    AutoStopperConfig(Path dataDirectory, Logger logger) {
        this(dataDirectory, logger, ignored -> true);
    }

    public ConfigLoadResult loadConfig() {
        ConfigSnapshot retained = current.get();
        try {
            Files.createDirectories(dataDirectory);
            if (!Files.exists(configFile)) {
                logger.info("Creating default configuration file...");
                writeDefaultConfig();
                logger.info("Default configuration created at: {}", configFile.toAbsolutePath());
            }

            ConfigSnapshot candidate = parseAndValidate();
            current.set(candidate);
            logAppliedConfig(candidate);
            return ConfigLoadResult.success(candidate);
        } catch (ConfigValidationException e) {
            logRejectedConfig(e.errors());
            return ConfigLoadResult.failure(retained, e.errors());
        } catch (MarkedYAMLException e) {
            String location = e.getProblemMark() == null
                    ? "config.yml"
                    : "config.yml:" + (e.getProblemMark().getLine() + 1) + ":"
                            + (e.getProblemMark().getColumn() + 1);
            String detail = e.getProblem() == null ? e.getMessage() : e.getProblem();
            List<String> errors = List.of(location + ": invalid YAML: " + detail);
            logRejectedConfig(errors);
            return ConfigLoadResult.failure(retained, errors);
        } catch (YAMLException e) {
            List<String> errors = List.of("config.yml: invalid YAML: " + safeMessage(e));
            logRejectedConfig(errors);
            return ConfigLoadResult.failure(retained, errors);
        } catch (IOException e) {
            List<String> errors = List.of("config.yml: could not be read or created: " + safeMessage(e));
            logger.error("Configuration was not applied; previous configuration remains active", e);
            return ConfigLoadResult.failure(retained, errors);
        } catch (RuntimeException e) {
            List<String> errors = List.of("config.yml: could not be loaded: " + safeMessage(e));
            logger.error("Configuration was not applied; previous configuration remains active", e);
            return ConfigLoadResult.failure(retained, errors);
        }
    }

    private ConfigSnapshot parseAndValidate() throws IOException, ConfigValidationException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));

        Object document;
        try (BufferedReader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            document = yaml.load(reader);
        }

        List<String> errors = new ArrayList<>();
        if (document == null) {
            throw new ConfigValidationException(List.of("config.yml: configuration is empty"));
        }
        if (!(document instanceof Map<?, ?> root)) {
            throw new ConfigValidationException(List.of("config.yml: expected a mapping at the document root"));
        }

        int timeout = parseTimeout(root, errors);
        int shutdownTimeout = parsePositiveInteger(root.get(SHUTDOWN_TIMEOUT_KEY),
                SHUTDOWN_TIMEOUT_KEY, ConfigSnapshot.DEFAULT_SHUTDOWN_TIMEOUT_SECONDS,
                Integer.MAX_VALUE, errors);
        StopRetrySettings stopRetry = parseStopRetry(root.get(STOP_RETRY_KEY), errors);
        List<ServerMapping> mappings = parseMappings(root, errors);
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return new ConfigSnapshot(timeout, shutdownTimeout, stopRetry, mappings);
    }

    private StopRetrySettings parseStopRetry(Object value, List<String> errors) {
        StopRetrySettings defaults = StopRetrySettings.defaults();
        if (value == null) {
            return defaults;
        }
        if (!(value instanceof Map<?, ?> retry)) {
            errors.add(STOP_RETRY_KEY + ": expected a mapping");
            return defaults;
        }
        int maxAttempts = parsePositiveInteger(retry.get("max_attempts"),
                STOP_RETRY_KEY + ".max_attempts", defaults.maxAttempts(), 100, errors);
        int initialBackoffSeconds = parsePositiveInteger(retry.get("initial_backoff_seconds"),
                STOP_RETRY_KEY + ".initial_backoff_seconds",
                (int) defaults.initialBackoff().toSeconds(), Integer.MAX_VALUE, errors);
        int maxBackoffSeconds = parsePositiveInteger(retry.get("max_backoff_seconds"),
                STOP_RETRY_KEY + ".max_backoff_seconds",
                (int) defaults.maxBackoff().toSeconds(), Integer.MAX_VALUE, errors);
        if (maxBackoffSeconds < initialBackoffSeconds) {
            errors.add(STOP_RETRY_KEY
                    + ".max_backoff_seconds: must be greater than or equal to initial_backoff_seconds");
            return defaults;
        }
        return new StopRetrySettings(maxAttempts, Duration.ofSeconds(initialBackoffSeconds),
                Duration.ofSeconds(maxBackoffSeconds));
    }

    private int parseTimeout(Map<?, ?> root, List<String> errors) {
        if (!root.containsKey(TIMEOUT_KEY)) {
            return ConfigSnapshot.DEFAULT_INACTIVITY_TIMEOUT_SECONDS;
        }
        Object value = root.get(TIMEOUT_KEY);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            errors.add(TIMEOUT_KEY + ": expected a positive integer");
            return ConfigSnapshot.DEFAULT_INACTIVITY_TIMEOUT_SECONDS;
        }
        long timeout = ((Number) value).longValue();
        if (timeout <= 0 || timeout > Integer.MAX_VALUE) {
            errors.add(TIMEOUT_KEY + ": expected a positive integer no greater than " + Integer.MAX_VALUE);
            return ConfigSnapshot.DEFAULT_INACTIVITY_TIMEOUT_SECONDS;
        }
        return (int) timeout;
    }

    private List<ServerMapping> parseMappings(Map<?, ?> root, List<String> errors) {
        if (!root.containsKey(SERVERS_KEY)) {
            return List.of();
        }
        Object value = root.get(SERVERS_KEY);
        if (!(value instanceof List<?> entries)) {
            errors.add(SERVERS_KEY + ": expected a list");
            return List.of();
        }

        List<ServerMapping> mappings = new ArrayList<>();
        Set<String> serverNames = new HashSet<>();
        Set<String> containerNames = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            String path = SERVERS_KEY + "[" + index + "]";
            Object entry = entries.get(index);
            if (!(entry instanceof Map<?, ?> mapping)) {
                errors.add(path + ": expected a mapping");
                continue;
            }

            String serverName = parseName(mapping.get("server_name"), path + ".server_name", errors);
            String containerName = parseName(mapping.get("container_name"), path + ".container_name", errors);
            ReadinessSettings readiness = parseReadiness(mapping.get("readiness"), path + ".readiness", errors);
            if (serverName == null || containerName == null) {
                continue;
            }
            if (!knownServer.test(serverName)) {
                errors.add(path + ".server_name: unknown Velocity server '" + serverName + "'");
            }
            if (!serverNames.add(serverName)) {
                errors.add(path + ".server_name: duplicate server mapping '" + serverName + "'");
            }
            if (!containerNames.add(containerName)) {
                errors.add(path + ".container_name: duplicate container mapping '" + containerName + "'");
            }
            mappings.add(new ServerMapping(serverName, containerName, readiness));
        }
        return mappings;
    }

    private ReadinessSettings parseReadiness(Object value, String path, List<String> errors) {
        ReadinessSettings defaults = ReadinessSettings.defaults();
        if (value == null) {
            return defaults;
        }
        if (!(value instanceof Map<?, ?> readiness)) {
            errors.add(path + ": expected a mapping");
            return defaults;
        }

        ReadinessStrategy strategy = parseReadinessStrategy(readiness.get("strategy"), path + ".strategy", errors);
        String targetHost = parseOptionalName(readiness.get("target_host"), path + ".target_host", errors);
        Integer targetPort = parseOptionalPositiveInteger(
                readiness.get("target_port"), path + ".target_port", 65_535, errors);
        if ((targetHost == null) != (targetPort == null)) {
            errors.add(path + ": target_host and target_port must be configured together");
            targetHost = null;
            targetPort = null;
        }

        int intervalMillis = parsePositiveInteger(
                readiness.get("probe_interval_millis"),
                path + ".probe_interval_millis",
                ReadinessSettings.DEFAULT_PROBE_INTERVAL_MILLIS,
                Integer.MAX_VALUE,
                errors);
        int timeoutSeconds = parsePositiveInteger(
                readiness.get("timeout_seconds"),
                path + ".timeout_seconds",
                ReadinessSettings.DEFAULT_TIMEOUT_SECONDS,
                Integer.MAX_VALUE,
                errors);
        int connectTimeoutMillis = parsePositiveInteger(
                readiness.get("connect_timeout_millis"),
                path + ".connect_timeout_millis",
                ReadinessSettings.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                Integer.MAX_VALUE,
                errors);
        int readTimeoutMillis = parsePositiveInteger(
                readiness.get("read_timeout_millis"),
                path + ".read_timeout_millis",
                ReadinessSettings.DEFAULT_READ_TIMEOUT_MILLIS,
                Integer.MAX_VALUE,
                errors);

        return new ReadinessSettings(
                strategy,
                targetHost,
                targetPort,
                Duration.ofMillis(intervalMillis),
                Duration.ofSeconds(timeoutSeconds),
                Duration.ofMillis(connectTimeoutMillis),
                Duration.ofMillis(readTimeoutMillis));
    }

    private ReadinessStrategy parseReadinessStrategy(Object value, String path, List<String> errors) {
        if (value == null) {
            return ReadinessStrategy.MINECRAFT_STATUS;
        }
        if (!(value instanceof String name)) {
            errors.add(path + ": expected one of minecraft_status, docker_health, docker_health_or_status");
            return ReadinessStrategy.MINECRAFT_STATUS;
        }
        return ReadinessStrategy.fromConfigValue(name).orElseGet(() -> {
            errors.add(path + ": expected one of minecraft_status, docker_health, docker_health_or_status");
            return ReadinessStrategy.MINECRAFT_STATUS;
        });
    }

    private String parseOptionalName(Object value, String path, List<String> errors) {
        return value == null ? null : parseName(value, path, errors);
    }

    private Integer parseOptionalPositiveInteger(Object value, String path, int maximum, List<String> errors) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            errors.add(path + ": expected a positive integer");
            return null;
        }
        long number = ((Number) value).longValue();
        if (number <= 0 || number > maximum) {
            errors.add(path + ": expected a positive integer no greater than " + maximum);
            return null;
        }
        return (int) number;
    }

    private int parsePositiveInteger(Object value, String path, int defaultValue, int maximum, List<String> errors) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            errors.add(path + ": expected a positive integer");
            return defaultValue;
        }
        long number = ((Number) value).longValue();
        if (number <= 0 || number > maximum) {
            errors.add(path + ": expected a positive integer no greater than " + maximum);
            return defaultValue;
        }
        return (int) number;
    }

    private String parseName(Object value, String path, List<String> errors) {
        if (!(value instanceof String name)) {
            errors.add(path + ": expected a string");
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            errors.add(path + ": must not be blank");
            return null;
        }
        if (!trimmed.equals(name)) {
            errors.add(path + ": must not have leading or trailing whitespace");
            return null;
        }
        return name;
    }

    private void writeDefaultConfig() throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
            writer.write("# AutoStopper Configuration\n");
            writer.write("# Number of seconds of inactivity before a server is shut down.\n");
            writer.write(TIMEOUT_KEY + ": " + ConfigSnapshot.DEFAULT_INACTIVITY_TIMEOUT_SECONDS + "\n\n");
            writer.write("# Hard deadline for cancelling plugin work during proxy shutdown.\n");
            writer.write(SHUTDOWN_TIMEOUT_KEY + ": "
                    + ConfigSnapshot.DEFAULT_SHUTDOWN_TIMEOUT_SECONDS + "\n\n");
            writer.write("# Failed stops are retried with capped exponential backoff.\n");
            writer.write(STOP_RETRY_KEY + ":\n");
            writer.write("  max_attempts: " + StopRetrySettings.DEFAULT_MAX_ATTEMPTS + "\n");
            writer.write("  initial_backoff_seconds: "
                    + StopRetrySettings.DEFAULT_INITIAL_BACKOFF_SECONDS + "\n");
            writer.write("  max_backoff_seconds: " + StopRetrySettings.DEFAULT_MAX_BACKOFF_SECONDS + "\n\n");
            writer.write("# Add only server names already registered in Velocity.\n");
            writer.write(SERVERS_KEY + ": []\n\n");
            writer.write("# Example:\n");
            writer.write("# monitored_servers:\n");
            writer.write("#   - server_name: purpur\n");
            writer.write("#     container_name: purpur-server\n");
            writer.write("#     readiness:\n");
            writer.write("#       strategy: minecraft_status\n");
            writer.write("#       target_host: purpur\n");
            writer.write("#       target_port: 25565\n");
            writer.write("#       probe_interval_millis: 1000\n");
            writer.write("#       timeout_seconds: 120\n");
            writer.write("#       connect_timeout_millis: 1000\n");
            writer.write("#       read_timeout_millis: 1000\n");
        }
    }

    private void logAppliedConfig(ConfigSnapshot snapshot) {
        logger.info("Configuration loaded successfully!");
        logger.info("Applied configuration:");
        logger.info("- Inactivity timeout: {} seconds", snapshot.inactivityTimeoutSeconds());
        logger.info("- Shutdown timeout: {} seconds", snapshot.shutdownTimeoutSeconds());
        logger.info("- Stop retries: {} attempts, {}-{} second backoff",
                snapshot.stopRetry().maxAttempts(), snapshot.stopRetry().initialBackoff().toSeconds(),
                snapshot.stopRetry().maxBackoff().toSeconds());
        logger.info("- Monitored servers: {}", String.join(", ", snapshot.serverNames()));
    }

    private void logRejectedConfig(List<String> errors) {
        for (String error : errors) {
            logger.error("Configuration validation error: {}", error);
        }
        logger.error("Configuration was not applied; previous configuration remains active");
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public ConfigSnapshot snapshot() {
        return current.get();
    }

    public int getInactivityTimeout() {
        return snapshot().inactivityTimeoutSeconds();
    }

    public List<ServerMapping> getServers() {
        return snapshot().servers();
    }

    public String[] getServerNames() {
        return snapshot().serverNames().toArray(String[]::new);
    }

    public Map<String, String> getServerToContainerMap() {
        return snapshot().serverToContainer();
    }

    private static final class ConfigValidationException extends Exception {
        private final List<String> errors;

        private ConfigValidationException(List<String> errors) {
            this.errors = List.copyOf(errors);
        }

        private List<String> errors() {
            return errors;
        }
    }
}
