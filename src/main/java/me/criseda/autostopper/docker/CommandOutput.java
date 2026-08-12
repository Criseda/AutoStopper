package me.criseda.autostopper.docker;

public record CommandOutput(Outcome outcome, int exitCode, String stdout, String stderr) {

    public enum Outcome {
        COMPLETED,
        TIMED_OUT,
        SPAWN_FAILED
    }
}