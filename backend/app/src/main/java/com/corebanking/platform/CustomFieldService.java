package com.corebanking.platform;

import com.corebanking.kernel.CustomFields;
import com.corebanking.kernel.CustomFields.DataType;
import com.corebanking.kernel.CustomFields.Definition;
import com.corebanking.kernel.PiiCipher;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom fields on customers, loan accounts and loan products (US-014). Definitions change through maker-checker
 * (entity CUSTOM_FIELD); a definition is deactivated, never deleted, and its type and personal-data flag are fixed
 * once created. The rules are the pure {@link CustomFields}; the database checks stored values again
 * ({@code platform.validate_custom}, V19).
 * <p>
 * Modules that own an entity call {@link #prepare} before they store or propose a record and {@link #view} when
 * they return one. Personal-data fields are sealed with the tenant's PII cipher by {@code prepare} and come back
 * masked from {@code view}; the clear value is not returned by any API.
 */
@Service
public class CustomFieldService {

    /** openapi.yaml#/components/schemas/CustomFieldInput. */
    public record Input(String entity, String key, String label, String dataType, String enumType, String widget, Boolean required,
                        String regex, BigDecimal min, BigDecimal max, Boolean pii, Boolean active, Integer sortOrder) {}

    private final JdbcTemplate jdbc;
    private final Json json;
    private final ApprovalService approvals;

    public CustomFieldService(JdbcTemplate jdbc, Json json, ApprovalService approvals) {
        this.jdbc = jdbc;
        this.json = json;
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------------------------------------ definitions
    /** Every definition of the entity, active or not, with the active codes of its enumeration type. */
    public List<Definition> definitions(String entity) {
        return jdbc.query("""
                SELECT f.entity, f.key, f.label, f.data_type, f.enum_type, f.required, f.regex, f.min_value, f.max_value, f.pii, f.active,
                       (SELECT string_agg(e.code, ',') FROM platform.enumeration e WHERE e.enum_type = f.enum_type AND e.active) AS codes
                  FROM platform.custom_field f
                 WHERE f.entity = ?
                 ORDER BY f.sort_order, f.key
                """, (rs, i) -> new Definition(rs.getString(1), rs.getString(2), rs.getString(3), DataType.valueOf(rs.getString(4)),
                        rs.getString(5), rs.getString(12) == null ? Set.of() : Set.of(rs.getString(12).split(",")), rs.getBoolean(6),
                        rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBoolean(10), rs.getBoolean(11)), entity);
    }

    /** openapi.yaml#/components/schemas/CustomField, for one entity or (entity null) for all. */
    public List<Map<String, Object>> list(String entity) {
        if (entity != null && !CustomFields.ENTITIES.contains(entity)) throw ApiException.invalid("entity must be one of " + sorted(CustomFields.ENTITIES));
        return jdbc.query("""
                SELECT entity, key, label, data_type, enum_type, widget, required, regex, min_value, max_value, pii, active, sort_order,
                       updated_by, to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                  FROM platform.custom_field
                 WHERE (?::text IS NULL OR entity = ?)
                 ORDER BY entity, sort_order, key
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("entity", rs.getString(1));
                    m.put("key", rs.getString(2));
                    m.put("label", rs.getString(3));
                    m.put("dataType", rs.getString(4));
                    m.put("enumType", rs.getString(5));
                    m.put("widget", rs.getString(6) == null ? defaultWidget(rs.getString(4)) : rs.getString(6));
                    m.put("required", rs.getBoolean(7));
                    m.put("regex", rs.getString(8));
                    m.put("min", plain(rs.getBigDecimal(9)));
                    m.put("max", plain(rs.getBigDecimal(10)));
                    m.put("pii", rs.getBoolean(11));
                    m.put("active", rs.getBoolean(12));
                    m.put("sortOrder", rs.getInt(13));
                    m.put("updatedBy", rs.getString(14));
                    m.put("updatedAt", rs.getString(15));
                    return m;
                }, entity, entity);
    }

    /** Proposes a new definition or a change to one (maker-checker). */
    @Transactional
    public ApprovalRequest propose(Input in) {
        DataType type;
        try {
            type = in.dataType() == null ? null : DataType.valueOf(in.dataType());
        } catch (IllegalArgumentException e) {
            type = null;
        }
        if (in.widget() != null && !CustomFields.WIDGETS.contains(in.widget())) {
            throw ApiException.invalid("widget must be one of " + sorted(CustomFields.WIDGETS));
        }
        Set<String> codes = in.enumType() == null ? Set.of() : Set.copyOf(jdbc.queryForList(
                "SELECT code FROM platform.enumeration WHERE enum_type = ? AND active", String.class, in.enumType()));
        Definition proposed = new Definition(in.entity(), in.key(), in.label(), type, in.enumType(), codes, Boolean.TRUE.equals(in.required()),
                in.regex(), in.min(), in.max(), Boolean.TRUE.equals(in.pii()), !Boolean.FALSE.equals(in.active()));
        Definition existing = in.entity() == null || in.key() == null ? null
                : definitions(in.entity()).stream().filter(d -> d.key().equals(in.key())).findFirst().orElse(null);
        List<CustomFields.Problem> problems = CustomFields.checkDefinition(proposed, existing);
        if (!problems.isEmpty()) throw invalid("the custom field definition is not valid", "", problems);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entity", in.entity());
        payload.put("key", in.key());
        payload.put("label", in.label().trim());
        payload.put("dataType", type.name());
        payload.put("enumType", in.enumType());
        payload.put("widget", in.widget());
        payload.put("required", proposed.required());
        payload.put("regex", in.regex());
        payload.put("min", plain(in.min()));
        payload.put("max", plain(in.max()));
        payload.put("pii", proposed.pii());
        payload.put("active", proposed.active());
        payload.put("sortOrder", in.sortOrder() == null ? 0 : in.sortOrder());
        Map<String, Object> current = existing == null ? null
                : list(in.entity()).stream().filter(m -> in.key().equals(m.get("key"))).findFirst().orElse(null);
        return approvals.propose("CUSTOM_FIELD", existing == null ? "CREATE" : "UPDATE", in.entity() + "." + in.key(), payload, current,
                null, null, null);
    }

    // ------------------------------------------------------------------------------------------------ values
    /**
     * Checks the {@code custom} object of a request and returns it as it is stored: canonical values, with
     * personal-data fields sealed. Also reports a missing required field, so call it even when the request has no
     * custom values.
     *
     * @param cipher asked for only when a personal-data field has a value
     * @throws ApiException 422 with {@code errors[{field, message}]}; fields are named {@code custom.<key>}
     */
    public Map<String, Object> prepare(String entity, Map<String, Object> custom, Supplier<PiiCipher> cipher) {
        List<Definition> definitions = definitions(entity);
        if (definitions.isEmpty() && (custom == null || custom.isEmpty())) return Map.of();
        Map<String, Object> validated;
        try {
            validated = CustomFields.validate(entity, definitions, custom);
        } catch (CustomFields.InvalidException e) {
            throw invalid(e.problems().size() == 1 ? e.problems().get(0).message() : "custom fields are not valid", "custom.", e.problems());
        }
        boolean personal = definitions.stream().anyMatch(d -> d.pii() && validated.containsKey(d.key()));
        return personal ? CustomFields.seal(entity, definitions, validated, cipher.get()) : validated;
    }

    /** The {@code custom} object returned by the API for a stored value: personal-data fields masked. */
    public Map<String, Object> view(String storedJson) {
        return CustomFields.view(storedJson == null ? null : json.readMap(storedJson));
    }

    private static ApiException invalid(String detail, String prefix, List<CustomFields.Problem> problems) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, detail, Map.of("errors",
                problems.stream().map(p -> Map.of("field", prefix + p.field(), "message", p.message())).toList()));
    }

    private static String defaultWidget(String dataType) {
        return switch (dataType) {
            case "NUMBER" -> "NUMBER";
            case "DATE" -> "DATE";
            case "BOOLEAN" -> "CHECKBOX";
            case "ENUM" -> "SELECT";
            default -> "TEXT";
        };
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static String plain(BigDecimal v) {
        if (v == null) return null;
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    /** Applies an approved definition. The database refuses a change of type or of the personal-data flag. */
    @Service
    static class Applier implements ApprovalApplier {
        private final JdbcTemplate jdbc;

        Applier(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override public String entityType() { return "CUSTOM_FIELD"; }

        @Override
        public String apply(ApprovalRequest r) {
            Map<String, Object> p = r.payload();
            jdbc.update("""
                    INSERT INTO platform.custom_field (entity, key, label, data_type, enum_type, widget, required, regex, min_value, max_value,
                                                       pii, active, sort_order, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (entity, key) DO UPDATE SET label = EXCLUDED.label, data_type = EXCLUDED.data_type,
                        enum_type = EXCLUDED.enum_type, widget = EXCLUDED.widget, required = EXCLUDED.required, regex = EXCLUDED.regex,
                        min_value = EXCLUDED.min_value, max_value = EXCLUDED.max_value, pii = EXCLUDED.pii, active = EXCLUDED.active,
                        sort_order = EXCLUDED.sort_order, updated_by = EXCLUDED.updated_by
                    """, p.get("entity"), p.get("key"), p.get("label"), p.get("dataType"), p.get("enumType"), p.get("widget"),
                    Boolean.TRUE.equals(p.get("required")), p.get("regex"), decimal(p.get("min")), decimal(p.get("max")),
                    Boolean.TRUE.equals(p.get("pii")), !Boolean.FALSE.equals(p.get("active")),
                    p.get("sortOrder") instanceof Number n ? n.intValue() : 0, r.maker());
            return p.get("entity") + "." + p.get("key");
        }

        private static BigDecimal decimal(Object v) {
            return v == null ? null : new BigDecimal(String.valueOf(v));
        }
    }
}
