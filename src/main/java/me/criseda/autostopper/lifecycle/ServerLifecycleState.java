package me.criseda.autostopper.lifecycle;

public enum ServerLifecycleState {
    STOPPED,
    STARTING,
    READY,
    STOPPING,
    FAILED;

    boolean canTransitionTo(ServerLifecycleState next) {
        return switch (this) {
            case STOPPED -> next == STARTING || next == READY || next == STOPPING;
            case STARTING -> next == READY || next == FAILED;
            case READY -> next == STOPPING || next == FAILED || next == STOPPED;
            case STOPPING -> next == STOPPED || next == FAILED || next == READY;
            case FAILED -> next == STARTING || next == READY || next == STOPPING || next == STOPPED;
        };
    }
}
