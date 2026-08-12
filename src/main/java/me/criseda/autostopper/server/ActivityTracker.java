package me.criseda.autostopper.server;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import me.criseda.autostopper.AutoStopperPlugin;
import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.executor.AutoStopperExecutor;

import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ActivityTracker {
    private final ProxyServer server;
    private final Logger logger;
    private final AutoStopperConfig config;
    private final ServerManager serverManager;
    private final AutoStopperExecutor executor;
    private final Map<String, Instant> lastActivity = new HashMap<>();
    private final AutoStopperPlugin plugin;
    private final AtomicBoolean inactivityScanActive = new AtomicBoolean(false);

    public ActivityTracker(ProxyServer server, Logger logger, AutoStopperConfig config, ServerManager serverManager,
            AutoStopperExecutor executor, AutoStopperPlugin plugin) {
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.serverManager = serverManager;
        this.executor = executor;
        this.plugin = plugin;
        initializeActivityTracking();
    }

    private void initializeActivityTracking() {
        // Initialize all monitored servers with current time
        for (String serverName : config.getServerNames()) {
            lastActivity.put(serverName, Instant.now());
            logger.info("Initialized activity tracking for server: " + serverName);
        }

        // Log the initial state
        logger.info("Initial server activity state:");
        for (Map.Entry<String, Instant> entry : lastActivity.entrySet()) {
            logger.info("- " + entry.getKey() + ": " + entry.getValue());
        }
    }

    public void startInactivityCheck() {
        server.getScheduler().buildTask(plugin, this::requestInactivityCheck)
                .repeat(1, TimeUnit.MINUTES)
                .schedule();
    }

    CompletableFuture<Void> requestInactivityCheck() {
        if (!inactivityScanActive.compareAndSet(false, true)) {
            logger.debug("Skipping inactivity check because the previous scan is still running");
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> scan = executor.supply(() -> {
            runInactivityCheck();
            return null;
        });
        scan.whenComplete((ignored, error) -> {
            inactivityScanActive.set(false);
            if (error != null) {
                logger.warn("Inactivity check could not run: {}", error.toString());
            }
        });
        return scan;
    }

    private void runInactivityCheck() {
        logger.debug("Running inactivity check...");
        for (String serverName : config.getServerNames()) {
            server.getServer(serverName).ifPresent(registeredServer -> evaluateServer(serverName, registeredServer));
        }
    }

    private void evaluateServer(String serverName, RegisteredServer registeredServer) {
        // If players are connected, update the timestamp
        if (!registeredServer.getPlayersConnected().isEmpty()) {
            updateActivity(serverName);
            logger.debug("Players active on " + serverName + ", refreshing timestamp");
            return;
        }

        // If no players are connected, check if the server is actually running
        Optional<ContainerStatus> status = serverManager.getServerStatus(serverName);
        if (status.isEmpty()) {
            removeActivity(serverName);
            return;
        }

        switch (status.get()) {
            case STOPPED, MISSING, INACCESSIBLE, TIMED_OUT, FAILED:
                if (status.get().isIndeterminate()) {
                    logger.warn("Server {} status is {}; skipping inactivity shutdown",
                            serverName, status.get());
                }
                removeActivity(serverName);
                return;
            case RUNNING:
                break;
        }

        // If it is running but not being tracked, start tracking it now
        if (!lastActivity.containsKey(serverName)) {
            lastActivity.put(serverName, Instant.now());
        }

        // Otherwise check if the server has been inactive for too long
        Instant lastActive = lastActivity.getOrDefault(serverName, Instant.now());
        Duration inactiveDuration = Duration.between(lastActive, Instant.now());
        long minutesInactive = inactiveDuration.toMinutes();

        logger.debug(serverName + " has been inactive for " + minutesInactive + " minutes");

        if (inactiveDuration.getSeconds() > config.getInactivityTimeout()) {
            logger.info("Server " + serverName + " has been inactive for " + minutesInactive +
                    " minutes, shutting down");
            serverManager.stopServer(serverName);
            removeActivity(serverName);
        }
    }

    public void updateActivity(String serverName) {
        if (serverManager.isMonitoredServer(serverName)) {
            lastActivity.put(serverName, Instant.now());
        }
    }

    public void removeActivity(String serverName) {
        lastActivity.remove(serverName);
    }

    public Instant getLastActivity(String serverName) {
        return lastActivity.get(serverName);
    }

    public long getMinutesSinceActivity(String serverName) {
        Instant lastActive = lastActivity.get(serverName);
        if (lastActive == null) {
            return 0;
        }

        return Duration.between(lastActive, Instant.now()).toMinutes();
    }

    public Map<String, Instant> getAllActivity() {
        return new HashMap<>(lastActivity);
    }
}
