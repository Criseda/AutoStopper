package me.criseda.autostopper.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutoStopperConfig {
    private final Path dataDirectory;
    private final Logger logger;
    private Path configFile;

    private int inactivityTimeout = 300; // Default: 5 minutes
    private List<ServerMapping> servers = new ArrayList<>();

    public AutoStopperConfig(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.configFile = dataDirectory.resolve("config.yml");
    }

    public void loadConfig() {
        try {
            Files.createDirectories(dataDirectory);

            // Check if config exists, create it if it doesn't
            if (!Files.exists(configFile)) {
                logger.info("Creating default configuration file...");
                createDefaultConfig();
                save();
                logger.info("Default configuration created at: " + configFile.toAbsolutePath());
            }

            // Load the configuration
            load();
            logger.info("Configuration loaded successfully!");

        } catch (IOException e) {
            logger.error("Failed to setup configuration", e);
            // Use default values if config fails
            createDefaultConfig();
        }
    }

    private void createDefaultConfig() {
        // Add some example servers
        servers.clear();
        servers.add(new ServerMapping("purpur", "purpur-server"));
        servers.add(new ServerMapping("fabric", "fabric-server"));
    }

    private void save() throws IOException {
        // First write the template with comments
        String template = "# AutoStopper Configuration\n" +
                "# -----------------------\n" +
                "# This file controls the behavior of the AutoStopper plugin.\n" +
                "\n" +
                "# Number of seconds of inactivity before a server is shut down\n" +
                "# Default: 300 (5 minutes)\n" +
                "inactivity_timeout_seconds: " + inactivityTimeout + "\n" +
                "\n" +
                "# Servers to monitor for inactivity\n" +
                "# Each entry must have:\n" +
                "#   - server_name: The name of the server in Velocity (what players type in /server command)\n" +
                "#   - container_name: The Docker container name for this server\n" +
                "monitored_servers:\n";

        try (BufferedWriter writer = Files.newBufferedWriter(configFile)) {
            // Write the template with comments
            writer.write(template);

            // Configure YAML for proper output format
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            new Yaml(options);

            // Convert servers to maps and write them
            for (ServerMapping server : servers) {
                writer.write("  # Server configuration for " + server.getServerName() + "\n");
                writer.write("  - server_name: " + server.getServerName() + "\n");
                writer.write("    container_name: " + server.getContainerName() + "\n");
                writer.write("\n");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void load() throws IOException {
        Yaml yaml = new Yaml();
        try (BufferedReader reader = Files.newBufferedReader(configFile)) {
            Map<String, Object> data = yaml.load(reader);

            // Load timeout setting
            if (data.containsKey("inactivity_timeout_seconds")) {
                Object timeoutObj = data.get("inactivity_timeout_seconds");
                if (timeoutObj instanceof Integer) {
                    inactivityTimeout = (Integer) timeoutObj;
                } else if (timeoutObj != null) {
                    inactivityTimeout = Integer.parseInt(timeoutObj.toString());
                }
            }

            // Load servers
            servers.clear();
            if (data.containsKey("monitored_servers")) {
                List<Map<String, Object>> serversData = (List<Map<String, Object>>) data.get("monitored_servers");
                for (Map<String, Object> serverData : serversData) {
                    String serverName = (String) serverData.get("server_name");
                    String containerName = (String) serverData.get("container_name");

                    if (serverName != null && containerName != null) {
                        servers.add(new ServerMapping(serverName, containerName));
                    }
                }
            }
        }

        logger.info("Applied configuration: ");
        logger.info("- Inactivity timeout: " + inactivityTimeout + " seconds");
        logger.info("- Monitored servers: " + String.join(", ", getServerNames()));
    }

    public static class ServerMapping {
        private String serverName;
        private String containerName;

        public ServerMapping(String serverName, String containerName) {
            this.serverName = serverName;
            this.containerName = containerName;
        }

        public String getServerName() {
            return serverName;
        }

        public String getContainerName() {
            return containerName;
        }
    }

    public int getInactivityTimeout() {
        return inactivityTimeout;
    }

    public List<ServerMapping> getServers() {
        return servers;
    }

    public String[] getServerNames() {
        return servers.stream()
                .map(ServerMapping::getServerName)
                .toArray(String[]::new);
    }

    public Map<String, String> getServerToContainerMap() {
        Map<String, String> map = new HashMap<>();
        for (ServerMapping server : servers) {
            map.put(server.getServerName(), server.getContainerName());
        }
        return map;
    }
}