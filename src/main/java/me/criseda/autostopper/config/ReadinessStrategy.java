package me.criseda.autostopper.config;

import java.util.Locale;
import java.util.Optional;

public enum ReadinessStrategy {
    MINECRAFT_STATUS("minecraft_status"),
    DOCKER_HEALTH("docker_health"),
    DOCKER_HEALTH_OR_STATUS("docker_health_or_status");

    private final String configValue;

    ReadinessStrategy(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public boolean usesDockerHealth() {
        return this != MINECRAFT_STATUS;
    }

    public boolean usesMinecraftStatus() {
        return this != DOCKER_HEALTH;
    }

    public static Optional<ReadinessStrategy> fromConfigValue(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        for (ReadinessStrategy strategy : values()) {
            if (strategy.configValue.equals(normalized)) {
                return Optional.of(strategy);
            }
        }
        return Optional.empty();
    }
}
