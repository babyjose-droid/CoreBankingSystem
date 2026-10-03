package com.corebanking.integration.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for integration payloads, so that signing, provider requests and their tests stay
 * free of any library. Objects are {@link LinkedHashMap}s in insertion order (the written text is deterministic,
 * which matters because the text is what gets signed), arrays are lists, numbers are {@link BigDecimal}.
 * <p>
 * The reader is strict and bounded: input comes from outside (provider callbacks), so nesting is limited to
 * {@value #MAX_DEPTH} levels and the text to {@value #MAX_LENGTH} characters, duplicate keys are refused and
 * nothing but whitespace may follow the value.
 */
public final class MiniJson {

    public static final int MAX_DEPTH = 32;
    public static final int MAX_LENGTH = 1_000_000;

    /** The text is not JSON this reader accepts. */
    public static final class JsonException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    private MiniJson() {}

    // ------------------------------------------------------------------------------------------------ writing
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int depth) {
        if (depth > MAX_DEPTH) throw new JsonException("value is nested too deeply");
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (v instanceof BigDecimal d) {
            sb.append(d.toPlainString());
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte
                || v instanceof java.math.BigInteger) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new JsonException("number is not finite");
            sb.append(BigDecimal.valueOf(d).toPlainString());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o, depth + 1);
            }
            sb.append(']');
        } else {
            quote(sb, String.valueOf(v));      // dates, UUIDs, enums
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // control characters, and the two separators that break JavaScript string literals
                    if (c < 0x20 || c == 0x7F || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------------------------------------ reading
    public static Object parse(String text) {
        Reader r = reader(text);
        Object v = r.value(0);
        r.end();
        return v;
    }

    /** Parses text that must be a JSON object. */
    public static Map<String, Object> parseObject(String text) {
        Reader r = reader(text);
        r.skipWhitespace();
        if (r.peek() != '{') throw new JsonException("expected a JSON object");
        Map<String, Object> m = r.object(0);
        r.end();
        return m;
    }

    /** Parses text that must be a JSON array. */
    public static List<Object> parseArray(String text) {
        Reader r = reader(text);
        r.skipWhitespace();
        if (r.peek() != '[') throw new JsonException("expected a JSON array");
        List<Object> l = r.array(0);
        r.end();
        return l;
    }

    private static Reader reader(String text) {
        if (text == null) throw new JsonException("no JSON text");
        if (text.length() > MAX_LENGTH) throw new JsonException("JSON text is too long");
        return new Reader(text);
    }

    /** A nested object as a map with string keys, or an empty map when the value is not an object. */
    public static Map<String, Object> object(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /** A nested array as a list, or an empty list when the value is not an array. */
    public static List<Object> array(Object value) {
        List<Object> out = new ArrayList<>();
        if (value instanceof Collection<?> c) out.addAll(c);
        return out;
    }

    /** The member as text: strings as they are, numbers in plain notation, booleans as true/false; null when absent. */
    public static String string(Map<String, ?> object, String key) {
        Object v = object.get(key);
        if (v == null) return null;
        if (v instanceof BigDecimal d) return d.toPlainString();
        if (v instanceof Map<?, ?> || v instanceof Collection<?>) return write(v);
        return String.valueOf(v);
    }

    /** The member as a decimal (a JSON number, or a string holding one); null when absent or blank. */
    public static BigDecimal decimal(Map<String, ?> object, String key) {
        Object v = object.get(key);
        if (v == null) return null;
        if (v instanceof BigDecimal d) return d;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new JsonException(key + " is not a number");
        }
    }

    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) {
            this.s = s;
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        void skipWhitespace() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
            }
        }

        void end() {
            skipWhitespace();
            if (i != s.length()) throw new JsonException("unexpected text after the JSON value at position " + i);
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw new JsonException("JSON is nested too deeply");
            skipWhitespace();
            char c = peek();
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (c == 't') return literal("true", Boolean.TRUE);
            if (c == 'f') return literal("false", Boolean.FALSE);
            if (c == 'n') return literal("null", null);
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw new JsonException(i >= s.length() ? "unexpected end of JSON" : "unexpected character at position " + i);
        }

        Map<String, Object> object(int depth) {
            if (depth > MAX_DEPTH) throw new JsonException("JSON is nested too deeply");
            Map<String, Object> m = new LinkedHashMap<>();
            i++;                                   // {
            skipWhitespace();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') throw new JsonException("expected a member name at position " + i);
                String key = string();
                skipWhitespace();
                if (peek() != ':') throw new JsonException("expected ':' at position " + i);
                i++;
                Object v = value(depth + 1);
                if (m.containsKey(key)) throw new JsonException("member '" + key + "' appears twice");
                m.put(key, v);
                skipWhitespace();
                char c = peek();
                i++;
                if (c == '}') return m;
                if (c != ',') throw new JsonException("expected ',' or '}' at position " + (i - 1));
            }
        }

        List<Object> array(int depth) {
            if (depth > MAX_DEPTH) throw new JsonException("JSON is nested too deeply");
            List<Object> l = new ArrayList<>();
            i++;                                   // [
            skipWhitespace();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value(depth + 1));
                skipWhitespace();
                char c = peek();
                i++;
                if (c == ']') return l;
                if (c != ',') throw new JsonException("expected ',' or ']' at position " + (i - 1));
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++;                                   // opening quote
            while (true) {
                if (i >= s.length()) throw new JsonException("a string is not closed");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw new JsonException("control character in a string at position " + (i - 1));
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) throw new JsonException("a string is not closed");
                char e = s.charAt(i++);
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
                        if (i + 4 > s.length()) throw new JsonException("incomplete \\u escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException x) {
                            throw new JsonException("invalid \\u escape at position " + i);
                        }
                        i += 4;
                    }
                    default -> throw new JsonException("invalid escape at position " + (i - 1));
                }
            }
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, i)) throw new JsonException("unexpected text at position " + i);
            i += word.length();
            return value;
        }

        private static boolean ascii(char c) {
            return c >= '0' && c <= '9';
        }

        BigDecimal number() {
            int start = i;
            if (peek() == '-') i++;
            int digits = i;
            while (i < s.length() && ascii(s.charAt(i))) i++;
            if (i == digits) throw new JsonException("invalid number at position " + start);
            if (s.charAt(digits) == '0' && i - digits > 1) throw new JsonException("number with a leading zero at position " + start);
            if (peek() == '.') {
                i++;
                int frac = i;
                while (i < s.length() && ascii(s.charAt(i))) i++;
                if (i == frac) throw new JsonException("invalid number at position " + start);
            }
            if (peek() == 'e' || peek() == 'E') {
                i++;
                if (peek() == '+' || peek() == '-') i++;
                int exp = i;
                while (i < s.length() && ascii(s.charAt(i))) i++;
                if (i == exp || i - exp > 3) throw new JsonException("invalid exponent at position " + start);
            }
            if (i - start > 60) throw new JsonException("number is too long at position " + start);
            return new BigDecimal(s.substring(start, i));
        }
    }
}
