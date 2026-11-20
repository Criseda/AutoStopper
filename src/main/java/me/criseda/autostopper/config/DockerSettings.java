package me.criseda.autostopper.config;

import java.util.Objects;
import java.util.Optional;

/**
 * How AutoStopper reaches Docker. An empty endpoint means {@code auto}: {@code DOCKER_HOST}, then
 * the {@code autostopper-docker} socket-proxy sidecar, then the local Unix socket.
 */
public record DockerSettings(Optional<DockerEndpoint> endpoint) {
    public static final String AUTO = "auto";

    public DockerSettings {
        Objects.requireNonNull(endpoint, "endpoint");
    }

    public static DockerSettings defaults() {
        return new DockerSettings(Optional.empty());
    }

    public String endpointConfigValue() {
        return endpoint.map(DockerEndpoint::toString).orElse(AUTO);
    }
}
