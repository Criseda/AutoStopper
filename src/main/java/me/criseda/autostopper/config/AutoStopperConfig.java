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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public class AutoStopperConfig {
    private static final String TIMEOUT_KEY = "inactivity_timeout_seconds";
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
        List<ServerMapping> mappings = parseMappings(root, errors);
        if (!errors.isEmpty()) {
            throw new ConfigValidationException(errors);
        }
        return new ConfigSnapshot(timeout, mappings);
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
            mappings.add(new ServerMapping(serverName, containerName));
        }
        return mappings;
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
            writer.write("# Add only server names already registered in Velocity.\n");
            writer.write(SERVERS_KEY + ": []\n\n");
            writer.write("# Example:\n");
            writer.write("# monitored_servers:\n");
            writer.write("#   - server_name: purpur\n");
            writer.write("#     container_name: purpur-server\n");
        }
    }

    private void logAppliedConfig(ConfigSnapshot snapshot) {
        logger.info("Configuration loaded successfully!");
        logger.info("Applied configuration:");
        logger.info("- Inactivity timeout: {} seconds", snapshot.inactivityTimeoutSeconds());
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
