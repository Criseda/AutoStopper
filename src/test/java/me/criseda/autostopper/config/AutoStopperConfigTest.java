package me.criseda.autostopper.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@ExtendWith(MockitoExtension.class)
public class AutoStopperConfigTest {

    @TempDir
    Path tempDir;

    @Mock
    private Logger logger;

    private final Set<String> knownServers = ConcurrentHashMap.newKeySet();
    private AutoStopperConfig config;
    private Path configFile;

    @BeforeEach
    public void setup() {
        knownServers.addAll(List.of("test-server", "server1", "server2", "sérver"));
        config = new AutoStopperConfig(tempDir, logger, knownServers::contains);
        configFile = tempDir.resolve("config.yml");
    }

    @Test
    public void missingFileCreatesUniversallyValidEmptyDefault() throws IOException {
        ConfigLoadResult result = config.loadConfig();

        assertTrue(result.successful());
        assertEquals(ConfigSnapshot.DEFAULT_INACTIVITY_TIMEOUT_SECONDS,
                result.snapshot().inactivityTimeoutSeconds());
        assertEquals(ConfigSnapshot.DEFAULT_SHUTDOWN_TIMEOUT_SECONDS,
                result.snapshot().shutdownTimeoutSeconds());
        assertTrue(result.snapshot().servers().isEmpty());
        assertTrue(Files.readString(configFile, StandardCharsets.UTF_8).contains("monitored_servers: []"));
        verify(logger).info(contains("Creating default configuration"));
    }

    @Test
    public void validYamlPublishesOrderedImmutableSnapshot() throws IOException {
        writeConfig("""
                inactivity_timeout_seconds: 600
                monitored_servers:
                  - server_name: server1
                    container_name: container1
                  - server_name: server2
                    container_name: container2
                """);

        ConfigLoadResult result = config.loadConfig();

        assertTrue(result.successful());
        assertEquals(600, result.snapshot().inactivityTimeoutSeconds());
        assertEquals(StopRetrySettings.defaults(), result.snapshot().stopRetry());
        assertEquals(List.of("server1", "server2"), result.snapshot().serverNames());
        assertEquals("container1", result.snapshot().serverToContainer().get("server1"));
        assertSame(result.snapshot(), config.snapshot());
        assertThrows(UnsupportedOperationException.class,
                () -> result.snapshot().servers().add(new ServerMapping("x", "y")));
        assertThrows(UnsupportedOperationException.class,
                () -> result.snapshot().serverToContainer().put("x", "y"));
    }

    @Test
    public void stopRetryPolicyIsValidatedAndPublishedAtomically() throws IOException {
        writeConfig("""
                inactivity_timeout_seconds: 600
                stop_retry:
                  max_attempts: 4
                  initial_backoff_seconds: 15
                  max_backoff_seconds: 45
                monitored_servers: []
                """);

        ConfigLoadResult result = config.loadConfig();

        assertTrue(result.successful());
        assertEquals(4, result.snapshot().stopRetry().maxAttempts());
        assertEquals(15, result.snapshot().stopRetry().initialBackoff().toSeconds());
        assertEquals(45, result.snapshot().stopRetry().maxBackoff().toSeconds());
    }

    @Test
    public void shutdownDeadlineIsValidatedAndPublishedAtomically() throws IOException {
        writeConfig("""
                shutdown_timeout_seconds: 7
                monitored_servers: []
                """);

        ConfigLoadResult result = config.loadConfig();

        assertTrue(result.successful());
        assertEquals(7, result.snapshot().shutdownTimeoutSeconds());

        ConfigSnapshot previous = config.snapshot();
        writeConfig("""
                shutdown_timeout_seconds: 0
                monitored_servers: []
                """);
        ConfigLoadResult rejected = config.loadConfig();
        assertFalse(rejected.successful());
        assertSame(previous, config.snapshot());
        assertTrue(rejected.errorSummary().contains("shutdown_timeout_seconds"));
    }

    @Test
    public void invalidStopRetryPolicyRetainsPreviousSnapshot() throws IOException {
        loadInitialSnapshot();
        ConfigSnapshot previous = config.snapshot();
        writeConfig("""
                stop_retry:
                  max_attempts: 0
                  initial_backoff_seconds: 30
                  max_backoff_seconds: 10
                monitored_servers: []
                """);

        ConfigLoadResult result = config.loadConfig();

        assertFalse(result.successful());
        assertSame(previous, config.snapshot());
        assertTrue(result.errorSummary().contains("stop_retry.max_attempts"));
        assertTrue(result.errorSummary().contains("stop_retry.max_backoff_seconds"));
    }

    @Test
    public void readinessPolicyIsValidatedAndCapturedPerServer() throws IOException {
        writeConfig("""
                inactivity_timeout_seconds: 600
                monitored_servers:
                  - server_name: server1
                    container_name: container1
                    readiness:
                      strategy: docker_health_or_status
                      target_host: minecraft.internal
                      target_port: 25566
                      probe_interval_millis: 250
                      timeout_seconds: 45
                      connect_timeout_millis: 500
                      read_timeout_millis: 750
                """);

        ConfigLoadResult result = config.loadConfig();

        assertTrue(result.successful());
        ReadinessSettings readiness = result.snapshot().server("server1").orElseThrow().readiness();
        assertEquals(ReadinessStrategy.DOCKER_HEALTH_OR_STATUS, readiness.strategy());
        assertEquals(new ReadinessSettings.Target("minecraft.internal", 25566),
                readiness.explicitTarget().orElseThrow());
        assertEquals(250, readiness.probeInterval().toMillis());
        assertEquals(45, readiness.timeout().toSeconds());
        assertEquals(500, readiness.connectTimeout().toMillis());
        assertEquals(750, readiness.readTimeout().toMillis());
    }

    @Test
    public void invalidReadinessPolicyIsRejectedAtomicallyWithPrecisePaths() throws IOException {
        loadInitialSnapshot();
        ConfigSnapshot previous = config.snapshot();
        writeConfig("""
                monitored_servers:
                  - server_name: server1
                    container_name: container1
                    readiness:
                      strategy: logs
                      target_host: localhost
                      target_port: 70000
                      probe_interval_millis: 0
                      timeout_seconds: -1
                      connect_timeout_millis: fast
                      read_timeout_millis: 0
                """);

        ConfigLoadResult result = config.loadConfig();

        assertFalse(result.successful());
        assertSame(previous, config.snapshot());
        String errors = result.errorSummary();
        assertTrue(errors.contains("monitored_servers[0].readiness.strategy"));
        assertTrue(errors.contains("monitored_servers[0].readiness.target_port"));
        assertTrue(errors.contains("target_host and target_port must be configured together"));
        assertTrue(errors.contains("monitored_servers[0].readiness.probe_interval_millis"));
        assertTrue(errors.contains("monitored_servers[0].readiness.timeout_seconds"));
        assertTrue(errors.contains("monitored_servers[0].readiness.connect_timeout_millis"));
        assertTrue(errors.contains("monitored_servers[0].readiness.read_timeout_millis"));
    }

    @Test
    public void malformedYamlFailsGracefullyAndRetainsPreviousSnapshot() throws IOException {
        loadInitialSnapshot();
        ConfigSnapshot previous = config.snapshot();
        writeConfig("monitored_servers: [unterminated\n");

        ConfigLoadResult result = assertDoesNotThrow(config::loadConfig);

        assertFalse(result.successful());
        assertSame(previous, config.snapshot());
        assertTrue(result.errorSummary().contains("invalid YAML"));
    }

    @Test
    public void emptyNonMapAndIncorrectTypesAreRejectedWithoutPartialPublication() throws IOException {
        loadInitialSnapshot();
        ConfigSnapshot previous = config.snapshot();
        List<String> invalidDocuments = List.of(
                "",
                "- not\n- a\n- map\n",
                "inactivity_timeout_seconds: '600'\nmonitored_servers: []\n",
                "inactivity_timeout_seconds: null\nmonitored_servers: []\n",
                "inactivity_timeout_seconds: 600\nmonitored_servers: {}\n",
                "inactivity_timeout_seconds: 600\nmonitored_servers: null\n",
                "inactivity_timeout_seconds: 600\nmonitored_servers:\n  - not-a-map\n",
                "inactivity_timeout_seconds: 600\nmonitored_servers:\n"
                        + "  - server_name: 42\n    container_name: container1\n");

        for (String document : invalidDocuments) {
            writeConfig(document);
            ConfigLoadResult result = assertDoesNotThrow(config::loadConfig);
            assertFalse(result.successful(), "document should be rejected: " + document);
            assertSame(previous, config.snapshot());
            assertFalse(result.errors().isEmpty());
        }
    }

    @Test
    public void validationReportsPathsForUnknownDuplicatesBlanksAndNonPositiveTimeout() throws IOException {
        writeConfig("""
                inactivity_timeout_seconds: 0
                monitored_servers:
                  - server_name: server1
                    container_name: shared
                  - server_name: server1
                    container_name: shared
                  - server_name: missing-server
                    container_name: other
                  - server_name: " "
                    container_name: ""
                """);

        ConfigLoadResult result = config.loadConfig();

        assertFalse(result.successful());
        String errors = result.errorSummary();
        assertTrue(errors.contains("inactivity_timeout_seconds"));
        assertTrue(errors.contains("monitored_servers[1].server_name: duplicate"));
        assertTrue(errors.contains("monitored_servers[1].container_name: duplicate"));
        assertTrue(errors.contains("monitored_servers[2].server_name: unknown Velocity server"));
        assertTrue(errors.contains("monitored_servers[3].server_name: must not be blank"));
        assertTrue(errors.contains("monitored_servers[3].container_name: must not be blank"));
    }

    @Test
    public void duplicateYamlKeysAreRejected() throws IOException {
        writeConfig("""
                inactivity_timeout_seconds: 300
                inactivity_timeout_seconds: 600
                monitored_servers: []
                """);

        ConfigLoadResult result = config.loadConfig();

        assertFalse(result.successful());
        assertTrue(result.errorSummary().contains("invalid YAML"));
    }

    @Test
    public void utf8NamesAreLoadedWithoutPlatformEncodingDependence() throws IOException {
        writeConfig("""
                inactivity_timeout_seconds: 300
                monitored_servers:
                  - server_name: sérver
                    container_name: contêneur
                """);

        ConfigLoadResult result = config.loadConfig();

        assertTrue(result.successful());
        assertEquals("contêneur", result.snapshot().serverToContainer().get("sérver"));
    }

    @Test
    public void concurrentReadersObserveOnlyWholeOldOrNewSnapshots() throws Exception {
        writeConfig(configDocument(100, "server1", "container1"));
        assertTrue(config.loadConfig().successful());

        int readerCount = 4;
        ExecutorService readers = Executors.newFixedThreadPool(readerCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<java.util.concurrent.Future<?>> readerTasks = new ArrayList<>();
        for (int i = 0; i < readerCount; i++) {
            readerTasks.add(readers.submit(() -> {
                try {
                    start.await();
                    while (running.get()) {
                        assertSnapshotInvariant(config.snapshot());
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }));
        }

        try {
            start.countDown();
            for (int i = 0; i < 100; i++) {
                boolean first = (i & 1) == 0;
                writeConfig(first
                        ? configDocument(100, "server1", "container1")
                        : configDocument(200, "server2", "container2"));
                assertTrue(config.loadConfig().successful());
            }
        } finally {
            running.set(false);
            readers.shutdownNow();
        }
        for (java.util.concurrent.Future<?> readerTask : readerTasks) {
            readerTask.get(2, TimeUnit.SECONDS);
        }

        assertTrue(failures.isEmpty(), () -> "reader observed a partial snapshot: " + failures);
    }

    @Test
    public void serverMappingIsAnImmutableValue() {
        ServerMapping mapping = new ServerMapping("test-server", "test-container");
        assertEquals("test-server", mapping.getServerName());
        assertEquals("test-container", mapping.getContainerName());
    }

    private void loadInitialSnapshot() throws IOException {
        writeConfig(configDocument(100, "server1", "container1"));
        assertTrue(config.loadConfig().successful());
    }

    private void assertSnapshotInvariant(ConfigSnapshot snapshot) {
        if (snapshot.inactivityTimeoutSeconds() == 100) {
            assertEquals(List.of("server1"), snapshot.serverNames());
            assertEquals("container1", snapshot.serverToContainer().get("server1"));
        } else if (snapshot.inactivityTimeoutSeconds() == 200) {
            assertEquals(List.of("server2"), snapshot.serverNames());
            assertEquals("container2", snapshot.serverToContainer().get("server2"));
        } else {
            fail("unexpected timeout from partial snapshot: " + snapshot.inactivityTimeoutSeconds());
        }
    }

    private String configDocument(int timeout, String server, String container) {
        return "inactivity_timeout_seconds: " + timeout + "\n"
                + "monitored_servers:\n"
                + "  - server_name: " + server + "\n"
                + "    container_name: " + container + "\n";
    }

    private void writeConfig(String contents) throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(configFile, contents, StandardCharsets.UTF_8);
    }
}
