package me.criseda.autostopper.docker;

import static me.criseda.autostopper.docker.FakeDockerEngine.Reply;
import static me.criseda.autostopper.docker.FakeDockerEngine.inspectBody;
import static org.junit.jupiter.api.Assertions.*;

import me.criseda.autostopper.config.DockerEndpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@ExtendWith(MockitoExtension.class)
public class EngineApiDockerBackendTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String INSPECT = "GET /containers/mc/json";
    private static final String START = "POST /containers/mc/start";
    private static final String STOP = "POST /containers/mc/stop";

    @TempDir
    Path tempDir;

    @Mock
    private Logger logger;

    private final List<FakeDockerEngine> engines = new ArrayList<>();

    @AfterEach
    public void closeEngines() throws IOException {
        for (FakeDockerEngine engine : engines) {
            engine.close();
        }
    }

    @Test
    public void inspectReadsStateOverUnixSocketAndTcp() throws IOException {
        for (FakeDockerEngine engine : List.of(unixEngine(), tcpEngine())) {
            engine.on(INSPECT, Reply.json(200, inspectBody(true, null, null)))
                    .on(INSPECT, Reply.chunked(200, inspectBody(false, null, null), 7));
            EngineApiDockerBackend backend = backend(engine);

            assertEquals(ContainerInspection.healthy(ContainerStatus.RUNNING), backend.inspect("mc", TIMEOUT));
            assertEquals(ContainerInspection.healthy(ContainerStatus.STOPPED), backend.inspect("mc", TIMEOUT),
                    "chunked bodies are decoded");
            assertEquals(List.of(INSPECT, INSPECT), engine.requests(), "paths are unversioned");
        }
    }

    @Test
    public void inspectMapsStatusCodesToDiagnostics() throws IOException {
        FakeDockerEngine engine = unixEngine()
                .on(INSPECT, Reply.json(404, "{\"message\":\"No such container: mc\"}"))
                .on(INSPECT, Reply.json(403, "{\"message\":\"forbidden by allowlist\"}"))
                .on(INSPECT, Reply.json(401, "{}"))
                .on(INSPECT, Reply.json(500, "{\"message\":\"daemon exploded\"}"))
                .on(INSPECT, Reply.json(200, "{\"State\":{}}"))
                .on(INSPECT, Reply.json(200, "not json"));
        EngineApiDockerBackend backend = backend(engine);

        assertDiagnostic(ContainerStatus.MISSING, DockerDiagnostic.CONTAINER_MISSING, backend.inspect("mc", TIMEOUT));
        ContainerInspection denied = backend.inspect("mc", TIMEOUT);
        assertDiagnostic(ContainerStatus.INACCESSIBLE, DockerDiagnostic.PERMISSION_DENIED, denied);
        assertTrue(denied.remediation().contains("allowlist"));
        assertFalse(denied.detail().contains("forbidden by allowlist"), "raw bodies stay in operator logs");
        assertDiagnostic(ContainerStatus.INACCESSIBLE, DockerDiagnostic.PERMISSION_DENIED,
                backend.inspect("mc", TIMEOUT));
        ContainerInspection failed = backend.inspect("mc", TIMEOUT);
        assertDiagnostic(ContainerStatus.FAILED, DockerDiagnostic.INDETERMINATE, failed);
        assertFalse(failed.detail().contains("exploded"));
        assertDiagnostic(ContainerStatus.FAILED, DockerDiagnostic.INDETERMINATE, backend.inspect("mc", TIMEOUT));
        assertDiagnostic(ContainerStatus.FAILED, DockerDiagnostic.INDETERMINATE, backend.inspect("mc", TIMEOUT));
    }

    @Test
    public void healthComesFromTheSameInspectCall() throws IOException {
        FakeDockerEngine engine = tcpEngine()
                .on(INSPECT, Reply.json(200, inspectBody(true, "healthy", null)))
                .on(INSPECT, Reply.json(200, inspectBody(true, "starting", null)))
                .on(INSPECT, Reply.json(200, inspectBody(true, "unhealthy", null)))
                .on(INSPECT, Reply.json(200, inspectBody(true, null, null)))
                .on(INSPECT, Reply.json(200, inspectBody(false, "healthy", null)))
                .on(INSPECT, Reply.json(200, inspectBody(true, "sideways", null)))
                .on(INSPECT, Reply.json(404, "{}"))
                .on(INSPECT, Reply.json(403, "{}"))
                .on(INSPECT, Reply.json(503, "{}"));
        EngineApiDockerBackend backend = backend(engine);

        for (ContainerHealth expected : List.of(ContainerHealth.HEALTHY, ContainerHealth.STARTING,
                ContainerHealth.UNHEALTHY, ContainerHealth.NO_HEALTHCHECK, ContainerHealth.STOPPED,
                ContainerHealth.FAILED, ContainerHealth.MISSING, ContainerHealth.INACCESSIBLE,
                ContainerHealth.FAILED)) {
            assertEquals(expected, backend.health("mc", TIMEOUT));
        }
    }

    @Test
    public void startAndStopMapStatusCodes() throws IOException {
        FakeDockerEngine engine = unixEngine();
        for (int status : List.of(204, 304, 404, 403, 500)) {
            engine.on(START, Reply.empty(status)).on(STOP, Reply.empty(status));
        }
        EngineApiDockerBackend backend = backend(engine);

        for (ContainerStatus expected : List.of(ContainerStatus.RUNNING, ContainerStatus.RUNNING,
                ContainerStatus.MISSING, ContainerStatus.INACCESSIBLE, ContainerStatus.FAILED)) {
            assertEquals(expected, backend.start("mc", TIMEOUT));
        }
        for (ContainerStatus expected : List.of(ContainerStatus.STOPPED, ContainerStatus.STOPPED,
                ContainerStatus.MISSING, ContainerStatus.INACCESSIBLE, ContainerStatus.FAILED)) {
            assertEquals(expected, backend.stop("mc", TIMEOUT));
        }
    }

    @Test
    public void requestsAreWellFormedAndContainerNamesEncoded() throws IOException {
        FakeDockerEngine engine = unixEngine()
                .on("POST /containers/my%20mc%2F%C3%A9/stop", Reply.empty(204));
        EngineApiDockerBackend backend = backend(engine);

        assertEquals(ContainerStatus.STOPPED, backend.stop("my mc/é", TIMEOUT));

        String raw = engine.rawRequests().get(0);
        assertTrue(raw.startsWith("POST /containers/my%20mc%2F%C3%A9/stop HTTP/1.1\r\n"), raw);
        assertTrue(raw.contains("\r\nConnection: close\r\n"), raw);
        assertTrue(raw.contains("\r\nContent-Length: 0\r\n"), raw);
        assertFalse(raw.contains("?t="), "the daemon applies the container's own StopTimeout");
    }

    @Test
    public void stopTimeoutIsReadFromInspect() throws IOException {
        FakeDockerEngine engine = unixEngine()
                .on(INSPECT, Reply.json(200, inspectBody(true, null, 45L)))
                .on(INSPECT, Reply.json(200, inspectBody(true, null, null)))
                .on(INSPECT, Reply.json(404, "{}"));
        EngineApiDockerBackend backend = backend(engine);

        assertEquals(OptionalLong.of(45), backend.stopTimeoutSeconds("mc", TIMEOUT));
        assertEquals(OptionalLong.empty(), backend.stopTimeoutSeconds("mc", TIMEOUT));
        assertEquals(OptionalLong.empty(), backend.stopTimeoutSeconds("mc", TIMEOUT));
    }

    @Test
    public void oversizedResponsesAreRejected() throws IOException {
        String large = "{\"pad\":\"" + "x".repeat(4096) + "\"}";
        FakeDockerEngine engine = unixEngine()
                .on(INSPECT, Reply.json(200, large))
                .on(INSPECT, Reply.chunked(200, large, 512));
        EngineApiDockerBackend backend = new EngineApiDockerBackend(logger,
                new EngineApiTransport(HostResolver.system(), 1024), fixed(engine.endpoint()));

        assertDiagnostic(ContainerStatus.FAILED, DockerDiagnostic.INDETERMINATE, backend.inspect("mc", TIMEOUT));
        assertDiagnostic(ContainerStatus.FAILED, DockerDiagnostic.INDETERMINATE, backend.inspect("mc", TIMEOUT));
    }

    @Test
    public void stalledResponseIsCutAtTheDeadline() throws Exception {
        FakeDockerEngine engine = tcpEngine()
                .on(INSPECT, Reply.stall("HTTP/1.1 200 OK\r\nContent-Length: 500\r\n\r\n{\"State\":"));
        EngineApiDockerBackend backend = backend(engine);

        long started = System.nanoTime();
        ContainerInspection result = backend.inspect("mc", Duration.ofMillis(300));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertDiagnostic(ContainerStatus.TIMED_OUT, DockerDiagnostic.TIMED_OUT, result);
        assertTrue(elapsedMillis >= 250 && elapsedMillis < 3_000, "elapsed " + elapsedMillis + "ms");
        assertTrue(engine.awaitClientDisconnect(Duration.ofSeconds(5)), "the channel is closed");
    }

    @Test
    public void interruptAbortsTheRequestAndKeepsTheFlag() throws Exception {
        FakeDockerEngine engine = unixEngine().on(STOP, Reply.stall(""));
        EngineApiDockerBackend backend = backend(engine);
        AtomicReference<ContainerStatus> result = new AtomicReference<>();
        AtomicBoolean interruptedAfter = new AtomicBoolean();

        Thread worker = new Thread(() -> {
            result.set(backend.stop("mc", Duration.ofMinutes(5)));
            interruptedAfter.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        assertTrue(engine.awaitRequest(Duration.ofSeconds(5)));
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive(), "interrupt must not wait for the stop deadline");
        assertEquals(ContainerStatus.TIMED_OUT, result.get());
        assertTrue(interruptedAfter.get(), "the interrupt flag is preserved");
        assertTrue(engine.awaitClientDisconnect(Duration.ofSeconds(5)), "the channel is closed");
    }

    @Test
    public void alreadyInterruptedThreadSendsNothing() throws IOException {
        FakeDockerEngine engine = unixEngine();
        EngineApiDockerBackend backend = backend(engine);

        Thread.currentThread().interrupt();
        try {
            assertEquals(ContainerStatus.TIMED_OUT, backend.start("mc", TIMEOUT));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(List.of(), engine.requests());
    }

    @Test
    public void connectionRefusedAndMissingSocketMeanDaemonUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            closedPort = socket.getLocalPort();
        }
        EngineApiDockerBackend refused = backend(DockerEndpoint.tcp("127.0.0.1", closedPort));
        EngineApiDockerBackend missing = backend(DockerEndpoint.unix(
                tempDir.resolve("absent.sock").toString().replace('\\', '/')));

        assertDiagnostic(ContainerStatus.INACCESSIBLE, DockerDiagnostic.DAEMON_UNAVAILABLE,
                refused.inspect("mc", TIMEOUT));
        assertDiagnostic(ContainerStatus.INACCESSIBLE, DockerDiagnostic.DAEMON_UNAVAILABLE,
                missing.inspect("mc", TIMEOUT));
        assertEquals(ContainerHealth.INACCESSIBLE, missing.health("mc", TIMEOUT));
        assertEquals(ContainerStatus.INACCESSIBLE, missing.start("mc", TIMEOUT));
        assertEquals(ContainerStatus.INACCESSIBLE, missing.stop("mc", TIMEOUT));
    }

    @Test
    public void unresolvedHostMeansDaemonUnavailable() {
        EngineApiDockerBackend backend = new EngineApiDockerBackend(logger,
                new EngineApiTransport((host, timeout) -> {
                    throw new UnknownHostException(host);
                }), fixed(DockerEndpoint.tcp("autostopper-docker", 2375)));

        assertDiagnostic(ContainerStatus.INACCESSIBLE, DockerDiagnostic.DAEMON_UNAVAILABLE,
                backend.inspect("mc", TIMEOUT));
    }

    @Test
    public void connectFailuresAreClassified() {
        DockerEndpoint endpoint = DockerEndpoint.unix("/var/run/docker.sock");

        assertEquals(EngineApiException.Kind.PERMISSION_DENIED, EngineApiTransport.classify(endpoint,
                new SocketException("Permission denied"), false).kind());
        assertEquals(EngineApiException.Kind.UNAVAILABLE, EngineApiTransport.classify(endpoint,
                new SocketException("No such file or directory"), false).kind());
        assertEquals(EngineApiException.Kind.UNAVAILABLE, EngineApiTransport.classify(endpoint,
                new ConnectException("Connection refused"), false).kind());
        assertEquals(EngineApiException.Kind.PROTOCOL, EngineApiTransport.classify(endpoint,
                new SocketException("Connection reset"), true).kind());
    }

    @Test
    public void longStopWithinTheGracePeriodSucceeds() throws IOException {
        FakeDockerEngine engine = unixEngine()
                .on(INSPECT, Reply.json(200, inspectBody(true, null, 2L)))
                .on(STOP, Reply.empty(204).after(Duration.ofMillis(1_000)));
        DockerManager manager = new DockerManager(logger, backend(engine), Duration.ofMillis(300));

        assertEquals(ContainerStatus.STOPPED, manager.stopContainer("mc"),
                "the stop deadline is the grace period plus the command timeout, not the command timeout");
        assertEquals(List.of(INSPECT, STOP), engine.requests());
    }

    @Test
    public void stopPastTheDeadlineIsReinspected() throws IOException {
        FakeDockerEngine engine = unixEngine()
                .on(INSPECT, Reply.json(200, inspectBody(true, null, 0L)))
                .on(STOP, Reply.stall(""))
                .on(INSPECT, Reply.json(200, inspectBody(false, null, 0L)))
                .on(INSPECT, Reply.json(200, inspectBody(true, null, 0L)))
                .on(STOP, Reply.stall(""))
                .on(INSPECT, Reply.json(200, inspectBody(true, null, 0L)));
        DockerManager manager = new DockerManager(logger, backend(engine), Duration.ofMillis(300));

        assertEquals(ContainerStatus.STOPPED, manager.stopContainer("mc"));
        assertEquals(ContainerStatus.TIMED_OUT, manager.stopContainer("mc"));
    }

    @Test
    public void startChecksStatusFirst() throws IOException {
        FakeDockerEngine engine = tcpEngine()
                .on(INSPECT, Reply.json(200, inspectBody(false, null, null)))
                .on(START, Reply.empty(204))
                .on(INSPECT, Reply.json(200, inspectBody(true, null, null)));
        DockerManager manager = new DockerManager(logger, backend(engine), TIMEOUT);

        assertEquals(ContainerStatus.RUNNING, manager.startContainer("mc"));
        assertEquals(ContainerStatus.RUNNING, manager.startContainer("mc"));
        assertEquals(List.of(INSPECT, START, INSPECT), engine.requests());
    }

    @Test
    public void describesTheResolvedEndpoint() throws IOException {
        FakeDockerEngine engine = unixEngine().on(INSPECT, Reply.json(200, inspectBody(true, null, null)));
        EngineApiDockerBackend backend = backend(engine);

        backend.inspect("mc", TIMEOUT);

        assertEquals("Engine API, endpoint " + engine.endpoint() + " (test)", backend.describe());
    }

    private FakeDockerEngine unixEngine() throws IOException {
        FakeDockerEngine engine = FakeDockerEngine.unix(tempDir);
        engines.add(engine);
        return engine;
    }

    private FakeDockerEngine tcpEngine() throws IOException {
        FakeDockerEngine engine = FakeDockerEngine.tcp();
        engines.add(engine);
        return engine;
    }

    private EngineApiDockerBackend backend(FakeDockerEngine engine) {
        return backend(engine.endpoint());
    }

    private EngineApiDockerBackend backend(DockerEndpoint endpoint) {
        return new EngineApiDockerBackend(logger, new EngineApiTransport(HostResolver.system()), fixed(endpoint));
    }

    private static EngineApiDockerBackend.EndpointSource fixed(DockerEndpoint endpoint) {
        return new EngineApiDockerBackend.EndpointSource() {
            @Override
            public DockerEndpoint resolve(long deadlineNanos) {
                return endpoint;
            }

            @Override
            public String describe(DockerEndpoint resolved) {
                return "endpoint " + endpoint + " (test)";
            }
        };
    }

    private static void assertDiagnostic(ContainerStatus status, DockerDiagnostic diagnostic,
            ContainerInspection inspection) {
        assertEquals(status, inspection.status(), inspection.toString());
        assertEquals(diagnostic, inspection.diagnostic(), inspection.toString());
    }
}
