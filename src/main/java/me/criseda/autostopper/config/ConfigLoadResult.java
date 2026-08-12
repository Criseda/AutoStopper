package me.criseda.autostopper.config;

import java.util.List;
import java.util.Objects;

public record ConfigLoadResult(boolean successful, ConfigSnapshot snapshot, List<String> errors) {
    public ConfigLoadResult {
        Objects.requireNonNull(snapshot, "snapshot");
        errors = List.copyOf(errors);
        if (successful && !errors.isEmpty()) {
            throw new IllegalArgumentException("a successful result cannot contain errors");
        }
        if (!successful && errors.isEmpty()) {
            throw new IllegalArgumentException("a failed result must contain an error");
        }
    }

    public static ConfigLoadResult success(ConfigSnapshot snapshot) {
        return new ConfigLoadResult(true, snapshot, List.of());
    }

    public static ConfigLoadResult failure(ConfigSnapshot retainedSnapshot, List<String> errors) {
        return new ConfigLoadResult(false, retainedSnapshot, errors);
    }

    public String errorSummary() {
        return String.join("; ", errors);
    }
}
