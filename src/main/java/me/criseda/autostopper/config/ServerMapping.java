package me.criseda.autostopper.config;

import java.util.Objects;

public record ServerMapping(String serverName, String containerName) {
    public ServerMapping {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(containerName, "containerName");
    }

    public String getServerName() {
        return serverName;
    }

    public String getContainerName() {
        return containerName;
    }
}
