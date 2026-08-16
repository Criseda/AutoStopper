package me.criseda.autostopper.config;

/**
 * Service contract for accessing and loading plugin configuration snapshots.
 */
public interface ConfigProvider {

    /**
     * Returns the currently active, immutable configuration snapshot.
     */
    ConfigSnapshot snapshot();

    /**
     * Loads the configuration from disk, validating values and atomically updating the active snapshot.
     */
    ConfigLoadResult loadConfig();
}
