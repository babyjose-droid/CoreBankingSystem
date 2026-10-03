package com.corebanking.kernel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON reader (RFC 8259) for the replies of the identity provider's admin API, so that parsing
 * them is plain Java that can be tested without a server. Objects become {@link LinkedHashMap}, arrays
 * {@link ArrayList}, numbers {@link BigDecimal}, and true, false and null their Java values.
 * <p>
 * It reads; it does not write. The application's own JSON (requests, responses, jsonb) keeps using Jackson.
 * Input is limited in nesting depth, and anything after the value is an error.
 */
public final class MiniJson {

    private static final int MAX_DEPTH = 32;

    private final String text;
    private int pos;

    private MiniJson(String text) {
        this.text = text;
    }

    /** @throws IllegalArgumentException when the text is not one JSON value */
    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("no JSON text");
        MiniJson p = new MiniJson(text);
        p.whitespace();
        Object value = p.value(0);
        p.whitespace();
        if (p.pos != text.length()) throw p.error("unexpected text after the value");
        return value;
    }

    /** The value as an object, or an error that says what was expected. */
    public static Map<?, ?> object(Object value, String what) {
        if (value instanceof Map<?, ?> m) return m;
        throw new IllegalArgumentException(what + ": expected a JSON object");
    }

    public static List<?> array(Object value, String what) {
        if (value instanceof List<?> l) return l;
        throw new IllegalArgumentException(what + ": expected a JSON array");
    }

    /** A string property, or null when it is absent, null or not a string. */
    public static String string(Map<?, ?> object, String name) {
        return object.get(name) instanceof String s ? s : null;
    }

    /** A whole-number property, or null when it is absent or not a whole number that fits a long. */
    public static Long whole(Map<?, ?> object, String name) {
        if (!(object.get(name) instanceof BigDecimal b)) return null;
        try {
            return b.longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw error("nested too deeply");
        if (pos >= text.length()) throw error("unexpected end");
        char c = text.charAt(pos);
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (c == 't') return literal("true", Boolean.TRUE);
        if (c == 'f') return literal("false", Boolean.FALSE);
        if (c == 'n') return literal("null", null);
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        throw error("unexpected character '" + c + "'");
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        whitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            whitespace();
            if (peek() != '"') throw error("expected a property name");
            String name = string();
            whitespace();
            if (peek() != ':') throw error("expected ':'");
            pos++;
            whitespace();
            map.put(name, value(depth + 1));
            whitespace();
            char c = peek();
            pos++;
            if (c == '}') return map;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        pos++;
        whitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            whitespace();
            list.add(value(depth + 1));
            whitespace();
            char c = peek();
            pos++;
            if (c == ']') return list;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        pos++;
        while (true) {
            if (pos >= text.length()) throw error("unterminated string");
            char c = text.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c < 0x20) throw error("control character in a string");
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) throw error("unterminated escape");
            char e = text.charAt(pos++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length() || !text.substring(pos, pos + 4).matches("[0-9A-Fa-f]{4}")) throw error("bad \\u escape");
                    sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw error("bad escape '\\" + e + "'");
            }
        }
    }

    private BigDecimal number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        String n = text.substring(start, pos);
        if (!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?") || n.length() > 60) {
            pos = start;
            throw error("bad number");
        }
        return new BigDecimal(n);
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) throw error("unexpected text");
        pos += word.length();
        return value;
    }

    private char peek() {
        if (pos >= text.length()) throw error("unexpected end");
        return text.charAt(pos);
    }

    private void whitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            pos++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON: " + message + " at position " + pos);
    }
}
