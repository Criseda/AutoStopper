package me.criseda.autostopper.operational;

import java.util.Optional;

public record OperationalServerStatus(OperationalState state, int waitingPlayers,
        Optional<OperationalFailure> lastFailure) {
}
