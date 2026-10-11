package com.corebanking.deposits.internal;

import com.corebanking.deposits.DepositRules;
import com.corebanking.platform.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The deposit rule set in force for this tenant (tenant V28: deposits.rule, chosen by the legal entity's type).
 * Read from the database on every use, so a rule ended or added takes effect at once.
 */
@Service
public class DepositRuleSets {

    private final JdbcTemplate jdbc;

    DepositRuleSets(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** BANK or NBFC_DEPOSIT; null for an institution that takes no deposits here. */
    String kind() {
        return jdbc.queryForObject("SELECT deposits.rule_kind()", String.class);
    }

    /** The rules in force on the business date, for the engine. 409 when this institution takes no deposits. */
    public DepositRules current() {
        String kind = kind();
        if (kind == null) throw ApiException.conflict(whyNone());
        Map<String, BigDecimal> values = new HashMap<>();
        jdbc.query("SELECT code, value FROM deposits.rules_in_force()", rs -> {
            values.put(rs.getString(1), rs.getBigDecimal(2));
        });
        return new DepositRules(DepositRules.Kind.valueOf(kind), values);
    }

    private String whyNone() {
        List<Map<String, Object>> entity = jdbc.queryForList("SELECT entity_type FROM platform.legal_entity WHERE id = 1");
        if (entity.isEmpty()) return "the legal entity is not set up";
        String type = String.valueOf(entity.get(0).get("entity_type"));
        return "NBFC".equals(type)
                ? "this NBFC is not recorded as registered by RBI to accept public deposits"
                : "deposits are not supported for an institution of type " + type;
    }

    /** The rule set as shown to staff: every rule in force with its source and whether the text was verified. */
    public Map<String, Object> view() {
        String kind = kind();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> entity = jdbc.queryForList(
                "SELECT entity_type, deposit_taking, deposit_registration_ref FROM platform.legal_entity WHERE id = 1");
        out.put("kind", kind);
        out.put("entityType", entity.isEmpty() ? null : entity.get(0).get("entity_type"));
        out.put("depositTaking", entity.isEmpty() ? null : entity.get(0).get("deposit_taking"));
        out.put("depositRegistrationRef", entity.isEmpty() ? null : entity.get(0).get("deposit_registration_ref"));
        out.put("asOf", jdbc.queryForObject("SELECT business_date FROM platform.business_day WHERE id = 1", LocalDate.class));
        out.put("reason", kind == null ? whyNone() : null);
        List<Map<String, Object>> rules = kind == null ? List.of() : jdbc.query("""
                SELECT code, value::text, effective_from, source, verified, note FROM deposits.rules_in_force() ORDER BY code
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString(1));
                    m.put("value", plain(rs.getString(2)));
                    m.put("effectiveFrom", rs.getObject(3, LocalDate.class));
                    m.put("source", rs.getString(4));
                    m.put("verified", rs.getBoolean(5));
                    m.put("note", rs.getString(6));
                    return m;
                });
        out.put("rules", rules);
        out.put("unverified", rules.stream().filter(r -> !Boolean.TRUE.equals(r.get("verified"))).count());
        return out;
    }

    /** A stored number without its trailing zeros: "12.5000" is shown as "12.5". */
    static String plain(String number) {
        if (number == null) return null;
        BigDecimal v = new BigDecimal(number);
        return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }

    static String plain(BigDecimal v) {
        return v == null ? null : plain(v.toPlainString());
    }
}
