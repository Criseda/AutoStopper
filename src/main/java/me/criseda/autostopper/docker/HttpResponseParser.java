package me.criseda.autostopper.docker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Incrementally frames one HTTP/1.1 response read from a {@code Connection: close} exchange. It
 * supports {@code Content-Length}, chunked and read-until-close bodies and rejects responses larger
 * than its byte limit.
 */
final class HttpResponseParser {
    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};
    private final int maxBytes;
    private final ByteArrayOutputStream raw = new ByteArrayOutputStream();
    private Head head;

    HttpResponseParser(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** Adds bytes; returns the response once it is complete, otherwise {@code null}. */
    EngineApiResponse feed(byte[] bytes, int offset, int length) throws EngineApiException {
        if (raw.size() + length > maxBytes) {
            throw new EngineApiException(EngineApiException.Kind.PROTOCOL,
                    "Docker API response exceeds " + maxBytes + " bytes");
        }
        raw.write(bytes, offset, length);
        return tryComplete(false);
    }

    /** Called at end of stream; returns the response or fails when it is incomplete. */
    EngineApiResponse finish() throws EngineApiException {
        EngineApiResponse response = tryComplete(true);
        if (response == null) {
            throw new EngineApiException(EngineApiException.Kind.PROTOCOL,
                    "Docker API connection closed before the response was complete");
        }
        return response;
    }

    private EngineApiResponse tryComplete(boolean endOfStream) throws EngineApiException {
        byte[] data = raw.toByteArray();
        if (head == null) {
            int headerEnd = indexOf(data, HEADER_END, 0);
            if (headerEnd < 0) {
                return null;
            }
            head = parseHead(new String(data, 0, headerEnd, StandardCharsets.ISO_8859_1), headerEnd + 4);
        }
        int bodyStart = head.bodyStart();
        if (head.status() == 204 || head.status() == 304) {
            return new EngineApiResponse(head.status(), "");
        }
        if (head.chunked()) {
            return dechunk(data, bodyStart, endOfStream);
        }
        if (head.contentLength() >= 0) {
            if (data.length - bodyStart < head.contentLength()) {
                return null;
            }
            return new EngineApiResponse(head.status(),
                    new String(data, bodyStart, (int) head.contentLength(), StandardCharsets.UTF_8));
        }
        if (!endOfStream) {
            return null;
        }
        return new EngineApiResponse(head.status(),
                new String(data, bodyStart, data.length - bodyStart, StandardCharsets.UTF_8));
    }

    private EngineApiResponse dechunk(byte[] data, int start, boolean endOfStream) throws EngineApiException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int position = start;
        while (true) {
            int lineEnd = indexOf(data, new byte[] {'\r', '\n'}, position);
            if (lineEnd < 0) {
                return incomplete(endOfStream);
            }
            String sizeLine = new String(data, position, lineEnd - position, StandardCharsets.ISO_8859_1);
            int extension = sizeLine.indexOf(';');
            String sizeText = (extension < 0 ? sizeLine : sizeLine.substring(0, extension)).trim();
            long size;
            try {
                size = Long.parseLong(sizeText, 16);
            } catch (NumberFormatException e) {
                throw protocol("invalid chunk size");
            }
            if (size < 0 || size > maxBytes) {
                throw protocol("invalid chunk size");
            }
            position = lineEnd + 2;
            if (size == 0) {
                // Trailers are ignored; the body is complete once the zero-size chunk arrives.
                return new EngineApiResponse(head.status(), body.toString(StandardCharsets.UTF_8));
            }
            if (data.length - position < size + 2) {
                return incomplete(endOfStream);
            }
            body.write(data, position, (int) size);
            position += (int) size;
            if (data[position] != '\r' || data[position + 1] != '\n') {
                throw protocol("missing chunk terminator");
            }
            position += 2;
        }
    }

    private EngineApiResponse incomplete(boolean endOfStream) throws EngineApiException {
        if (endOfStream) {
            throw protocol("connection closed inside a chunked body");
        }
        return null;
    }

    private Head parseHead(String headText, int bodyStart) throws EngineApiException {
        String[] lines = headText.split("\r\n");
        String[] statusLine = lines[0].split(" ", 3);
        if (statusLine.length < 2 || !statusLine[0].startsWith("HTTP/1.")) {
            throw protocol("invalid status line");
        }
        int status;
        try {
            status = Integer.parseInt(statusLine[1]);
        } catch (NumberFormatException e) {
            throw protocol("invalid status code");
        }
        if (status < 200 || status > 599) {
            throw protocol("unsupported status code " + status);
        }
        boolean chunked = false;
        long contentLength = -1;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                throw protocol("invalid header line");
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if (name.equals("transfer-encoding")) {
                chunked = value.toLowerCase(Locale.ROOT).contains("chunked");
            } else if (name.equals("content-length")) {
                try {
                    contentLength = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw protocol("invalid Content-Length");
                }
                if (contentLength < 0) {
                    throw protocol("invalid Content-Length");
                }
                if (contentLength > maxBytes) {
                    throw protocol("Docker API response exceeds " + maxBytes + " bytes");
                }
            }
        }
        return new Head(status, chunked, contentLength, bodyStart);
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static EngineApiException protocol(String message) {
        return new EngineApiException(EngineApiException.Kind.PROTOCOL, "malformed Docker API response: " + message);
    }

    private record Head(int status, boolean chunked, long contentLength, int bodyStart) {
    }
}
