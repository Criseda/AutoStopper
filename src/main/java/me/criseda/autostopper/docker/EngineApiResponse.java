package me.criseda.autostopper.docker;

import java.util.Objects;

/** A complete Docker Engine API response. The body is raw daemon output for operator logs and parsing only. */
record EngineApiResponse(int status, String body) {
    EngineApiResponse {
        Objects.requireNonNull(body, "body");
    }
}
