package me.criseda.autostopper.docker;

import java.util.Objects;

/**
 * A Docker status result with a safe diagnostic. Raw process output deliberately never leaves
 * {@link DockerManager}.
 */
public record ContainerInspection(ContainerStatus status, DockerDiagnostic diagnostic,
        String detail, String remediation) {

    public ContainerInspection {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(diagnostic, "diagnostic");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(remediation, "remediation");
    }

    public static ContainerInspection healthy(ContainerStatus status) {
        if (status != ContainerStatus.RUNNING && status != ContainerStatus.STOPPED) {
            throw new IllegalArgumentException("healthy inspection must be running or stopped");
        }
        return new ContainerInspection(status, DockerDiagnostic.HEALTHY,
                status == ContainerStatus.RUNNING ? "container is running" : "container is stopped", "");
    }

    public boolean healthy() {
        return diagnostic == DockerDiagnostic.HEALTHY;
    }
}
