package com.corebanking.deposits.internal;

import com.corebanking.customer.PiiKeys;
import com.corebanking.deposits.AccountHolds;
import com.corebanking.deposits.AccountHolds.Direction;
import com.corebanking.deposits.AccountHolds.Hold;
import com.corebanking.deposits.DepositPostings;
import com.corebanking.deposits.DepositPostings.FundingSource;
import com.corebanking.deposits.DepositPostings.Leg;
import com.corebanking.deposits.Tenure;
import com.corebanking.kernel.PiiCipher;
import com.corebanking.ledger.LedgerSettings;
import com.corebanking.ledger.PostingService;
import com.corebanking.ledger.TransactionLot;
import com.corebanking.platform.AmountLimitService;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money on deposit accounts (P4-2): cash deposits and withdrawals, transfers, bank transfers recorded with the bank's
 * reference (UTR), reversals, freezes and liens, and funding a term deposit.
 * <p>
 * Every request goes through maker-checker. Money in and out is applied at once below the tenant's threshold (approval
 * rules of tenant V30: 2,00,000 to start with) and waits for a checker from it upward; reversals, holds and the reversal
 * of a funding always wait. Each check is made when the request is raised, so the maker sees every reason at once (422
 * with {@code problems}), and again when it is applied. Each movement is one balanced lot through the single posting
 * engine plus one {@code deposits.movement} row per account; the database refuses a movement that disagrees with its lot,
 * takes a balance below zero or below the liens, or ignores a freeze (tenant V30).
 * <p>
 * Bank transfers are recorded by staff with the UTR; sending and receiving them through the payment gateway comes with
 * P4-2b. Bank account numbers are sealed with the tenant's data key and shown masked.
 */
@Service
public class DepositMoneyService {

    static final String TXN_ENTITY = "DEPOSIT_TXN";
    static final String HOLD_ENTITY = "DEPOSIT_HOLD";
    static final String CASH_DEPOSIT = "CASH_DEPOSIT";
    static final String CASH_WITHDRAWAL = "CASH_WITHDRAWAL";
    static final String BANK_TRANSFER_IN = "BANK_TRANSFER_IN";
    static final String BANK_TRANSFER_OUT = "BANK_TRANSFER_OUT";
    static final String TRANSFER = "TRANSFER";
    static final String REVERSE = "REVERSE";
    static final String FUND = "FUND";
    static final String REVERSE_FUNDING = "REVERSE_FUNDING";
    static final Set<String> TXN_TYPES = Set.of(CASH_DEPOSIT, CASH_WITHDRAWAL, BANK_TRANSFER_IN, BANK_TRANSFER_OUT);
    static final Set<String> HOLD_KINDS = Set.of("DEBIT_FREEZE", "CREDIT_FREEZE", "TOTAL_FREEZE", "LIEN");
    private static final String NAME_AAD = "deposits.bank_account.holder_name";
    private static final String ACCOUNT_AAD = "deposits.bank_account.number";

    /** openapi.yaml#/components/schemas/BankAccountInput: an account at another bank. */
    public record BankAccountInput(String holderName, String accountNumber, String ifsc) {}

    /** openapi.yaml#/components/schemas/DepositTransactionInput. {@code bankAccount}: the other bank's account. */
    public record TransactionInput(String type, BigDecimal amount, String reference, String narration, BankAccountInput bankAccount) {}

    /** openapi.yaml#/components/schemas/DepositTransferInput. */
    public record TransferInput(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String narration) {}

    /** openapi.yaml#/components/schemas/ReasonInput. */
    public record ReasonInput(String reason) {}

    /** openapi.yaml#/components/schemas/DepositHoldInput. */
    public record HoldInput(String kind, BigDecimal amount, String reason, String reference) {}

    /** openapi.yaml#/components/schemas/TermDepositFundingInput. */
    public record FundingInput(String source, UUID fromAccountId, String reference, BankAccountInput payoutBankAccount) {}

    /** A bank account as an approval request carries it: the holder and the number sealed, the rest in clear. */
    public record SealedBankAccount(String holderNameSealed, String accountNumberSealed, String last4, String ifsc) {}

    /**
     * What a money request carries. {@code limitType}: the role amount limit the checker's approval counts against.
     * For a transfer {@code accountId} pays and {@code toAccountId} receives.
     */
    public record Txn(String type, String accountId, String accountNo, String toAccountId, String toAccountNo, String amount,
                      String reference, String narration, SealedBankAccount bankAccount, String movementId, String reason,
                      String limitType) {}

    /** What a hold request carries. */
    public record HoldRequest(String accountId, String accountNo, String kind, String amount, String reason, String reference,
                              String holdId) {}

    /** What a funding request carries. */
    public record Funding(String depositId, String depositNo, String source, String fromAccountId, String fromAccountNo, String amount,
                          String reference, SealedBankAccount payoutBankAccount, String reason, String limitType) {}

    /** A deposit account as the checks and postings need it. */
    record Account(UUID id, String accountNo, String family, String status, String branch, String gl, UUID customerId) {
        Leg leg() { return new Leg(branch, gl, accountNo); }
    }

    private record Movement(UUID id, UUID accountId, String kind, String direction, BigDecimal amount, UUID lotId, UUID reversedBy) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final AmountLimitService limits;
    private final PostingService postings;
    private final LedgerSettings ledger;
    private final BusinessDays days;
    private final BranchScope scope;
    private final PiiKeys keys;
    private final Json json;

    DepositMoneyService(JdbcTemplate jdbc, ApprovalService approvals, AmountLimitService limits, PostingService postings, LedgerSettings ledger,
                        BusinessDays days, BranchScope scope, PiiKeys keys, Json json) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.limits = limits;
        this.postings = postings;
        this.ledger = ledger;
        this.days = days;
        this.scope = scope;
        this.keys = keys;
        this.json = json;
    }

    // ================================================================================================ money in and out

    /** Proposes a cash deposit or withdrawal, or a bank transfer in or out, on a savings or current account. */
    @Transactional
    public ApprovalRequest proposeTransaction(UUID accountId, TransactionInput in, String idempotencyKey) {
        if (in == null || in.type() == null || !TXN_TYPES.contains(in.type())) {
            throw ApiException.invalid("type is one of " + String.join(", ", TXN_TYPES.stream().sorted().toList()));
        }
        Account a = account(accountId, DepositProductService.CASA, true);
        boolean credit = CASH_DEPOSIT.equals(in.type()) || BANK_TRANSFER_IN.equals(in.type());
        boolean bank = in.type().startsWith("BANK_");
        List<String> problems = new ArrayList<>();
        String reference = text(in.reference(), 60, "reference", problems);
        String narration = text(in.narration(), 200, "narration", problems);
        if (bank && reference == null) problems.add("the bank's reference (UTR) is required for a bank transfer");
        SealedBankAccount party = null;
        if (BANK_TRANSFER_OUT.equals(in.type()) && in.bankAccount() == null) problems.add("name the bank account the money is sent to");
        if (in.bankAccount() != null) {
            if (!bank) problems.add("only a bank transfer names an account at another bank");
            else party = seal(in.bankAccount(), problems);
        }
        problems.addAll(movementProblems(a, credit ? Direction.CREDIT : Direction.DEBIT, in.amount(), false));
        DepositAccountService.refuseIfAny("the transaction cannot be made", problems);
        String limitType = credit ? "DEPOSIT_CREDIT" : "DEPOSIT_DEBIT";
        limits.require(limitType, AmountLimitService.MAKE, in.amount(), a.accountNo());
        Txn t = new Txn(in.type(), a.id().toString(), a.accountNo(), null, null, in.amount().toPlainString(), reference, narration, party,
                null, null, limitType);
        return approvals.propose(TXN_ENTITY, in.type(), a.id().toString(), toMap(t), null, in.amount(), a.branch(), idempotencyKey);
    }

    /** Proposes a transfer from one savings or current account to another. The paying account must be in the caller's branches. */
    @Transactional
    public ApprovalRequest proposeTransfer(TransferInput in, String idempotencyKey) {
        if (in == null || in.fromAccountId() == null || in.toAccountId() == null) throw ApiException.invalid("fromAccountId and toAccountId are required");
        Account from = account(in.fromAccountId(), DepositProductService.CASA, true);
        Account to = account(in.toAccountId(), DepositProductService.CASA, false);
        List<String> problems = new ArrayList<>();
        if (from.id().equals(to.id())) problems.add("an account cannot transfer to itself");
        String narration = text(in.narration(), 200, "narration", problems);
        problems.addAll(movementProblems(from, Direction.DEBIT, in.amount(), false));
        if (in.amount() != null && in.amount().signum() > 0) {
            for (String p : movementProblems(to, Direction.CREDIT, in.amount(), false)) problems.add("account " + to.accountNo() + ": " + p);
        }
        DepositAccountService.refuseIfAny("the transfer cannot be made", problems);
        limits.require("DEPOSIT_DEBIT", AmountLimitService.MAKE, in.amount(), from.accountNo());
        Txn t = new Txn(TRANSFER, from.id().toString(), from.accountNo(), to.id().toString(), to.accountNo(), in.amount().toPlainString(), null,
                narration, null, null, null, "DEPOSIT_DEBIT");
        return approvals.propose(TXN_ENTITY, TRANSFER, from.id().toString(), toMap(t), null, in.amount(), from.branch(), idempotencyKey);
    }

    /**
     * Proposes the reversal of a posted movement, with a reason. A transfer is reversed on both sides. The funding of a
     * term deposit is not reversed here but with {@link #proposeFundingReversal}.
     */
    @Transactional
    public ApprovalRequest proposeReversal(UUID movementId, ReasonInput in, String idempotencyKey) {
        Movement m = movement(movementId);
        Account a = account(m.accountId(), null, true);
        List<String> problems = new ArrayList<>();
        String reason = in == null ? null : text(in.reason(), 500, "reason", problems);
        if (reason == null) problems.add("a reason is required");
        problems.addAll(reversalProblems(m));
        DepositAccountService.refuseIfAny("the movement cannot be reversed", problems);
        Txn t = new Txn(REVERSE, a.id().toString(), a.accountNo(), null, null, m.amount().toPlainString(), null, null, null,
                m.id().toString(), reason, null);
        return approvals.propose(TXN_ENTITY, REVERSE, m.id().toString(), toMap(t), null, m.amount(), a.branch(), idempotencyKey);
    }

    /** Posts an approved money request; returns the id of the (first) movement written. */
    String applyTxn(ApprovalRequest r) {
        Txn t = convert(r.payload(), Txn.class);
        return switch (t.type()) {
            case REVERSE -> reverse(t, r);
            case TRANSFER -> transfer(t, r);
            default -> transaction(t, r);
        };
    }

    private String transaction(Txn t, ApprovalRequest r) {
        Account a = account(UUID.fromString(t.accountId()), DepositProductService.CASA, false);
        BigDecimal amount = new BigDecimal(t.amount());
        boolean credit = CASH_DEPOSIT.equals(t.type()) || BANK_TRANSFER_IN.equals(t.type());
        DepositAccountService.refuseIfAny("the transaction cannot be made",
                movementProblems(a, credit ? Direction.CREDIT : Direction.DEBIT, amount, false));
        LocalDate today = days.requireOpen();
        DepositPostings post = new DepositPostings(heads(), today);
        String ref = t.reference() == null ? a.accountNo() : t.reference();
        TransactionLot lot = switch (t.type()) {
            case CASH_DEPOSIT -> post.cashDeposit(a.leg(), amount, ref, t.narration());
            case CASH_WITHDRAWAL -> post.cashWithdrawal(a.leg(), amount, ref, t.narration());
            case BANK_TRANSFER_IN -> post.bankTransferIn(a.leg(), amount, ref, t.narration());
            case BANK_TRANSFER_OUT -> post.bankTransferOut(a.leg(), amount, ref, t.narration());
            default -> throw new IllegalStateException("unknown transaction type " + t.type());
        };
        postings.post(lot, CurrentUser.username());
        UUID id = insertMovement(a.id(), t.type(), credit ? "CREDIT" : "DEBIT", amount, lot.id(), t.reference(), t.narration(), null,
                t.bankAccount(), r);
        checkNow();
        return id.toString();
    }

    private String transfer(Txn t, ApprovalRequest r) {
        Account from = account(UUID.fromString(t.accountId()), DepositProductService.CASA, false);
        Account to = account(UUID.fromString(t.toAccountId()), DepositProductService.CASA, false);
        BigDecimal amount = new BigDecimal(t.amount());
        List<String> problems = new ArrayList<>(movementProblems(from, Direction.DEBIT, amount, false));
        for (String p : movementProblems(to, Direction.CREDIT, amount, false)) problems.add("account " + to.accountNo() + ": " + p);
        DepositAccountService.refuseIfAny("the transfer cannot be made", problems);
        TransactionLot lot = new DepositPostings(heads(), days.requireOpen()).transfer(from.leg(), to.leg(), amount, from.accountNo(), t.narration());
        postings.post(lot, CurrentUser.username());
        UUID out = insertMovement(from.id(), "TRANSFER_OUT", "DEBIT", amount, lot.id(), null, t.narration(), to.id(), null, r);
        insertMovement(to.id(), "TRANSFER_IN", "CREDIT", amount, lot.id(), null, t.narration(), from.id(), null, r);
        checkNow();
        return out.toString();
    }

    private String reverse(Txn t, ApprovalRequest r) {
        Movement m = movement(UUID.fromString(t.movementId()));
        DepositAccountService.refuseIfAny("the movement cannot be reversed", reversalProblems(m));
        return reverseLot(m, t.reason(), r).toString();
    }

    /** Reverses every movement of the movement's lot with the mirror lot; returns the id of the reversal of {@code m}. */
    private UUID reverseLot(Movement m, String reason, ApprovalRequest r) {
        LocalDate today = days.requireOpen();
        TransactionLot lot = TransactionLot.reversal(postings.load(m.lotId()), today, reason);
        postings.post(lot, CurrentUser.username());
        UUID mine = null;
        for (Movement each : lotMovements(m.lotId())) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO deposits.movement (id, account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id,
                                                   reverses, reversal_reason, approval_id, created_by)
                    VALUES (?, ?, 'REVERSAL', ?, ?, 0, ?, ?, ?, ?, ?, ?, ?)
                    """, id, each.accountId(), "DEBIT".equals(each.direction()) ? "CREDIT" : "DEBIT", each.amount(), today, today, lot.id(),
                    each.id(), reason, r.id(), r.maker());
            jdbc.update("UPDATE deposits.movement SET reversed_by = ? WHERE id = ?", id, each.id());
            if (each.id().equals(m.id())) mine = id;
        }
        return mine;
    }

    // ================================================================================================ freezes and liens

    /** Proposes a freeze (savings and current accounts) or a lien (any active account). */
    @Transactional
    public ApprovalRequest proposeHold(String family, UUID accountId, HoldInput in, String idempotencyKey) {
        Account a = account(accountId, family, true);
        if (in == null || in.kind() == null || !HOLD_KINDS.contains(in.kind())) {
            throw ApiException.invalid("kind is one of DEBIT_FREEZE, CREDIT_FREEZE, TOTAL_FREEZE, LIEN");
        }
        List<String> problems = new ArrayList<>();
        AccountHolds.Kind kind = AccountHolds.Kind.valueOf(in.kind());
        if (DepositProductService.TERM_DEPOSIT.equals(a.family()) && kind.isFreeze()) problems.add("a term deposit takes a lien, not a freeze");
        if (!"ACTIVE".equals(a.status())) {
            problems.add("account " + a.accountNo() + " is " + a.status() + "; a hold is placed on an active account"
                    + ("BOOKED".equals(a.status()) ? " (fund the deposit first)" : ""));
        }
        String reference = text(in.reference(), 100, "reference", problems);
        String reason = in.reason() == null ? null : in.reason().trim();
        problems.addAll(AccountHolds.checkNewHold(balance(a.id()), holds(a.id()), kind, in.amount(), reason));
        DepositAccountService.refuseIfAny("the hold cannot be placed", problems);
        HoldRequest h = new HoldRequest(a.id().toString(), a.accountNo(), in.kind(), in.amount() == null ? null : in.amount().toPlainString(),
                reason, reference, null);
        return approvals.propose(HOLD_ENTITY, "PLACE", a.id().toString(), toMap(h), null, in.amount(), a.branch(), idempotencyKey);
    }

    /** Proposes the release of an active freeze or lien, with a reason. */
    @Transactional
    public ApprovalRequest proposeRelease(String family, UUID holdId, ReasonInput in, String idempotencyKey) {
        Map<String, Object> hold = holdRow(holdId);
        Account a = account((UUID) hold.get("account_id"), family, true);
        List<String> problems = new ArrayList<>();
        if (!"ACTIVE".equals(hold.get("status"))) problems.add("the hold is already released");
        String reason = in == null ? null : text(in.reason(), 500, "reason", problems);
        if (reason == null) problems.add("a reason is required");
        DepositAccountService.refuseIfAny("the hold cannot be released", problems);
        BigDecimal amount = (BigDecimal) hold.get("amount");
        HoldRequest h = new HoldRequest(a.id().toString(), a.accountNo(), (String) hold.get("kind"), amount == null ? null : amount.toPlainString(),
                reason, null, holdId.toString());
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("kind", hold.get("kind"));
        current.put("amount", amount == null ? null : DepositRuleSets.plain(amount));
        current.put("reason", hold.get("reason"));
        current.put("placedOn", hold.get("placed_on"));
        return approvals.propose(HOLD_ENTITY, "RELEASE", holdId.toString(), toMap(h), current, null, a.branch(), idempotencyKey);
    }

    /** Places or releases a hold; returns the hold id. */
    String applyHold(ApprovalRequest r) {
        HoldRequest h = convert(r.payload(), HoldRequest.class);
        if ("RELEASE".equals(r.action())) {
            int n = jdbc.update("""
                    UPDATE deposits.account_hold SET status = 'RELEASED', released_on = current_date, released_by = ?, release_reason = ?,
                           release_approval_id = ? WHERE id = ? AND status = 'ACTIVE'
                    """, r.maker(), h.reason(), r.id(), UUID.fromString(h.holdId()));
            if (n == 0) throw ApiException.conflict("the hold is already released");
            return h.holdId();
        }
        Account a = account(UUID.fromString(h.accountId()), null, false);
        BigDecimal amount = h.amount() == null ? null : new BigDecimal(h.amount());
        DepositAccountService.refuseIfAny("the hold cannot be placed",
                AccountHolds.checkNewHold(balance(a.id()), holds(a.id()), AccountHolds.Kind.valueOf(h.kind()), amount, h.reason()));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deposits.account_hold (id, account_id, kind, amount, reason, reference, placed_on, placed_by, approval_id)
                VALUES (?, ?, ?, ?, ?, ?, current_date, ?, ?)
                """, id, a.id(), h.kind(), amount, h.reason(), h.reference(), r.maker(), r.id());
        return id.toString();
    }

    // ================================================================================================ funding

    /**
     * Proposes funding a booked term deposit with its principal: from a savings or current account the depositor holds,
     * in cash, or by a bank transfer (with its UTR). A deposit paid out to a bank account has that account recorded now.
     */
    @Transactional
    public ApprovalRequest proposeFunding(UUID depositId, FundingInput in, String idempotencyKey) {
        Account d = account(depositId, DepositProductService.TERM_DEPOSIT, true);
        if (in == null || in.source() == null || !Set.of("ACCOUNT", "CASH", "BANK_TRANSFER").contains(in.source())) {
            throw ApiException.invalid("source is one of ACCOUNT, CASH, BANK_TRANSFER");
        }
        Map<String, Object> td = termDeposit(depositId);
        BigDecimal principal = ((BigDecimal) td.get("principal")).stripTrailingZeros();
        List<String> problems = new ArrayList<>(fundingProblems(d, td));
        String reference = text(in.reference(), 60, "reference", problems);
        if ("BANK_TRANSFER".equals(in.source()) && reference == null) problems.add("the bank's reference (UTR) is required for a bank transfer");
        Account from = null;
        if ("ACCOUNT".equals(in.source())) {
            if (in.fromAccountId() == null) {
                problems.add("name the savings or current account the deposit is funded from");
            } else {
                from = account(in.fromAccountId(), DepositProductService.CASA, false);
                if (!holds(from.id(), d.customerId())) problems.add("account " + from.accountNo() + " is not held by the depositor");
                for (String p : movementProblems(from, Direction.DEBIT, principal, false)) problems.add("account " + from.accountNo() + ": " + p);
            }
        } else if (in.fromAccountId() != null) {
            problems.add("only a funding from an account names one");
        }
        SealedBankAccount bank = null;
        boolean toBank = "BANK_ACCOUNT".equals(td.get("payout_to"));
        if (toBank && in.payoutBankAccount() == null) {
            problems.add("the deposit is paid out to a bank account: record that account now");
        } else if (!toBank && in.payoutBankAccount() != null) {
            problems.add("the deposit is paid out to account " + td.get("payout_account_no") + " here; it takes no bank account");
        } else if (in.payoutBankAccount() != null) {
            bank = seal(in.payoutBankAccount(), problems);
        }
        DepositAccountService.refuseIfAny("the deposit cannot be funded", problems);
        String limitType = from != null ? "DEPOSIT_DEBIT" : "DEPOSIT_CREDIT";
        limits.require(limitType, AmountLimitService.MAKE, principal, d.accountNo());
        Funding f = new Funding(d.id().toString(), d.accountNo(), in.source(), from == null ? null : from.id().toString(),
                from == null ? null : from.accountNo(), principal.toPlainString(), reference, bank, null, limitType);
        return approvals.propose(DepositAccountService.TD_ENTITY, FUND, d.id().toString(), toMap(f), null, principal, d.branch(), idempotencyKey);
    }

    /** Proposes the reversal of a deposit's funding: the money goes back where it came from and the deposit is booked again. */
    @Transactional
    public ApprovalRequest proposeFundingReversal(UUID depositId, ReasonInput in, String idempotencyKey) {
        Account d = account(depositId, DepositProductService.TERM_DEPOSIT, true);
        List<String> problems = new ArrayList<>();
        String reason = in == null ? null : text(in.reason(), 500, "reason", problems);
        if (reason == null) problems.add("a reason is required");
        Map<String, Object> td = termDeposit(depositId);
        problems.addAll(fundingReversalProblems(d, td));
        DepositAccountService.refuseIfAny("the funding cannot be reversed", problems);
        BigDecimal principal = ((BigDecimal) td.get("principal")).stripTrailingZeros();
        Funding f = new Funding(d.id().toString(), d.accountNo(), (String) td.get("funding_source"), null, null, principal.toPlainString(), null,
                null, reason, null);
        return approvals.propose(DepositAccountService.TD_ENTITY, REVERSE_FUNDING, d.id().toString(), toMap(f), null, principal, d.branch(),
                idempotencyKey);
    }

    /** Funds a deposit, or reverses its funding; returns the deposit number. */
    String applyFunding(ApprovalRequest r) {
        Funding f = convert(r.payload(), Funding.class);
        Account d = account(UUID.fromString(f.depositId()), DepositProductService.TERM_DEPOSIT, false);
        Map<String, Object> td = termDeposit(d.id());
        if (REVERSE_FUNDING.equals(r.action())) {
            DepositAccountService.refuseIfAny("the funding cannot be reversed", fundingReversalProblems(d, td));
            Movement m = movement((UUID) td.get("funding_movement_id"));
            reverseLot(m, f.reason(), r);
            jdbc.update("""
                    UPDATE deposits.term_deposit SET start_date = NULL, maturity_date = NULL, funding_source = NULL, funding_account_id = NULL,
                           funding_movement_id = NULL, funding_reference = NULL, payout_bank_name_cipher = NULL, payout_bank_account_cipher = NULL,
                           payout_bank_account_last4 = NULL, payout_bank_ifsc = NULL
                     WHERE account_id = ?
                    """, d.id());
            jdbc.update("UPDATE deposits.account SET status = 'BOOKED' WHERE id = ?", d.id());
            checkNow();
            return d.accountNo();
        }
        BigDecimal principal = ((BigDecimal) td.get("principal")).stripTrailingZeros();
        if (principal.compareTo(new BigDecimal(f.amount())) != 0) throw ApiException.conflict("the principal of deposit " + d.accountNo() + " changed");
        List<String> problems = new ArrayList<>(fundingProblems(d, td));
        Account from = null;
        if (f.fromAccountId() != null) {
            from = account(UUID.fromString(f.fromAccountId()), DepositProductService.CASA, false);
            if (!holds(from.id(), d.customerId())) problems.add("account " + from.accountNo() + " is not held by the depositor");
            for (String p : movementProblems(from, Direction.DEBIT, principal, false)) problems.add("account " + from.accountNo() + ": " + p);
        }
        DepositAccountService.refuseIfAny("the deposit cannot be funded", problems);
        LocalDate today = days.requireOpen();
        FundingSource source = FundingSource.valueOf(f.source());
        TransactionLot lot = new DepositPostings(heads(), today).funding(d.leg(), principal, source, from == null ? null : from.leg(),
                f.reference() == null ? d.accountNo() : f.reference());
        postings.post(lot, CurrentUser.username());
        UUID funding = insertMovement(d.id(), "FUNDING", "CREDIT", principal, lot.id(), f.reference(), null, from == null ? null : from.id(), null, r);
        if (from != null) insertMovement(from.id(), "FUNDING_PAID", "DEBIT", principal, lot.id(), f.reference(), null, d.id(), null, r);
        Tenure tenure = new Tenure((Integer) td.get("tenure_months"), (Integer) td.get("tenure_days"));
        SealedBankAccount bank = f.payoutBankAccount();
        jdbc.update("""
                UPDATE deposits.term_deposit SET start_date = ?, maturity_date = ?, funding_source = ?, funding_account_id = ?, funding_movement_id = ?,
                       funding_reference = ?, payout_bank_name_cipher = ?, payout_bank_account_cipher = ?, payout_bank_account_last4 = ?,
                       payout_bank_ifsc = ?
                 WHERE account_id = ?
                """, today, tenure.addTo(today), f.source(), from == null ? null : from.id(), funding, f.reference(),
                bank == null ? null : Base64.getDecoder().decode(bank.holderNameSealed()),
                bank == null ? null : Base64.getDecoder().decode(bank.accountNumberSealed()), bank == null ? null : bank.last4(),
                bank == null ? null : bank.ifsc(), d.id());
        jdbc.update("UPDATE deposits.account SET status = 'ACTIVE' WHERE id = ?", d.id());
        checkNow();
        return d.accountNo();
    }

    // ================================================================================================ reading

    /** The movements of an account, newest first, with the balance after each; optionally between two business dates. */
    public List<Map<String, Object>> movements(String family, UUID accountId, LocalDate from, LocalDate to) {
        account(accountId, family, true);
        return jdbc.query("""
                SELECT m.id, m.seq, m.business_date, m.value_date, m.kind, m.direction, m.amount, m.balance_after, m.reference, m.narration,
                       c.account_no, m.party_account_last4, m.party_ifsc, m.reverses, m.reversed_by, m.reversal_reason, m.created_by, m.approval_id,
                       m.created_at
                  FROM deposits.movement m LEFT JOIN deposits.account c ON c.id = m.counterparty_id
                 WHERE m.account_id = ? AND (?::date IS NULL OR m.business_date >= ?::date) AND (?::date IS NULL OR m.business_date <= ?::date)
                 ORDER BY m.seq DESC LIMIT 1000
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("seq", rs.getLong(2));
                    m.put("businessDate", rs.getObject(3, LocalDate.class));
                    m.put("valueDate", rs.getObject(4, LocalDate.class));
                    m.put("kind", rs.getString(5));
                    m.put("direction", rs.getString(6));
                    m.put("amount", DepositRuleSets.plain(rs.getBigDecimal(7)));
                    m.put("balanceAfter", DepositRuleSets.plain(rs.getBigDecimal(8)));
                    m.put("reference", rs.getString(9));
                    m.put("narration", rs.getString(10));
                    m.put("counterpartyAccountNo", rs.getString(11));
                    m.put("bankAccountMasked", rs.getString(12) == null ? null : "XXXXXXXX" + rs.getString(12));
                    m.put("bankIfsc", rs.getString(13));
                    m.put("reverses", rs.getString(14));
                    m.put("reversedBy", rs.getString(15));
                    m.put("reversalReason", rs.getString(16));
                    m.put("createdBy", rs.getString(17));
                    m.put("approvalId", rs.getString(18));
                    m.put("createdAt", rs.getObject(19, java.time.OffsetDateTime.class));
                    return m;
                }, accountId, from, from, to, to);
    }

    /** Every freeze and lien of an account, active first. */
    public List<Map<String, Object>> holdList(String family, UUID accountId) {
        account(accountId, family, true);
        return jdbc.query("""
                SELECT id, kind, amount, reason, reference, status, placed_on, placed_by, released_on, released_by, release_reason
                  FROM deposits.account_hold WHERE account_id = ? ORDER BY status = 'ACTIVE' DESC, created_at DESC
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("kind", rs.getString(2));
                    m.put("amount", DepositRuleSets.plain(rs.getBigDecimal(3)));
                    m.put("reason", rs.getString(4));
                    m.put("reference", rs.getString(5));
                    m.put("status", rs.getString(6));
                    m.put("placedOn", rs.getObject(7, LocalDate.class));
                    m.put("placedBy", rs.getString(8));
                    m.put("releasedOn", rs.getObject(9, LocalDate.class));
                    m.put("releasedBy", rs.getString(10));
                    m.put("releaseReason", rs.getString(11));
                    return m;
                }, accountId);
    }

    /** The balance figures of an account, for its detail: balance, liens, available balance and the freezes in force. */
    Map<String, Object> balanceView(UUID accountId) {
        BigDecimal balance = balance(accountId);
        List<Hold> holds = holds(accountId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("balance", DepositRuleSets.plain(balance));
        m.put("lienAmount", DepositRuleSets.plain(AccountHolds.liens(holds)));
        m.put("availableBalance", DepositRuleSets.plain(AccountHolds.available(balance, holds).max(BigDecimal.ZERO)));
        m.put("freezes", holds.stream().filter(h -> h.kind().isFreeze()).map(h -> h.kind().name()).sorted().toList());
        return m;
    }

    // ================================================================================================ checks

    /** Why money may not move in this direction on this account; empty when it may. */
    private List<String> movementProblems(Account a, Direction d, BigDecimal amount, boolean correction) {
        List<String> out = new ArrayList<>();
        if (DepositProductService.CASA.equals(a.family()) && !"ACTIVE".equals(a.status())) out.add("account " + a.accountNo() + " is " + a.status());
        if (amount == null) {
            out.add("the amount is required");
            return out;
        }
        out.addAll(AccountHolds.checkMovement(balance(a.id()), holds(a.id()), d, amount, correction));
        return out;
    }

    private List<String> reversalProblems(Movement m) {
        List<String> out = new ArrayList<>();
        if ("REVERSAL".equals(m.kind())) out.add("a reversal is not reversed");
        else if (m.reversedBy() != null) out.add("the movement is already reversed");
        else if ("FUNDING".equals(m.kind()) || "FUNDING_PAID".equals(m.kind())) {
            out.add("this movement funded a term deposit: reverse the funding of the deposit instead");
        }
        if (!out.isEmpty()) return out;
        for (Movement each : lotMovements(m.lotId())) {
            Account a = account(each.accountId(), null, false);
            Direction back = "DEBIT".equals(each.direction()) ? Direction.CREDIT : Direction.DEBIT;
            for (String p : movementProblems(a, back, each.amount(), true)) out.add("account " + a.accountNo() + ": " + p);
        }
        return out;
    }

    private List<String> fundingProblems(Account d, Map<String, Object> td) {
        List<String> out = new ArrayList<>();
        if (!"BOOKED".equals(d.status())) out.add("deposit " + d.accountNo() + " is " + d.status() + "; only a booked deposit is funded");
        LocalDate today = days.requireOpen();
        LocalDate booked = localDate(td.get("booked_on"));
        Integer within = fundingWithinDays();
        if (within != null && today.isAfter(booked.plusDays(within))) {
            out.add("the booking of " + booked + " has lapsed (a deposit is funded within " + within + " days of booking); book it again");
        }
        return out;
    }

    private List<String> fundingReversalProblems(Account d, Map<String, Object> td) {
        List<String> out = new ArrayList<>();
        if (!"ACTIVE".equals(d.status()) || td.get("funding_movement_id") == null) {
            out.add("deposit " + d.accountNo() + " is " + d.status() + "; only a funded deposit has a funding to reverse");
            return out;
        }
        Movement m = movement((UUID) td.get("funding_movement_id"));
        if (m.reversedBy() != null) out.add("the funding is already reversed");
        // nothing else may have happened to the deposit since (interest from P4-3 on, a closure)
        Integer later = jdbc.queryForObject("SELECT count(*)::int FROM deposits.movement WHERE account_id = ? AND seq > (SELECT seq FROM deposits.movement WHERE id = ?)",
                Integer.class, d.id(), m.id());
        if (later != null && later > 0) out.add("money has moved on the deposit since it was funded");
        for (Movement each : lotMovements(m.lotId())) {
            Account a = account(each.accountId(), null, false);
            Direction back = "DEBIT".equals(each.direction()) ? Direction.CREDIT : Direction.DEBIT;
            for (String p : AccountHolds.checkMovement(balance(a.id()), holds(a.id()), back, each.amount(), true)) out.add("account " + a.accountNo() + ": " + p);
        }
        return out;
    }

    /** Runs the deferred checks of tenant V30 now, so a refusal is this request's answer; then defers them again. */
    private void checkNow() {
        jdbc.execute("SET CONSTRAINTS deposits.movement_matches_lot, deposits.movement_funded, deposits.account_funded, deposits.term_deposit_funded IMMEDIATE");
        jdbc.execute("SET CONSTRAINTS deposits.movement_matches_lot, deposits.movement_funded, deposits.account_funded, deposits.term_deposit_funded DEFERRED");
    }

    // ================================================================================================ data

    /**
     * An account of the family (any family when null). With {@code scoped}, it must be in the caller's branches;
     * outside them it is not found.
     */
    private Account account(UUID id, String family, boolean scoped) {
        if (id == null) throw ApiException.invalid("the account is required");
        List<Account> rows = jdbc.query("""
                SELECT a.id, a.account_no, a.family, a.status, a.branch_code, p.gl_liability, a.customer_id
                  FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code WHERE a.id = ?
                """, (rs, i) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getObject(7, UUID.class)), id);
        String what = (DepositProductService.TERM_DEPOSIT.equals(family) ? "term deposit " : "account ") + id;
        if (rows.isEmpty() || (family != null && !family.equals(rows.get(0).family()))) throw ApiException.notFound(what);
        if (scoped) scope.requireRecord(rows.get(0).branch(), what);
        return rows.get(0);
    }

    private Movement movement(UUID id) {
        List<Movement> rows = jdbc.query("SELECT id, account_id, kind, direction, amount, lot_id, reversed_by FROM deposits.movement WHERE id = ?",
                (rs, i) -> new Movement(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class)), id);
        if (rows.isEmpty()) throw ApiException.notFound("movement " + id);
        return rows.get(0);
    }

    private List<Movement> lotMovements(UUID lotId) {
        return jdbc.query("SELECT id, account_id, kind, direction, amount, lot_id, reversed_by FROM deposits.movement WHERE lot_id = ? ORDER BY seq",
                (rs, i) -> new Movement(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class)), lotId);
    }

    private BigDecimal balance(UUID accountId) {
        List<BigDecimal> b = jdbc.queryForList("SELECT balance FROM deposits.account_balance WHERE account_id = ?", BigDecimal.class, accountId);
        return b.isEmpty() ? BigDecimal.ZERO : b.get(0);
    }

    private List<Hold> holds(UUID accountId) {
        return jdbc.query("SELECT kind, amount, reason FROM deposits.account_hold WHERE account_id = ? AND status = 'ACTIVE'",
                (rs, i) -> new Hold(AccountHolds.Kind.valueOf(rs.getString(1)), rs.getBigDecimal(2), rs.getString(3)), accountId);
    }

    private boolean holds(UUID accountId, UUID customerId) {
        return !jdbc.queryForList("SELECT 1 FROM deposits.account_holder WHERE account_id = ? AND customer_id = ?", accountId, customerId).isEmpty();
    }

    private Map<String, Object> holdRow(UUID holdId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT account_id, kind, amount::numeric AS amount, reason, placed_on::text AS placed_on, status FROM deposits.account_hold WHERE id = ?
                """, holdId);
        if (rows.isEmpty()) throw ApiException.notFound("hold " + holdId);
        return rows.get(0);
    }

    private Map<String, Object> termDeposit(UUID depositId) {
        return jdbc.queryForMap("""
                SELECT t.principal::numeric AS principal, t.tenure_months, t.tenure_days, t.booked_on, t.payout_to, pay.account_no AS payout_account_no, t.funding_source,
                       t.funding_movement_id
                  FROM deposits.term_deposit t LEFT JOIN deposits.account pay ON pay.id = t.payout_account_id WHERE t.account_id = ?
                """, depositId);
    }

    private UUID insertMovement(UUID accountId, String kind, String direction, BigDecimal amount, UUID lotId, String reference, String narration,
                                UUID counterparty, SealedBankAccount party, ApprovalRequest r) {
        UUID id = UUID.randomUUID();
        LocalDate today = days.requireOpen();
        jdbc.update("""
                INSERT INTO deposits.movement (id, account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, reference,
                                               narration, counterparty_id, party_name_cipher, party_account_cipher, party_account_last4, party_ifsc,
                                               approval_id, created_by)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, accountId, kind, direction, amount, today, today, lotId, reference, narration, counterparty,
                party == null ? null : Base64.getDecoder().decode(party.holderNameSealed()),
                party == null ? null : Base64.getDecoder().decode(party.accountNumberSealed()), party == null ? null : party.last4(),
                party == null ? null : party.ifsc(), r.id(), r.maker());
        return id;
    }

    /** The settlement heads from the tenant's settings (tenant V30), with the starter chart's as defaults. */
    private DepositPostings.Heads heads() {
        DepositPostings.Heads d = DepositPostings.Heads.starter();
        Map<String, String> p = new LinkedHashMap<>();
        jdbc.query("SELECT key, value FROM platform.system_property WHERE key IN ('deposits.gl.cash','deposits.gl.bank-in','deposits.gl.bank-out')",
                rs -> { p.put(rs.getString(1), rs.getString(2).trim()); });
        return new DepositPostings.Heads(p.getOrDefault("deposits.gl.cash", d.cash()), p.getOrDefault("deposits.gl.bank-in", d.bankIn()),
                p.getOrDefault("deposits.gl.bank-out", d.bankOut()), ledger.interBranchGl());
    }

    /** A date column read through queryForMap: java.sql.Date from the JDBC driver. */
    private static LocalDate localDate(Object v) {
        return v instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) v;
    }

    private Integer fundingWithinDays() {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = 'deposits.funding-within-days'", String.class);
        return v.isEmpty() ? null : Integer.valueOf(v.get(0).trim());
    }

    private SealedBankAccount seal(BankAccountInput in, List<String> problems) {
        String name = in.holderName() == null ? "" : in.holderName().trim();
        String number = in.accountNumber() == null ? "" : in.accountNumber().replace(" ", "");
        String ifsc = in.ifsc() == null ? "" : in.ifsc().trim().toUpperCase(Locale.ROOT);
        int before = problems.size();
        if (name.isEmpty() || name.length() > 100) problems.add("the account holder's name is required (at most 100 characters)");
        if (!number.matches("[A-Za-z0-9]{6,35}")) problems.add("the bank account number is 6 to 35 letters or digits");
        if (!ifsc.matches("[A-Z]{4}0[A-Z0-9]{6}")) problems.add("the IFSC is not a valid IFSC");
        if (problems.size() > before) return null;
        PiiCipher cipher = keys.forTenant(CurrentUser.requireTenant());
        Base64.Encoder b64 = Base64.getEncoder();
        return new SealedBankAccount(b64.encodeToString(cipher.encrypt(name, NAME_AAD)), b64.encodeToString(cipher.encrypt(number, ACCOUNT_AAD)),
                number.length() <= 4 ? number : number.substring(number.length() - 4), ifsc);
    }

    private static String text(String value, int max, String field, List<String> problems) {
        if (value == null || value.isBlank()) return null;
        String t = value.trim();
        if (t.length() > max) problems.add("the " + field + " is longer than " + max + " characters");
        return t;
    }

    private Map<String, Object> toMap(Object record) {
        return json.toMap(record);
    }

    private <T> T convert(Map<String, Object> payload, Class<T> type) {
        return json.convert(payload, type);
    }

    // ================================================================================================ appliers

    @Service
    static class TxnApplier implements ApprovalApplier {
        private final DepositMoneyService money;

        TxnApplier(@Lazy DepositMoneyService money) {
            this.money = money;
        }

        @Override public String entityType() { return TXN_ENTITY; }

        @Override public String apply(ApprovalRequest r) { return money.applyTxn(r); }
    }

    @Service
    static class HoldApplier implements ApprovalApplier {
        private final DepositMoneyService money;

        HoldApplier(@Lazy DepositMoneyService money) {
            this.money = money;
        }

        @Override public String entityType() { return HOLD_ENTITY; }

        @Override public String apply(ApprovalRequest r) { return money.applyHold(r); }
    }
}
