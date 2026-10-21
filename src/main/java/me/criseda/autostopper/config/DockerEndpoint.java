package me.criseda.autostopper.config;

import java.util.Objects;
import java.util.Optional;

/** A Docker Engine API address: a Unix socket path or a plain TCP host and port. */
public record DockerEndpoint(Kind kind, String socketPath, String host, int port) {
    public static final int DEFAULT_TCP_PORT = 2375;

    public enum Kind {
        UNIX,
        TCP
    }

    public DockerEndpoint {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.UNIX) {
            Objects.requireNonNull(socketPath, "socketPath");
        } else {
            Objects.requireNonNull(host, "host");
            if (port <= 0 || port > 65_535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
        }
    }

    public static DockerEndpoint unix(String socketPath) {
        return new DockerEndpoint(Kind.UNIX, socketPath, null, 0);
    }

    public static DockerEndpoint tcp(String host, int port) {
        return new DockerEndpoint(Kind.TCP, null, host, port);
    }

    /**
     * Parses {@code unix:///absolute/path} or {@code tcp://host[:port]}. The TCP port defaults to
     * {@value #DEFAULT_TCP_PORT}, like the Docker CLI without TLS. Other schemes are not supported.
     */
    public static Optional<DockerEndpoint> parse(String value) {
        if (value == null || value.isEmpty() || !value.strip().equals(value)) {
            return Optional.empty();
        }
        if (value.startsWith("unix://")) {
            String path = value.substring("unix://".length());
            if (!path.startsWith("/") || path.length() == 1 || path.chars().anyMatch(Character::isISOControl)) {
                return Optional.empty();
            }
            return Optional.of(unix(path));
        }
        if (value.startsWith("tcp://")) {
            return parseTcp(value.substring("tcp://".length()));
        }
        return Optional.empty();
    }

    private static Optional<DockerEndpoint> parseTcp(String authority) {
        if (authority.endsWith("/")) {
            authority = authority.substring(0, authority.length() - 1);
        }
        String host;
        String portText;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                return Optional.empty();
            }
            host = authority.substring(1, close);
            String rest = authority.substring(close + 1);
            if (!host.matches("[0-9A-Fa-f:.]+") || !(rest.isEmpty() || rest.startsWith(":"))) {
                return Optional.empty();
            }
            portText = rest.isEmpty() ? null : rest.substring(1);
        } else {
            int colon = authority.lastIndexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
            portText = colon < 0 ? null : authority.substring(colon + 1);
            if (!host.matches("[A-Za-z0-9._-]+")) {
                return Optional.empty();
            }
        }
        if (host.isEmpty()) {
            return Optional.empty();
        }
        if (portText == null) {
            return Optional.of(tcp(host, DEFAULT_TCP_PORT));
        }
        if (!portText.matches("[0-9]{1,5}")) {
            return Optional.empty();
        }
        int port = Integer.parseInt(portText);
        if (port <= 0 || port > 65_535) {
            return Optional.empty();
        }
        return Optional.of(tcp(host, port));
    }

    @Override
    public String toString() {
        if (kind == Kind.UNIX) {
            return "unix://" + socketPath;
        }
        return "tcp://" + (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
}
