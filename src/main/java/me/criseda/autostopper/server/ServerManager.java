package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ReadinessSettings;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.docker.ContainerInspection;
import me.criseda.autostopper.docker.DockerManager;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.readiness.ReadinessResult;
import me.criseda.autostopper.readiness.ServerReadinessChecker;
import me.criseda.autostopper.readiness.SocketMinecraftStatusProbe;

import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ServerManager {
    private final ProxyServer server;
    private final Logger logger;
    private final AutoStopperConfig config;
    private final DockerManager dockerManager;
    private final AutoStopperExecutor executor;
    private final ServerReadinessChecker readinessChecker;

    public ServerManager(ProxyServer server, Logger logger, AutoStopperConfig config, DockerManager dockerManager,
            AutoStopperExecutor executor) {
        this(server, logger, config, dockerManager, executor,
                new ServerReadinessChecker(logger, dockerManager, new SocketMinecraftStatusProbe()));
    }

    ServerManager(ProxyServer server, Logger logger, AutoStopperConfig config, DockerManager dockerManager,
            AutoStopperExecutor executor, ServerReadinessChecker readinessChecker) {
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.dockerManager = dockerManager;
        this.executor = executor;
        this.readinessChecker = readinessChecker;
    }

    public Optional<ContainerStatus> getServerStatus(String serverName) {
        Optional<ServerMapping> mapping = getServerMapping(serverName);
        if (mapping.isEmpty()) {
            logger.warn("No container mapped for server: {}", serverName);
            return Optional.empty();
        }
        return getServerStatus(mapping.get());
    }

    public Optional<ContainerStatus> getServerStatus(ServerMapping mapping) {
        return Optional.of(dockerManager.getContainerStatus(mapping.containerName()));
    }

    public ContainerInspection inspectContainer(ServerMapping mapping) {
        return dockerManager.inspectContainer(mapping.containerName());
    }

    public ContainerStatus startServer(String serverName) {
        Optional<ServerMapping> mapping = getServerMapping(serverName);
        if (mapping.isEmpty()) {
            logger.warn("No container mapped for server: {}", serverName);
            return ContainerStatus.MISSING;
        }
        return startServer(mapping.get());
    }

    public ContainerStatus startServer(ServerMapping mapping) {
        return dockerManager.startContainer(mapping.containerName());
    }

    /** How Docker is reached, for operator output. Never blocks. */
    public String dockerAccess() {
        return dockerManager.describeAccess();
    }

    public ContainerStatus stopServer(String serverName) {
        Optional<ServerMapping> mapping = getServerMapping(serverName);
        if (mapping.isEmpty()) {
            logger.warn("No container mapped for server: {}", serverName);
            return ContainerStatus.MISSING;
        }
        return stopServer(mapping.get());
    }

    public ContainerStatus stopServer(ServerMapping mapping) {
        ContainerStatus result = dockerManager.stopContainer(mapping.containerName());
        if (result == ContainerStatus.STOPPED) {
            logger.info("Stopped server: {} (container: {})",
                    mapping.serverName(), mapping.containerName());
        } else {
            logger.warn("Could not stop server: {} (container: {}, result: {})",
                    mapping.serverName(), mapping.containerName(), result);
        }
        return result;
    }

    public boolean isMonitoredServer(String serverName) {
        return config.snapshot().containsServer(serverName);
    }

    public Optional<ServerMapping> getServerMapping(String serverName) {
        return config.snapshot().server(serverName);
    }

    public String getContainerName(String serverName) {
        return getServerMapping(serverName).map(ServerMapping::containerName).orElse(null);
    }

    public Optional<RegisteredServer> getServer(String name) {
        return server.getServer(name);
    }

    public CompletableFuture<Optional<ContainerStatus>> getServerStatusAsync(String serverName) {
        return executor.supply(() -> getServerStatus(serverName));
    }

    public CompletableFuture<Optional<ContainerStatus>> getServerStatusAsync(ServerMapping mapping) {
        return executor.supply(() -> getServerStatus(mapping));
    }

    public CompletableFuture<ContainerInspection> inspectContainerAsync(ServerMapping mapping) {
        return executor.supply(() -> inspectContainer(mapping));
    }

    public CompletableFuture<ContainerStatus> startServerAsync(String serverName) {
        return executor.supply(() -> startServer(serverName));
    }

    public CompletableFuture<ContainerStatus> startServerAsync(ServerMapping mapping) {
        return executor.supply(() -> startServer(mapping));
    }

    public CompletableFuture<ReadinessResult> waitForServerReadyAsync(String serverName) {
        Optional<ServerMapping> mapping = getServerMapping(serverName);
        if (mapping.isEmpty()) {
            logger.warn("No container mapped for server: {}", serverName);
            return CompletableFuture.completedFuture(
                    ReadinessResult.failure(ReadinessResult.Outcome.CONTAINER_MISSING, 0, null));
        }
        return waitForServerReadyAsync(mapping.get());
    }

    /**
     * Each readiness attempt runs as a short worker task; the probe interval between attempts holds no worker, so
     * concurrent startups do not starve status checks, stops, or other startups.
     */
    public CompletableFuture<ReadinessResult> waitForServerReadyAsync(ServerMapping mapping) {
        try {
            return readinessChecker.awaitReady(
                    mapping, resolveReadinessTarget(mapping).orElse(null), executor::supplyAfter);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<Map<String, Optional<ContainerStatus>>> getStatusesAsync(ConfigSnapshot snapshot) {
        List<ServerMapping> mappings = snapshot.servers();
        return byServerName(mappings.stream().map(ServerMapping::serverName).toList(),
                executor.supplyAll(mappings, this::getServerStatus));
    }

    /**
     * Inspects every mapped container with at most one inspect per worker in flight, so the batch never fills the
     * worker queue, however many servers are mapped.
     */
    public CompletableFuture<Map<String, ContainerInspection>> inspectContainersAsync(ConfigSnapshot snapshot) {
        List<ServerMapping> mappings = snapshot.servers();
        return byServerName(mappings.stream().map(ServerMapping::serverName).toList(),
                executor.supplyAll(mappings, this::inspectContainer));
    }

    public CompletableFuture<Map<String, Optional<ContainerStatus>>> getStatusesAsync(List<String> serverNames) {
        return byServerName(serverNames, executor.supplyAll(serverNames, this::getServerStatus));
    }

    /** Keys the batch results by server name; cancelling the returned future cancels the batch. */
    private static <V> CompletableFuture<Map<String, V>> byServerName(List<String> serverNames,
            CompletableFuture<List<V>> batch) {
        CompletableFuture<Map<String, V>> result = batch.thenApply(values -> {
            Map<String, V> byName = new LinkedHashMap<>();
            for (int i = 0; i < serverNames.size(); i++) {
                byName.put(serverNames.get(i), values.get(i));
            }
            return byName;
        });
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) {
                batch.cancel(true);
            }
        });
        return result;
    }

    private Optional<ReadinessSettings.Target> resolveReadinessTarget(ServerMapping mapping) {
        if (!mapping.readiness().strategy().usesMinecraftStatus()) {
            return Optional.empty();
        }
        Optional<ReadinessSettings.Target> configured = mapping.readiness().explicitTarget();
        if (configured.isPresent()) {
            return configured;
        }
        return getServer(mapping.serverName())
                .map(registered -> registered.getServerInfo().getAddress())
                .map(address -> new ReadinessSettings.Target(
                        address.getHostString(), address.getPort()));
    }
}
