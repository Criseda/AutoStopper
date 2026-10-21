package me.criseda.autostopper.docker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import me.criseda.autostopper.config.DockerEndpoint;
import me.criseda.autostopper.config.DockerSettings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class DockerBackendSelectorTest {
    private static final DockerSettings AUTO = DockerSettings.defaults();

    @TempDir
    Path tempDir;

    private final Logger logger = mock(Logger.class);
    private final CommandRunner commandRunner = (command, timeout) -> {
        throw new AssertionError("selection must not run the Docker CLI");
    };

    @Test
    public void autoUsesTheEngineApiWithAutoEndpointOnLinux() {
        DockerBackend backend = selector(Map.of(), "Linux").select(AUTO);

        assertInstanceOf(EngineApiDockerBackend.class, backend);
        assertEquals("Engine API, endpoint auto (not resolved yet)", backend.describe());
    }

    @Test
    public void explicitEndpointWins() {
        DockerSettings settings = new DockerSettings(DockerSettings.Backend.AUTO,
                Optional.of(DockerEndpoint.tcp("proxy", 2375)));

        DockerBackend backend = selector(Map.of("DOCKER_HOST", "ssh://user@host"), "Windows 11").select(settings);

        assertEquals("Engine API, endpoint tcp://proxy:2375 (configured)", backend.describe());
    }

    @Test
    public void supportedDockerHostIsFollowed() {
        assertEquals("Engine API, endpoint unix:///run/user/1000/docker.sock (from DOCKER_HOST)",
                selector(Map.of("DOCKER_HOST", "unix:///run/user/1000/docker.sock"), "Linux")
                        .select(AUTO).describe());
        assertEquals("Engine API, endpoint tcp://dind:2375 (from DOCKER_HOST)",
                selector(Map.of("DOCKER_HOST", "tcp://dind:2375", "DOCKER_CONTEXT", "remote"), "Windows 11")
                        .select(AUTO).describe(), "DOCKER_HOST takes precedence over contexts, as in the CLI");
    }

    @Test
    public void autoFallsBackToTheCliWhereTheApiBackendCannotFollow() throws IOException {
        Files.writeString(tempDir.resolve("config.json"), "{\"currentContext\":\"rootless\"}",
                StandardCharsets.UTF_8);
        Map<Map<String, String>, String> cases = Map.of(
                Map.of("DOCKER_HOST", "ssh://user@host"), "DOCKER_HOST uses ssh://",
                Map.of("DOCKER_HOST", "npipe:////./pipe/docker_engine"), "DOCKER_HOST uses npipe://",
                Map.of("DOCKER_HOST", "tcp://host:2376", "DOCKER_TLS_VERIFY", "1"), "DOCKER_HOST uses TLS",
                Map.of("DOCKER_HOST", "tcp://host:2376", "DOCKER_CERT_PATH", "/certs"), "DOCKER_HOST uses TLS",
                Map.of("DOCKER_CONTEXT", "remote"), "Docker context 'remote' is selected");
        cases.forEach((environment, reason) -> {
            DockerBackend backend = selector(environment, "Linux").select(AUTO);

            assertInstanceOf(DockerCliBackend.class, backend, reason);
            assertEquals("Docker CLI (" + reason + "; deprecated)", backend.describe());
            assertFalse(backend.describe().contains("user@host"), "addresses are not echoed");
        });

        DockerBackendSelector withConfig = new DockerBackendSelector(logger, commandRunner, Map.of(), "Linux",
                unknownHosts(), tempDir.resolve("config.json"));
        assertEquals("Docker CLI (Docker context 'rootless' is selected; deprecated)",
                withConfig.select(AUTO).describe());
        assertEquals("Docker CLI (Windows host without DOCKER_HOST; deprecated)",
                selector(Map.of(), "Windows 11").select(AUTO).describe());
    }

    @Test
    public void defaultContextAndUnreadableConfigKeepTheApi() throws IOException {
        Path config = tempDir.resolve("config.json");
        Files.writeString(config, "{\"currentContext\":\"default\"}", StandardCharsets.UTF_8);
        assertInstanceOf(EngineApiDockerBackend.class, new DockerBackendSelector(logger, commandRunner,
                Map.of("DOCKER_CONTEXT", "default"), "Linux", unknownHosts(), config).select(AUTO));

        Files.writeString(config, "{not json", StandardCharsets.UTF_8);
        assertInstanceOf(EngineApiDockerBackend.class, new DockerBackendSelector(logger, commandRunner,
                Map.of(), "Linux", unknownHosts(), config).select(AUTO));
    }

    @Test
    public void explicitBackendsAreHonoured() {
        DockerSettings cli = new DockerSettings(DockerSettings.Backend.CLI, Optional.empty());
        DockerSettings api = new DockerSettings(DockerSettings.Backend.API, Optional.empty());

        assertEquals("Docker CLI (docker.backend: cli; deprecated)",
                selector(Map.of("DOCKER_HOST", "unix:///var/run/docker.sock"), "Linux").select(cli).describe());
        DockerBackend forced = selector(Map.of("DOCKER_HOST", "ssh://host", "DOCKER_CONTEXT", "x"), "Windows 11")
                .select(api);
        assertInstanceOf(EngineApiDockerBackend.class, forced);
        assertEquals("Engine API, endpoint auto (not resolved yet)", forced.describe());
    }

    @Test
    public void autoEndpointPrefersTheSidecarThenTheLocalSocket() throws EngineApiException {
        AtomicReference<String> looked = new AtomicReference<>();
        DockerBackendSelector resolving = new DockerBackendSelector(logger, commandRunner, Map.of(), "Linux",
                (host, timeout) -> {
                    looked.set(host);
                    assertTrue(timeout.compareTo(DockerBackendSelector.SIDECAR_LOOKUP_LIMIT) <= 0);
                    return InetAddress.getLoopbackAddress();
                }, null);
        EngineApiDockerBackend.EndpointSource sidecar = resolving.autoEndpoint();

        DockerEndpoint resolved = sidecar.resolve(deadline(Duration.ofSeconds(30)));

        assertEquals(DockerEndpoint.tcp("autostopper-docker", 2375), resolved);
        assertEquals("autostopper-docker", looked.get());
        assertEquals("endpoint auto: tcp://autostopper-docker:2375 (socket-proxy sidecar)",
                sidecar.describe(resolved));

        EngineApiDockerBackend.EndpointSource local = selector(Map.of(), "Linux").autoEndpoint();
        DockerEndpoint socket = local.resolve(deadline(Duration.ofSeconds(30)));
        assertEquals(DockerEndpoint.unix("/var/run/docker.sock"), socket);
        assertEquals("endpoint auto: unix:///var/run/docker.sock (local socket)", local.describe(socket));

        EngineApiDockerBackend.EndpointSource slowDns = new DockerBackendSelector(logger, commandRunner,
                Map.of(), "Linux", (host, timeout) -> {
                    throw new TimeoutException();
                }, null).autoEndpoint();
        assertEquals(DockerEndpoint.unix("/var/run/docker.sock"), slowDns.resolve(deadline(Duration.ofSeconds(30))),
                "a slow lookup of an absent sidecar falls back to the local socket");
    }

    @Test
    public void autoEndpointHonoursDeadlineAndInterrupt() {
        EngineApiDockerBackend.EndpointSource source = selector(Map.of(), "Linux").autoEndpoint();
        EngineApiException expired = assertThrows(EngineApiException.class,
                () -> source.resolve(System.nanoTime() - 1));
        assertEquals(EngineApiException.Kind.TIMED_OUT, expired.kind());

        EngineApiDockerBackend.EndpointSource interrupted = new DockerBackendSelector(logger, commandRunner,
                Map.of(), "Linux", (host, timeout) -> {
                    throw new InterruptedException();
                }, null).autoEndpoint();
        try {
            EngineApiException error = assertThrows(EngineApiException.class,
                    () -> interrupted.resolve(deadline(Duration.ofSeconds(30))));
            assertEquals(EngineApiException.Kind.INTERRUPTED, error.kind());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void systemResolverIsBounded() throws Exception {
        HostResolver resolver = HostResolver.system();

        assertTrue(resolver.resolve("127.0.0.1", Duration.ofSeconds(5)).isLoopbackAddress());
    }

    private DockerBackendSelector selector(Map<String, String> environment, String osName) {
        return new DockerBackendSelector(logger, commandRunner, environment, osName, unknownHosts(), null);
    }

    private static HostResolver unknownHosts() {
        return (host, timeout) -> {
            throw new UnknownHostException(host);
        };
    }

    private static long deadline(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }
}
