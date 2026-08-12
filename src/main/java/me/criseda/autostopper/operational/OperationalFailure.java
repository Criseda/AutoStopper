package me.criseda.autostopper.operational;

import java.time.Instant;
import java.util.Objects;

/** A bounded, safe diagnostic. It must never contain raw Docker stderr. */
public record OperationalFailure(Instant timestamp, String context, String detail, String remediation) {
    public OperationalFailure {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(remediation, "remediation");
    }
}
