package me.criseda.autostopper.readiness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class SocketMinecraftStatusProbe implements MinecraftStatusProbe {
    private static final int MAX_PACKET_BYTES = 1_048_576;

    @Override
    public ProbeResult probe(String host, int port, Duration connectTimeout, Duration readTimeout,
            Duration attemptTimeout) {
        long deadline = saturatedAdd(System.nanoTime(), attemptTimeout.toNanos());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis(connectTimeout, deadline));
            socket.setSoTimeout(timeoutMillis(readTimeout, deadline));
            writeStatusRequest(socket.getOutputStream(), host, port);
            readStatusResponse(socket.getInputStream());
            return new ProbeResult(Outcome.READY);
        } catch (SocketTimeoutException error) {
            return new ProbeResult(Outcome.TIMED_OUT);
        } catch (ConnectException | NoRouteToHostException | UnknownHostException error) {
            return new ProbeResult(Outcome.UNREACHABLE);
        } catch (ProtocolException error) {
            return new ProbeResult(Outcome.INVALID_RESPONSE);
        } catch (IOException error) {
            return new ProbeResult(Outcome.UNREACHABLE);
        } catch (RuntimeException error) {
            return new ProbeResult(Outcome.FAILED);
        }
    }

    private void writeStatusRequest(OutputStream output, String host, int port) throws IOException {
        ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
        try (DataOutputStream handshake = new DataOutputStream(handshakeBytes)) {
            writeVarInt(handshake, 0);
            writeVarInt(handshake, 0);
            writeString(handshake, host);
            handshake.writeShort(port);
            writeVarInt(handshake, 1);
        }

        DataOutputStream packets = new DataOutputStream(output);
        writeVarInt(packets, handshakeBytes.size());
        packets.write(handshakeBytes.toByteArray());
        writeVarInt(packets, 1);
        writeVarInt(packets, 0);
        packets.flush();
    }

    private void readStatusResponse(InputStream input) throws IOException {
        int packetLength = readVarInt(input);
        if (packetLength < 2 || packetLength > MAX_PACKET_BYTES) {
            throw new ProtocolException("invalid status packet length");
        }
        byte[] packet = input.readNBytes(packetLength);
        if (packet.length != packetLength) {
            throw new EOFException("truncated status response");
        }

        ByteArrayInputStream payload = new ByteArrayInputStream(packet);
        if (readVarInt(payload) != 0) {
            throw new ProtocolException("unexpected status packet id");
        }
        int jsonLength = readVarInt(payload);
        if (jsonLength <= 1 || jsonLength > payload.available()) {
            throw new ProtocolException("invalid status response length");
        }
        String json = new String(payload.readNBytes(jsonLength), StandardCharsets.UTF_8).trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new ProtocolException("invalid status response payload");
        }
    }

    private void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, encoded.length);
        output.write(encoded);
    }

    private void writeVarInt(DataOutputStream output, int value) throws IOException {
        do {
            int current = value & 0x7f;
            value >>>= 7;
            if (value != 0) {
                current |= 0x80;
            }
            output.writeByte(current);
        } while (value != 0);
    }

    private int readVarInt(InputStream input) throws IOException {
        int value = 0;
        for (int position = 0; position < 35; position += 7) {
            int current = input.read();
            if (current == -1) {
                throw new EOFException("truncated VarInt");
            }
            value |= (current & 0x7f) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new ProtocolException("VarInt is too long");
    }

    private int timeoutMillis(Duration configured, long deadline) throws SocketTimeoutException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new SocketTimeoutException("readiness attempt deadline expired");
        }
        long configuredMillis = Math.max(1, configured.toMillis());
        long remainingMillis = Math.max(1, Duration.ofNanos(remainingNanos).toMillis());
        return (int) Math.min(Integer.MAX_VALUE, Math.min(configuredMillis, remainingMillis));
    }

    private long saturatedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static final class ProtocolException extends IOException {
        private ProtocolException(String message) {
            super(message);
        }
    }
}
