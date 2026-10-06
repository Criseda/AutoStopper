package me.criseda.autostopper.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Everything shutdown took from the lifecycle entries under their locks, for the caller to end
 * outside them: operations to cancel, startups and manual stops to complete with PROXY_SHUTDOWN,
 * stranded waiters to abandon, and interrupted startups whose telemetry is still owed.
 */
final class ShutdownDrain {
    private final List<CompletableFuture<?>> operations = new ArrayList<>();
    private final List<CompletableFuture<StartupOutcome>> startups = new ArrayList<>();
    private final List<CompletableFuture<ManualStopOutcome>> manualStops = new ArrayList<>();
    private final List<ConnectionWaiter> waiters = new ArrayList<>();
    private final List<InterruptedStartup> interruptedStartups = new ArrayList<>();

    void addOperation(CompletableFuture<?> operation) {
        operations.add(operation);
    }

    void addStartup(CompletableFuture<StartupOutcome> startup) {
        startups.add(startup);
    }

    void addManualStop(CompletableFuture<ManualStopOutcome> manualStop) {
        manualStops.add(manualStop);
    }

    void addWaiter(ConnectionWaiter waiter) {
        waiters.add(waiter);
    }

    void addInterruptedStartup(String serverName, long startNanos) {
        interruptedStartups.add(new InterruptedStartup(serverName, startNanos));
    }

    List<CompletableFuture<?>> operations() {
        return operations;
    }

    List<CompletableFuture<StartupOutcome>> startups() {
        return startups;
    }

    List<CompletableFuture<ManualStopOutcome>> manualStops() {
        return manualStops;
    }

    List<ConnectionWaiter> waiters() {
        return waiters;
    }

    List<InterruptedStartup> interruptedStartups() {
        return interruptedStartups;
    }

    /** A startup shutdown interrupted before it recorded its telemetry. */
    record InterruptedStartup(String serverName, long startNanos) {
    }
}
