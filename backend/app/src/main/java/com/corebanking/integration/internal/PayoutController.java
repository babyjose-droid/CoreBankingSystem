package com.corebanking.integration.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Payout beneficiaries and payout instructions (US-051). Account numbers are returned masked. */
@RestController
@RequestMapping("/api/v1")
class PayoutController {

    private final PayoutService payouts;

    PayoutController(PayoutService payouts) {
        this.payouts = payouts;
    }

    @PutMapping("/loans/{id}/payout-beneficiary")
    @PreAuthorize("hasAuthority('payout:beneficiary')")
    Map<String, Object> setBeneficiary(@PathVariable UUID id, @RequestBody PayoutService.BeneficiaryInput in) {
        return payouts.setBeneficiary(id, in);
    }

    @GetMapping("/loans/{id}/payout-beneficiary")
    @PreAuthorize("hasAuthority('payout:view')")
    Map<String, Object> beneficiary(@PathVariable UUID id) {
        return payouts.beneficiary(id);
    }

    /** Reconciliation query. */
    @GetMapping("/payouts")
    @PreAuthorize("hasAuthority('payout:view')")
    List<Map<String, Object>> search(@RequestParam(required = false) String status, @RequestParam(required = false) Boolean needsAction,
                                     @RequestParam(required = false) UUID loanId, @RequestParam(required = false) LocalDate from,
                                     @RequestParam(required = false) LocalDate to) {
        return payouts.search(status, needsAction, loanId, from, to);
    }

    @GetMapping("/payouts/{id}")
    @PreAuthorize("hasAuthority('payout:view')")
    Map<String, Object> get(@PathVariable UUID id) {
        return payouts.one(id);
    }

    @PostMapping("/payouts/{id}/retry")
    @PreAuthorize("hasAuthority('payout:admin')")
    Map<String, Object> retry(@PathVariable UUID id) {
        return payouts.retry(id);
    }

    @PostMapping("/payouts/{id}/refresh")
    @PreAuthorize("hasAuthority('payout:admin')")
    Map<String, Object> refresh(@PathVariable UUID id) {
        return payouts.refresh(id);
    }
}
