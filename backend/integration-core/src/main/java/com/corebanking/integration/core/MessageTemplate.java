package com.corebanking.integration.core;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Message templates with {@code {{placeholders}}} (US-123). Rendering is strict, because an SMS must match the
 * text registered on the operator's DLT platform and a half-filled message to a borrower is worse than none:
 * <ul>
 *   <li>a placeholder without a value is an error, and so is a value the template does not use;</li>
 *   <li>names are {@code [a-zA-Z][a-zA-Z0-9_]{0,39}}; anything else between braces, or a stray {@code {{} or
 *       {@code }}}, is an error — there are no expressions, loops or includes;</li>
 *   <li>values are plain text: no control characters, no braces, and a length limit per value (a DLT variable
 *       holds at most 30 characters, which is the default for SMS);</li>
 *   <li>values are inserted once: a value that contains {@code {{name}}} is refused, never expanded.</li>
 * </ul>
 */
public final class MessageTemplate {

    /** TRAI DLT: one variable ({#var#}) carries at most 30 characters. */
    public static final int SMS_VALUE_LIMIT = 30;
    public static final int EMAIL_VALUE_LIMIT = 200;
    public static final int MAX_BODY_LENGTH = 4000;

    private static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,39}");

    /** The template or the values cannot produce a message. The text names the placeholder, never a value. */
    public static final class TemplateException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public TemplateException(String message) {
            super(message);
        }
    }

    private MessageTemplate() {}

    /** The placeholder names in the body, in order of first use. Throws when the body is malformed. */
    public static Set<String> placeholders(String body) {
        Set<String> names = new LinkedHashSet<>();
        walk(body, null, 0, names);
        return names;
    }

    /** Checks a template body when it is proposed. */
    public static void validate(String body) {
        placeholders(body);
    }

    public static String render(String body, Map<String, String> values, int maxValueLength) {
        Set<String> names = new LinkedHashSet<>();
        Map<String, String> v = values == null ? Map.of() : values;
        for (Map.Entry<String, String> e : v.entrySet()) checkValue(e.getKey(), e.getValue(), maxValueLength);
        String out = walk(body, v, maxValueLength, names);
        for (String given : v.keySet()) {
            if (!names.contains(given)) throw new TemplateException("the template has no placeholder '" + given + "'");
        }
        return out;
    }

    private static void checkValue(String name, String value, int max) {
        if (name == null || !NAME.matcher(name).matches()) throw new TemplateException("invalid placeholder name");
        if (value == null) throw new TemplateException("no value for placeholder '" + name + "'");
        if (value.length() > max) throw new TemplateException("the value for '" + name + "' is longer than " + max + " characters");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '{' || c == '}') {
                throw new TemplateException("the value for '" + name + "' contains a character that is not allowed");
            }
        }
    }

    /** Scans the body; with {@code values} it also substitutes. */
    private static String walk(String body, Map<String, String> values, int max, Set<String> names) {
        if (body == null || body.isBlank()) throw new TemplateException("the template body is empty");
        if (body.length() > MAX_BODY_LENGTH) throw new TemplateException("the template body is longer than " + MAX_BODY_LENGTH + " characters");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < body.length()) {
            int open = body.indexOf("{{", i);
            int close = body.indexOf("}}", i);
            if (close >= 0 && (open < 0 || close < open)) throw new TemplateException("'}}' without '{{' at position " + close);
            if (open < 0) {
                out.append(body, i, body.length());
                break;
            }
            if (close < 0) throw new TemplateException("'{{' is not closed at position " + open);
            String name = body.substring(open + 2, close).trim();
            if (!NAME.matcher(name).matches()) throw new TemplateException("invalid placeholder at position " + open);
            names.add(name);
            out.append(body, i, open);
            if (values != null) {
                String value = values.get(name);
                if (value == null) throw new TemplateException("no value for placeholder '" + name + "'");
                out.append(value);
            }
            i = close + 2;
        }
        return out.toString();
    }

    /**
     * The body as it has to be registered on the DLT platform: each placeholder becomes {@code {#var#}}. Shown to
     * the tenant so that the registered template and ours cannot drift apart.
     */
    public static String dltForm(String body) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        placeholders(body);
        while (i < body.length()) {
            int open = body.indexOf("{{", i);
            if (open < 0) {
                out.append(body, i, body.length());
                break;
            }
            int close = body.indexOf("}}", open);
            out.append(body, i, open).append("{#var#}");
            i = close + 2;
        }
        return out.toString();
    }
}
