package me.criseda.autostopper.docker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal strict JSON parser for Docker Engine API responses. Objects become {@link Map}s, arrays
 * {@link List}s, numbers {@link Long} or {@link Double}, and JSON {@code null} Java {@code null}.
 * It exists so AutoStopper reads a few inspect fields without depending on the proxy's Gson.
 */
final class JsonReader {
    private static final int MAX_DEPTH = 64;
    private final String text;
    private int position;

    private JsonReader(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        JsonReader reader = new JsonReader(text);
        reader.skipWhitespace();
        Object value = reader.readValue(0);
        reader.skipWhitespace();
        if (reader.position != text.length()) {
            throw reader.error("trailing content");
        }
        return value;
    }

    /** Follows object keys from {@code root}; returns {@code null} when any step is absent or not an object. */
    static Object path(Object root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> object)) {
                return null;
            }
            current = object.get(key);
        }
        return current;
    }

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nesting too deep");
        }
        if (position >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(position);
        return switch (c) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber();
                }
                throw error("unexpected character");
            }
        };
    }

    private Map<String, Object> readObject(int depth) {
        Map<String, Object> object = new LinkedHashMap<>();
        position++;
        skipWhitespace();
        if (consume('}')) {
            return object;
        }
        while (true) {
            skipWhitespace();
            if (position >= text.length() || text.charAt(position) != '"') {
                throw error("expected object key");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, readValue(depth + 1));
            skipWhitespace();
            if (consume('}')) {
                return object;
            }
            expect(',');
        }
    }

    private List<Object> readArray(int depth) {
        List<Object> array = new ArrayList<>();
        position++;
        skipWhitespace();
        if (consume(']')) {
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue(depth + 1));
            skipWhitespace();
            if (consume(']')) {
                return array;
            }
            expect(',');
        }
    }

    private String readString() {
        StringBuilder value = new StringBuilder();
        position++;
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') {
                return value.toString();
            }
            if (c < 0x20) {
                throw error("control character in string");
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            if (position >= text.length()) {
                break;
            }
            char escaped = text.charAt(position++);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length()) {
                        throw error("truncated unicode escape");
                    }
                    try {
                        value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    } catch (NumberFormatException e) {
                        throw error("invalid unicode escape");
                    }
                    position += 4;
                }
                default -> throw error("invalid escape");
            }
        }
        throw error("unterminated string");
    }

    private Object readNumber() {
        int start = position;
        consume('-');
        boolean integral = true;
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c >= '0' && c <= '9') {
                position++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                integral = false;
                position++;
            } else {
                break;
            }
        }
        String number = text.substring(start, position);
        try {
            return integral ? (Object) Long.parseLong(number) : (Object) Double.parseDouble(number);
        } catch (NumberFormatException e) {
            if (integral && number.matches("-?[0-9]+")) {
                return Double.parseDouble(number);
            }
            throw error("invalid number");
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, position)) {
            throw error("invalid literal");
        }
        position += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            position++;
        }
    }

    private boolean consume(char expected) {
        if (position < text.length() && text.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            throw error("expected '" + expected + "'");
        }
    }

    private IllegalArgumentException error(String problem) {
        return new IllegalArgumentException("invalid JSON at offset " + position + ": " + problem);
    }
}
