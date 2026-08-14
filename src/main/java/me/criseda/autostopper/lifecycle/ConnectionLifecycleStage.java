package me.criseda.autostopper.lifecycle;

/**
 * User-facing stages of one managed backend connection request.
 *
 * <p>The startup stages are authoritative for the shared lifecycle operation. Terminal stages are
 * recorded per waiter because Velocity can return a different connection result for each player.
 */
public enum ConnectionLifecycleStage {
    INSPECTING,
    STARTING,
    WAITING_FOR_READINESS,
    CONNECTING,
    SUCCEEDED,
    FAILED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
