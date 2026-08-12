package me.criseda.autostopper.readiness;

import java.time.Duration;

@FunctionalInterface
public interface MinecraftStatusProbe {
    ProbeResult probe(String host, int port, Duration connectTimeout, Duration readTimeout, Duration attemptTimeout);

    record ProbeResult(Outcome outcome) {
        public ProbeResult {
            if (outcome == null) {
                throw new NullPointerException("outcome");
            }
        }

        public boolean ready() {
            return outcome == Outcome.READY;
        }
    }

    enum Outcome {
        READY,
        UNREACHABLE,
        TIMED_OUT,
        INVALID_RESPONSE,
        FAILED
    }
}
