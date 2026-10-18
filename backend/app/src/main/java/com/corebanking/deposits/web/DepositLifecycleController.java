package com.corebanking.deposits.web;

import com.corebanking.deposits.internal.DepositLifecycleService;
import com.corebanking.platform.ApprovalView;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
 * The life of deposit accounts (P4-4): charges and closure of savings and current accounts (CASA module, permission
 * {@code casa:transact}); closure, maturity instruction and renewal of term deposits and the maturities list (TD module,
 * {@code deposit:fund} and {@code deposit:view}). Every change answers 202 with the approval request; a quote is read-only.
 */
@RestController
@RequestMapping("/api/v1")
class DepositLifecycleController {

    private static final String KEY = "Idempotency-Key";

    private final DepositLifecycleService lifecycle;

    DepositLifecycleController(DepositLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    // ---- savings and current accounts ------------------------------------------------------------------------------
    @GetMapping("/casa/accounts/{id}/charges")
    @PreAuthorize("hasAuthority('casa:view')")
    List<Map<String, Object>> charges(@PathVariable UUID id) {
        return lifecycle.charges(id);
    }

    @PostMapping("/casa/accounts/{id}/charges")
    @PreAuthorize("hasAuthority('casa:transact')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> charge(@PathVariable UUID id, @RequestBody DepositLifecycleService.ChargeInput in,
                               @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(lifecycle.proposeCharge(id, in, key));
    }

    @GetMapping("/casa/accounts/{id}/minimum-balance")
    @PreAuthorize("hasAuthority('casa:view')")
    List<Map<String, Object>> minimumBalance(@PathVariable UUID id) {
        return lifecycle.minimumBalance(id);
    }

    @GetMapping("/casa/accounts/{id}/closure-quote")
    @PreAuthorize("hasAuthority('casa:view')")
    Map<String, Object> casaClosureQuote(@PathVariable UUID id) {
        return lifecycle.casaClosureQuote(id);
    }

    @PostMapping("/casa/accounts/{id}/close")
    @PreAuthorize("hasAuthority('casa:transact')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> closeCasa(@PathVariable UUID id, @RequestBody DepositLifecycleService.CasaClosureInput in,
                                  @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(lifecycle.proposeCasaClosure(id, in, key));
    }

    // ---- term deposits -------------------------------------------------------------------------------------------
    @GetMapping("/deposits/accounts/{id}/closure-quote")
    @PreAuthorize("hasAuthority('deposit:view')")
    Map<String, Object> depositClosureQuote(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        return lifecycle.depositClosureQuote(id, reason);
    }

    @PostMapping("/deposits/accounts/{id}/close")
    @PreAuthorize("hasAuthority('deposit:fund')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> closeDeposit(@PathVariable UUID id, @RequestBody DepositLifecycleService.DepositClosureInput in,
                                     @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(lifecycle.proposeDepositClosure(id, in, key));
    }

    @PostMapping("/deposits/accounts/{id}/maturity-instruction")
    @PreAuthorize("hasAuthority('deposit:fund')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> instruction(@PathVariable UUID id, @RequestBody DepositLifecycleService.InstructionInput in,
                                    @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(lifecycle.proposeInstruction(id, in, key));
    }

    @PostMapping("/deposits/accounts/{id}/renew")
    @PreAuthorize("hasAuthority('deposit:fund')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> renew(@PathVariable UUID id, @RequestBody DepositLifecycleService.RenewalInput in,
                              @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(lifecycle.proposeRenewal(id, in, key));
    }

    @GetMapping("/deposits/maturities")
    @PreAuthorize("hasAuthority('deposit:view')")
    List<Map<String, Object>> maturities(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return lifecycle.maturities(from, to);
    }
}
