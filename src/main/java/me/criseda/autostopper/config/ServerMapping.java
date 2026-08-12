package me.criseda.autostopper.config;

import java.util.Objects;

public record ServerMapping(String serverName, String containerName, ReadinessSettings readiness) {
    public ServerMapping {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(containerName, "containerName");
        Objects.requireNonNull(readiness, "readiness");
    }

    public ServerMapping(String serverName, String containerName) {
        this(serverName, containerName, ReadinessSettings.defaults());
    }

    public String getServerName() {
        return serverName;
    }

    public String getContainerName() {
        return containerName;
    }
}
