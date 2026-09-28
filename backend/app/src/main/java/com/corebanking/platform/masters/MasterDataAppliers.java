package com.corebanking.platform.masters;

import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Applies approved branch, holiday and tax-rate changes. */
@Configuration(proxyBeanMethods = false)
class MasterDataAppliers {

    @Bean
    ApprovalApplier branchApplier(JdbcTemplate jdbc) {
        return new ApprovalApplier() {
            @Override public String entityType() { return "BRANCH"; }
            @Override public String apply(ApprovalRequest r) {
                Map<String, Object> p = r.payload();
                jdbc.update("""
                        INSERT INTO platform.branch (code, name, ifsc, state_code, parent_code, is_head_office, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, ifsc = EXCLUDED.ifsc,
                            state_code = EXCLUDED.state_code, parent_code = EXCLUDED.parent_code,
                            is_head_office = EXCLUDED.is_head_office, status = EXCLUDED.status
                        """, p.get("code"), p.get("name"), p.get("ifsc"), p.get("stateCode"), p.get("parentCode"),
                        Boolean.TRUE.equals(p.get("headOffice")), p.getOrDefault("status", "ACTIVE"));
                return (String) p.get("code");
            }
        };
    }

    @Bean
    ApprovalApplier holidayApplier(JdbcTemplate jdbc) {
        return new ApprovalApplier() {
            @Override public String entityType() { return "HOLIDAY"; }
            @Override public String apply(ApprovalRequest r) {
                List<?> list = (List<?>) r.payload().get("holidays");
                for (Object o : list) {
                    Map<?, ?> h = (Map<?, ?>) o;
                    jdbc.update("""
                            INSERT INTO platform.holiday (branch_code, day, reason) VALUES (?, ?::date, ?)
                            ON CONFLICT (branch_code, day) DO UPDATE SET reason = EXCLUDED.reason
                            """, h.get("branchCode"), String.valueOf(h.get("day")), h.get("reason"));
                }
                return list.size() + " holidays";
            }
        };
    }

    /**
     * A new open-ended rate for a code closes the previous open-ended period the day before, so periods never
     * overlap (the database also forbids overlaps).
     */
    @Bean
    ApprovalApplier taxRateApplier(JdbcTemplate jdbc) {
        return new ApprovalApplier() {
            @Override public String entityType() { return "TAX_RATE"; }
            @Override public String apply(ApprovalRequest r) {
                Map<String, Object> p = r.payload();
                LocalDate from = LocalDate.parse(String.valueOf(p.get("effectiveFrom")));
                Object to = p.get("effectiveTo");
                jdbc.update("""
                        UPDATE platform.tax_rate SET effective_to = ?::date - 1
                         WHERE code = ? AND effective_to IS NULL AND effective_from < ?::date
                        """, from.toString(), p.get("code"), from.toString());
                jdbc.update("""
                        INSERT INTO platform.tax_rate (code, tax_type, rate_percent, effective_from, effective_to)
                        VALUES (?, ?, ?, ?::date, ?::date)
                        """, p.get("code"), p.get("taxType"), new BigDecimal(String.valueOf(p.get("ratePercent"))),
                        from.toString(), to == null ? null : String.valueOf(to));
                return p.get("code") + "@" + from;
            }
        };
    }
}
