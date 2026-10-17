package me.criseda.autostopper.operational;

import me.criseda.autostopper.lifecycle.ConnectionLifecycleStage;

import java.util.Optional;

/** One server's composed status. {@code stage} is present only while the state is {@code STARTING}. */
public record OperationalServerStatus(OperationalState state, int waitingPlayers,
        Optional<OperationalFailure> lastFailure, boolean held, Optional<ConnectionLifecycleStage> stage) {

    public OperationalServerStatus {
        lastFailure = lastFailure == null ? Optional.empty() : lastFailure;
        stage = stage == null || state != OperationalState.STARTING ? Optional.empty() : stage;
    }

    public OperationalServerStatus(OperationalState state, int waitingPlayers,
            Optional<OperationalFailure> lastFailure, boolean held) {
        this(state, waitingPlayers, lastFailure, held, Optional.empty());
    }

    public OperationalServerStatus(OperationalState state, int waitingPlayers,
            Optional<OperationalFailure> lastFailure) {
        this(state, waitingPlayers, lastFailure, false);
    }
}
