package me.criseda.autostopper.lifecycle;

import me.criseda.autostopper.operational.OperationalFailure;

import java.util.Optional;

/** A coherent mapping-specific lifecycle view used by operational diagnostics. */
public record LifecycleStatusSnapshot(Optional<ServerLifecycleState> state, int waitingPlayers,
        Optional<OperationalFailure> lastFailure, long revision) {
    public LifecycleStatusSnapshot {
        state = state == null ? Optional.empty() : state;
        lastFailure = lastFailure == null ? Optional.empty() : lastFailure;
        if (waitingPlayers < 0) {
            throw new IllegalArgumentException("waitingPlayers must not be negative");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }

    public static LifecycleStatusSnapshot absent() {
        return new LifecycleStatusSnapshot(Optional.empty(), 0, Optional.empty(), 0);
    }
}
