package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.docker.DockerManager;
import me.criseda.autostopper.executor.AutoStopperExecutor;

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

    public ServerManager(ProxyServer server, Logger logger, AutoStopperConfig config, DockerManager dockerManager,
            AutoStopperExecutor executor) {
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.dockerManager = dockerManager;
        this.executor = executor;
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
        logger.info("Stopped server: {} (container: {}, result: {})",
                mapping.serverName(), mapping.containerName(), result);
        return result;
    }

    public boolean waitForServerReady(String serverName, int timeoutSeconds) {
        Optional<ServerMapping> mapping = getServerMapping(serverName);
        if (mapping.isEmpty()) {
            logger.warn("No container mapped for server: {}", serverName);
            return false;
        }
        return waitForServerReady(mapping.get(), timeoutSeconds);
    }

    public boolean waitForServerReady(ServerMapping mapping, int timeoutSeconds) {
        return dockerManager.waitForContainerReady(
                mapping.containerName(),
                timeoutSeconds,
                "Done (",
                "] Done (",
                "For help, type \"help\"");
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

    public CompletableFuture<ContainerStatus> startServerAsync(String serverName) {
        return executor.supply(() -> startServer(serverName));
    }

    public CompletableFuture<ContainerStatus> startServerAsync(ServerMapping mapping) {
        return executor.supply(() -> startServer(mapping));
    }

    public CompletableFuture<Boolean> waitForServerReadyAsync(String serverName, int timeoutSeconds) {
        return executor.supply(() -> waitForServerReady(serverName, timeoutSeconds));
    }

    public CompletableFuture<Boolean> waitForServerReadyAsync(ServerMapping mapping, int timeoutSeconds) {
        return executor.supply(() -> waitForServerReady(mapping, timeoutSeconds));
    }

    public CompletableFuture<Map<String, Optional<ContainerStatus>>> getStatusesAsync(ConfigSnapshot snapshot) {
        List<ServerMapping> mappings = snapshot.servers();
        @SuppressWarnings("unchecked")
        CompletableFuture<Optional<ContainerStatus>>[] futures = mappings.stream()
                .map(this::getServerStatusAsync)
                .toArray(CompletableFuture[]::new);
        return collectStatuses(mappings.stream().map(ServerMapping::serverName).toList(), futures);
    }

    public CompletableFuture<Map<String, Optional<ContainerStatus>>> getStatusesAsync(List<String> serverNames) {
        @SuppressWarnings("unchecked")
        CompletableFuture<Optional<ContainerStatus>>[] futures = serverNames.stream()
                .map(this::getServerStatusAsync)
                .toArray(CompletableFuture[]::new);

        return collectStatuses(serverNames, futures);
    }

    private CompletableFuture<Map<String, Optional<ContainerStatus>>> collectStatuses(List<String> serverNames,
            CompletableFuture<Optional<ContainerStatus>>[] futures) {
        CompletableFuture<Map<String, Optional<ContainerStatus>>> result = new CompletableFuture<>();
        CompletableFuture.allOf(futures).whenComplete((ignored, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            Map<String, Optional<ContainerStatus>> statuses = new LinkedHashMap<>();
            for (int i = 0; i < serverNames.size(); i++) {
                statuses.put(serverNames.get(i), futures[i].join());
            }
            result.complete(statuses);
        });

        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) {
                for (CompletableFuture<?> future : futures) {
                    future.cancel(true);
                }
            }
        });
        return result;
    }
}
