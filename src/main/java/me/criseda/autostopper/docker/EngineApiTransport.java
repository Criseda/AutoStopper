package me.criseda.autostopper.docker;

import me.criseda.autostopper.config.DockerEndpoint;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends one HTTP/1.1 request to the Docker Engine API over a Unix socket or plain TCP, using only
 * the JDK. Channels are non-blocking and every wait is bounded by the caller's deadline, because a
 * blocking {@link SocketChannel} read ignores socket timeouts. Interrupting the calling thread
 * aborts the exchange and keeps the interrupt flag set.
 */
final class EngineApiTransport {
    /** Inspect bodies are tens of KiB; anything near this limit is not a Docker response we expect. */
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int READ_BUFFER_BYTES = 16 * 1024;

    private final HostResolver resolver;
    private final int maxResponseBytes;

    EngineApiTransport(HostResolver resolver) {
        this(resolver, MAX_RESPONSE_BYTES);
    }

    EngineApiTransport(HostResolver resolver, int maxResponseBytes) {
        this.resolver = resolver;
        this.maxResponseBytes = maxResponseBytes;
    }

    EngineApiResponse exchange(DockerEndpoint endpoint, String method, String path, long deadlineNanos)
            throws EngineApiException {
        if (Thread.currentThread().isInterrupted()) {
            throw interrupted();
        }
        SocketAddress address = address(endpoint, deadlineNanos);
        boolean connected = false;
        try (SocketChannel channel = endpoint.kind() == DockerEndpoint.Kind.UNIX
                ? SocketChannel.open(StandardProtocolFamily.UNIX)
                : SocketChannel.open();
                Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            SelectionKey key = channel.register(selector, 0);
            if (!channel.connect(address)) {
                key.interestOps(SelectionKey.OP_CONNECT);
                while (!channel.finishConnect()) {
                    await(selector, deadlineNanos);
                }
            }
            connected = true;

            ByteBuffer request = ByteBuffer.wrap(request(endpoint, method, path));
            key.interestOps(SelectionKey.OP_WRITE);
            while (request.hasRemaining()) {
                if (channel.write(request) == 0) {
                    await(selector, deadlineNanos);
                }
            }

            key.interestOps(SelectionKey.OP_READ);
            HttpResponseParser parser = new HttpResponseParser(maxResponseBytes);
            ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
            while (true) {
                buffer.clear();
                int read = channel.read(buffer);
                if (read < 0) {
                    return parser.finish();
                }
                if (read == 0) {
                    await(selector, deadlineNanos);
                    continue;
                }
                EngineApiResponse response = parser.feed(buffer.array(), 0, read);
                if (response != null) {
                    return response;
                }
            }
        } catch (ClosedByInterruptException e) {
            throw interrupted();
        } catch (UnresolvedAddressException e) {
            throw new EngineApiException(EngineApiException.Kind.UNAVAILABLE,
                    "Docker host could not be resolved: " + endpoint, e);
        } catch (IOException e) {
            throw classify(endpoint, e, connected);
        }
    }

    private SocketAddress address(DockerEndpoint endpoint, long deadlineNanos) throws EngineApiException {
        if (endpoint.kind() == DockerEndpoint.Kind.UNIX) {
            Path socket;
            try {
                socket = Path.of(endpoint.socketPath());
            } catch (InvalidPathException e) {
                throw new EngineApiException(EngineApiException.Kind.UNAVAILABLE,
                        "invalid Docker socket path " + endpoint, e);
            }
            if (Files.notExists(socket)) {
                throw new EngineApiException(EngineApiException.Kind.UNAVAILABLE,
                        "Docker socket not found at " + endpoint);
            }
            return UnixDomainSocketAddress.of(socket);
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw timedOut();
        }
        try {
            InetAddress resolved = resolver.resolve(endpoint.host(), Duration.ofNanos(remaining));
            return new InetSocketAddress(resolved, endpoint.port());
        } catch (UnknownHostException e) {
            throw new EngineApiException(EngineApiException.Kind.UNAVAILABLE,
                    "Docker host could not be resolved: " + endpoint, e);
        } catch (TimeoutException e) {
            throw timedOut();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw interrupted();
        }
    }

    private void await(Selector selector, long deadlineNanos) throws IOException, EngineApiException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw timedOut();
        }
        selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining + 999_999)));
        selector.selectedKeys().clear();
        if (Thread.currentThread().isInterrupted()) {
            throw interrupted();
        }
    }

    private static byte[] request(DockerEndpoint endpoint, String method, String path) {
        String host = endpoint.kind() == DockerEndpoint.Kind.UNIX
                ? "docker"
                : endpoint.toString().substring("tcp://".length());
        StringBuilder request = new StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("User-Agent: AutoStopper\r\n")
                .append("Accept: application/json\r\n")
                .append("Connection: close\r\n");
        if (!"GET".equals(method)) {
            request.append("Content-Length: 0\r\n");
        }
        return request.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII);
    }

    static EngineApiException classify(DockerEndpoint endpoint, IOException error, boolean connected) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("permission denied") || lower.contains("access denied")
                || lower.contains("access is denied")) {
            return new EngineApiException(EngineApiException.Kind.PERMISSION_DENIED,
                    "permission denied connecting to " + endpoint + ": " + message, error);
        }
        if (!connected) {
            return new EngineApiException(EngineApiException.Kind.UNAVAILABLE,
                    "could not connect to " + endpoint + ": " + message, error);
        }
        return new EngineApiException(EngineApiException.Kind.PROTOCOL,
                "connection to " + endpoint + " failed: " + message, error);
    }

    private static EngineApiException timedOut() {
        return new EngineApiException(EngineApiException.Kind.TIMED_OUT, "Docker API request deadline expired");
    }

    private static EngineApiException interrupted() {
        return new EngineApiException(EngineApiException.Kind.INTERRUPTED, "Docker API request interrupted");
    }
}
