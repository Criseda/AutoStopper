package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import me.criseda.autostopper.config.AutoStopperConfig;
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

    public ServerManager(ProxyServer server, Logger logger, AutoStopperConfig config) {
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.dockerManager = new DockerManager(logger);
    }

    public boolean isServerRunning(String serverName) {
        String containerName = getContainerName(serverName);
        return dockerManager.isContainerRunning(containerName);
    }

    public boolean startServer(String serverName) {
        String containerName = getContainerName(serverName);
        return dockerManager.startContainer(containerName);
    }

    public boolean stopServer(String serverName) {
        String containerName = getContainerName(serverName);
        boolean result = dockerManager.stopContainer(containerName);
        logger.info("Stopped server: " + serverName + " (container: " + containerName + ")");
        return result;
    }

    public boolean waitForServerReady(String serverName, int timeoutSeconds) {
        String containerName = getContainerName(serverName);
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
        return mapping.getOrDefault(serverName, serverName);
    }

    public Optional<RegisteredServer> getServer(String name) {
        return server.getServer(name);
    }

    public AtomicBoolean getServerStartingStatus(String serverName) {
        return serverStartingStatus.computeIfAbsent(serverName, k -> new AtomicBoolean(false));
    }
}