package io.github.haydengriffin.prism;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer for Home Assistant messages. Android's org.json is a stub on the host JVM, so the
 * transport uses this to stay testable there. Objects become maps, arrays lists, numbers Long or Double.
 */
final class Json {

    private static final int MAX_DEPTH = 64;

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) throws IOException {
        Json reader = new Json(text);
        Object value = reader.value(0);
        reader.skipSpace();
        if (reader.at != text.length()) throw invalid();
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    static List<?> array(Object value) {
        return value instanceof List ? (List<?>) value : Collections.emptyList();
    }

    static String text(Object value) {
        return value instanceof String ? (String) value : "";
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            if (ch == '"' || ch == '\\') out.append('\\').append(ch);
            else if (ch < 32) out.append(String.format("\\u%04x", (int) ch));
            else out.append(ch);
        }
        return out.append('"').toString();
    }

    private Object value(int depth) throws IOException {
        if (depth > MAX_DEPTH) throw invalid();
        skipSpace();
        if (at >= text.length()) throw invalid();
        char ch = text.charAt(at);
        if (ch == '{') return objectValue(depth);
        if (ch == '[') return arrayValue(depth);
        if (ch == '"') return string();
        if (literal("true")) return Boolean.TRUE;
        if (literal("false")) return Boolean.FALSE;
        if (literal("null")) return null;
        return number();
    }

    private Map<String, Object> objectValue(int depth) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        at++;
        skipSpace();
        if (consume('}')) return result;
        do {
            skipSpace();
            if (at >= text.length() || text.charAt(at) != '"') throw invalid();
            String key = string();
            skipSpace();
            expect(':');
            result.put(key, value(depth + 1));
            skipSpace();
        } while (consume(','));
        expect('}');
        return result;
    }

    private List<Object> arrayValue(int depth) throws IOException {
        List<Object> result = new ArrayList<>();
        at++;
        skipSpace();
        if (consume(']')) return result;
        do {
            result.add(value(depth + 1));
            skipSpace();
        } while (consume(','));
        expect(']');
        return result;
    }

    private String string() throws IOException {
        StringBuilder out = new StringBuilder();
        at++;
        while (at < text.length()) {
            char ch = text.charAt(at++);
            if (ch == '"') return out.toString();
            if (ch == '\\') out.append(escape());
            else if (ch < 32) throw invalid();
            else out.append(ch);
        }
        throw invalid();
    }

    private char escape() throws IOException {
        if (at >= text.length()) throw invalid();
        char ch = text.charAt(at++);
        switch (ch) {
            case '"':
            case '\\':
            case '/':
                return ch;
            case 'b':
                return '\b';
            case 'f':
                return '\f';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case 'u':
                return unicode();
            default:
                throw invalid();
        }
    }

    private char unicode() throws IOException {
        if (at + 4 > text.length()) throw invalid();
        try {
            char ch = (char) Integer.parseInt(text.substring(at, at + 4), 16);
            at += 4;
            return ch;
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private Object number() throws IOException {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
        String raw = text.substring(start, at);
        try {
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) return Double.valueOf(raw);
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private boolean literal(String word) {
        if (!text.startsWith(word, at)) return false;
        at += word.length();
        return true;
    }

    private boolean consume(char ch) {
        if (at < text.length() && text.charAt(at) == ch) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(char ch) throws IOException {
        if (!consume(ch)) throw invalid();
    }

    private void skipSpace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    private static IOException invalid() {
        return new IOException("Home Assistant sent invalid JSON.");
    }
}
