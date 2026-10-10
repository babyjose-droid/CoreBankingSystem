package com.corebanking.lending.internal;

import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BusinessDays;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interest-rate slab tables (V12, V25): the rate of a product by loan amount and tenor band. A table is proposed whole
 * (code, mode, rows) through maker-checker; an existing code is replaced on approval (rows deleted and re-inserted in
 * one transaction). Loans already booked keep the rate they were booked with; a replaced SPREAD table is used for
 * their next reset.
 * <p>
 * Modes: {@code ABSOLUTE} (a fixed table: the slab's rate is the loan rate; {@code FIXED} is accepted as an alias),
 * {@code ADDITIVE} (base rate + slab) and {@code SPREAD} (the slab is a spread over the product's benchmark).
 */
@Service
public class InterestTableService {

    static final String ENTITY = "INTEREST_TABLE";
    static final Set<String> MODES = Set.of("ABSOLUTE", "ADDITIVE", "SPREAD");
    static final int MAX_ROWS = 200;
    static final BigDecimal MAX_RATE = BigDecimal.valueOf(60);       // an annual rate above 60% is a typing mistake
    static final BigDecimal MAX_SPREAD = BigDecimal.valueOf(30);
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");
    static final int MAX_TENOR = 600;

    /** openapi.yaml#/components/schemas/InterestTableRowInput. */
    public record RowInput(BigDecimal minAmount, BigDecimal maxAmount, Integer minTenorMonths, Integer maxTenorMonths, BigDecimal rate) {}

    /** openapi.yaml#/components/schemas/InterestTableInput. */
    public record TableInput(String code, String name, String mode, BigDecimal baseRate, LocalDate effectiveFrom, List<RowInput> rows) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final BusinessDays days;

    InterestTableService(JdbcTemplate jdbc, ApprovalService approvals, BusinessDays days) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.days = days;
    }

    /** Every table with its rows (by amount, then tenor) and the products that use it. */
    public List<Map<String, Object>> list() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        jdbc.query("SELECT table_code, min_amount::text, max_amount::text, min_tenor_months, max_tenor_months, rate::text "
                + "FROM lending.interest_slab ORDER BY table_code, min_amount, min_tenor_months", rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("minAmount", rs.getString(2));
                    m.put("maxAmount", rs.getString(3));
                    m.put("minTenorMonths", rs.getInt(4));
                    m.put("maxTenorMonths", rs.getInt(5));
                    m.put("rate", rs.getString(6));
                    rows.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(m);
                });
        Map<String, List<String>> used = new LinkedHashMap<>();
        jdbc.query("SELECT interest_table_code, code FROM lending.loan_product WHERE interest_table_code IS NOT NULL ORDER BY code",
                rs -> { used.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2)); });
        return jdbc.query("SELECT code, name, mode, base_rate::text, effective_from::text FROM lending.interest_table ORDER BY code", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", rs.getString(1));
            m.put("name", rs.getString(2));
            m.put("mode", rs.getString(3));
            m.put("baseRate", rs.getString(4));
            m.put("effectiveFrom", rs.getString(5));
            m.put("rows", rows.getOrDefault(rs.getString(1), List.of()));
            m.put("usedByProducts", used.getOrDefault(rs.getString(1), List.of()));
            return m;
        });
    }

    @Transactional
    public ApprovalRequest propose(TableInput in) {
        Map<String, Object> p = normalise(in);
        if (p.get("effectiveFrom") == null) {
            BusinessDays.BusinessDay bd = days.current();
            p.put("effectiveFrom", (bd == null ? LocalDate.now() : bd.businessDate()).toString());
        }
        checkAgainstProducts(String.valueOf(p.get("code")), String.valueOf(p.get("mode")));
        Map<String, Object> current = current(String.valueOf(p.get("code")));
        return approvals.propose(ENTITY, current == null ? "CREATE" : "UPDATE", String.valueOf(p.get("code")), p, current, null, null, null);
    }

    /** Checked when proposed and again when approved (a product may have been attached in between). */
    void checkAgainstProducts(String code, String mode) {
        // a table in use cannot become (or stop being) a SPREAD table: its products' benchmark link would no longer fit
        List<String> modeNow = jdbc.queryForList("SELECT mode FROM lending.interest_table WHERE code = ?", String.class, code);
        if (modeNow.isEmpty() || modeNow.get(0).equals(mode)) return;
        if ("SPREAD".equals(modeNow.get(0)) != "SPREAD".equals(mode)) {
            List<String> products = jdbc.queryForList("SELECT code FROM lending.loan_product WHERE interest_table_code = ? ORDER BY code", String.class, code);
            if (!products.isEmpty()) {
                throw ApiException.invalid("table " + code + " is used by product(s) " + String.join(", ", products)
                        + ": a table cannot change to or from SPREAD while a product uses it (a SPREAD table goes only with a benchmark-linked product)");
            }
        }
    }

    private Map<String, Object> current(String code) {
        return list().stream().filter(t -> code.equals(t.get("code"))).findFirst().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>(t);
            m.remove("usedByProducts");
            return m;
        }).orElse(null);
    }

    /** Validates the input and returns the payload that is stored on the approval request (numbers as plain strings). */
    static Map<String, Object> normalise(TableInput in) {
        if (in == null || in.code() == null || !in.code().matches("[A-Z0-9_]{2,20}")) {
            throw ApiException.invalid("code is 2 to 20 capital letters, digits or underscores");
        }
        if (in.name() == null || in.name().isBlank() || in.name().length() > 100) throw ApiException.invalid("name is required (at most 100 characters)");
        String mode = in.mode() == null ? "" : in.mode().trim().toUpperCase(java.util.Locale.ROOT);
        if ("FIXED".equals(mode)) mode = "ABSOLUTE";
        if (!MODES.contains(mode)) throw ApiException.invalid("mode is FIXED (ABSOLUTE), ADDITIVE or SPREAD");
        BigDecimal base = in.baseRate() == null ? BigDecimal.ZERO : in.baseRate();
        if (!"ADDITIVE".equals(mode) && base.signum() != 0) throw ApiException.invalid("baseRate is used by ADDITIVE tables only; leave it out (0) for " + mode);
        checkRate("baseRate", base, MAX_RATE);
        List<RowInput> rows = in.rows() == null ? List.of() : in.rows();
        if (rows.isEmpty()) throw ApiException.invalid("at least one row (amount band, tenor band and rate) is required");
        if (rows.size() > MAX_ROWS) throw ApiException.invalid("at most " + MAX_ROWS + " rows");
        BigDecimal cap = "SPREAD".equals(mode) ? MAX_SPREAD : MAX_RATE;
        List<RowInput> sorted = new ArrayList<>();
        int n = 0;
        for (RowInput r : rows) {
            n++;
            if (r == null || r.minAmount() == null || r.maxAmount() == null || r.minTenorMonths() == null || r.maxTenorMonths() == null || r.rate() == null) {
                throw ApiException.invalid("row " + n + ": minAmount, maxAmount, minTenorMonths, maxTenorMonths and rate are required");
            }
            if (r.minAmount().signum() < 0 || r.maxAmount().compareTo(r.minAmount()) < 0 || r.maxAmount().compareTo(MAX_AMOUNT) > 0
                    || r.minAmount().scale() > 2 || r.maxAmount().scale() > 2) {
                throw ApiException.invalid("row " + n + ": amounts are from 0, maxAmount is not below minAmount, with at most two decimals");
            }
            if (r.minTenorMonths() < 1 || r.maxTenorMonths() < r.minTenorMonths() || r.maxTenorMonths() > MAX_TENOR) {
                throw ApiException.invalid("row " + n + ": tenor is 1 to " + MAX_TENOR + " months and maxTenorMonths is not below minTenorMonths");
            }
            checkRate("row " + n + ": rate", r.rate(), cap);
            sorted.add(r);
        }
        sorted.sort(Comparator.comparing(RowInput::minAmount).thenComparing(RowInput::minTenorMonths));
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                RowInput a = sorted.get(i), b = sorted.get(j);
                boolean amount = a.minAmount().compareTo(b.maxAmount()) <= 0 && b.minAmount().compareTo(a.maxAmount()) <= 0;
                boolean tenor = a.minTenorMonths() <= b.maxTenorMonths() && b.minTenorMonths() <= a.maxTenorMonths();
                if (amount && tenor) {
                    throw ApiException.invalid("bands overlap: " + a.minAmount().toPlainString() + "-" + a.maxAmount().toPlainString() + " / "
                            + a.minTenorMonths() + "-" + a.maxTenorMonths() + " months and " + b.minAmount().toPlainString() + "-"
                            + b.maxAmount().toPlainString() + " / " + b.minTenorMonths() + "-" + b.maxTenorMonths() + " months (bands include both ends)");
                }
            }
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", in.code());
        p.put("name", in.name().trim());
        p.put("mode", mode);
        p.put("baseRate", base.toPlainString());
        p.put("effectiveFrom", in.effectiveFrom() == null ? null : in.effectiveFrom().toString());
        List<Map<String, Object>> out = new ArrayList<>();
        for (RowInput r : sorted) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("minAmount", r.minAmount().toPlainString());
            m.put("maxAmount", r.maxAmount().toPlainString());
            m.put("minTenorMonths", r.minTenorMonths());
            m.put("maxTenorMonths", r.maxTenorMonths());
            m.put("rate", r.rate().toPlainString());
            out.add(m);
        }
        p.put("rows", out);
        return p;
    }

    private static void checkRate(String what, BigDecimal v, BigDecimal max) {
        if (v.signum() < 0 || v.compareTo(max) > 0 || v.scale() > 4) {
            throw ApiException.invalid(what + " is a percentage from 0 to " + max.toPlainString() + " with at most four decimals");
        }
    }

    @Service
    static class Applier implements ApprovalApplier {
        private final JdbcTemplate jdbc;
        private final InterestTableService tables;

        Applier(JdbcTemplate jdbc, @Lazy InterestTableService tables) {
            this.jdbc = jdbc;
            this.tables = tables;
        }

        @Override public String entityType() { return ENTITY; }

        @Override
        @SuppressWarnings("unchecked")
        public String apply(ApprovalRequest r) {
            Map<String, Object> p = r.payload();
            String code = String.valueOf(p.get("code"));
            String mode = String.valueOf(p.get("mode"));
            tables.checkAgainstProducts(code, mode);
            boolean exists = !jdbc.queryForList("SELECT 1 FROM lending.interest_table WHERE code = ?", code).isEmpty();
            if ("CREATE".equals(r.action()) && exists) throw ApiException.conflict("interest table " + code + " already exists");
            if (!"CREATE".equals(r.action()) && !exists) throw ApiException.conflict("interest table " + code + " no longer exists");
            if (exists) {
                // rows first, so the exclusion constraint never sees old and new bands together; a SPREAD <-> other change
                // of an unused table needs the base-rate check order below
                jdbc.update("DELETE FROM lending.interest_slab WHERE table_code = ?", code);
                jdbc.update("UPDATE lending.interest_table SET name = ?, mode = ?, base_rate = ?, effective_from = ? WHERE code = ?",
                        p.get("name"), mode, new BigDecimal(String.valueOf(p.get("baseRate"))), LocalDate.parse(String.valueOf(p.get("effectiveFrom"))), code);
            } else {
                jdbc.update("INSERT INTO lending.interest_table (code, name, base_rate, mode, effective_from) VALUES (?, ?, ?, ?, ?)",
                        code, p.get("name"), new BigDecimal(String.valueOf(p.get("baseRate"))), mode, LocalDate.parse(String.valueOf(p.get("effectiveFrom"))));
            }
            for (Map<String, Object> row : (List<Map<String, Object>>) p.get("rows")) {
                jdbc.update("INSERT INTO lending.interest_slab (table_code, min_amount, max_amount, min_tenor_months, max_tenor_months, rate) VALUES (?, ?, ?, ?, ?, ?)",
                        code, new BigDecimal(String.valueOf(row.get("minAmount"))), new BigDecimal(String.valueOf(row.get("maxAmount"))),
                        ((Number) row.get("minTenorMonths")).intValue(), ((Number) row.get("maxTenorMonths")).intValue(), new BigDecimal(String.valueOf(row.get("rate"))));
            }
            return code;
        }
    }
}
