package com.corebanking.ledger.web;

import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.ApprovalView;
import com.corebanking.platform.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Chart of accounts, vouchers and financial statements (US-100, US-103, US-104). Module: GL. */
@RestController
@RequestMapping("/api/v1/gl")
class GlController {

    record GlHead(String code, String name, String category, String parentCode, Boolean posting, String status) {}
    record Reason(String note) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final VoucherService vouchers;
    private final Json json;
    private final BranchScope scope;

    GlController(JdbcTemplate jdbc, ApprovalService approvals, VoucherService vouchers, Json json, BranchScope scope) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.vouchers = vouchers;
        this.json = json;
        this.scope = scope;
    }

    /** A named branch must be in scope; no branch means the consolidated view, which needs all-branch access. */
    private void branchOrAll(String branch) {
        if (branch == null || branch.isBlank()) scope.requireAll();
        else scope.require(branch);
    }

    // ---- chart of accounts -------------------------------------------------------------------
    @GetMapping("/heads")
    @PreAuthorize("hasAuthority('gl:view')")
    List<GlHead> heads() {
        return jdbc.query("SELECT code, name, category, parent_code, is_posting, status FROM ledger.gl_head ORDER BY code",
                (rs, i) -> new GlHead(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), rs.getString(6)));
    }

    @PostMapping("/heads")
    @PreAuthorize("hasAuthority('gl:head-propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeHead(@RequestBody GlHead h) {
        if (h.code() == null || !h.code().matches("[0-9A-Z]{2,12}")) throw new IllegalArgumentException("code must be 2-12 digits/capitals");
        if (h.name() == null || h.name().isBlank()) throw new IllegalArgumentException("name is required");
        if (h.category() == null || !h.category().matches("ASSET|LIABILITY|EQUITY|INCOME|EXPENSE")) {
            throw new IllegalArgumentException("category must be ASSET, LIABILITY, EQUITY, INCOME or EXPENSE");
        }
        List<GlHead> existing = heads().stream().filter(x -> x.code().equals(h.code())).toList();
        GlHead normalised = new GlHead(h.code(), h.name(), h.category(), h.parentCode(), Boolean.TRUE.equals(h.posting()),
                h.status() == null ? "ACTIVE" : h.status());
        return view(approvals.propose("GL_HEAD", existing.isEmpty() ? "CREATE" : "UPDATE", h.code(), json.toMap(normalised),
                existing.isEmpty() ? null : json.toMap(existing.get(0)), null, null, null));
    }

    // ---- vouchers ----------------------------------------------------------------------------
    @GetMapping("/vouchers")
    @PreAuthorize("hasAuthority('gl:view')")
    List<Map<String, Object>> listVouchers(@RequestParam(required = false) LocalDate from,
                                           @RequestParam(required = false) LocalDate to) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, voucher_no, voucher_type, value_date, business_date, reference, description, amount,
                       status, lot_id, reversal_lot_id, maker, checker
                  FROM ledger.voucher v
                 WHERE (?::date IS NULL OR business_date >= ?) AND (?::date IS NULL OR business_date <= ?)
                   AND EXISTS (SELECT 1 FROM ledger.account_entry e WHERE e.lot_id = v.lot_id
                                  AND e.branch_code IN (SELECT branch_code FROM platform.visible_branches(?)))
                 ORDER BY business_date DESC, voucher_no DESC LIMIT 500
                """, from, from, to, to, scope.user());
        return rows.stream().map(this::voucherView).toList();
    }

    private Map<String, Object> voucherView(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("voucherNo", r.get("voucher_no"));
        m.put("voucherType", r.get("voucher_type"));
        m.put("valueDate", String.valueOf(r.get("value_date")));
        m.put("businessDate", String.valueOf(r.get("business_date")));
        m.put("reference", r.get("reference"));
        m.put("description", r.get("description"));
        m.put("amount", money(r.get("amount")));
        m.put("status", r.get("status"));
        m.put("lotId", r.get("lot_id"));
        m.put("reversalLotId", r.get("reversal_lot_id"));
        m.put("lines", jdbc.queryForList("""
                SELECT branch_code AS branch, gl_code AS "glCode", account_no AS account, side, amount::text AS amount, narration
                  FROM ledger.account_entry WHERE lot_id = ? ORDER BY id
                """, r.get("lot_id")));
        return m;
    }

    @PostMapping("/vouchers")
    @PreAuthorize("hasAuthority('voucher:create')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeVoucher(@RequestBody VoucherService.Input in,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return view(vouchers.propose(in, key));
    }

    /** CSV voucher upload (US-103). 202 with one approval per voucher; all or nothing. */
    @PostMapping(path = "/vouchers/upload", consumes = {"text/csv", "text/plain"})
    @PreAuthorize("hasAuthority('voucher:create')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> uploadVouchers(@RequestBody String csv,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        List<Map<String, Object>> approvals = vouchers.proposeUpload(csv, key).stream().map(GlController::view).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vouchers", approvals.size());
        m.put("approvals", approvals);
        return m;
    }

    @PostMapping("/vouchers/{id}/reverse")
    @PreAuthorize("hasAuthority('voucher:reverse')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> reverse(@PathVariable UUID id, @RequestBody Reason reason) {
        return view(vouchers.proposeReversal(id, reason.note()));
    }

    // ---- statements --------------------------------------------------------------------------
    @GetMapping("/trial-balance")
    @PreAuthorize("hasAuthority('gl:view')")
    List<Map<String, Object>> trialBalance(@RequestParam LocalDate asOf, @RequestParam(required = false) String branch) {
        branchOrAll(branch);
        return jdbc.queryForList("""
                SELECT gl_code AS "glCode", gl_name AS "glName", category, debit::text AS debit, credit::text AS credit,
                       net::text AS net
                  FROM ledger.trial_balance(?, ?)
                """, asOf, branch);
    }

    @GetMapping("/entries")
    @PreAuthorize("hasAuthority('gl:view')")
    List<Map<String, Object>> entries(@RequestParam String glCode, @RequestParam LocalDate from, @RequestParam LocalDate to,
                                      @RequestParam(required = false) String branch) {
        branchOrAll(branch);
        return jdbc.queryForList("""
                SELECT lot_id AS "lotId", business_date::text AS "businessDate", branch_code AS branch, gl_code AS "glCode",
                       account_no AS account, side, amount::text AS amount, narration, lot_type AS "lotType"
                  FROM ledger.gl_entries(?, ?, ?, ?)
                """, glCode, from, to, branch);
    }

    @GetMapping("/profit-and-loss")
    @PreAuthorize("hasAuthority('gl:view')")
    List<Map<String, Object>> profitAndLoss(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        scope.requireAll();
        return jdbc.queryForList("""
                SELECT section, gl_code AS "glCode", gl_name AS "glName", amount::text AS amount
                  FROM ledger.profit_and_loss(?, ?)
                """, from, to);
    }

    @GetMapping("/balance-sheet")
    @PreAuthorize("hasAuthority('gl:view')")
    List<Map<String, Object>> balanceSheet(@RequestParam LocalDate asOf) {
        scope.requireAll();
        return jdbc.queryForList("""
                SELECT section, gl_code AS "glCode", gl_name AS "glName", amount::text AS amount
                  FROM ledger.balance_sheet(?)
                """, asOf);
    }

    static Map<String, Object> view(ApprovalRequest r) {
        return ApprovalView.of(r);
    }

    private static String money(Object o) {
        return o == null ? null : ((BigDecimal) o).toPlainString();
    }

    /** Applies approved GL head changes. */
    @Configuration(proxyBeanMethods = false)
    static class HeadApplierConfig {
        @Bean
        ApprovalApplier glHeadApplier(JdbcTemplate jdbc) {
            return new ApprovalApplier() {
                @Override public String entityType() { return "GL_HEAD"; }
                @Override public String apply(ApprovalRequest r) {
                    Map<String, Object> p = r.payload();
                    jdbc.update("""
                            INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting, status)
                            VALUES (?, ?, ?, ?, ?, ?)
                            ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, category = EXCLUDED.category,
                                parent_code = EXCLUDED.parent_code, is_posting = EXCLUDED.is_posting, status = EXCLUDED.status
                            """, p.get("code"), p.get("name"), p.get("category"), p.get("parentCode"),
                            Boolean.TRUE.equals(p.get("posting")), p.getOrDefault("status", "ACTIVE"));
                    return (String) p.get("code");
                }
            };
        }
    }
}
