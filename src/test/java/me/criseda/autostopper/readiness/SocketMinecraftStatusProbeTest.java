package me.criseda.autostopper.readiness;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketMinecraftStatusProbeTest {
    private final SocketMinecraftStatusProbe probe = new SocketMinecraftStatusProbe();

    @Test
    void acceptsAValidMinecraftStatusResponse() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> responder = CompletableFuture.runAsync(() -> respondWithStatus(server));

            MinecraftStatusProbe.ProbeResult result = probe.probe(
                    "127.0.0.1",
                    server.getLocalPort(),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2));

            assertEquals(MinecraftStatusProbe.Outcome.READY, result.outcome());
            responder.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void unreachableTargetFailsWithinConnectDeadline() throws Exception {
        int port;
        try (ServerSocket server = new ServerSocket(0)) {
            port = server.getLocalPort();
        }

        long started = System.nanoTime();
        MinecraftStatusProbe.ProbeResult result = probe.probe(
                "127.0.0.1",
                port,
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                Duration.ofMillis(200));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(MinecraftStatusProbe.Outcome.UNREACHABLE, result.outcome());
        assertTrue(elapsedMillis < 1_000, "connect timeout must remain bounded");
    }

    @Test
    void silentTargetFailsWithinReadDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> responder = CompletableFuture.runAsync(() -> acceptSilently(server));

            long started = System.nanoTime();
            MinecraftStatusProbe.ProbeResult result = probe.probe(
                    "127.0.0.1",
                    server.getLocalPort(),
                    Duration.ofMillis(100),
                    Duration.ofMillis(100),
                    Duration.ofMillis(250));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertEquals(MinecraftStatusProbe.Outcome.TIMED_OUT, result.outcome());
            assertTrue(elapsedMillis < 1_000, "read timeout must remain bounded");
            responder.get(2, TimeUnit.SECONDS);
        }
    }

    private void respondWithStatus(ServerSocket server) {
        try (Socket socket = server.accept()) {
            socket.getInputStream().readNBytes(2);
            byte[] json = "{\"version\":{\"name\":\"test\",\"protocol\":0}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(payloadBytes);
            writeVarInt(payload, 0);
            writeVarInt(payload, json.length);
            payload.write(json);

            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            writeVarInt(output, payloadBytes.size());
            output.write(payloadBytes.toByteArray());
            output.flush();
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private void acceptSilently(ServerSocket server) {
        try (Socket ignored = server.accept()) {
            Thread.sleep(300);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private void writeVarInt(DataOutputStream output, int value) throws Exception {
        do {
            int current = value & 0x7f;
            value >>>= 7;
            if (value != 0) {
                current |= 0x80;
            }
            output.writeByte(current);
        } while (value != 0);
    }
}
