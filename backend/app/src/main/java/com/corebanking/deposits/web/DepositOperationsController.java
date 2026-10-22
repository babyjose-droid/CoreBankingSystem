package com.corebanking.deposits.web;

import com.corebanking.deposits.internal.DepositCheques;
import com.corebanking.deposits.internal.DepositDormancy;
import com.corebanking.deposits.internal.DepositMoneyService;
import com.corebanking.deposits.internal.DepositRegister;
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
 * Cheques and the clearing simulator, inoperative and unclaimed accounts, the register of deposits and a deposit-taking
 * NBFC's net owned fund (P4-6).
 */
@RestController
@RequestMapping("/api/v1")
class DepositOperationsController {

    private static final String KEY = "Idempotency-Key";

    private final DepositCheques cheques;
    private final DepositDormancy dormancy;
    private final DepositRegister register;

    DepositOperationsController(DepositCheques cheques, DepositDormancy dormancy, DepositRegister register) {
        this.cheques = cheques;
        this.dormancy = dormancy;
        this.register = register;
    }

    // ---- cheques --------------------------------------------------------------------------------------------------
    @GetMapping("/casa/accounts/{id}/cheques")
    @PreAuthorize("hasAuthority('casa:view')")
    Map<String, Object> cheques(@PathVariable UUID id) {
        return cheques.cheques(id);
    }

    @PostMapping("/casa/accounts/{id}/cheque-books")
    @PreAuthorize("hasAuthority('casa:transact')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> issueBook(@PathVariable UUID id, @RequestBody(required = false) DepositCheques.BookInput in,
                                  @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(cheques.proposeBook(id, in, key));
    }

    @PostMapping("/casa/accounts/{id}/cheques/stop")
    @PreAuthorize("hasAuthority('casa:transact')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> stop(@PathVariable UUID id, @RequestBody DepositCheques.StopInput in, @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(cheques.proposeStop(id, in, key));
    }

    @PostMapping("/casa/accounts/{id}/cheques/{leafNo}/revoke-stop")
    @PreAuthorize("hasAuthority('casa:transact')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> revokeStop(@PathVariable UUID id, @PathVariable int leafNo, @RequestBody DepositMoneyService.ReasonInput in,
                                   @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(cheques.proposeRevoke(id, leafNo, in, key));
    }

    @PostMapping("/casa/clearing/inward")
    @PreAuthorize("hasAuthority('casa:transact')")
    Map<String, Object> present(@RequestBody DepositCheques.PresentInput in) {
        return cheques.present(in);
    }

    @PostMapping("/casa/accounts/{id}/cheque-deposits")
    @PreAuthorize("hasAuthority('casa:transact')")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> lodge(@PathVariable UUID id, @RequestBody DepositCheques.LodgeInput in) {
        return cheques.lodge(id, in);
    }

    @GetMapping("/casa/cheque-deposits")
    @PreAuthorize("hasAuthority('casa:view')")
    List<Map<String, Object>> inClearing() {
        return cheques.inClearing();
    }

    @PostMapping("/casa/cheque-deposits/{id}/settle")
    @PreAuthorize("hasAuthority('casa:transact')")
    Map<String, Object> settle(@PathVariable UUID id, @RequestBody DepositCheques.SettleInput in) {
        return cheques.settle(id, in);
    }

    // ---- inoperative and unclaimed ----------------------------------------------------------------------------
    @GetMapping("/casa/dormancy")
    @PreAuthorize("hasAuthority('casa:view')")
    Map<String, Object> dormancy(@RequestParam(required = false) String stage) {
        return dormancy.list(stage);
    }

    @PostMapping("/casa/accounts/{id}/reactivate")
    @PreAuthorize("hasAuthority('casa:open')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> reactivate(@PathVariable UUID id, @RequestBody DepositDormancy.ReactivationInput in,
                                   @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(dormancy.propose(id, in, key));
    }

    @PostMapping("/deposits/accounts/{id}/claim")
    @PreAuthorize("hasAuthority('deposit:fund')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> claim(@PathVariable UUID id, @RequestBody DepositDormancy.ReactivationInput in,
                              @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(dormancy.propose(id, in, key));
    }

    // ---- register and net owned fund ---------------------------------------------------------------------------
    @GetMapping("/deposits/register")
    @PreAuthorize("hasAuthority('deposit:view')")
    Map<String, Object> register(@RequestParam(required = false) String branch) {
        return register.register(branch);
    }

    @GetMapping("/deposits/net-owned-fund")
    @PreAuthorize("hasAuthority('deposit:view')")
    Map<String, Object> nof() {
        return register.nofView();
    }

    @PostMapping("/deposits/net-owned-fund")
    @PreAuthorize("hasAuthority('product:propose')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> recordNof(@RequestBody DepositRegister.NofInput in, @RequestHeader(value = KEY, required = false) String key) {
        return ApprovalView.of(register.proposeNof(in, key));
    }
}
