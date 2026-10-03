package com.corebanking.lending.web;

import com.corebanking.lending.internal.DeferredReceiptService;
import com.corebanking.lending.internal.DeferredReceiptStore;
import com.corebanking.lending.internal.LoanCustomFields;
import com.corebanking.lending.internal.LoanService;
import com.corebanking.platform.ApprovalView;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loan creation and detail with custom fields (US-014), repayments with acceptance during end of day (US-111), and
 * the receipts accepted that way. Module: LENDING.
 */
@RestController
@RequestMapping("/api/v1")
class LoanEntryController {

    record Receipt(BigDecimal amount, LocalDate valueDate, String mode, String reference) {}
    record Note(String note) {}
    record CustomValues(Map<String, Object> custom) {}

    private final LoanService loans;
    private final LoanCustomFields custom;
    private final DeferredReceiptService receipts;
    private final DeferredReceiptStore store;
    private final BranchScope scope;
    private final Json json;

    LoanEntryController(LoanService loans, LoanCustomFields custom, DeferredReceiptService receipts, DeferredReceiptStore store,
                        BranchScope scope, Json json) {
        this.loans = loans;
        this.custom = custom;
        this.receipts = receipts;
        this.store = store;
        this.scope = scope;
        this.json = json;
    }

    /** A loan outside the caller's branch scope is reported as not found (US-020). */
    private UUID visible(UUID id) {
        scope.requireRecord(loans.branchOf(id), "loan " + id);
        return id;
    }

    /** The body is a LoanApplication; its optional {@code custom} object holds the tenant's custom fields. */
    @PostMapping("/loans")
    @PreAuthorize("hasAuthority('loan:create') or hasAuthority('loan:stp')")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> application = new LinkedHashMap<>(body);
        Object values = application.remove("custom");
        if (values != null && !(values instanceof Map<?, ?>)) throw new IllegalArgumentException("custom must be an object");
        LoanService.Application a;
        try {
            a = json.convert(application, LoanService.Application.class);
        } catch (RuntimeException e) {
            // the body is bound here and not by Spring: a wrong type (text for an amount, a bad date) is the caller's error
            throw new IllegalArgumentException("a value in the loan application is not valid");
        }
        return custom.create(a, values == null ? null : json.toMap(values));
    }

    @GetMapping("/loans/{id}")
    @PreAuthorize("hasAuthority('loan:view')")
    Map<String, Object> get(@PathVariable UUID id) {
        return custom.get(visible(id));
    }

    /**
     * 200 with the loan when the repayment was posted; 202 with the receipt when end of day has started and the
     * repayment was accepted for the next business date (straight-through clients only).
     */
    @PostMapping("/loans/{id}/repayments")
    @PreAuthorize("hasAuthority('loan:repay') or hasAuthority('loan:stp')")
    ResponseEntity<Map<String, Object>> repay(@PathVariable UUID id, @RequestBody Receipt r,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        DeferredReceiptService.Outcome o = receipts.repay(visible(id), r.amount(), r.valueDate(), r.mode(), r.reference(), key);
        if (!o.deferred()) return ResponseEntity.ok(o.loan());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ACCEPTED_FOR_NEXT_BUSINESS_DATE");
        body.put("receipt", o.receipt());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/loans/{id}/deferred-receipts")
    @PreAuthorize("hasAuthority('loan:view')")
    List<Map<String, Object>> receiptsOfLoan(@PathVariable UUID id) {
        return store.ofLoan(visible(id));
    }

    @GetMapping("/deferred-receipts")
    @PreAuthorize("hasAuthority('loan:view')")
    List<Map<String, Object>> receipts(@RequestParam(required = false) String status) {
        return store.list(scope.user(), status);
    }

    @PostMapping("/deferred-receipts/{id}/retry")
    @PreAuthorize("hasAuthority('loan:admin')")
    Map<String, Object> retry(@PathVariable UUID id) {
        scope.requireRecord(store.branchOf(id), "receipt " + id);
        return store.retry(id);
    }

    @PostMapping("/deferred-receipts/{id}/cancel")
    @PreAuthorize("hasAuthority('loan:admin')")
    Map<String, Object> cancel(@PathVariable UUID id, @RequestBody Note body) {
        scope.requireRecord(store.branchOf(id), "receipt " + id);
        return store.cancel(id, body == null ? null : body.note());
    }

    // ---- loan product custom values ------------------------------------------------------------
    @GetMapping("/loan-products/{code}/custom")
    @PreAuthorize("hasAuthority('product:view')")
    Map<String, Object> productCustom(@PathVariable String code) {
        return custom.productCustom(code);
    }

    @PutMapping("/loan-products/{code}/custom")
    @PreAuthorize("hasAuthority('product:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeProductCustom(@PathVariable String code, @RequestBody CustomValues body) {
        return ApprovalView.of(custom.proposeProductCustom(code, body == null ? null : body.custom()));
    }
}
