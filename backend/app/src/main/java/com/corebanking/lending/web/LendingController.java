package com.corebanking.lending.web;

import com.corebanking.lending.internal.LoanService;
import com.corebanking.lending.internal.ProductService;
import com.corebanking.platform.ApprovalView;
import com.corebanking.platform.BranchScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Loan products and loan accounts (Phase 2). Module: LENDING. */
@RestController
@RequestMapping("/api/v1")
class LendingController {

    record KfsAcceptance(String channel, String evidenceRef) {}
    record Receipt(BigDecimal amount, LocalDate valueDate, String mode, String reference) {}
    record Prepayment(BigDecimal amount, String mode) {}
    record Amount(BigDecimal amount) {}
    record ChargeRequest(String feeCode, BigDecimal base) {}
    record Waiver(BigDecimal amount, String reason) {}
    record Reason(String reason) {}

    private final ProductService products;
    private final LoanService loans;
    private final BranchScope scope;

    LendingController(ProductService products, LoanService loans, BranchScope scope) {
        this.products = products;
        this.loans = loans;
        this.scope = scope;
    }

    /** A loan outside the caller's branch scope is reported as not found (US-020). */
    private UUID visible(UUID id) {
        scope.requireRecord(loans.branchOf(id), "loan " + id);
        return id;
    }

    // ---- products ----------------------------------------------------------------------------
    @GetMapping("/loan-products")
    @PreAuthorize("hasAuthority('product:view')")
    List<ProductService.Product> products() {
        return products.list();
    }

    @GetMapping("/loan-products/{code}")
    @PreAuthorize("hasAuthority('product:view')")
    ProductService.Product product(@PathVariable String code) {
        return products.get(code);
    }

    @PostMapping("/loan-products")
    @PreAuthorize("hasAuthority('product:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeProduct(@RequestBody ProductService.Product p) {
        return ApprovalView.of(products.propose(p));
    }

    // ---- origination -------------------------------------------------------------------------
    @PostMapping("/loans/preview")
    @PreAuthorize("hasAuthority('loan:view')")
    Map<String, Object> preview(@RequestBody LoanService.Application a) {
        return loans.preview(a);
    }

    @PostMapping("/loans")
    @PreAuthorize("hasAuthority('loan:create') or hasAuthority('loan:stp')")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@RequestBody LoanService.Application a) {
        return loans.create(a);
    }

    @GetMapping("/loans")
    @PreAuthorize("hasAuthority('loan:view')")
    List<Map<String, Object>> search(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return loans.search(q, status, page, size);
    }

    @GetMapping("/loans/{id}")
    @PreAuthorize("hasAuthority('loan:view')")
    Map<String, Object> get(@PathVariable UUID id) {
        return loans.get(visible(id));
    }

    @GetMapping("/loans/{id}/schedule")
    @PreAuthorize("hasAuthority('loan:view')")
    Map<String, Object> schedule(@PathVariable UUID id) {
        return loans.schedule(visible(id));
    }

    @GetMapping("/loans/{id}/transactions")
    @PreAuthorize("hasAuthority('loan:view')")
    List<Map<String, Object>> transactions(@PathVariable UUID id) {
        return loans.transactions(visible(id));
    }

    @GetMapping("/loans/{id}/kfs")
    @PreAuthorize("hasAuthority('loan:view')")
    Map<String, Object> kfs(@PathVariable UUID id) {
        return loans.kfsOf(visible(id));
    }

    @PostMapping("/loans/{id}/kfs-acceptance")
    @PreAuthorize("hasAuthority('loan:create') or hasAuthority('loan:stp')")
    Map<String, Object> acceptKfs(@PathVariable UUID id, @RequestBody KfsAcceptance k) {
        return loans.acceptKfs(visible(id), k.channel(), k.evidenceRef());
    }

    /** 202 with an approval for staff; 200 with the disbursed loan for LOS clients (loan:stp). */
    @PostMapping("/loans/{id}/disbursement")
    @PreAuthorize("hasAuthority('loan:disburse') or hasAuthority('loan:stp')")
    ResponseEntity<Map<String, Object>> disburse(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> instruction) {
        Map<String, Object> r = loans.requestDisbursement(visible(id), instruction);
        return ResponseEntity.status(r.containsKey("entityType") ? HttpStatus.ACCEPTED : HttpStatus.OK).body(r);
    }

    // ---- servicing ---------------------------------------------------------------------------
    @PostMapping("/loans/{id}/repayments")
    @PreAuthorize("hasAuthority('loan:repay') or hasAuthority('loan:stp')")
    Map<String, Object> repay(@PathVariable UUID id, @RequestBody Receipt r) {
        return loans.repay(visible(id), r.amount(), r.valueDate(), r.mode(), r.reference());
    }

    @PostMapping("/loans/{id}/prepayments")
    @PreAuthorize("hasAuthority('loan:repay')")
    Map<String, Object> prepay(@PathVariable UUID id, @RequestBody Prepayment p) {
        return loans.prepay(visible(id), p.amount(), p.mode());
    }

    @GetMapping("/loans/{id}/preclosure-quote")
    @PreAuthorize("hasAuthority('loan:view')")
    Map<String, Object> preclosureQuote(@PathVariable UUID id) {
        return loans.preclosureQuote(visible(id));
    }

    @PostMapping("/loans/{id}/preclosure")
    @PreAuthorize("hasAuthority('loan:repay')")
    Map<String, Object> preclose(@PathVariable UUID id, @RequestBody Amount a) {
        return loans.preclose(visible(id), a.amount());
    }

    @GetMapping("/loans/{id}/cancellation-quote")
    @PreAuthorize("hasAuthority('loan:view')")
    Map<String, Object> cancellationQuote(@PathVariable UUID id) {
        return loans.cancellationQuote(visible(id));
    }

    @PostMapping("/loans/{id}/cancellation")
    @PreAuthorize("hasAuthority('loan:repay')")
    Map<String, Object> cancel(@PathVariable UUID id, @RequestBody Amount a) {
        return loans.cancel(visible(id), a.amount());
    }

    @PostMapping("/loans/{id}/charges")
    @PreAuthorize("hasAuthority('loan:repay')")
    Map<String, Object> charge(@PathVariable UUID id, @RequestBody ChargeRequest c) {
        return loans.chargeFee(visible(id), c.feeCode(), c.base());
    }

    @PostMapping("/loans/{id}/charges/{chargeId}/waiver")
    @PreAuthorize("hasAuthority('loan:waive')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> waive(@PathVariable UUID id, @PathVariable String chargeId, @RequestBody Waiver w) {
        return loans.proposeWaiver(visible(id), chargeId, w.amount(), w.reason());
    }

    @PostMapping("/loans/{id}/transactions/{txnId}/reverse")
    @PreAuthorize("hasAuthority('loan:reverse')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> reverse(@PathVariable UUID id, @PathVariable UUID txnId, @RequestBody Reason r) {
        return loans.proposeReversal(visible(id), txnId, r.reason());
    }

    @PostMapping("/loans/{id}/freeze")
    @PreAuthorize("hasAuthority('loan:admin')")
    Map<String, Object> freeze(@PathVariable UUID id, @RequestBody Reason r) {
        return loans.setFrozen(visible(id), true, r.reason());
    }

    @PostMapping("/loans/{id}/unfreeze")
    @PreAuthorize("hasAuthority('loan:admin')")
    Map<String, Object> unfreeze(@PathVariable UUID id, @RequestBody Reason r) {
        return loans.setFrozen(visible(id), false, r.reason());
    }
}
