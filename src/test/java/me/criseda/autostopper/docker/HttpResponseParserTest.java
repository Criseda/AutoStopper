package me.criseda.autostopper.docker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class HttpResponseParserTest {

    @Test
    public void framesContentLengthBodiesAcrossReads() throws EngineApiException {
        HttpResponseParser parser = new HttpResponseParser(1024);
        byte[] response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"a\":true}\n"
                .getBytes(StandardCharsets.US_ASCII);

        for (int i = 0; i < response.length - 1; i++) {
            assertNull(parser.feed(response, i, 1), "incomplete at byte " + i);
        }
        EngineApiResponse complete = parser.feed(response, response.length - 1, 1);

        assertEquals(200, complete.status());
        assertEquals("{\"a\":true}\n", complete.body());
    }

    @Test
    public void decodesChunkedBodies() throws EngineApiException {
        HttpResponseParser parser = new HttpResponseParser(1024);
        String head = "HTTP/1.1 404 Not Found\r\nTransfer-Encoding: chunked\r\n\r\n";
        String body = "5;ext=1\r\n{\"mes\r\n10\r\nsage\":\"missing\"}\r\n0\r\n\r\n";

        assertNull(feed(parser, head + body.substring(0, 20)));
        EngineApiResponse complete = feed(parser, body.substring(20));

        assertEquals(404, complete.status());
        assertEquals("{\"message\":\"missing\"}", complete.body());
    }

    @Test
    public void emptyStatusesCompleteAtTheHeaders() throws EngineApiException {
        for (int status : List.of(204, 304)) {
            EngineApiResponse response = feed(new HttpResponseParser(1024), "HTTP/1.1 " + status + " X\r\n\r\n");

            assertEquals(status, response.status());
            assertEquals("", response.body());
        }
    }

    @Test
    public void unframedBodiesEndAtEndOfStream() throws EngineApiException {
        HttpResponseParser parser = new HttpResponseParser(1024);

        assertNull(feed(parser, "HTTP/1.0 500 Internal Server Error\r\n\r\nboom"));
        EngineApiResponse response = parser.finish();

        assertEquals(500, response.status());
        assertEquals("boom", response.body());
    }

    @Test
    public void rejectsOversizedResponses() {
        assertProtocolError(new HttpResponseParser(64),
                "HTTP/1.1 200 OK\r\nContent-Length: 65\r\n\r\n");
        assertProtocolError(new HttpResponseParser(64),
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + "x".repeat(64));
    }

    @Test
    public void rejectsMalformedResponses() {
        for (String invalid : List.of("SSH-2.0-OpenSSH\r\n\r\n", "HTTP/1.1 abc OK\r\n\r\n",
                "HTTP/1.1 100 Continue\r\n\r\n", "HTTP/1.1 200 OK\r\nbad header\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nabXY")) {
            assertProtocolError(new HttpResponseParser(1024), invalid);
        }
    }

    @Test
    public void truncatedResponsesFailAtEndOfStream() throws EngineApiException {
        for (String truncated : List.of("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nab", "HTTP/1.1 200")) {
            HttpResponseParser parser = new HttpResponseParser(1024);
            assertNull(feed(parser, truncated));

            EngineApiException error = assertThrows(EngineApiException.class, parser::finish, truncated);
            assertEquals(EngineApiException.Kind.PROTOCOL, error.kind());
        }
    }

    private static void assertProtocolError(HttpResponseParser parser, String input) {
        EngineApiException error = assertThrows(EngineApiException.class, () -> feed(parser, input), input);
        assertEquals(EngineApiException.Kind.PROTOCOL, error.kind());
    }

    private static EngineApiResponse feed(HttpResponseParser parser, String text) throws EngineApiException {
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        return parser.feed(bytes, 0, bytes.length);
    }
}
