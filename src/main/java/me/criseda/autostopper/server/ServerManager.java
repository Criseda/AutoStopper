package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.docker.DockerManager;
import me.criseda.autostopper.executor.AutoStopperExecutor;

import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerManager {
    private final ProxyServer server;
    private final Logger logger;
    private final AutoStopperConfig config;
    private final DockerManager dockerManager;
    private final AutoStopperExecutor executor;

    private final Map<String, AtomicBoolean> serverStartingStatus = new ConcurrentHashMap<>();

    public ServerManager(ProxyServer server, Logger logger, AutoStopperConfig config, DockerManager dockerManager,
            AutoStopperExecutor executor) {
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.dockerManager = dockerManager;
        this.executor = executor;
    }

    public Optional<ContainerStatus> getServerStatus(String serverName) {
        String containerName = getContainerName(serverName);
        if (containerName == null) {
            logger.warn("No container mapped for server: {}", serverName);
            return Optional.empty();
        }
        return Optional.of(dockerManager.getContainerStatus(containerName));
    }

    public ContainerStatus startServer(String serverName) {
        String containerName = getContainerName(serverName);
        if (containerName == null) {
            logger.warn("No container mapped for server: {}", serverName);
            return ContainerStatus.MISSING;
        }
        return dockerManager.startContainer(containerName);
    }

    public ContainerStatus stopServer(String serverName) {
        String containerName = getContainerName(serverName);
        if (containerName == null) {
            logger.warn("No container mapped for server: {}", serverName);
            return ContainerStatus.MISSING;
        }
        ContainerStatus result = dockerManager.stopContainer(containerName);
        logger.info("Stopped server: {} (container: {}, result: {})",
                serverName, containerName, result);
        return result;
    }

    public boolean waitForServerReady(String serverName, int timeoutSeconds) {
        String containerName = getContainerName(serverName);
        if (containerName == null) {
            logger.warn("No container mapped for server: {}", serverName);
            return false;
        }
        return dockerManager.waitForContainerReady(
                containerName,
                timeoutSeconds,
                "Done (",
                "] Done (",
                "For help, type \"help\"");
    }

    public boolean isMonitoredServer(String serverName) {
        for (String s : config.getServerNames()) {
            if (s.equals(serverName))
                return true;
        }
        return false;
    }

    public String getContainerName(String serverName) {
        Map<String, String> mapping = config.getServerToContainerMap();
        return mapping.get(serverName);
    }

    public Optional<RegisteredServer> getServer(String name) {
        return server.getServer(name);
    }

    public AtomicBoolean getServerStartingStatus(String serverName) {
        return serverStartingStatus.computeIfAbsent(serverName, k -> new AtomicBoolean(false));
    }

    public CompletableFuture<Optional<ContainerStatus>> getServerStatusAsync(String serverName) {
        return executor.supply(() -> getServerStatus(serverName));
    }

    public CompletableFuture<ContainerStatus> startServerAsync(String serverName) {
        return executor.supply(() -> startServer(serverName));
    }

    public CompletableFuture<Boolean> waitForServerReadyAsync(String serverName, int timeoutSeconds) {
        return executor.supply(() -> waitForServerReady(serverName, timeoutSeconds));
    }

    public CompletableFuture<Map<String, Optional<ContainerStatus>>> getStatusesAsync(List<String> serverNames) {
        @SuppressWarnings("unchecked")
        CompletableFuture<Optional<ContainerStatus>>[] futures = serverNames.stream()
                .map(this::getServerStatusAsync)
                .toArray(CompletableFuture[]::new);

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
