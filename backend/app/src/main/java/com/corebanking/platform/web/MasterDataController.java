package com.corebanking.platform.web;

import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.Json;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** Branches, holidays, tax rates and enumerations (US-010, US-011, US-013, US-017). */
@RestController
@RequestMapping("/api/v1")
class MasterDataController {

    record Branch(@NotBlank @Pattern(regexp = "^[A-Z0-9]{2,10}$") String code, @NotBlank String name, String ifsc,
                  @NotBlank String stateCode, String parentCode, Boolean headOffice, String status) {}
    record Holiday(String branchCode, @NotNull LocalDate day, @NotBlank String reason) {}
    record TaxRate(@NotBlank String code, @NotBlank @Pattern(regexp = "GST|TDS") String taxType,
                   @NotBlank @Pattern(regexp = "^[0-9]{1,3}(\\.[0-9]{1,4})?$") String ratePercent, @NotNull LocalDate effectiveFrom, LocalDate effectiveTo) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final Json json;

    MasterDataController(JdbcTemplate jdbc, ApprovalService approvals, Json json) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.json = json;
    }

    // ---- branches ----------------------------------------------------------------------------
    @GetMapping("/branches")
    @PreAuthorize("hasAuthority('branch:view')")
    List<Branch> branches() {
        return jdbc.query("SELECT code, name, ifsc, state_code, parent_code, is_head_office, status FROM platform.branch ORDER BY code",
                (rs, i) -> new Branch(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), rs.getString(7)));
    }

    @PostMapping("/branches")
    @PreAuthorize("hasAuthority('branch:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeBranch(@Valid @RequestBody Branch b,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        List<Branch> existing = branches().stream().filter(x -> x.code().equals(b.code())).toList();
        Branch normalised = new Branch(b.code(), b.name(), b.ifsc(), b.stateCode(), b.parentCode(),
                Boolean.TRUE.equals(b.headOffice()), b.status() == null ? "ACTIVE" : b.status());
        return ApprovalController.view(approvals.propose("BRANCH", existing.isEmpty() ? "CREATE" : "UPDATE", b.code(),
                json.toMap(normalised), existing.isEmpty() ? null : json.toMap(existing.get(0)), null, null, key));
    }

    // ---- holidays ----------------------------------------------------------------------------
    @GetMapping("/holidays")
    @PreAuthorize("hasAuthority('holiday:view')")
    List<Holiday> holidays(@RequestParam int year, @RequestParam(required = false) String branch) {
        return jdbc.query("""
                SELECT branch_code, day, reason FROM platform.holiday
                 WHERE extract(year FROM day) = ? AND (?::text IS NULL OR branch_code IS NULL OR branch_code = ?)
                 ORDER BY day
                """, (rs, i) -> new Holiday(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getString(3)),
                year, branch, branch);
    }

    @PostMapping("/holidays")
    @PreAuthorize("hasAuthority('holiday:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeHolidays(@Valid @RequestBody List<@Valid Holiday> holidays) {
        if (holidays.isEmpty() || holidays.size() > 366) throw ApiException.invalid("send 1 to 366 holidays");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("holidays", holidays.stream().map(json::toMap).toList());
        return ApprovalController.view(approvals.propose("HOLIDAY", "CREATE", null, payload, null, null, null, null));
    }

    // ---- tax rates ---------------------------------------------------------------------------
    @GetMapping("/tax-rates")
    @PreAuthorize("hasAuthority('tax:view')")
    List<TaxRate> taxRates(@RequestParam(required = false) LocalDate asOf) {
        return jdbc.query("""
                SELECT code, tax_type, rate_percent::text, effective_from, effective_to FROM platform.tax_rate
                 WHERE ?::date IS NULL OR (effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?))
                 ORDER BY code, effective_from
                """, (rs, i) -> new TaxRate(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class)), asOf, asOf, asOf);
    }

    @PostMapping("/tax-rates")
    @PreAuthorize("hasAuthority('tax:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeTaxRate(@Valid @RequestBody TaxRate t) {
        if (new BigDecimal(t.ratePercent()).compareTo(BigDecimal.valueOf(100)) > 0) throw ApiException.invalid("rate cannot exceed 100%");
        return ApprovalController.view(approvals.propose("TAX_RATE", "CREATE", t.code(), json.toMap(t), null, null, null, null));
    }

    // ---- enumerations ------------------------------------------------------------------------
    @GetMapping("/enumerations/{type}")
    List<Map<String, Object>> enumeration(@PathVariable String type) {
        return jdbc.query("SELECT code, label, active FROM platform.enumeration WHERE enum_type = ? ORDER BY sort_order, label",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString(1));
                    m.put("label", rs.getString(2));
                    m.put("active", rs.getBoolean(3));
                    return m;
                }, type);
    }
}
