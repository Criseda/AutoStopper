package me.criseda.autostopper.readiness;

import java.util.Objects;
import java.util.Optional;

public record ReadinessResult(
        Outcome outcome,
        int attempts,
        MinecraftStatusProbe.Outcome lastStatusProbe) {

    public ReadinessResult {
        Objects.requireNonNull(outcome, "outcome");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    public static ReadinessResult ready(int attempts) {
        return new ReadinessResult(Outcome.READY, attempts, null);
    }

    public static ReadinessResult failure(Outcome outcome, int attempts,
            MinecraftStatusProbe.Outcome lastStatusProbe) {
        return new ReadinessResult(outcome, attempts, lastStatusProbe);
    }

    public boolean ready() {
        return outcome == Outcome.READY;
    }

    public Optional<MinecraftStatusProbe.Outcome> lastStatusProbeOptional() {
        return Optional.ofNullable(lastStatusProbe);
    }

    public String playerDetail() {
        return switch (outcome) {
            case READY -> "The configured readiness check passed.";
            case TIMED_OUT -> timeoutDetail();
            case CONTAINER_STOPPED -> "The container stopped while the server was starting.";
            case CONTAINER_MISSING -> "The configured container could no longer be found.";
            case DOCKER_INACCESSIBLE -> "Docker became inaccessible during the readiness check.";
            case DOCKER_FAILED -> "Docker could not report container readiness.";
            case NO_HEALTHCHECK -> "The container has no Docker health check configured.";
            case INVALID_TARGET -> "The Minecraft readiness target is not configured correctly.";
            case INTERRUPTED -> "The readiness check was cancelled.";
        };
    }

    private String timeoutDetail() {
        if (lastStatusProbe == null || lastStatusProbe == MinecraftStatusProbe.Outcome.READY) {
            return "The configured readiness deadline expired.";
        }
        return switch (lastStatusProbe) {
            case UNREACHABLE -> "The configured Minecraft status target remained unreachable.";
            case TIMED_OUT -> "The configured Minecraft status target did not respond in time.";
            case INVALID_RESPONSE -> "The target did not return a valid Minecraft status response.";
            case FAILED -> "The Minecraft status probe failed.";
            case READY -> "The configured readiness deadline expired.";
        };
    }

    public enum Outcome {
        READY,
        TIMED_OUT,
        CONTAINER_STOPPED,
        CONTAINER_MISSING,
        DOCKER_INACCESSIBLE,
        DOCKER_FAILED,
        NO_HEALTHCHECK,
        INVALID_TARGET,
        INTERRUPTED
    }
}
