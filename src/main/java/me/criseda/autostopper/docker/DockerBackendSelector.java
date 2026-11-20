package me.criseda.autostopper.docker;

import me.criseda.autostopper.config.DockerEndpoint;
import me.criseda.autostopper.config.DockerSettings;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Chooses how to reach Docker for {@link DockerSettings}. A configured endpoint wins; otherwise a
 * {@code unix://} or plain {@code tcp://} {@code DOCKER_HOST}, then the socket-proxy sidecar, then
 * the local socket. An environment that points the Docker CLI somewhere the Engine API backend
 * cannot follow (an {@code ssh://} or TLS {@code DOCKER_HOST}, a selected Docker context, or a
 * Windows host without {@code DOCKER_HOST}) gets {@link UnsupportedDockerAccess} instead of a
 * different daemon. Selection runs on a worker thread; it reads the Docker CLI config file at most
 * once per settings change.
 */
final class DockerBackendSelector {
    static final String SIDECAR_HOST = "autostopper-docker";
    static final int SIDECAR_PORT = 2375;
    static final DockerEndpoint DEFAULT_SOCKET = DockerEndpoint.unix("/var/run/docker.sock");
    /** The sidecar lookup is a probe; a slow resolver must not hold an operation for long. */
    static final Duration SIDECAR_LOOKUP_LIMIT = Duration.ofSeconds(2);
    private static final long MAX_DOCKER_CONFIG_BYTES = 1024 * 1024;

    private final Logger logger;
    private final Map<String, String> environment;
    private final String osName;
    private final HostResolver resolver;
    private final Path dockerConfigFile;

    DockerBackendSelector(Logger logger) {
        this(logger, System.getenv(), System.getProperty("os.name", ""), HostResolver.system(),
                defaultDockerConfigFile(System.getenv()));
    }

    DockerBackendSelector(Logger logger, Map<String, String> environment, String osName, HostResolver resolver,
            Path dockerConfigFile) {
        this.logger = logger;
        this.environment = Map.copyOf(environment);
        this.osName = osName;
        this.resolver = resolver;
        this.dockerConfigFile = dockerConfigFile;
    }

    DockerBackend select(DockerSettings settings) {
        if (settings.endpoint().isPresent()) {
            return api(fixed(settings.endpoint().get(), "configured"));
        }

        String dockerHost = env("DOCKER_HOST");
        if (dockerHost != null) {
            Optional<String> unsupported = unsupportedDockerHost(dockerHost);
            if (unsupported.isPresent()) {
                return unsupported(unsupported.get());
            }
            return api(fixed(DockerEndpoint.parse(dockerHost).orElseThrow(), "from DOCKER_HOST"));
        }
        Optional<String> context = selectedContext();
        if (context.isPresent()) {
            return unsupported("Docker context '" + context.get() + "' is selected");
        }
        if (osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            return unsupported("Windows host without DOCKER_HOST");
        }
        return api(autoEndpoint());
    }

    private Optional<String> unsupportedDockerHost(String dockerHost) {
        Optional<DockerEndpoint> endpoint = DockerEndpoint.parse(dockerHost);
        if (endpoint.isEmpty()) {
            int scheme = dockerHost.indexOf("://");
            return Optional.of("DOCKER_HOST uses "
                    + (scheme > 0 ? dockerHost.substring(0, scheme + 3) : "an unsupported address"));
        }
        if (endpoint.get().kind() == DockerEndpoint.Kind.TCP
                && (env("DOCKER_TLS_VERIFY") != null || env("DOCKER_CERT_PATH") != null)) {
            return Optional.of("DOCKER_HOST uses TLS");
        }
        return Optional.empty();
    }

    private Optional<String> selectedContext() {
        String context = env("DOCKER_CONTEXT");
        if (context == null) {
            context = configuredContext();
        }
        return context == null || "default".equals(context) ? Optional.empty() : Optional.of(context);
    }

    private String configuredContext() {
        if (dockerConfigFile == null) {
            return null;
        }
        try {
            if (!Files.isRegularFile(dockerConfigFile) || Files.size(dockerConfigFile) > MAX_DOCKER_CONFIG_BYTES) {
                return null;
            }
            Object context = JsonReader.path(
                    JsonReader.parse(Files.readString(dockerConfigFile, StandardCharsets.UTF_8)), "currentContext");
            return context instanceof String name && !name.isBlank() ? name : null;
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not read the Docker CLI context from {}: {}", dockerConfigFile, e.getMessage());
            return null;
        }
    }

    private DockerBackend unsupported(String reason) {
        logger.error("Cannot reach Docker: {}, which AutoStopper does not support. {}", reason,
                UnsupportedDockerAccess.REMEDIATION);
        return new UnsupportedDockerAccess(reason);
    }

    private DockerBackend api(EngineApiDockerBackend.EndpointSource endpoints) {
        return new EngineApiDockerBackend(logger, new EngineApiTransport(resolver), endpoints);
    }

    private static EngineApiDockerBackend.EndpointSource fixed(DockerEndpoint endpoint, String origin) {
        return new EngineApiDockerBackend.EndpointSource() {
            @Override
            public DockerEndpoint resolve(long deadlineNanos) {
                return endpoint;
            }

            @Override
            public String describe(DockerEndpoint resolved) {
                return "endpoint " + endpoint + " (" + origin + ")";
            }
        };
    }

    /** {@code tcp://autostopper-docker:2375} when that name resolves, otherwise the local socket. */
    EngineApiDockerBackend.EndpointSource autoEndpoint() {
        return new EngineApiDockerBackend.EndpointSource() {
            @Override
            public DockerEndpoint resolve(long deadlineNanos) throws EngineApiException {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw new EngineApiException(EngineApiException.Kind.TIMED_OUT,
                            "Docker API request deadline expired");
                }
                Duration limit = Duration.ofNanos(Math.min(remaining, SIDECAR_LOOKUP_LIMIT.toNanos()));
                try {
                    resolver.resolve(SIDECAR_HOST, limit);
                    return DockerEndpoint.tcp(SIDECAR_HOST, SIDECAR_PORT);
                } catch (UnknownHostException e) {
                    return DEFAULT_SOCKET;
                } catch (TimeoutException e) {
                    if (deadlineNanos - System.nanoTime() <= 0) {
                        throw new EngineApiException(EngineApiException.Kind.TIMED_OUT,
                                "Docker API request deadline expired");
                    }
                    return DEFAULT_SOCKET;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new EngineApiException(EngineApiException.Kind.INTERRUPTED,
                            "Docker API request interrupted");
                }
            }

            @Override
            public String describe(DockerEndpoint resolved) {
                if (resolved == null) {
                    return "endpoint auto (not resolved yet)";
                }
                return "endpoint auto: " + resolved + (DEFAULT_SOCKET.equals(resolved)
                        ? " (local socket)" : " (socket-proxy sidecar)");
            }
        };
    }

    private String env(String name) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Path defaultDockerConfigFile(Map<String, String> environment) {
        try {
            String configDir = environment.get("DOCKER_CONFIG");
            if (configDir != null && !configDir.isBlank()) {
                return Path.of(configDir, "config.json");
            }
            String home = System.getProperty("user.home");
            return home == null || home.isBlank() ? null : Path.of(home, ".docker", "config.json");
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
