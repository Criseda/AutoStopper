package me.criseda.autostopper.messages;

import me.criseda.autostopper.lifecycle.ManualRestartOutcome;
import me.criseda.autostopper.lifecycle.ManualStartOutcome;
import me.criseda.autostopper.lifecycle.ManualStopOutcome;

import java.util.Locale;
import java.util.Objects;

/**
 * Operator-facing cause, remediation, and next step for each failed manual lifecycle command.
 * Constant names match the typed {@code outcome=} codes in lifecycle telemetry log lines, and each
 * constant has a matching section in {@code docs/troubleshooting.md}. Text here is sanitized and
 * must never contain raw Docker stderr.
 */
public enum OperatorFailureGuide {
    SERVER_STARTING("A start is already in progress for this server.",
            "Wait for the start to finish, then retry.", NextStep.RETRY),
    SERVER_STOPPING("A stop is already in progress for this server.",
            "Wait for the stop to finish, then retry.", NextStep.RETRY),
    PLAYERS_CONNECTED("Players are connected to this server.",
            "Move players to another server or wait for them to leave.", NextStep.NONE),
    WAITERS_PRESENT("Players are waiting for this server to become ready.",
            "Let the pending connections finish, then retry.", NextStep.RETRY),
    MAPPING_CHANGED("A configuration reload changed or removed this server's mapping during the operation.",
            "Check the mapping in config.yml, then retry.", NextStep.RETRY),
    CONTAINER_MISSING("The configured container does not exist.",
            "Create the container or correct container_name, then reload.", NextStep.STATUS),
    DOCKER_INACCESSIBLE("The proxy cannot reach the Docker daemon.",
            "Restore Docker daemon and socket access, then retry.", NextStep.STATUS),
    STATUS_TIMED_OUT("The Docker status check did not respond in time.",
            "Check Docker daemon responsiveness and host load, then retry.", NextStep.RETRY),
    STATUS_FAILED("The Docker status check failed.",
            "Review the proxy log and Docker state, then retry.", NextStep.STATUS),
    START_TIMED_OUT("Docker did not start the container in time.",
            "Check Docker daemon responsiveness and host load, then retry.", NextStep.RETRY),
    START_FAILED("Docker could not start the container.",
            "Review the proxy log and Docker state, then retry.", NextStep.STATUS),
    STOP_TIMED_OUT("Docker did not stop the container in time.",
            "Check the container's stop grace period and host load, then retry.", NextStep.RETRY),
    STOP_FAILED("Docker could not stop the container.",
            "Review the proxy log and Docker state, then retry.", NextStep.STATUS),
    SERVER_NOT_READY("The container started but did not pass its readiness check.",
            "Verify the readiness strategy and backend endpoint, then retry.", NextStep.STATUS),
    OVERLOADED("AutoStopper's worker queue is full.",
            "Wait for current operations to finish, then retry.", NextStep.RETRY),
    CANCELLED("A reload, shutdown, or conflicting operation cancelled this one.",
            "Retry after the reload or conflicting operation completes.", NextStep.RETRY);

    /** The follow-up command an operator is offered after a failure. */
    public enum NextStep {
        /** Run the same command again. */
        RETRY,
        /** Inspect {@code /autostopper status} before retrying. */
        STATUS,
        /** No command helps; the remediation is outside AutoStopper. */
        NONE
    }

    private final String cause;
    private final String remediation;
    private final NextStep nextStep;

    OperatorFailureGuide(String cause, String remediation, NextStep nextStep) {
        this.cause = cause;
        this.remediation = remediation;
        this.nextStep = nextStep;
    }

    public String cause() {
        return cause;
    }

    public String remediation() {
        return remediation;
    }

    public NextStep nextStep() {
        return nextStep;
    }

    /** The typed outcome code, as logged in {@code velocity.log}. */
    public String outcomeCode() {
        return name();
    }

    /** The section anchor in {@code docs/troubleshooting.md}. */
    public String troubleshootingAnchor() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static OperatorFailureGuide of(ManualStartOutcome outcome) {
        return failure(Objects.requireNonNull(outcome, "outcome").name(), outcome.isSuccessful());
    }

    public static OperatorFailureGuide of(ManualStopOutcome outcome) {
        return failure(Objects.requireNonNull(outcome, "outcome").name(), outcome.isSuccessful());
    }

    public static OperatorFailureGuide of(ManualRestartOutcome outcome) {
        return failure(Objects.requireNonNull(outcome, "outcome").name(), outcome.isSuccessful());
    }

    private static OperatorFailureGuide failure(String outcomeName, boolean successful) {
        if (successful) {
            throw new IllegalArgumentException(outcomeName + " is not a failure");
        }
        return valueOf(outcomeName);
    }
}
