package me.criseda.autostopper.lifecycle;

import com.velocitypowered.api.proxy.Player;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.server.ServerManager;
import me.criseda.autostopper.telemetry.LifecycleTelemetry;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The lifecycle map, reconnect permits, and shutdown flag shared by
 * {@link ServerLifecycleCoordinator} and its collaborators, plus the dependencies they all use.
 *
 * <p>Locking: admission and shutdown hold the shutdown lock, then the map's per-key lock, then
 * the entry monitor, then (briefly) a waiter monitor. Asynchronous callbacks take only the entry
 * monitor. Every method here that hands out an entry does so with its monitor held.
 */
final class LifecycleRuntime {
    final Logger logger;
    final ServerManager serverManager;
    final AutoStopperExecutor executor;
    final LifecycleTelemetry telemetry;
    private final LongSupplier nanoTime;
    private final Map<String, LifecycleEntry> lifecycles = new ConcurrentHashMap<>();
    private final Set<ReconnectPermit> reconnectPermits = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicLong lifecycleRevision = new AtomicLong();
    private final Object shutdownLock = new Object();

    LifecycleRuntime(Logger logger, ServerManager serverManager, AutoStopperExecutor executor,
            LongSupplier nanoTime, LifecycleTelemetry telemetry) {
        this.logger = logger;
        this.serverManager = serverManager;
        this.executor = executor;
        this.nanoTime = nanoTime;
        this.telemetry = telemetry;
    }

    // --- Lifecycle map ---

    /**
     * Runs {@code decide} against the entry for {@code mapping} with the shutdown, map, and entry
     * locks held. A missing entry is created, an idle entry for a replaced mapping is swapped for
     * a fresh one, and an idle retired entry is revived before {@code decide} sees it. The
     * decision is returned once every lock is released.
     */
    <A> A admit(ServerMapping mapping, A shutdownRejection, A mappingChangedRejection,
            Function<LifecycleEntry, A> decide) {
        AtomicReference<A> decision = new AtomicReference<>();
        synchronized (shutdownLock) {
            lifecycles.compute(mapping.serverName(), (serverName, current) -> {
                if (shutdown.get()) {
                    decision.set(shutdownRejection);
                    return current;
                }
                LifecycleEntry locked = current != null ? current : newEntry(mapping);
                synchronized (locked) {
                    LifecycleEntry entry = locked;
                    if (!entry.matches(mapping)) {
                        if (entry.isBusy()) {
                            decision.set(mappingChangedRejection);
                            return entry;
                        }
                        entry = newEntry(mapping);
                    } else if (entry.isDisposable()) {
                        entry.unretire();
                    }
                    decision.set(decide.apply(entry));
                    return entry;
                }
            });
        }
        return decision.get();
    }

    /**
     * Moves an idle server to STOPPING for an automatic inactivity stop. Unlike {@link #admit},
     * a retired entry is not revived.
     */
    boolean tryBeginStop(ServerMapping mapping) {
        AtomicBoolean admitted = new AtomicBoolean(false);
        synchronized (shutdownLock) {
            lifecycles.compute(mapping.serverName(), (ignored, current) -> {
                if (shutdown.get()) {
                    return current;
                }
                LifecycleEntry locked = current != null ? current : newEntry(mapping);
                synchronized (locked) {
                    LifecycleEntry entry = locked;
                    if (!entry.matches(mapping)) {
                        if (entry.isBusy()) {
                            return entry;
                        }
                        entry = newEntry(mapping);
                    }
                    if (!entry.isBusy()) {
                        entry.beginStop();
                        admitted.set(true);
                    }
                    return entry;
                }
            });
        }
        return admitted.get();
    }

    /** Reads an entry under its monitor, or returns {@code absent} when there is none. */
    <T> T read(String serverName, T absent, Function<LifecycleEntry, T> reader) {
        LifecycleEntry entry = lifecycles.get(serverName);
        if (entry == null) {
            return absent;
        }
        synchronized (entry) {
            return reader.apply(entry);
        }
    }

    /**
     * Updates an existing entry under the map and entry locks. The entry is dropped from the map
     * when {@code update} returns {@code false}.
     */
    void update(String serverName, Predicate<LifecycleEntry> update) {
        lifecycles.computeIfPresent(serverName, (ignored, entry) -> {
            synchronized (entry) {
                return update.test(entry) ? entry : null;
            }
        });
    }

    /** Applies {@link #update} to every entry present when the call starts. */
    void updateAll(Predicate<LifecycleEntry> update) {
        for (String serverName : List.copyOf(lifecycles.keySet())) {
            update(serverName, update);
        }
    }

    /** Drops {@code expected} if it is retired and idle and still the mapped entry. */
    void cleanupRetired(String serverName, LifecycleEntry expected) {
        lifecycles.computeIfPresent(serverName, (ignored, current) -> {
            if (current != expected) {
                return current;
            }
            synchronized (current) {
                return current.isDisposable() ? null : current;
            }
        });
    }

    Optional<LifecycleStatusSnapshot> markStoppedIfUnchanged(ServerMapping mapping, long expectedRevision) {
        if (shutdown.get()) {
            return Optional.empty();
        }
        AtomicReference<LifecycleStatusSnapshot> accepted = new AtomicReference<>();
        lifecycles.compute(mapping.serverName(), (ignored, entry) -> {
            if (entry == null) {
                if (expectedRevision == 0) {
                    accepted.set(LifecycleStatusSnapshot.absent());
                }
                return null;
            }
            synchronized (entry) {
                if (entry.isRetired() || !entry.matches(mapping)
                        || entry.revision() != expectedRevision || entry.isBusy()) {
                    return entry;
                }
                entry.markStoppedExternally();
                accepted.set(entry.snapshot());
                return entry;
            }
        });
        return Optional.ofNullable(accepted.get());
    }

    private LifecycleEntry newEntry(ServerMapping mapping) {
        return new LifecycleEntry(mapping, lifecycleRevision::incrementAndGet, logger);
    }

    // --- Reconnect permits ---

    /**
     * Allows the next pre-connect event for this player and server through without another
     * lifecycle admission, because AutoStopper itself issued the connection.
     */
    ReconnectPermit grantReconnect(UUID playerId, String serverName) {
        ReconnectPermit permit = new ReconnectPermit(playerId, serverName);
        reconnectPermits.add(permit);
        return permit;
    }

    void revokeReconnect(ReconnectPermit permit) {
        reconnectPermits.remove(permit);
    }

    boolean consumeReconnect(UUID playerId, String serverName) {
        if (shutdown.get()) {
            return false;
        }
        return reconnectPermits.remove(new ReconnectPermit(playerId, serverName));
    }

    void revokeReconnects(UUID playerId) {
        reconnectPermits.removeIf(permit -> permit.playerId().equals(playerId));
    }

    // --- Shutdown ---

    boolean isShutdown() {
        return shutdown.get();
    }

    /**
     * Marks the runtime shut down, hands every entry to {@code drain} under its monitor, and
     * forgets all entries and permits. Admission cannot interleave.
     *
     * @return {@code false} if shutdown had already happened
     */
    boolean shutdown(Consumer<LifecycleEntry> drain) {
        synchronized (shutdownLock) {
            if (!shutdown.compareAndSet(false, true)) {
                return false;
            }
            for (LifecycleEntry entry : lifecycles.values()) {
                synchronized (entry) {
                    drain.accept(entry);
                }
            }
            lifecycles.clear();
            reconnectPermits.clear();
            return true;
        }
    }

    // --- Shared helpers ---

    long now() {
        return nanoTime.getAsLong();
    }

    Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos));
    }

    Duration elapsed(ConnectionWaiter waiter) {
        return elapsedSince(waiter.startNanos);
    }

    OperationalFailure failure(String context, String detail, String remediation) {
        return new OperationalFailure(Instant.now(), context, detail, remediation);
    }

    void safeSend(Player player, Component message) {
        if (shutdown.get() || !isPlayerActive(player)) {
            return;
        }
        try {
            player.sendMessage(message);
        } catch (RuntimeException error) {
            logger.debug("Could not send lifecycle message to a player", error);
        }
    }

    boolean isPlayerActive(Player player) {
        try {
            return player.isActive();
        } catch (RuntimeException error) {
            logger.debug("Could not check whether a lifecycle waiter is active", error);
            return false;
        }
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Classifies an asynchronous failure: worker saturation, cancellation (including executor
     * shutdown), or anything else.
     */
    static <T> T classifyFailure(Throwable error, T overloaded, T cancelled, T failed) {
        Throwable cause = unwrap(error);
        if (cause instanceof AutoStopperExecutor.SaturationException) {
            return overloaded;
        }
        if (cause instanceof CancellationException || cause instanceof AutoStopperExecutor.ShutdownException) {
            return cancelled;
        }
        return failed;
    }

    record ReconnectPermit(UUID playerId, String serverName) {
    }
}
