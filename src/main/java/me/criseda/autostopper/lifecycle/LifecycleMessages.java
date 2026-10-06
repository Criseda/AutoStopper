package me.criseda.autostopper.lifecycle;

import me.criseda.autostopper.messages.AutoStopperMessages;
import me.criseda.autostopper.readiness.ReadinessResult;
import net.kyori.adventure.text.Component;

/**
 * Chooses the player-facing message for each lifecycle stage and outcome.
 */
final class LifecycleMessages {
    private LifecycleMessages() {
    }

    static Component stage(ConnectionLifecycleStage stage, String serverName) {
        return switch (stage) {
            case INSPECTING -> AutoStopperMessages.lifecycleInspecting(serverName);
            case STARTING -> AutoStopperMessages.lifecycleStarting(serverName);
            case WAITING_FOR_READINESS -> AutoStopperMessages.lifecycleWaitingForReadiness(serverName);
            case CONNECTING -> AutoStopperMessages.lifecycleConnecting(serverName);
            case SUCCEEDED, FAILED -> throw new IllegalArgumentException("Terminal stage requires an outcome message");
        };
    }

    static Component rejected(String serverName, ConnectionOutcome outcome) {
        if (outcome == ConnectionOutcome.SERVER_STOPPING) {
            return AutoStopperMessages.serverStopping(serverName);
        }
        return AutoStopperMessages.mappingChanged(serverName);
    }

    static Component startupFailure(String serverName, StartupOutcome outcome,
            ReadinessResult readinessFailure) {
        return switch (outcome) {
            case STATUS_NO_MAPPING -> AutoStopperMessages.noContainerMapping(serverName);
            case STATUS_MISSING, START_MISSING -> AutoStopperMessages.containerMissing(serverName);
            case STATUS_INACCESSIBLE -> AutoStopperMessages.dockerUnavailable("manage", serverName);
            case START_INACCESSIBLE -> AutoStopperMessages.dockerUnavailable("start", serverName);
            case STATUS_TIMED_OUT -> AutoStopperMessages.statusCheckTimedOut(serverName);
            case STATUS_FAILED -> AutoStopperMessages.statusCheckFailed(serverName);
            case STATUS_ERROR -> AutoStopperMessages.statusCheckError(serverName);
            case START_TIMED_OUT -> AutoStopperMessages.startTimedOut(serverName);
            case START_FAILED -> AutoStopperMessages.startFailed(serverName);
            case START_ERROR -> AutoStopperMessages.startError(serverName);
            case NOT_READY, READINESS_ERROR -> readinessFailure == null
                    ? AutoStopperMessages.serverNotReady(serverName)
                    : AutoStopperMessages.serverNotReady(serverName, readinessFailure.playerDetail());
            case CANCELLED -> AutoStopperMessages.startCancelled(serverName);
            case OVERLOADED -> AutoStopperMessages.overloaded();
            case READY_RUNNING, READY_AFTER_START -> throw new IllegalArgumentException("ready outcome is not a failure");
            case PROXY_SHUTDOWN -> throw new IllegalArgumentException("shutdown outcome is not a startup failure");
        };
    }

    static Component connectionFailure(String serverName, ConnectionOutcome outcome) {
        return switch (outcome) {
            case CONNECTION_IN_PROGRESS -> AutoStopperMessages.connectionInProgress(serverName);
            case CONNECTION_CANCELLED -> AutoStopperMessages.connectionCancelled(serverName);
            case SERVER_DISCONNECTED -> AutoStopperMessages.connectionRefused(serverName);
            default -> AutoStopperMessages.connectionFailed(serverName);
        };
    }
}
