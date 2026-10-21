package me.criseda.autostopper.docker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

public class JsonReaderTest {

    @Test
    public void readsInspectFields() {
        Object document = JsonReader.parse("""
                {"Id":"abc","State":{"Running":true,"Health":{"Status":"healthy","Log":[{"ExitCode":0}]}},
                 "Config":{"StopTimeout":60,"Env":["A=\\"quoted\\"","B=\\u00e9"],"Labels":null},
                 "Ratio":-1.5e2}
                """);

        assertEquals(Boolean.TRUE, JsonReader.path(document, "State", "Running"));
        assertEquals("healthy", JsonReader.path(document, "State", "Health", "Status"));
        assertEquals(60L, JsonReader.path(document, "Config", "StopTimeout"));
        assertEquals(List.of("A=\"quoted\"", "B=\u00e9"), JsonReader.path(document, "Config", "Env"));
        assertNull(JsonReader.path(document, "Config", "Labels"));
        assertNull(JsonReader.path(document, "State", "Running", "Nested"));
        assertNull(JsonReader.path(document, "Missing", "Key"));
        assertEquals(-150.0, JsonReader.path(document, "Ratio"));
        assertEquals(Map.of(), JsonReader.parse(" {} "));
        assertEquals(List.of(), JsonReader.parse("[]"));
    }

    @Test
    public void rejectsMalformedDocuments() {
        for (String invalid : List.of("", "{", "{\"a\":}", "{\"a\" 1}", "{\"a\":1,}", "[1 2]", "tru",
                "\"unterminated", "\"bad\\q\"", "\"\\u12\"", "{} {}", "01x", "-", "1.2.3", "{a:1}",
                "\"tab\there\"")) {
            assertThrows(IllegalArgumentException.class, () -> JsonReader.parse(invalid), invalid);
        }
    }

    @Test
    public void rejectsExcessiveNesting() {
        String deep = "[".repeat(100) + "]".repeat(100);

        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse(deep));
    }
}
