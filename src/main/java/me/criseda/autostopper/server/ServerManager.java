package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.docker.DockerManager;

import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerManager {
    private final ProxyServer server;
    private final Logger logger;
    private final AutoStopperConfig config;
    private final DockerManager dockerManager;

    private final Map<String, AtomicBoolean> serverStartingStatus = new ConcurrentHashMap<>();

    public ServerManager(ProxyServer server, Logger logger, AutoStopperConfig config, DockerManager dockerManager) {
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.dockerManager = dockerManager;
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
}