package io.github.jichuo1.meguviz.hook.testing.conformance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict test parser for the JSON subset used by trace records; independent of the writer. */
final class TraceJsonParser {
    private final String input;
    private int at;

    private TraceJsonParser(String input) { this.input = input; }

    static Object parse(String input) {
        TraceJsonParser parser = new TraceJsonParser(input);
        Object result = parser.value();
        parser.space();
        if (parser.at != input.length()) throw new AssertionError("trailing JSON");
        return result;
    }

    private Object value() {
        space();
        if (at >= input.length()) throw new AssertionError("missing value");
        char c = input.charAt(at);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (input.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
        if (input.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
        if (input.startsWith("null", at)) { at += 4; return null; }
        int start = at;
        if (c == '-') at++;
        while (at < input.length() && input.charAt(at) >= '0' && input.charAt(at) <= '9') at++;
        if (at == start || (at == start + 1 && c == '-')) throw new AssertionError("invalid number");
        return Long.valueOf(input.substring(start, at));
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        space();
        if (take('}')) return result;
        do {
            space();
            String key = string();
            space(); expect(':');
            Object value = value();
            if (result.containsKey(key)) throw new AssertionError("duplicate key");
            result.put(key, value);
            space();
            if (take('}')) return result;
            expect(',');
        } while (true);
    }

    private List<Object> array() {
        expect('[');
        List<Object> result = new ArrayList<Object>();
        space();
        if (take(']')) return result;
        do {
            result.add(value());
            space();
            if (take(']')) return result;
            expect(',');
        } while (true);
    }

    private String string() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (at < input.length()) {
            char c = input.charAt(at++);
            if (c == '"') return value.toString();
            if (c < 0x20) throw new AssertionError("raw control");
            if (c != '\\') { value.append(c); continue; }
            if (at >= input.length()) throw new AssertionError("unfinished escape");
            char escaped = input.charAt(at++);
            if (escaped == '"' || escaped == '\\' || escaped == '/') value.append(escaped);
            else if (escaped == 'u') {
                char code = hex();
                if (Character.isHighSurrogate(code)) {
                    if (!take('\\') || !take('u')) throw new AssertionError("unpaired high surrogate");
                    char low = hex();
                    if (!Character.isLowSurrogate(low)) throw new AssertionError("invalid surrogate pair");
                    value.append(code).append(low);
                } else if (Character.isLowSurrogate(code)) throw new AssertionError("unpaired low surrogate");
                else value.append(code);
            } else throw new AssertionError("unsupported escape");
        }
        throw new AssertionError("unterminated string");
    }

    private char hex() {
        if (at + 4 > input.length()) throw new AssertionError("incomplete unicode escape");
        int result = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(input.charAt(at++), 16);
            if (digit < 0) throw new AssertionError("invalid unicode escape");
            result = result * 16 + digit;
        }
        return (char) result;
    }

    private void space() {
        while (at < input.length() && (input.charAt(at) == ' ' || input.charAt(at) == '\t'
                || input.charAt(at) == '\r' || input.charAt(at) == '\n')) at++;
    }

    private boolean take(char c) {
        if (at < input.length() && input.charAt(at) == c) { at++; return true; }
        return false;
    }

    private void expect(char c) { if (!take(c)) throw new AssertionError("expected " + c + " at " + at); }
}
