package com.corebanking.deposits.web;

import com.corebanking.deposits.internal.DepositMoneyService;
import com.corebanking.deposits.internal.DepositProductService;
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
 * Money on deposit accounts (P4-2): transactions, transfers, reversals, freezes and liens on savings and current
 * accounts (CASA module); funding, liens and the movements of term deposits (TD module). Every change answers 202 with
 * the approval request, which is already APPROVED when the tenant's rules let it post at once. Interest (P4-3) is read-only here:
 * the day-end accrues and credits it.
 */
@RestController
@RequestMapping("/api/v1")
class DepositMoneyController {

    private static final String CASA = DepositProductService.CASA;
    private static final String TD = DepositProductService.TERM_DEPOSIT;
    private static final String KEY = "Idempotency-Key";

    private final DepositMoneyService money;

    DepositMoneyController(DepositMoneyService money) {
        this.money = money;
    }

    // ---- savings and current accounts ------------------------------------------------------------------------------
    @PostMapping("/casa/accounts/{id}/transactions")
    @PreAuthorize("hasAuthority('casa:transact')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> transaction(@PathVariable UUID id, @RequestBody DepositMoneyService.TransactionInput in,
                                    @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(money.proposeTransaction(id, in, key));
    }

    @PostMapping("/casa/transfers")
    @PreAuthorize("hasAuthority('casa:transact')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> transfer(@RequestBody DepositMoneyService.TransferInput in, @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(money.proposeTransfer(in, key));
    }

    @PostMapping("/casa/movements/{movementId}/reverse")
    @PreAuthorize("hasAuthority('casa:transact')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> reverse(@PathVariable UUID movementId, @RequestBody DepositMoneyService.ReasonInput in,
                                @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(money.proposeReversal(movementId, in, key));
    }

    @GetMapping("/casa/accounts/{id}/movements")
    @PreAuthorize("hasAuthority('casa:view')")
    List<Map<String, Object>> casaMovements(@PathVariable UUID id,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return money.movements(CASA, id, from, to);
    }

    @GetMapping("/casa/accounts/{id}/interest")
    @PreAuthorize("hasAuthority('casa:view')")
    Map<String, Object> casaInterest(@PathVariable UUID id) {
        return money.interestView(CASA, id);
    }

    @GetMapping("/deposits/accounts/{id}/interest")
    @PreAuthorize("hasAuthority('deposit:view')")
    Map<String, Object> depositInterest(@PathVariable UUID id) {
        return money.interestView(TD, id);
    }

    @GetMapping("/casa/accounts/{id}/holds")
    @PreAuthorize("hasAuthority('casa:view')")
    List<Map<String, Object>> casaHolds(@PathVariable UUID id) {
        return money.holdList(CASA, id);
    }

    @PostMapping("/casa/accounts/{id}/holds")
    @PreAuthorize("hasAuthority('casa:hold')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> placeCasaHold(@PathVariable UUID id, @RequestBody DepositMoneyService.HoldInput in,
                                      @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(money.proposeHold(CASA, id, in, key));
    }

    @PostMapping("/casa/holds/{holdId}/release")
    @PreAuthorize("hasAuthority('casa:hold')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> releaseCasaHold(@PathVariable UUID holdId, @RequestBody DepositMoneyService.ReasonInput in,
                                        @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(money.proposeRelease(CASA, holdId, in, key));
    }

    // ---- term deposits -------------------------------------------------------------------------------------------
    @PostMapping("/deposits/accounts/{id}/fund")
    @PreAuthorize("hasAuthority('deposit:fund')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> fund(@PathVariable UUID id, @RequestBody DepositMoneyService.FundingInput in,
                             @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(money.proposeFunding(id, in, key));
    }

    @GetMapping("/deposits/accounts/{id}/instalments")
    @PreAuthorize("hasAuthority('deposit:view')")
    Map<String, Object> instalments(@PathVariable UUID id) {
        return money.instalments(id);
    }

    @PostMapping("/deposits/accounts/{id}/instalments")
    @PreAuthorize("hasAuthority('deposit:fund')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> payInstalment(@PathVariable UUID id, @RequestBody DepositMoneyService.InstalmentInput in,
                                      @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(money.proposeInstalment(id, in, key));
    }

    @PostMapping("/deposits/accounts/{id}/funding/reverse")
    @PreAuthorize("hasAuthority('deposit:fund')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> reverseFunding(@PathVariable UUID id, @RequestBody DepositMoneyService.ReasonInput in,
                                       @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(money.proposeFundingReversal(id, in, key));
    }

    @GetMapping("/deposits/accounts/{id}/movements")
    @PreAuthorize("hasAuthority('deposit:view')")
    List<Map<String, Object>> depositMovements(@PathVariable UUID id) {
        return money.movements(TD, id, null, null);
    }

    @GetMapping("/deposits/accounts/{id}/holds")
    @PreAuthorize("hasAuthority('deposit:view')")
    List<Map<String, Object>> depositHolds(@PathVariable UUID id) {
        return money.holdList(TD, id);
    }

    @PostMapping("/deposits/accounts/{id}/holds")
    @PreAuthorize("hasAuthority('deposit:hold')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> placeLien(@PathVariable UUID id, @RequestBody DepositMoneyService.HoldInput in,
                                  @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(money.proposeHold(TD, id, in, key));
    }

    @PostMapping("/deposits/holds/{holdId}/release")
    @PreAuthorize("hasAuthority('deposit:hold')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> releaseLien(@PathVariable UUID holdId, @RequestBody DepositMoneyService.ReasonInput in,
                                    @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(money.proposeRelease(TD, holdId, in, key));
    }
}
