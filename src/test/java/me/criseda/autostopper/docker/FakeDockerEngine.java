package me.criseda.autostopper.docker;

import me.criseda.autostopper.config.DockerEndpoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * An in-process Docker Engine API stand-in on a Unix socket or loopback TCP. Each request line is
 * answered by a scripted {@link Reply}; unscripted requests get a 500.
 */
final class FakeDockerEngine implements AutoCloseable {
    private final ServerSocketChannel server;
    private final DockerEndpoint endpoint;
    private final Path socketPath;
    private final Map<String, Queue<Reply>> replies = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> rawRequests = new CopyOnWriteArrayList<>();
    private final List<SocketChannel> connections = new CopyOnWriteArrayList<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final CountDownLatch requestReceived = new CountDownLatch(1);
    private final CountDownLatch clientDisconnected = new CountDownLatch(1);
    private final Thread acceptor;

    private FakeDockerEngine(ServerSocketChannel server, DockerEndpoint endpoint, Path socketPath) {
        this.server = server;
        this.endpoint = endpoint;
        this.socketPath = socketPath;
        this.acceptor = new Thread(this::acceptLoop, "fake-docker-engine");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    static FakeDockerEngine unix(Path directory) throws IOException {
        Path socket = directory.resolve("docker.sock");
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        return new FakeDockerEngine(server, DockerEndpoint.unix(socket.toString().replace('\\', '/')), socket);
    }

    static FakeDockerEngine tcp() throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
        return new FakeDockerEngine(server, DockerEndpoint.tcp("127.0.0.1", port), null);
    }

    DockerEndpoint endpoint() {
        return endpoint;
    }

    /** Scripts the next reply for a request line such as {@code GET /containers/x/json}. */
    FakeDockerEngine on(String requestLine, Reply reply) {
        replies.computeIfAbsent(requestLine, ignored -> new ConcurrentLinkedQueue<>()).add(reply);
        return this;
    }

    List<String> requests() {
        return List.copyOf(requests);
    }

    List<String> rawRequests() {
        return List.copyOf(rawRequests);
    }

    boolean awaitRequest(Duration timeout) throws InterruptedException {
        return requestReceived.await(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Waits until a client closed a connection the engine was holding open. */
    boolean awaitClientDisconnect(Duration timeout) throws InterruptedException {
        return clientDisconnected.await(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() throws IOException {
        closed.countDown();
        server.close();
        for (SocketChannel connection : connections) {
            connection.close();
        }
        if (socketPath != null) {
            Files.deleteIfExists(socketPath);
        }
    }

    private void acceptLoop() {
        while (server.isOpen()) {
            try {
                SocketChannel connection = server.accept();
                connections.add(connection);
                Thread handler = new Thread(() -> handle(connection), "fake-docker-engine-connection");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(SocketChannel connection) {
        try (connection) {
            String request = readRequest(connection);
            if (request == null) {
                return;
            }
            rawRequests.add(request);
            String requestLine = request.substring(0, request.indexOf("\r\n"));
            String key = requestLine.substring(0, requestLine.lastIndexOf(' '));
            requests.add(key);
            requestReceived.countDown();
            Queue<Reply> queue = replies.get(key);
            Reply reply = queue == null ? null : queue.poll();
            if (reply == null) {
                reply = Reply.json(500, "{\"message\":\"unscripted request " + key + "\"}");
            }
            if (!reply.delay().isZero() && closed.await(reply.delay().toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return;
            }
            ByteBuffer bytes = ByteBuffer.wrap(reply.bytes());
            while (bytes.hasRemaining()) {
                connection.write(bytes);
            }
            if (reply.hang()) {
                // Hold the connection open until the client gives up or the engine closes.
                ByteBuffer probe = ByteBuffer.allocate(1);
                if (connection.read(probe) < 0) {
                    clientDisconnected.countDown();
                }
            }
        } catch (IOException e) {
            clientDisconnected.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readRequest(SocketChannel connection) throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        while (true) {
            buffer.clear();
            int read = connection.read(buffer);
            if (read < 0) {
                return null;
            }
            request.write(buffer.array(), 0, read);
            String text = request.toString(StandardCharsets.ISO_8859_1);
            if (text.contains("\r\n\r\n")) {
                return text;
            }
        }
    }

    record Reply(byte[] bytes, Duration delay, boolean hang) {
        static Reply json(int status, String body) {
            byte[] content = body.getBytes(StandardCharsets.UTF_8);
            return raw("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: "
                    + content.length + "\r\n\r\n" + body);
        }

        static Reply chunked(int status, String body, int chunkSize) {
            StringBuilder response = new StringBuilder("HTTP/1.1 " + status
                    + " X\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n");
            for (int i = 0; i < body.length(); i += chunkSize) {
                String chunk = body.substring(i, Math.min(body.length(), i + chunkSize));
                response.append(Integer.toHexString(chunk.length())).append("\r\n").append(chunk).append("\r\n");
            }
            return raw(response.append("0\r\n\r\n").toString());
        }

        static Reply empty(int status) {
            return raw("HTTP/1.1 " + status + " X\r\n\r\n");
        }

        static Reply raw(String response) {
            return new Reply(response.getBytes(StandardCharsets.UTF_8), Duration.ZERO, false);
        }

        /** Writes {@code partial} and then holds the connection open without finishing the response. */
        static Reply stall(String partial) {
            return new Reply(partial.getBytes(StandardCharsets.UTF_8), Duration.ZERO, true);
        }

        Reply after(Duration delay) {
            return new Reply(bytes, delay, hang);
        }
    }

    static String inspectBody(boolean running, String health, Long stopTimeout) {
        String healthJson = health == null ? "" : ",\"Health\":{\"Status\":\"" + health + "\",\"Log\":[]}";
        return "{\"Id\":\"0123\",\"Name\":\"/x\",\"State\":{\"Status\":\"" + (running ? "running" : "exited")
                + "\",\"Running\":" + running + healthJson + "},\"Config\":{\"Env\":[\"SECRET=hidden\"],"
                + "\"StopTimeout\":" + (stopTimeout == null ? "null" : stopTimeout) + "}}";
    }
}
