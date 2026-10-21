package me.criseda.autostopper.docker;

import me.criseda.autostopper.config.DockerEndpoint;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Talks to the Docker Engine API directly. One {@code GET /containers/{name}/json} answers status,
 * health and the stop grace period. Paths are unversioned so the daemon's own API version applies.
 * Stops omit {@code ?t=}, so the daemon waits for the container's configured StopTimeout, as
 * {@code docker stop} does.
 */
final class EngineApiDockerBackend implements DockerBackend {
    private static final int MAX_LOGGED_BODY = 300;
    private static final String HEX = "0123456789ABCDEF";

    private final Logger logger;
    private final EngineApiTransport transport;
    private final EndpointSource endpoints;
    private volatile DockerEndpoint lastEndpoint;

    EngineApiDockerBackend(Logger logger, EngineApiTransport transport, EndpointSource endpoints) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    }

    @Override
    public ContainerInspection inspect(String containerName, Duration timeout) {
        EngineApiResponse response;
        try {
            response = send("GET", containerPath(containerName, "json"), timeout);
        } catch (EngineApiException e) {
            return switch (e.kind()) {
                case TIMED_OUT, INTERRUPTED -> {
                    logger.warn("Timed out after {}ms checking status for container {}: {}",
                            timeout.toMillis(), containerName, e.getMessage());
                    yield DockerManager.diagnostic(ContainerStatus.TIMED_OUT, DockerDiagnostic.TIMED_OUT,
                            "Docker status check timed out",
                            "Check Docker daemon responsiveness and host load, then retry.");
                }
                case PERMISSION_DENIED -> {
                    logger.warn("Docker daemon inaccessible while checking container {}: {}",
                            containerName, e.getMessage());
                    yield DockerManager.permissionDenied();
                }
                case UNAVAILABLE -> {
                    logger.warn("Docker daemon unavailable while checking container {}: {}",
                            containerName, e.getMessage());
                    yield DockerManager.daemonUnavailable();
                }
                case PROTOCOL -> {
                    logger.warn("Could not check status for container {}: {}", containerName, e.getMessage());
                    yield statusCheckFailed();
                }
            };
        }

        switch (response.status()) {
            case 200:
                break;
            case 404:
                logger.warn("Container {} does not exist: {}", containerName, bodyForLog(response));
                return DockerManager.containerMissing();
            case 401:
            case 403:
                logger.warn("Docker endpoint refused inspecting container {} (HTTP {}): {}",
                        containerName, response.status(), bodyForLog(response));
                return proxyDenied();
            default:
                logger.warn("Could not check status for container {}: HTTP {}: {}",
                        containerName, response.status(), bodyForLog(response));
                return statusCheckFailed();
        }

        Object running = JsonReader.path(parse(response, containerName), "State", "Running");
        if (Boolean.TRUE.equals(running)) {
            return ContainerInspection.healthy(ContainerStatus.RUNNING);
        }
        if (Boolean.FALSE.equals(running)) {
            return ContainerInspection.healthy(ContainerStatus.STOPPED);
        }
        logger.warn("Unexpected inspect response for container {}: State.Running is {}", containerName, running);
        return DockerManager.diagnostic(ContainerStatus.FAILED, DockerDiagnostic.INDETERMINATE,
                "Docker returned an unexpected container state",
                "Inspect the configured container and verify its state.");
    }

    @Override
    public ContainerHealth health(String containerName, Duration timeout) {
        EngineApiResponse response;
        try {
            response = send("GET", containerPath(containerName, "json"), timeout);
        } catch (EngineApiException e) {
            logger.warn("Could not inspect health for container {}: {}", containerName, e.getMessage());
            return switch (e.kind()) {
                case TIMED_OUT, INTERRUPTED -> ContainerHealth.TIMED_OUT;
                case PERMISSION_DENIED, UNAVAILABLE -> ContainerHealth.INACCESSIBLE;
                case PROTOCOL -> ContainerHealth.FAILED;
            };
        }
        switch (response.status()) {
            case 200:
                break;
            case 404:
                return ContainerHealth.MISSING;
            case 401:
            case 403:
                logger.warn("Docker endpoint refused inspecting health of container {} (HTTP {}): {}",
                        containerName, response.status(), bodyForLog(response));
                return ContainerHealth.INACCESSIBLE;
            default:
                logger.warn("Could not inspect health for container {}: HTTP {}: {}",
                        containerName, response.status(), bodyForLog(response));
                return ContainerHealth.FAILED;
        }

        Object document = parse(response, containerName);
        Object running = JsonReader.path(document, "State", "Running");
        if (Boolean.FALSE.equals(running)) {
            return ContainerHealth.STOPPED;
        }
        if (!Boolean.TRUE.equals(running)) {
            logger.warn("Unexpected inspect response for container {}: State.Running is {}", containerName, running);
            return ContainerHealth.FAILED;
        }
        Object status = JsonReader.path(document, "State", "Health", "Status");
        if (status == null || "none".equals(status) || "".equals(status)) {
            return ContainerHealth.NO_HEALTHCHECK;
        }
        return switch (String.valueOf(status)) {
            case "healthy" -> ContainerHealth.HEALTHY;
            case "starting" -> ContainerHealth.STARTING;
            case "unhealthy" -> ContainerHealth.UNHEALTHY;
            default -> {
                logger.warn("Unexpected health status for container {}: {}", containerName, status);
                yield ContainerHealth.FAILED;
            }
        };
    }

    @Override
    public ContainerStatus start(String containerName, Duration timeout) {
        EngineApiResponse response;
        try {
            response = send("POST", containerPath(containerName, "start"), timeout);
        } catch (EngineApiException e) {
            return switch (e.kind()) {
                case TIMED_OUT, INTERRUPTED -> {
                    logger.error("Timed out after {}ms starting container {}: {}",
                            timeout.toMillis(), containerName, e.getMessage());
                    yield ContainerStatus.TIMED_OUT;
                }
                case PERMISSION_DENIED, UNAVAILABLE -> {
                    logger.error("Docker inaccessible starting container {}: {}", containerName, e.getMessage());
                    yield ContainerStatus.INACCESSIBLE;
                }
                case PROTOCOL -> {
                    logger.error("Failed to start container {}: {}", containerName, e.getMessage());
                    yield ContainerStatus.FAILED;
                }
            };
        }
        return switch (response.status()) {
            case 204 -> {
                logger.info("Started container: {}", containerName);
                yield ContainerStatus.RUNNING;
            }
            case 304 -> {
                logger.info("Container {} was already running.", containerName);
                yield ContainerStatus.RUNNING;
            }
            case 404 -> {
                logger.error("Cannot start container {}: container does not exist: {}",
                        containerName, bodyForLog(response));
                yield ContainerStatus.MISSING;
            }
            case 401, 403 -> {
                logger.error("Docker endpoint refused starting container {} (HTTP {}): {}",
                        containerName, response.status(), bodyForLog(response));
                yield ContainerStatus.INACCESSIBLE;
            }
            default -> {
                logger.error("Failed to start container {}: HTTP {}: {}",
                        containerName, response.status(), bodyForLog(response));
                yield ContainerStatus.FAILED;
            }
        };
    }

    @Override
    public OptionalLong stopTimeoutSeconds(String containerName, Duration timeout) {
        EngineApiResponse response;
        try {
            response = send("GET", containerPath(containerName, "json"), timeout);
        } catch (EngineApiException e) {
            logger.debug("Could not read the stop grace period for container {}: {}", containerName, e.getMessage());
            return OptionalLong.empty();
        }
        if (response.status() != 200) {
            logger.debug("Could not read the stop grace period for container {}: HTTP {}",
                    containerName, response.status());
            return OptionalLong.empty();
        }
        Object value = JsonReader.path(parse(response, containerName), "Config", "StopTimeout");
        if (value instanceof Long seconds) {
            return OptionalLong.of(seconds);
        }
        if (value != null) {
            logger.warn("Unexpected stop grace period for container {}: {}", containerName, value);
        }
        return OptionalLong.empty();
    }

    @Override
    public ContainerStatus stop(String containerName, Duration deadline) {
        EngineApiResponse response;
        try {
            response = send("POST", containerPath(containerName, "stop"), deadline);
        } catch (EngineApiException e) {
            return switch (e.kind()) {
                case TIMED_OUT, INTERRUPTED -> ContainerStatus.TIMED_OUT;
                case PERMISSION_DENIED, UNAVAILABLE -> {
                    logger.error("Docker inaccessible stopping container {}: {}", containerName, e.getMessage());
                    yield ContainerStatus.INACCESSIBLE;
                }
                case PROTOCOL -> {
                    logger.error("Failed to stop container {}: {}", containerName, e.getMessage());
                    yield ContainerStatus.FAILED;
                }
            };
        }
        return switch (response.status()) {
            case 204 -> {
                logger.info("Stopped container: {}", containerName);
                yield ContainerStatus.STOPPED;
            }
            case 304 -> {
                logger.info("Container {} was already stopped.", containerName);
                yield ContainerStatus.STOPPED;
            }
            case 404 -> {
                logger.error("Cannot stop container {}: container does not exist: {}",
                        containerName, bodyForLog(response));
                yield ContainerStatus.MISSING;
            }
            case 401, 403 -> {
                logger.error("Docker endpoint refused stopping container {} (HTTP {}): {}",
                        containerName, response.status(), bodyForLog(response));
                yield ContainerStatus.INACCESSIBLE;
            }
            default -> {
                logger.error("Failed to stop container {}: HTTP {}: {}",
                        containerName, response.status(), bodyForLog(response));
                yield ContainerStatus.FAILED;
            }
        };
    }

    @Override
    public String describe() {
        DockerEndpoint resolved = lastEndpoint;
        return "Engine API, " + endpoints.describe(resolved);
    }

    private EngineApiResponse send(String method, String path, Duration timeout) throws EngineApiException {
        long deadline = System.nanoTime() + timeout.toNanos();
        DockerEndpoint endpoint = endpoints.resolve(deadline);
        DockerEndpoint previous = lastEndpoint;
        if (!endpoint.equals(previous)) {
            lastEndpoint = endpoint;
            logger.info("Docker Engine API endpoint: {}", endpoints.describe(endpoint));
        }
        return transport.exchange(endpoint, method, path, deadline);
    }

    private Object parse(EngineApiResponse response, String containerName) {
        try {
            return JsonReader.parse(response.body());
        } catch (IllegalArgumentException e) {
            logger.warn("Could not parse the inspect response for container {}: {}", containerName, e.getMessage());
            return null;
        }
    }

    private static ContainerInspection statusCheckFailed() {
        return DockerManager.diagnostic(ContainerStatus.FAILED, DockerDiagnostic.INDETERMINATE,
                "Docker status check failed",
                "Inspect the configured container and review proxy logs.");
    }

    private static ContainerInspection proxyDenied() {
        return DockerManager.diagnostic(ContainerStatus.INACCESSIBLE, DockerDiagnostic.PERMISSION_DENIED,
                "Docker endpoint refused the request",
                "Allow container inspect, start and stop in the Docker socket proxy allowlist or "
                        + "authorization policy.");
    }

    static String containerPath(String containerName, String action) {
        StringBuilder path = new StringBuilder("/containers/");
        for (byte b : containerName.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                path.append(c);
            } else {
                path.append('%').append(HEX.charAt((b >> 4) & 0xF)).append(HEX.charAt(b & 0xF));
            }
        }
        return path.append('/').append(action).toString();
    }

    /** Docker error bodies are {@code {"message": "..."}}; logs get that message or a bounded excerpt. */
    private static String bodyForLog(EngineApiResponse response) {
        String body = response.body();
        try {
            Object message = JsonReader.path(JsonReader.parse(body), "message");
            if (message instanceof String text) {
                body = text;
            }
        } catch (IllegalArgumentException ignored) {
            // Not JSON: log the raw excerpt.
        }
        String flattened = body.replaceAll("\\p{Cntrl}+", " ").trim();
        return flattened.length() > MAX_LOGGED_BODY
                ? flattened.substring(0, MAX_LOGGED_BODY) + "[truncated]"
                : flattened;
    }

    /** Where requests go. Resolution may do bounded I/O and runs on the calling worker thread. */
    interface EndpointSource {
        DockerEndpoint resolve(long deadlineNanos) throws EngineApiException;

        /** Describes the source, including {@code resolved} when known (may be {@code null}). */
        String describe(DockerEndpoint resolved);
    }
}
