package com.corebanking.deposits.web;

import com.corebanking.deposits.internal.DepositAccountService;
import com.corebanking.deposits.internal.DepositProductService;
import com.corebanking.deposits.internal.DepositRuleSets;
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
 * Deposits (P4-1). Paths under /api/v1/casa need the CASA module (savings and current accounts: banks only), paths
 * under /api/v1/deposits the TD module (term deposits: banks and deposit-taking NBFCs); the tenant filter answers
 * 403 otherwise. Products use the product permissions; accounts have their own. Every change goes through
 * maker-checker and answers 202 with the approval request.
 */
@RestController
@RequestMapping("/api/v1")
class DepositController {

    private static final String CASA = DepositProductService.CASA;
    private static final String TD = DepositProductService.TERM_DEPOSIT;

    record Reason(String reason) {}

    private final DepositRuleSets rules;
    private final DepositProductService products;
    private final DepositAccountService accounts;

    DepositController(DepositRuleSets rules, DepositProductService products, DepositAccountService accounts) {
        this.rules = rules;
        this.products = products;
        this.accounts = accounts;
    }

    // ---- the rule set in force (the same under both modules) ----------------------------------------------------
    @GetMapping({"/casa/rules", "/deposits/rules"})
    @PreAuthorize("hasAnyAuthority('product:view', 'casa:view', 'deposit:view')")
    Map<String, Object> rules() {
        return rules.view();
    }

    // ---- savings and current products ---------------------------------------------------------------------------
    @GetMapping("/casa/products")
    @PreAuthorize("hasAnyAuthority('product:view', 'casa:view')")
    List<DepositProductService.Product> casaProducts() {
        return products.list(CASA);
    }

    @GetMapping("/casa/products/{code}")
    @PreAuthorize("hasAnyAuthority('product:view', 'casa:view')")
    DepositProductService.Product casaProduct(@PathVariable String code) {
        return products.get(CASA, code);
    }

    @GetMapping("/casa/products/{code}/history")
    @PreAuthorize("hasAuthority('product:view')")
    List<Map<String, Object>> casaProductHistory(@PathVariable String code) {
        return products.history(CASA, code);
    }

    @PostMapping("/casa/products")
    @PreAuthorize("hasAuthority('product:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeCasaProduct(@RequestBody DepositProductService.ProductInput in) {
        return ApprovalView.of(products.propose(CASA, in));
    }

    @PostMapping("/casa/products/check")
    @PreAuthorize("hasAuthority('product:propose')")
    Map<String, Object> checkCasaProduct(@RequestBody DepositProductService.ProductInput in) {
        return Map.of("problems", products.check(CASA, in));
    }

    @PostMapping("/casa/products/{code}/withdraw")
    @PreAuthorize("hasAuthority('product:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> withdrawCasaProduct(@PathVariable String code, @RequestBody Reason body) {
        return ApprovalView.of(products.proposeWithdrawal(CASA, code, body == null ? null : body.reason()));
    }

    // ---- term deposit products ------------------------------------------------------------------------------------
    @GetMapping("/deposits/products")
    @PreAuthorize("hasAnyAuthority('product:view', 'deposit:view')")
    List<DepositProductService.Product> termProducts() {
        return products.list(TD);
    }

    @GetMapping("/deposits/products/{code}")
    @PreAuthorize("hasAnyAuthority('product:view', 'deposit:view')")
    DepositProductService.Product termProduct(@PathVariable String code) {
        return products.get(TD, code);
    }

    @GetMapping("/deposits/products/{code}/history")
    @PreAuthorize("hasAuthority('product:view')")
    List<Map<String, Object>> termProductHistory(@PathVariable String code) {
        return products.history(TD, code);
    }

    @PostMapping("/deposits/products")
    @PreAuthorize("hasAuthority('product:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> proposeTermProduct(@RequestBody DepositProductService.ProductInput in) {
        return ApprovalView.of(products.propose(TD, in));
    }

    @PostMapping("/deposits/products/check")
    @PreAuthorize("hasAuthority('product:propose')")
    Map<String, Object> checkTermProduct(@RequestBody DepositProductService.ProductInput in) {
        return Map.of("problems", products.check(TD, in));
    }

    @PostMapping("/deposits/products/{code}/withdraw")
    @PreAuthorize("hasAuthority('product:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> withdrawTermProduct(@PathVariable String code, @RequestBody Reason body) {
        return ApprovalView.of(products.proposeWithdrawal(TD, code, body == null ? null : body.reason()));
    }

    // ---- savings and current accounts -----------------------------------------------------------------------------
    @GetMapping("/casa/accounts")
    @PreAuthorize("hasAuthority('casa:view')")
    List<Map<String, Object>> casaAccounts(@RequestParam(required = false) UUID customerId, @RequestParam(required = false) String status) {
        return accounts.list(CASA, customerId, status);
    }

    @GetMapping("/casa/accounts/{id}")
    @PreAuthorize("hasAuthority('casa:view')")
    Map<String, Object> casaAccount(@PathVariable UUID id) {
        return accounts.get(CASA, id);
    }

    @PostMapping("/casa/accounts")
    @PreAuthorize("hasAuthority('casa:open')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> openCasaAccount(@RequestBody DepositAccountService.AccountInput in,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApprovalView.of(accounts.proposeCasa(in, key));
    }

    // ---- term deposits --------------------------------------------------------------------------------------------
    @GetMapping("/deposits/accounts")
    @PreAuthorize("hasAuthority('deposit:view')")
    List<Map<String, Object>> termDeposits(@RequestParam(required = false) UUID customerId, @RequestParam(required = false) String status) {
        return accounts.list(TD, customerId, status);
    }

    @GetMapping("/deposits/accounts/{id}")
    @PreAuthorize("hasAuthority('deposit:view')")
    Map<String, Object> termDeposit(@PathVariable UUID id) {
        return accounts.get(TD, id);
    }

    /** The figures of a deposit before it is booked; nothing is stored. */
    @PostMapping("/deposits/preview")
    @PreAuthorize("hasAnyAuthority('deposit:view', 'deposit:book')")
    Map<String, Object> preview(@RequestBody DepositAccountService.AccountInput in) {
        return accounts.preview(in);
    }

    @PostMapping("/deposits/accounts")
    @PreAuthorize("hasAuthority('deposit:book')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> bookTermDeposit(@RequestBody DepositAccountService.AccountInput in,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApprovalView.of(accounts.proposeTermDeposit(in, key));
    }
}
