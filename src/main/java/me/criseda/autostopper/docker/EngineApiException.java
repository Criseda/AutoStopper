package me.criseda.autostopper.docker;

import java.util.Objects;

/** A classified Docker Engine API transport failure. The message is for operator logs only. */
final class EngineApiException extends Exception {
    private static final long serialVersionUID = 1L;

    enum Kind {
        /** The socket or endpoint refused access to this process. */
        PERMISSION_DENIED,
        /** Nothing is listening: missing socket, refused connection or unknown host. */
        UNAVAILABLE,
        /** The request deadline expired. */
        TIMED_OUT,
        /** The calling thread was interrupted; its interrupt flag is preserved. */
        INTERRUPTED,
        /** The connection broke or the response was malformed or too large. */
        PROTOCOL
    }

    private final Kind kind;

    EngineApiException(Kind kind, String message) {
        this(kind, message, null);
    }

    EngineApiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    Kind kind() {
        return kind;
    }
}
