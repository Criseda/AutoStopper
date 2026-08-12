package me.criseda.autostopper.config;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record ReadinessSettings(
        ReadinessStrategy strategy,
        String targetHost,
        Integer targetPort,
        Duration probeInterval,
        Duration timeout,
        Duration connectTimeout,
        Duration readTimeout) {

    public static final int DEFAULT_PROBE_INTERVAL_MILLIS = 1_000;
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 1_000;
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 1_000;

    public ReadinessSettings {
        Objects.requireNonNull(strategy, "strategy");
        requirePositive(probeInterval, "probeInterval");
        requirePositive(timeout, "timeout");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        if ((targetHost == null) != (targetPort == null)) {
            throw new IllegalArgumentException("targetHost and targetPort must either both be set or both be absent");
        }
        if (targetHost != null && targetHost.isBlank()) {
            throw new IllegalArgumentException("targetHost must not be blank");
        }
        if (targetPort != null && (targetPort < 1 || targetPort > 65_535)) {
            throw new IllegalArgumentException("targetPort must be between 1 and 65535");
        }
    }

    public static ReadinessSettings defaults() {
        return new ReadinessSettings(
                ReadinessStrategy.MINECRAFT_STATUS,
                null,
                null,
                Duration.ofMillis(DEFAULT_PROBE_INTERVAL_MILLIS),
                Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
                Duration.ofMillis(DEFAULT_CONNECT_TIMEOUT_MILLIS),
                Duration.ofMillis(DEFAULT_READ_TIMEOUT_MILLIS));
    }

    public Optional<Target> explicitTarget() {
        return targetHost == null ? Optional.empty() : Optional.of(new Target(targetHost, targetPort));
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public record Target(String host, int port) {
        public Target {
            Objects.requireNonNull(host, "host");
            if (host.isBlank()) {
                throw new IllegalArgumentException("host must not be blank");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
        }
    }
}
