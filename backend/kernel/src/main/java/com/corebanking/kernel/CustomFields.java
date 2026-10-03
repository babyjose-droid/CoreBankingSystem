package com.corebanking.kernel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Custom fields on customers, loan accounts and loan products (US-014). A tenant defines extra attributes; values
 * travel in the API as an object named {@code custom} and are stored as JSON on the record.
 * <p>
 * The same rules are enforced twice: here, so the API can answer with every problem at once, and by the database
 * ({@code platform.validate_custom}), so no other writer can store a value that breaks them.
 * <ul>
 *   <li>An unknown key, a value of the wrong type and a missing required field are errors.</li>
 *   <li>TEXT may carry a pattern (the whole value must match) and a length range; NUMBER a value range; ENUM takes
 *       the active codes of an enumeration type; DATE is {@code YYYY-MM-DD}.</li>
 *   <li>A field flagged as personal data is TEXT. Its clear value is checked here and then sealed with the tenant's
 *       PII cipher: the record keeps {@code {"enc": ciphertext, "mask": masked text}} and the API returns the mask.</li>
 * </ul>
 */
public final class CustomFields {

    public static final Set<String> ENTITIES = Set.of("CUSTOMER", "LOAN_ACCOUNT", "LOAN_PRODUCT");
    public static final Set<String> WIDGETS = Set.of("TEXT", "TEXTAREA", "NUMBER", "DATE", "CHECKBOX", "SELECT", "RADIO");
    public static final int MAX_TEXT_LENGTH = 2000;
    public static final int MAX_PATTERN_LENGTH = 200;

    private static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9]{1,39}");
    private static final Pattern ENUM_TYPE = Pattern.compile("[a-z][a-z0-9-]{1,39}");

    public enum DataType { TEXT, NUMBER, DATE, BOOLEAN, ENUM }

    /**
     * @param enumValues the active codes of {@code enumType}; empty for other types
     * @param min        NUMBER: lowest value; TEXT: shortest length
     * @param max        NUMBER: highest value; TEXT: longest length
     */
    public record Definition(String entity, String key, String label, DataType dataType, String enumType, Set<String> enumValues,
                             boolean required, String regex, BigDecimal min, BigDecimal max, boolean pii, boolean active) {
        public Definition {
            enumValues = enumValues == null ? Set.of() : Set.copyOf(enumValues);
        }
    }

    /** One thing wrong with a definition or a value; {@code field} is the custom field key (or the attribute of a definition). */
    public record Problem(String field, String message) {}

    public static final class InvalidException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final transient List<Problem> problems;

        public InvalidException(List<Problem> problems) {
            super(problems.isEmpty() ? "custom fields are not valid" : problems.get(0).message());
            this.problems = List.copyOf(problems);
        }

        public List<Problem> problems() {
            return problems;
        }
    }

    private CustomFields() {}

    // ---------------------------------------------------------------------------------------------- definitions
    /**
     * Checks a proposed definition. {@code existing} is the definition already stored under the same entity and
     * key, or null for a new field: the type and the personal-data flag cannot change once values may exist.
     */
    public static List<Problem> checkDefinition(Definition d, Definition existing) {
        List<Problem> p = new ArrayList<>();
        if (d.entity() == null || !ENTITIES.contains(d.entity())) p.add(new Problem("entity", "entity must be CUSTOMER, LOAN_ACCOUNT or LOAN_PRODUCT"));
        if (d.key() == null || !KEY.matcher(d.key()).matches()) {
            p.add(new Problem("key", "key must start with a lower-case letter and have 2 to 40 letters or digits, e.g. employeeCode"));
        }
        if (d.label() == null || d.label().isBlank() || d.label().trim().length() > 80) p.add(new Problem("label", "label is required, at most 80 characters"));
        if (d.dataType() == null) {
            p.add(new Problem("dataType", "dataType must be TEXT, NUMBER, DATE, BOOLEAN or ENUM"));
            return p;
        }
        if (d.dataType() == DataType.ENUM) {
            if (d.enumType() == null || !ENUM_TYPE.matcher(d.enumType()).matches()) {
                p.add(new Problem("enumType", "an ENUM field needs the enumeration type that lists its choices"));
            } else if (d.enumValues().isEmpty()) {
                p.add(new Problem("enumType", "enumeration type " + d.enumType() + " has no active values"));
            }
        } else if (d.enumType() != null) {
            p.add(new Problem("enumType", "enumType is for ENUM fields only"));
        }
        if (d.regex() != null) {
            if (d.dataType() != DataType.TEXT) {
                p.add(new Problem("regex", "a pattern is for TEXT fields only"));
            } else if (d.regex().isEmpty() || d.regex().length() > MAX_PATTERN_LENGTH) {
                p.add(new Problem("regex", "the pattern must have 1 to " + MAX_PATTERN_LENGTH + " characters"));
            } else {
                try {
                    Pattern.compile(d.regex());
                } catch (PatternSyntaxException e) {
                    p.add(new Problem("regex", "the pattern is not a valid regular expression"));
                }
            }
        }
        if ((d.min() != null || d.max() != null) && d.dataType() != DataType.TEXT && d.dataType() != DataType.NUMBER) {
            p.add(new Problem("min", "min and max are for TEXT (length) and NUMBER (value) fields only"));
        }
        if (d.min() != null && d.max() != null && d.min().compareTo(d.max()) > 0) p.add(new Problem("min", "min cannot be greater than max"));
        if (d.dataType() == DataType.TEXT) {
            for (BigDecimal b : new BigDecimal[] {d.min(), d.max()}) {
                if (b != null && (b.signum() < 0 || b.stripTrailingZeros().scale() > 0 || b.compareTo(BigDecimal.valueOf(MAX_TEXT_LENGTH)) > 0)) {
                    p.add(new Problem("min", "for TEXT, min and max are lengths: whole numbers from 0 to " + MAX_TEXT_LENGTH));
                    break;
                }
            }
        }
        if (d.pii() && d.dataType() != DataType.TEXT) p.add(new Problem("pii", "only TEXT fields can be flagged as personal data"));
        if (existing != null) {
            if (existing.dataType() != d.dataType() || !java.util.Objects.equals(existing.enumType(), d.enumType())) {
                p.add(new Problem("dataType", "the type of an existing field cannot change; add a new field and deactivate this one"));
            }
            if (existing.pii() != d.pii()) p.add(new Problem("pii", "the personal-data flag of an existing field cannot change"));
        }
        return p;
    }

    // ---------------------------------------------------------------------------------------------- values
    /**
     * Checks the custom values of a new record and returns them in canonical form (numbers as {@link BigDecimal},
     * dates as ISO text, everything else as given). Personal-data fields are still in clear in the result: pass it
     * to {@link #seal} before it is stored or put in an approval request.
     *
     * @param definitions every definition of the entity, active or not
     * @param values      the {@code custom} object of the request; null means none
     * @throws InvalidException listing every problem
     */
    public static Map<String, Object> validate(String entity, List<Definition> definitions, Map<String, ?> values) {
        Map<String, Definition> byKey = new LinkedHashMap<>();
        for (Definition d : definitions) {
            if (d.entity().equals(entity)) byKey.put(d.key(), d);
        }
        List<Problem> problems = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        if (values != null) {
            for (Map.Entry<String, ?> e : values.entrySet()) {
                String key = e.getKey();
                Definition d = byKey.get(key);
                if (d == null) {
                    problems.add(new Problem(key, "custom field \"" + key + "\" is not defined for " + entity));
                } else if (!d.active()) {
                    problems.add(new Problem(key, "custom field \"" + key + "\" is no longer in use"));
                } else if (e.getValue() == null) {
                    problems.add(new Problem(key, "custom field \"" + key + "\": leave the field out instead of sending null"));
                } else {
                    try {
                        out.put(key, canonical(d, e.getValue()));
                    } catch (IllegalArgumentException x) {
                        problems.add(new Problem(key, "custom field \"" + key + "\" " + x.getMessage()));
                    }
                }
            }
        }
        for (Definition d : byKey.values()) {
            if (d.active() && d.required() && (values == null || !values.containsKey(d.key()))) {
                problems.add(new Problem(d.key(), "custom field \"" + d.key() + "\" (" + d.label() + ") is required"));
            }
        }
        if (!problems.isEmpty()) throw new InvalidException(problems);
        return out;
    }

    private static Object canonical(Definition d, Object v) {
        switch (d.dataType()) {
            case TEXT -> {
                if (!(v instanceof String s)) throw new IllegalArgumentException("must be text");
                if (s.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("is longer than " + MAX_TEXT_LENGTH + " characters");
                if (d.min() != null && s.length() < d.min().intValue()) {
                    throw new IllegalArgumentException("must have at least " + d.min().intValue() + " characters");
                }
                if (d.max() != null && s.length() > d.max().intValue()) {
                    throw new IllegalArgumentException("must have at most " + d.max().intValue() + " characters");
                }
                if (d.regex() != null && !Pattern.compile(d.regex()).matcher(s).matches()) {
                    throw new IllegalArgumentException("does not have the expected format");
                }
                return s;
            }
            case NUMBER -> {
                if (!(v instanceof Number n)) throw new IllegalArgumentException("must be a number");
                BigDecimal b;
                try {
                    b = n instanceof BigDecimal x ? x : new BigDecimal(n.toString());
                } catch (NumberFormatException x) {                       // NaN, Infinity
                    throw new IllegalArgumentException("must be a number");
                }
                if (d.min() != null && b.compareTo(d.min()) < 0) throw new IllegalArgumentException("must be at least " + d.min().toPlainString());
                if (d.max() != null && b.compareTo(d.max()) > 0) throw new IllegalArgumentException("must be at most " + d.max().toPlainString());
                return b;
            }
            case BOOLEAN -> {
                if (!(v instanceof Boolean)) throw new IllegalArgumentException("must be true or false");
                return v;
            }
            case DATE -> {
                if (!(v instanceof String s) || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("must be a date as YYYY-MM-DD");
                try {
                    return LocalDate.parse(s).toString();
                } catch (DateTimeParseException x) {
                    throw new IllegalArgumentException("must be a date as YYYY-MM-DD");
                }
            }
            default -> {
                if (!(v instanceof String s) || !d.enumValues().contains(s)) {
                    throw new IllegalArgumentException("must be one of the active values of " + d.enumType());
                }
                return s;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- personal data
    /** The associated data that binds a ciphertext to its field: a value cannot be moved to another field or entity. */
    public static String column(String entity, String key) {
        return "custom." + entity + "." + key;
    }

    /**
     * Replaces the clear value of every personal-data field with {@code {"enc": base64 ciphertext, "mask": mask}}.
     * The result is what the database stores, and what an approval request may carry.
     */
    public static Map<String, Object> seal(String entity, List<Definition> definitions, Map<String, Object> validated, PiiCipher cipher) {
        Map<String, Object> out = new LinkedHashMap<>(validated);
        for (Definition d : definitions) {
            if (!d.entity().equals(entity) || !d.pii() || !out.containsKey(d.key())) continue;
            String clear = (String) out.get(d.key());
            Map<String, Object> sealed = new LinkedHashMap<>();
            sealed.put("enc", Base64.getEncoder().encodeToString(cipher.encrypt(clear, column(entity, d.key()))));
            sealed.put("mask", mask(clear));
            out.put(d.key(), sealed);
        }
        return out;
    }

    /**
     * What the API returns for stored custom values: the mask for a personal-data field, the value itself otherwise.
     * A stored value that has the sealed shape is never returned as it is, whatever the definition says today.
     */
    public static Map<String, Object> view(Map<String, ?> stored) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (stored == null) return out;
        for (Map.Entry<String, ?> e : stored.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) {
                Object mask = m.get("mask");
                out.put(e.getKey(), mask instanceof String s ? s : "****");
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    /** The clear value of a sealed field, for the few places allowed to read it (none in the API today). */
    public static String open(String entity, String key, Object storedValue, PiiCipher cipher) {
        if (!(storedValue instanceof Map<?, ?> m) || !(m.get("enc") instanceof String enc)) {
            throw new IllegalArgumentException("custom field \"" + key + "\" is not sealed");
        }
        return cipher.decrypt(Base64.getDecoder().decode(enc), column(entity, key));
    }

    /** Keeps the last four characters of values of eight or more; shorter values are hidden completely. */
    public static String mask(String clear) {
        if (clear == null) return null;
        return clear.length() >= 8 ? "****" + clear.substring(clear.length() - 4) : "****";
    }
}
