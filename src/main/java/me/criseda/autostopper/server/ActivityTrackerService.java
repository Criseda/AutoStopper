package me.criseda.autostopper.server;

import me.criseda.autostopper.config.ConfigSnapshot;

import java.time.Instant;

/**
 * Service contract for tracking server player activity and inactivity lifecycle evaluation.
 */
public interface ActivityTrackerService {

    /**
     * Starts the periodic background inactivity scanning task.
     */
    void startInactivityCheck();

    /**
     * Cancels scheduled tasks and active scans, cleanly shutting down activity tracking.
     */
    void shutdown();

    /**
     * Records player activity for the specified monitored server at the current time.
     */
    void updateActivity(String serverName);

    /**
     * Synchronizes tracked activity states when configuration reload modifies monitored servers.
     */
    void reconcileConfig(ConfigSnapshot previous, ConfigSnapshot current);

    /**
     * Clears the tracked activity entry for the specified server.
     */
    void removeActivity(String serverName);

    /**
     * Returns the instant of last observed activity for the specified server, or null if untracked.
     */
    Instant getLastActivity(String serverName);

    /**
     * Returns the whole elapsed minutes since last observed activity, or 0 if untracked.
     */
    long getMinutesSinceActivity(String serverName);
}
