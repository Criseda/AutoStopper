package me.criseda.autostopper.lifecycle;

import me.criseda.autostopper.operational.OperationalFailure;

import java.util.Optional;

/**
 * A coherent mapping-specific lifecycle view used by operational diagnostics. {@code stage} is present
 * only while the server is {@link ServerLifecycleState#STARTING}.
 */
public record LifecycleStatusSnapshot(Optional<ServerLifecycleState> state, int waitingPlayers,
        Optional<OperationalFailure> lastFailure, long revision, Optional<ConnectionLifecycleStage> stage) {
    public LifecycleStatusSnapshot {
        state = state == null ? Optional.empty() : state;
        lastFailure = lastFailure == null ? Optional.empty() : lastFailure;
        stage = stage == null ? Optional.empty() : stage;
        if (waitingPlayers < 0) {
            throw new IllegalArgumentException("waitingPlayers must not be negative");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }

    public LifecycleStatusSnapshot(Optional<ServerLifecycleState> state, int waitingPlayers,
            Optional<OperationalFailure> lastFailure, long revision) {
        this(state, waitingPlayers, lastFailure, revision, Optional.empty());
    }

    public static LifecycleStatusSnapshot absent() {
        return new LifecycleStatusSnapshot(Optional.empty(), 0, Optional.empty(), 0);
    }
}
