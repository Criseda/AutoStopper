package me.criseda.autostopper.operational;

import java.util.Optional;

public record OperationalServerStatus(OperationalState state, int waitingPlayers,
        Optional<OperationalFailure> lastFailure, boolean held) {

    public OperationalServerStatus(OperationalState state, int waitingPlayers,
            Optional<OperationalFailure> lastFailure) {
        this(state, waitingPlayers, lastFailure, false);
    }
}
