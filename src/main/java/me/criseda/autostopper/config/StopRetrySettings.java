package me.criseda.autostopper.config;

import java.time.Duration;

public record StopRetrySettings(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final int DEFAULT_INITIAL_BACKOFF_SECONDS = 60;
    public static final int DEFAULT_MAX_BACKOFF_SECONDS = 300;

    public StopRetrySettings {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (initialBackoff.isZero() || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff must be positive");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be less than initialBackoff");
        }
    }

    public static StopRetrySettings defaults() {
        return new StopRetrySettings(
                DEFAULT_MAX_ATTEMPTS,
                Duration.ofSeconds(DEFAULT_INITIAL_BACKOFF_SECONDS),
                Duration.ofSeconds(DEFAULT_MAX_BACKOFF_SECONDS));
    }

    public Duration backoffAfterFailure(int failedAttempt) {
        long multiplier = 1L << Math.min(Math.max(failedAttempt - 1, 0), 62);
        long initialSeconds = initialBackoff.getSeconds();
        long seconds = multiplier > Long.MAX_VALUE / initialSeconds
                ? Long.MAX_VALUE
                : initialSeconds * multiplier;
        return Duration.ofSeconds(Math.min(seconds, maxBackoff.getSeconds()));
    }
}
