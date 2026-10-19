package com.corebanking.deposits.web;

import com.corebanking.deposits.internal.DepositMoneyService;
import com.corebanking.deposits.internal.DepositPledges;
import com.corebanking.deposits.internal.StandingInstructions;
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
 * Standing instructions on savings and current accounts and loans against term deposits (P4-5b). A standing
 * instruction is given and cancelled through a checker; the job STANDING_INSTRUCTIONS carries it out. A deposit is
 * pledged for a loan through a checker and released once the loan is closed.
 */
@RestController
@RequestMapping("/api/v1")
class StandingInstructionController {

    private static final String KEY = "Idempotency-Key";

    private final StandingInstructions instructions;
    private final DepositPledges pledges;

    StandingInstructionController(StandingInstructions instructions, DepositPledges pledges) {
        this.instructions = instructions;
        this.pledges = pledges;
    }

    @GetMapping("/casa/standing-instructions")
    @PreAuthorize("hasAuthority('casa:view')")
    List<Map<String, Object>> list(@RequestParam(required = false) UUID accountId, @RequestParam(required = false) UUID loanId) {
        return instructions.list(accountId, loanId);
    }

    @GetMapping("/casa/standing-instructions/{id}")
    @PreAuthorize("hasAuthority('casa:view')")
    Map<String, Object> get(@PathVariable UUID id) {
        return instructions.get(id);
    }

    @PostMapping("/casa/standing-instructions")
    @PreAuthorize("hasAuthority('casa:transact')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> propose(@RequestBody StandingInstructions.InstructionInput in, @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(instructions.propose(in, key));
    }

    @PostMapping("/casa/standing-instructions/{id}/cancel")
    @PreAuthorize("hasAuthority('casa:transact')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> cancel(@PathVariable UUID id, @RequestBody DepositMoneyService.ReasonInput in,
                               @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(instructions.proposeCancel(id, in, key));
    }

    @GetMapping("/deposits/accounts/{id}/pledge-quote")
    @PreAuthorize("hasAuthority('deposit:view')")
    Map<String, Object> pledgeQuote(@PathVariable UUID id, @RequestParam UUID loanId) {
        return pledges.quote(id, loanId);
    }

    @PostMapping("/deposits/accounts/{id}/pledge")
    @PreAuthorize("hasAuthority('deposit:hold')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> pledge(@PathVariable UUID id, @RequestBody DepositPledges.PledgeInput in, @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(pledges.propose(id, in, key));
    }

    @GetMapping("/deposits/pledges")
    @PreAuthorize("hasAuthority('deposit:view')")
    List<Map<String, Object>> pledgesForLoan(@RequestParam UUID loanId) {
        return pledges.forLoan(loanId);
    }
}
