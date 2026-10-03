package com.corebanking.lending.internal;

import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BusinessDays;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Benchmarks of floating-rate products and their rate history (V18). Both a new benchmark and a new rate go
 * through maker-checker. The history is append-only: a rate applies from its effective date until the next one,
 * a new rate is later than every rate on record, and a wrong rate is corrected by a later one, never rewritten.
 * <p>
 * A new rate is used at once for bookings (rate = benchmark + spread on the disbursal date). It does not change
 * a running loan: the loan appears in {@code lending.rate_reset_due} from its next reset date and the reset is a
 * RATE_CHANGE amendment, because the borrower chooses between EMI and tenure (RBI, 18-Aug-2023).
 */
@Service
public class BenchmarkService {

    static final String BENCHMARK = "BENCHMARK";
    static final String RATE = "BENCHMARK_RATE";
    private static final String UTC = "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'";

    /** openapi.yaml#/components/schemas/BenchmarkInput. */
    public record BenchmarkInput(String code, String name, String source, Boolean external) {}

    /** openapi.yaml#/components/schemas/BenchmarkRateInput. */
    public record RateInput(BigDecimal rate, LocalDate effectiveFrom) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final BusinessDays days;

    BenchmarkService(JdbcTemplate jdbc, ApprovalService approvals, BusinessDays days) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.days = days;
    }

    /** Every benchmark with its history (newest first) and the rate in force on the business date. */
    public List<Map<String, Object>> list() {
        BusinessDays.BusinessDay bd = days.current();
        LocalDate today = bd == null ? LocalDate.now() : bd.businessDate();
        Map<String, List<Map<String, Object>>> history = new LinkedHashMap<>();
        jdbc.query("SELECT benchmark_code, effective_from::text, rate::text, recorded_by, to_char(recorded_at AT TIME ZONE 'UTC', " + UTC + "), "
                + "approval_id::text FROM lending.benchmark_rate ORDER BY benchmark_code, effective_from DESC", (RowCallbackHandler) rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("effectiveFrom", rs.getString(2));
                    m.put("rate", rs.getString(3));
                    m.put("recordedBy", rs.getString(4));
                    m.put("recordedAt", rs.getString(5));
                    m.put("approvalId", rs.getString(6));
                    history.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(m);
                });
        return jdbc.query("SELECT code, name, source, external FROM lending.benchmark ORDER BY code", (rs, i) -> {
            List<Map<String, Object>> rates = history.getOrDefault(rs.getString(1), List.of());
            Map<String, Object> current = rates.stream()
                    .filter(r -> !LocalDate.parse((String) r.get("effectiveFrom")).isAfter(today)).findFirst().orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", rs.getString(1));
            m.put("name", rs.getString(2));
            m.put("source", rs.getString(3));
            m.put("external", rs.getBoolean(4));
            m.put("currentRate", current == null ? null : current.get("rate"));
            m.put("currentFrom", current == null ? null : current.get("effectiveFrom"));
            m.put("rates", rates);
            return m;
        });
    }

    @Transactional
    public ApprovalRequest propose(BenchmarkInput in) {
        if (in == null || in.code() == null || !in.code().matches("[A-Z0-9_]{2,20}")) {
            throw ApiException.invalid("code is 2 to 20 capital letters, digits or underscores");
        }
        if (in.name() == null || in.name().isBlank() || in.name().length() > 100) throw ApiException.invalid("name is required (at most 100 characters)");
        if (in.source() == null || in.source().isBlank() || in.source().length() > 100) {
            throw ApiException.invalid("source is required: who publishes the benchmark (at most 100 characters)");
        }
        if (exists(in.code())) throw ApiException.conflict("benchmark " + in.code() + " already exists");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", in.code());
        p.put("name", in.name().trim());
        p.put("source", in.source().trim());
        p.put("external", in.external() == null || in.external());
        return approvals.propose(BENCHMARK, "CREATE", in.code(), p, null, null, null, null);
    }

    @Transactional
    public ApprovalRequest proposeRate(String code, RateInput in) {
        if (!exists(code)) throw ApiException.notFound("benchmark " + code);
        if (in == null || in.rate() == null || in.effectiveFrom() == null) throw ApiException.invalid("rate and effectiveFrom are required");
        checkRate(code, in.rate(), in.effectiveFrom());
        Map<String, Object> current = jdbc.query("""
                SELECT effective_from::text AS "effectiveFrom", rate::text AS rate FROM lending.benchmark_rate
                 WHERE benchmark_code = ? ORDER BY effective_from DESC LIMIT 1
                """, (rs, i) -> Map.<String, Object>of("effectiveFrom", rs.getString(1), "rate", rs.getString(2)), code)
                .stream().findFirst().orElse(null);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", code);
        p.put("rate", in.rate().toPlainString());
        p.put("effectiveFrom", in.effectiveFrom().toString());
        return approvals.propose(RATE, "CREATE", code + "@" + in.effectiveFrom(), p, current, null, null, null);
    }

    private boolean exists(String code) {
        return !jdbc.queryForList("SELECT 1 FROM lending.benchmark WHERE code = ?", code).isEmpty();
    }

    /**
     * Checked when proposed and again when approved: another rate may have been approved in between. Not private:
     * the applier holds this service's lazy proxy, and a private method called on a proxy runs on the proxy itself.
     */
    void checkRate(String code, BigDecimal rate, LocalDate from) {
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0 || rate.scale() > 4) {
            throw ApiException.invalid("rate is a percentage from 0 to 100 with at most four decimals");
        }
        LocalDate last = jdbc.queryForObject("SELECT max(effective_from) FROM lending.benchmark_rate WHERE benchmark_code = ?", LocalDate.class, code);
        if (last != null && !from.isAfter(last)) {
            throw ApiException.conflict("a rate of " + code + " is already recorded from " + last
                    + ": the history is not rewritten, so a new rate takes effect on a later date");
        }
    }

    @Service
    static class BenchmarkApplier implements ApprovalApplier {
        private final JdbcTemplate jdbc;

        BenchmarkApplier(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override public String entityType() { return BENCHMARK; }

        @Override
        public String apply(ApprovalRequest r) {
            Map<String, Object> p = r.payload();
            int n = jdbc.update("INSERT INTO lending.benchmark (code, name, source, external) VALUES (?, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
                    p.get("code"), p.get("name"), p.get("source"), Boolean.TRUE.equals(p.get("external")));
            if (n == 0) throw ApiException.conflict("benchmark " + p.get("code") + " already exists");
            return String.valueOf(p.get("code"));
        }
    }

    @Service
    static class RateApplier implements ApprovalApplier {
        private final JdbcTemplate jdbc;
        private final BenchmarkService benchmarks;

        RateApplier(JdbcTemplate jdbc, @Lazy BenchmarkService benchmarks) {
            this.jdbc = jdbc;
            this.benchmarks = benchmarks;
        }

        @Override public String entityType() { return RATE; }

        @Override
        public String apply(ApprovalRequest r) {
            Map<String, Object> p = r.payload();
            String code = String.valueOf(p.get("code"));
            BigDecimal rate = new BigDecimal(String.valueOf(p.get("rate")));
            LocalDate from = LocalDate.parse(String.valueOf(p.get("effectiveFrom")));
            benchmarks.checkRate(code, rate, from);
            jdbc.update("INSERT INTO lending.benchmark_rate (benchmark_code, effective_from, rate, recorded_by, approval_id) VALUES (?, ?, ?, ?, ?)",
                    code, from, rate, r.maker(), r.id());
            return code + "@" + from;
        }
    }
}
