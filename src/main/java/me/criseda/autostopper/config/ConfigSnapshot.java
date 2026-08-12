package me.criseda.autostopper.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ConfigSnapshot {
    public static final int DEFAULT_INACTIVITY_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 10;

    private final int inactivityTimeoutSeconds;
    private final int shutdownTimeoutSeconds;
    private final StopRetrySettings stopRetry;
    private final List<ServerMapping> servers;
    private final Map<String, String> serverToContainer;

    public ConfigSnapshot(int inactivityTimeoutSeconds, List<ServerMapping> servers) {
        this(inactivityTimeoutSeconds, DEFAULT_SHUTDOWN_TIMEOUT_SECONDS,
                StopRetrySettings.defaults(), servers);
    }

    public ConfigSnapshot(int inactivityTimeoutSeconds, StopRetrySettings stopRetry, List<ServerMapping> servers) {
        this(inactivityTimeoutSeconds, DEFAULT_SHUTDOWN_TIMEOUT_SECONDS, stopRetry, servers);
    }

    public ConfigSnapshot(int inactivityTimeoutSeconds, int shutdownTimeoutSeconds,
            StopRetrySettings stopRetry, List<ServerMapping> servers) {
        this.inactivityTimeoutSeconds = inactivityTimeoutSeconds;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.stopRetry = stopRetry;
        this.servers = List.copyOf(servers);

        Map<String, String> mapping = new LinkedHashMap<>();
        for (ServerMapping server : this.servers) {
            mapping.put(server.serverName(), server.containerName());
        }
        this.serverToContainer = Collections.unmodifiableMap(mapping);
    }

    public static ConfigSnapshot emptyDefault() {
        return new ConfigSnapshot(DEFAULT_INACTIVITY_TIMEOUT_SECONDS, List.of());
    }

    public int inactivityTimeoutSeconds() {
        return inactivityTimeoutSeconds;
    }

    public int shutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    public StopRetrySettings stopRetry() {
        return stopRetry;
    }

    public List<ServerMapping> servers() {
        return servers;
    }

    public Map<String, String> serverToContainer() {
        return serverToContainer;
    }

    public List<String> serverNames() {
        return servers.stream().map(ServerMapping::serverName).toList();
    }

    public Optional<ServerMapping> server(String serverName) {
        return servers.stream().filter(mapping -> mapping.serverName().equals(serverName)).findFirst();
    }

    public boolean containsServer(String serverName) {
        return serverToContainer.containsKey(serverName);
    }
}
