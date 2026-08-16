package me.criseda.autostopper.lifecycle;

import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.config.ServerMapping;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for runtime holds on mapped servers.
 * A hold suppresses automatic inactivity shutdown while preserving normal player-driven
 * wake-up and manual lifecycle commands. Holds survive configuration reloads when the
 * mapping is unchanged, but are cleared on proxy shutdown, mapping removal, or replacement.
 */
public final class ServerHoldRegistry {
    private final Map<String, ServerMapping> holds = new ConcurrentHashMap<>();

    public boolean hold(ServerMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        return holds.putIfAbsent(mapping.serverName(), mapping) == null;
    }

    public boolean release(String serverName) {
        Objects.requireNonNull(serverName, "serverName");
        return holds.remove(serverName) != null;
    }

    public boolean isHeld(String serverName) {
        if (serverName == null) {
            return false;
        }
        return holds.containsKey(serverName);
    }

    public Set<String> heldServers() {
        return Collections.unmodifiableSet(holds.keySet());
    }

    public void reconcileConfig(ConfigSnapshot previous, ConfigSnapshot current) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        holds.entrySet().removeIf(entry -> {
            String serverName = entry.getKey();
            ServerMapping heldMapping = entry.getValue();
            Optional<ServerMapping> currentMapping = current.server(serverName);
            Optional<ServerMapping> previousMapping = previous.server(serverName);
            // Must exist in both and match exactly to survive reload
            return previousMapping.isEmpty()
                    || currentMapping.isEmpty()
                    || !heldMapping.equals(previousMapping.get())
                    || !heldMapping.equals(currentMapping.get());
        });
    }

    public void clear() {
        holds.clear();
    }
}
