package com.corebanking.deposits.web;

import com.corebanking.deposits.internal.DepositMoneyService;
import com.corebanking.deposits.internal.DepositTaxService;
import com.corebanking.platform.ApprovalView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

/**
 * Tax deducted at source on deposit interest (P4-3b, TD module): Form 121 declarations through maker-checker, and a
 * depositor's tax record for a tax year. The day-end deducts the tax.
 */
@RestController
@RequestMapping("/api/v1/deposits/tax")
class DepositTaxController {

    private final DepositTaxService tax;

    DepositTaxController(DepositTaxService tax) {
        this.tax = tax;
    }

    @GetMapping("/declarations")
    @PreAuthorize("hasAuthority('deposit:view')")
    List<Map<String, Object>> declarations(@RequestParam UUID customerId) {
        return tax.declarations(customerId);
    }

    @PostMapping("/declarations")
    @PreAuthorize("hasAuthority('deposit:tax')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> declare(@RequestBody DepositTaxService.DeclarationInput in, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApprovalView.of(tax.proposeDeclaration(in, key));
    }

    @PostMapping("/declarations/{id}/revoke")
    @PreAuthorize("hasAuthority('deposit:tax')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> revoke(@PathVariable UUID id, @RequestBody DepositMoneyService.ReasonInput in,
                               @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApprovalView.of(tax.proposeRevoke(id, in, key));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('deposit:view')")
    Map<String, Object> summary(@RequestParam UUID customerId, @RequestParam(required = false) Integer taxYear) {
        return tax.summary(customerId, taxYear);
    }
}
