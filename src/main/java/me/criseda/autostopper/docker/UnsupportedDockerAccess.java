package me.criseda.autostopper.docker;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Stands in for a backend when the environment points Docker somewhere AutoStopper cannot reach,
 * for example an {@code ssh://} {@code DOCKER_HOST}. Every operation fails as inaccessible without
 * contacting any daemon, so containers on another Docker host are never managed by mistake.
 */
final class UnsupportedDockerAccess implements DockerBackend {
    static final String REMEDIATION = "Set docker.endpoint (or DOCKER_HOST) to a unix:// or plain tcp:// "
            + "address, or use the socket-proxy sidecar.";

    private final String reason;

    UnsupportedDockerAccess(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    String reason() {
        return reason;
    }

    @Override
    public ContainerInspection inspect(String containerName, Duration timeout) {
        return DockerManager.diagnostic(ContainerStatus.INACCESSIBLE, DockerDiagnostic.DAEMON_UNAVAILABLE,
                "Docker endpoint is not supported: " + reason, REMEDIATION);
    }

    @Override
    public ContainerHealth health(String containerName, Duration timeout) {
        return ContainerHealth.INACCESSIBLE;
    }

    @Override
    public ContainerStatus start(String containerName, Duration timeout) {
        return ContainerStatus.INACCESSIBLE;
    }

    @Override
    public OptionalLong stopTimeoutSeconds(String containerName, Duration timeout) {
        return OptionalLong.empty();
    }

    @Override
    public ContainerStatus stop(String containerName, Duration deadline) {
        return ContainerStatus.INACCESSIBLE;
    }

    @Override
    public String describe() {
        return "unsupported (" + reason + ")";
    }
}
