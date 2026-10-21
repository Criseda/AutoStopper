package me.criseda.autostopper.config;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * How AutoStopper reaches Docker. An empty endpoint means {@code auto}: {@code DOCKER_HOST}, then
 * the {@code autostopper-docker} socket-proxy sidecar, then the local Unix socket.
 */
public record DockerSettings(Backend backend, Optional<DockerEndpoint> endpoint) {
    public static final String AUTO = "auto";

    public enum Backend {
        AUTO("auto"),
        API("api"),
        CLI("cli");

        private final String configValue;

        Backend(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }

        public static Optional<Backend> fromConfigValue(String value) {
            String normalized = value.toLowerCase(Locale.ROOT);
            for (Backend backend : values()) {
                if (backend.configValue.equals(normalized)) {
                    return Optional.of(backend);
                }
            }
            return Optional.empty();
        }
    }

    public DockerSettings {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(endpoint, "endpoint");
    }

    public static DockerSettings defaults() {
        return new DockerSettings(Backend.AUTO, Optional.empty());
    }

    public String endpointConfigValue() {
        return endpoint.map(DockerEndpoint::toString).orElse(AUTO);
    }
}
