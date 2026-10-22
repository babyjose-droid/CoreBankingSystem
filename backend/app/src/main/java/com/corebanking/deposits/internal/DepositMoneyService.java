package com.corebanking.deposits.internal;

import com.corebanking.customer.PiiKeys;
import com.corebanking.deposits.AccountHolds;
import com.corebanking.deposits.AccountHolds.Direction;
import com.corebanking.deposits.AccountHolds.Hold;
import com.corebanking.deposits.DepositEvents;
import com.corebanking.deposits.DepositOperations;
import com.corebanking.deposits.DepositPostings;
import com.corebanking.deposits.DepositPostings.FundingSource;
import com.corebanking.deposits.DepositPostings.Leg;
import com.corebanking.deposits.TermDeposit;
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
import com.corebanking.platform.Outbox;
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
 * Bank transfers are recorded by staff with the UTR, or (P4-2b) a transfer out is sent through the payment gateway:
 * it posts at once to the in-transit head and the integration module sends it ({@link DepositEvents#TRANSFER_OUT});
 * when the bank pays, the in-transit head is cleared, and when it fails or comes back the money returns to the account.
 * Money received through a gateway payment link credits the account, or funds a booked term deposit. Those calls come
 * through {@link DepositOperations}. Bank account numbers are sealed with the tenant's data key and shown masked.
 */
@Service
public class DepositMoneyService implements DepositOperations {

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
    static final String INSTALMENT = "INSTALMENT";
    static final Set<String> TXN_TYPES = Set.of(CASH_DEPOSIT, CASH_WITHDRAWAL, BANK_TRANSFER_IN, BANK_TRANSFER_OUT);
    static final Set<String> HOLD_KINDS = Set.of("DEBIT_FREEZE", "CREDIT_FREEZE", "TOTAL_FREEZE", "LIEN");
    private static final String NAME_AAD = "deposits.bank_account.holder_name";
    private static final String ACCOUNT_AAD = "deposits.bank_account.number";

    /** openapi.yaml#/components/schemas/BankAccountInput: an account at another bank. */
    public record BankAccountInput(String holderName, String accountNumber, String ifsc) {}

    /**
     * openapi.yaml#/components/schemas/DepositTransactionInput. {@code bankAccount}: the other bank's account.
     * {@code viaGateway}: a bank transfer out sent through the payment gateway (no UTR yet: the bank gives it when it pays).
     */
    public record TransactionInput(String type, BigDecimal amount, String reference, String narration, BankAccountInput bankAccount,
                                   Boolean viaGateway) {}

    /** openapi.yaml#/components/schemas/DepositTransferInput. */
    public record TransferInput(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String narration) {}

    /** openapi.yaml#/components/schemas/ReasonInput. */
    public record ReasonInput(String reason) {}

    /** openapi.yaml#/components/schemas/DepositHoldInput. */
    public record HoldInput(String kind, BigDecimal amount, String reason, String reference) {}

    /** openapi.yaml#/components/schemas/TermDepositFundingInput. */
    public record FundingInput(String source, UUID fromAccountId, String reference, BankAccountInput payoutBankAccount) {}

    /** openapi.yaml#/components/schemas/RdInstalmentInput: the next instalment of a recurring deposit, paid from an account, in cash or by a bank transfer. */
    public record InstalmentInput(String source, UUID fromAccountId, String reference) {}

    /** What an instalment request carries; the late charge is worked out again on the day it is applied. */
    public record Instalment(String depositId, String depositNo, int number, String dueDate, String amount, String lateCharge, String source,
                             String fromAccountId, String fromAccountNo, String reference, String limitType) {}

    /** A bank account as an approval request carries it: the holder and the number sealed, the rest in clear. */
    public record SealedBankAccount(String holderNameSealed, String accountNumberSealed, String last4, String ifsc) {}

    /**
     * What a money request carries. {@code limitType}: the role amount limit the checker's approval counts against.
     * For a transfer {@code accountId} pays and {@code toAccountId} receives.
     */
    public record Txn(String type, String accountId, String accountNo, String toAccountId, String toAccountNo, String amount,
                      String reference, String narration, SealedBankAccount bankAccount, String movementId, String reason,
                      String limitType, Boolean viaGateway) {}

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
    private final Outbox outbox;
    private final DepositLifecycleService lifecycle;

    DepositMoneyService(JdbcTemplate jdbc, ApprovalService approvals, AmountLimitService limits, PostingService postings, LedgerSettings ledger,
                        BusinessDays days, BranchScope scope, PiiKeys keys, Json json, Outbox outbox, @Lazy DepositLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.limits = limits;
        this.postings = postings;
        this.ledger = ledger;
        this.days = days;
        this.scope = scope;
        this.keys = keys;
        this.json = json;
        this.outbox = outbox;
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
        boolean gateway = Boolean.TRUE.equals(in.viaGateway());
        List<String> problems = new ArrayList<>();
        String reference = text(in.reference(), 60, "reference", problems);
        String narration = text(in.narration(), 200, "narration", problems);
        if (gateway && !BANK_TRANSFER_OUT.equals(in.type())) problems.add("only a bank transfer out is sent through the payment gateway");
        if (bank && !gateway && reference == null) problems.add("the bank's reference (UTR) is required for a bank transfer");
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
                null, null, limitType, gateway ? Boolean.TRUE : null);
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
                narration, null, null, null, "DEPOSIT_DEBIT", null);
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
                m.id().toString(), reason, null, null);
        return approvals.propose(TXN_ENTITY, REVERSE, m.id().toString(), toMap(t), null, m.amount(), a.branch(), idempotencyKey);
    }

    /** Posts an approved money request; returns the id of the (first) movement written. */
    String applyTxn(ApprovalRequest r) {
        if (DepositLifecycleService.CHARGE.equals(r.action())) return lifecycle.applyCharge(r);
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
        boolean gateway = Boolean.TRUE.equals(t.viaGateway()) && BANK_TRANSFER_OUT.equals(t.type());
        TransactionLot lot = switch (t.type()) {
            case CASH_DEPOSIT -> post.cashDeposit(a.leg(), amount, ref, t.narration());
            case CASH_WITHDRAWAL -> post.cashWithdrawal(a.leg(), amount, ref, t.narration());
            case BANK_TRANSFER_IN -> post.bankTransferIn(a.leg(), amount, ref, t.narration());
            case BANK_TRANSFER_OUT -> gateway ? post.gatewayTransferOut(a.leg(), amount, ref, t.narration())
                                              : post.bankTransferOut(a.leg(), amount, ref, t.narration());
            default -> throw new IllegalStateException("unknown transaction type " + t.type());
        };
        postings.post(lot, CurrentUser.username());
        UUID id = insertMovement(a.id(), t.type(), credit ? "CREDIT" : "DEBIT", amount, lot.id(), t.reference(), t.narration(), null,
                t.bankAccount(), r.id(), r.maker());
        if (gateway) {
            jdbc.update("INSERT INTO deposits.gateway_transfer (movement_id, status) VALUES (?, 'SENT')", id);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("movementId", id.toString());
            e.put("accountId", a.id().toString());
            e.put("accountNo", a.accountNo());
            e.put("customerId", a.customerId().toString());
            e.put("branch", a.branch());
            e.put("amount", DepositRuleSets.plain(amount));
            e.put("businessDate", today.toString());
            outbox.publish(jdbc, DepositEvents.TRANSFER_OUT, a.accountNo(), e);
        }
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
        UUID out = insertMovement(from.id(), "TRANSFER_OUT", "DEBIT", amount, lot.id(), null, t.narration(), to.id(), null, r.id(), r.maker());
        insertMovement(to.id(), "TRANSFER_IN", "CREDIT", amount, lot.id(), null, t.narration(), from.id(), null, r.id(), r.maker());
        checkNow();
        return out.toString();
    }

    private String reverse(Txn t, ApprovalRequest r) {
        Movement m = movement(UUID.fromString(t.movementId()));
        DepositAccountService.refuseIfAny("the movement cannot be reversed", reversalProblems(m));
        UUID back = reverseLot(m, t.reason(), r.id(), r.maker());
        if ("CHARGE".equals(m.kind())) lifecycle.cancelCharges(m.id(), back, days.requireOpen());
        return back.toString();
    }

    /**
     * Reverses every movement of the movement's lot with the mirror lot; returns the id of the reversal of {@code m}.
     * {@code approvalId} is null for a reversal the system makes (a gateway transfer that failed).
     */
    private UUID reverseLot(Movement m, String reason, UUID approvalId, String maker) {
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
                    each.id(), reason, approvalId, maker);
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
        problems.addAll(DepositPledges.releaseProblems(jdbc, holdId));
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
        boolean recorded = Boolean.TRUE.equals(td.get("payout_bank_recorded"));       // recorded for a payment link (P4-2b)
        if (toBank && in.payoutBankAccount() == null && !recorded) {
            problems.add("the deposit is paid out to a bank account: record that account now");
        } else if (!toBank && in.payoutBankAccount() != null) {
            problems.add("the deposit is paid out to account " + td.get("payout_account_no") + " here; it takes no bank account");
        } else if (in.payoutBankAccount() != null) {
            bank = seal(in.payoutBankAccount(), problems);
        }
        problems.addAll(DepositRegister.capProblems(jdbc, principal));     // P4-6: an NBFC holds public deposits to 1.5 x its net owned fund
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
            reverseLot(m, f.reason(), r.id(), r.maker());
            RecurringDeposits.fundingReversed(jdbc, m.id());
            reverseAccruedInterest(d, f.reason());
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
        problems.addAll(DepositRegister.capProblems(jdbc, principal));     // P4-6: an NBFC holds public deposits to 1.5 x its net owned fund
        DepositAccountService.refuseIfAny("the deposit cannot be funded", problems);
        LocalDate today = days.requireOpen();
        FundingSource source = FundingSource.valueOf(f.source());
        TransactionLot lot = new DepositPostings(heads(), today).funding(d.leg(), principal, source, from == null ? null : from.leg(),
                f.reference() == null ? d.accountNo() : f.reference());
        postings.post(lot, CurrentUser.username());
        UUID funding = insertMovement(d.id(), "FUNDING", "CREDIT", principal, lot.id(), f.reference(), null, from == null ? null : from.id(), null,
                r.id(), r.maker());
        if (from != null) insertMovement(from.id(), "FUNDING_PAID", "DEBIT", principal, lot.id(), f.reference(), null, d.id(), null, r.id(), r.maker());
        Tenure tenure = new Tenure((Integer) td.get("tenure_months"), (Integer) td.get("tenure_days"));
        SealedBankAccount bank = f.payoutBankAccount();
        jdbc.update("""
                UPDATE deposits.term_deposit SET start_date = ?, maturity_date = ?, funding_source = ?, funding_account_id = ?, funding_movement_id = ?,
                       funding_reference = ?, payout_bank_name_cipher = coalesce(?, payout_bank_name_cipher),
                       payout_bank_account_cipher = coalesce(?, payout_bank_account_cipher),
                       payout_bank_account_last4 = coalesce(?, payout_bank_account_last4), payout_bank_ifsc = coalesce(?, payout_bank_ifsc)
                 WHERE account_id = ?
                """, today, tenure.addTo(today), f.source(), from == null ? null : from.id(), funding, f.reference(),
                bank == null ? null : Base64.getDecoder().decode(bank.holderNameSealed()),
                bank == null ? null : Base64.getDecoder().decode(bank.accountNumberSealed()), bank == null ? null : bank.last4(),
                bank == null ? null : bank.ifsc(), d.id());
        jdbc.update("UPDATE deposits.account SET status = 'ACTIVE' WHERE id = ?", d.id());
        // after the start date is set: instalment 1 falls due on it
        RecurringDeposits.recordFunding(jdbc, d.id(), principal, today, f.source(), funding, r.id(), r.maker());
        checkNow();
        return d.accountNo();
    }

    // ================================================================================================ recurring deposit instalments (P4-5)

    /** The instalments of a recurring deposit: the schedule, what is paid and overdue, and what the next one takes today. */
    public Map<String, Object> instalments(UUID depositId) {
        Account d = account(depositId, DepositProductService.TERM_DEPOSIT, true);
        if (!RecurringDeposits.isRd(jdbc, d.id())) throw ApiException.notFound("recurring deposit " + depositId);
        return RecurringDeposits.schedule(jdbc, d.id(), days.requireOpen());
    }

    /** Why the next instalment of a recurring deposit cannot be paid today, from {@code from} (null: cash or a bank transfer). */
    private List<String> instalmentProblems(Account d, Map<String, Object> next, Account from, BigDecimal total) {
        List<String> out = new ArrayList<>();
        if (!"ACTIVE".equals(d.status())) out.add("recurring deposit " + d.accountNo() + " is " + d.status() + "; instalments are paid while it is active");
        if (next == null) out.add("every instalment of recurring deposit " + d.accountNo() + " is paid");
        else if (!Boolean.TRUE.equals(next.get("payableNow")) && "ACTIVE".equals(d.status())) out.add("recurring deposit " + d.accountNo() + " has reached its maturity date");
        for (String p : movementProblems(d, Direction.CREDIT, total, false)) out.add("deposit " + d.accountNo() + ": " + p);
        if (next != null) out.addAll(DepositRegister.capProblems(jdbc, new BigDecimal((String) next.get("amount"))));
        if (from != null) {
            if (!holds(from.id(), d.customerId())) out.add("account " + from.accountNo() + " is not held by the depositor");
            for (String p : movementProblems(from, Direction.DEBIT, total, false)) out.add("account " + from.accountNo() + ": " + p);
        }
        return out;
    }

    /**
     * Proposes paying the next instalment of a recurring deposit, with its late charge if it is paid after its due date:
     * from a savings or current account of the depositor, in cash or by a bank transfer (with its UTR). Posts at once
     * below the tenant's threshold, like a funding.
     */
    @Transactional
    public ApprovalRequest proposeInstalment(UUID depositId, InstalmentInput in, String idempotencyKey) {
        Account d = account(depositId, DepositProductService.TERM_DEPOSIT, true);
        if (!RecurringDeposits.isRd(jdbc, d.id())) throw ApiException.invalid("deposit " + d.accountNo() + " is not a recurring deposit; it takes no instalments");
        if (in == null || in.source() == null || !Set.of("ACCOUNT", "CASH", "BANK_TRANSFER").contains(in.source())) {
            throw ApiException.invalid("source is one of ACCOUNT, CASH, BANK_TRANSFER");
        }
        if ("BOOKED".equals(d.status())) throw ApiException.invalid("recurring deposit " + d.accountNo() + " is booked: its first instalment is its funding");
        List<String> problems = new ArrayList<>();
        String reference = text(in.reference(), 60, "reference", problems);
        if ("BANK_TRANSFER".equals(in.source()) && reference == null) problems.add("the bank's reference (UTR) is required for a bank transfer");
        Account from = null;
        if ("ACCOUNT".equals(in.source())) {
            if (in.fromAccountId() == null) problems.add("name the savings or current account the instalment is paid from");
            else from = account(in.fromAccountId(), DepositProductService.CASA, false);
        } else if (in.fromAccountId() != null) {
            problems.add("only an instalment from an account names one");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) RecurringDeposits.schedule(jdbc, d.id(), days.requireOpen()).get("next");
        BigDecimal amount = next == null ? BigDecimal.ZERO : new BigDecimal((String) next.get("amount"));
        BigDecimal late = next == null ? BigDecimal.ZERO : new BigDecimal((String) next.get("lateCharge"));
        problems.addAll(instalmentProblems(d, next, from, amount.add(late)));
        DepositAccountService.refuseIfAny("the instalment cannot be paid", problems);
        String limitType = from != null ? "DEPOSIT_DEBIT" : "DEPOSIT_CREDIT";
        limits.require(limitType, AmountLimitService.MAKE, amount.add(late), d.accountNo());
        Instalment i = new Instalment(d.id().toString(), d.accountNo(), (Integer) next.get("number"), String.valueOf(next.get("dueDate")),
                DepositRuleSets.plain(amount), DepositRuleSets.plain(late), in.source(), from == null ? null : from.id().toString(),
                from == null ? null : from.accountNo(), reference, limitType);
        return approvals.propose(DepositAccountService.TD_ENTITY, INSTALMENT, d.id().toString(), toMap(i), null, amount.add(late), d.branch(), idempotencyKey);
    }

    /** Pays an approved instalment; returns the id of the movement into the deposit. */
    String applyInstalment(ApprovalRequest r) {
        Instalment i = convert(r.payload(), Instalment.class);
        Account d = account(UUID.fromString(i.depositId()), DepositProductService.TERM_DEPOSIT, false);
        Account from = i.fromAccountId() == null ? null : account(UUID.fromString(i.fromAccountId()), DepositProductService.CASA, false);
        return payInstalment(d, from, i.source(), i.reference(), i.number(), r.id(), r.maker()).toString();
    }

    /**
     * Pays the next instalment of a recurring deposit now: the lot, the movements and the instalment record. The late
     * charge is the one due today. {@code expected}: the instalment number the request was raised for (0: any).
     */
    UUID payInstalment(Account d, Account from, String source, String reference, int expected, UUID approvalId, String maker) {
        LocalDate today = days.requireOpen();
        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) RecurringDeposits.schedule(jdbc, d.id(), today).get("next");
        if (next != null && expected > 0 && ((Integer) next.get("number")) != expected) {
            throw ApiException.conflict("instalment " + expected + " of recurring deposit " + d.accountNo() + " is already paid");
        }
        BigDecimal amount = next == null ? BigDecimal.ZERO : new BigDecimal((String) next.get("amount"));
        BigDecimal late = next == null ? BigDecimal.ZERO : new BigDecimal((String) next.get("lateCharge"));
        DepositAccountService.refuseIfAny("the instalment cannot be paid", instalmentProblems(d, next, from, amount.add(late)));
        int number = (Integer) next.get("number");
        String ref = reference == null ? d.accountNo() + "/" + number : reference;
        FundingSource fs = from != null ? FundingSource.ACCOUNT : "BANK_TRANSFER".equals(source) ? FundingSource.BANK_TRANSFER : FundingSource.CASH;
        TransactionLot lot = new DepositPostings(heads(), today).rdInstalment(d.leg(), amount, late, DepositClosing.chargeHeads(jdbc).income(), fs,
                from == null ? null : from.leg(), ref, number);
        postings.post(lot, CurrentUser.username());
        UUID in = insertMovement(d.id(), "RD_INSTALMENT", "CREDIT", amount, lot.id(), reference, "Instalment " + number, from == null ? null : from.id(), null,
                approvalId, maker);
        if (from != null) {
            insertMovement(from.id(), "FUNDING_PAID", "DEBIT", amount, lot.id(), reference, "Instalment " + number + " of " + d.accountNo(), d.id(), null,
                    approvalId, maker);
            if (late.signum() > 0) {
                insertMovement(from.id(), "RD_LATE_CHARGE", "DEBIT", late, lot.id(), reference, "Late charge, instalment " + number + " of " + d.accountNo(),
                        d.id(), null, approvalId, maker);
            }
        }
        RecurringDeposits.record(jdbc, d.id(), number, LocalDate.parse(String.valueOf(next.get("dueDate"))), amount, late, today, source, in, lot.id(),
                approvalId, maker);
        checkNow();
        return in;
    }

    // ================================================================================================ standing instructions (P4-5b)

    /** What a standing instruction paid: the debit to the paying account, its lot and the amount debited. */
    record Paid(UUID movementId, UUID lotId, BigDecimal amount) {}

    /** A transfer made by a standing instruction: as a staff transfer, by the system, with no approval request. */
    Paid siTransfer(Account from, Account to, BigDecimal amount, String reference, String narration) {
        List<String> problems = new ArrayList<>(movementProblems(from, Direction.DEBIT, amount, false));
        for (String p : movementProblems(to, Direction.CREDIT, amount, false)) problems.add("account " + to.accountNo() + ": " + p);
        DepositAccountService.refuseIfAny("the transfer cannot be made", problems);
        TransactionLot lot = new DepositPostings(heads(), days.requireOpen()).transfer(from.leg(), to.leg(), amount, reference, narration);
        postings.post(lot, CurrentUser.username());
        UUID out = insertMovement(from.id(), "TRANSFER_OUT", "DEBIT", amount, lot.id(), reference, narration, to.id(), null, null, CurrentUser.username());
        insertMovement(to.id(), "TRANSFER_IN", "CREDIT", amount, lot.id(), reference, narration, from.id(), null, null, CurrentUser.username());
        checkNow();
        return new Paid(out, lot.id(), amount);
    }

    /**
     * The deposit side of a loan instalment paid by a standing instruction: the paying account is debited and the loan's
     * collection head credited, in the loan's branch. The loan then takes the repayment from that head
     * ({@link com.corebanking.lending.LoanOperations#postRepayment}), in the same transaction.
     */
    Paid siLoanRepayment(Account from, com.corebanking.lending.LoanOperations.Terms loan, BigDecimal amount, String reference, String narration) {
        if (loan.collectionGl() == null) throw ApiException.conflict("loan " + loan.loanNo() + " is " + loan.status() + "; it takes no repayment");
        DepositAccountService.refuseIfAny("the instalment cannot be paid", movementProblems(from, Direction.DEBIT, amount, false));
        Leg collection = new Leg(loan.branch(), loan.collectionGl(), loan.collectionGl());
        TransactionLot lot = new DepositPostings(heads(), days.requireOpen()).transfer(from.leg(), collection, amount, reference, narration);
        postings.post(lot, CurrentUser.username());
        UUID out = insertMovement(from.id(), "LOAN_REPAYMENT", "DEBIT", amount, lot.id(), reference, narration, null, null, null, CurrentUser.username());
        checkNow();
        return new Paid(out, lot.id(), amount);
    }

    /** The next instalment of a recurring deposit, paid by a standing instruction from a savings or current account. */
    Paid siRdInstalment(Account from, Account rd, String reference) {
        UUID in = payInstalment(rd, from, "STANDING_INSTRUCTION", reference, 0, null, CurrentUser.username());
        Map<String, Object> m = jdbc.queryForMap("""
                SELECT o.id, o.lot_id, (SELECT coalesce(sum(x.amount), 0) FROM deposits.movement x WHERE x.lot_id = o.lot_id AND x.account_id = o.account_id)::numeric AS paid
                  FROM deposits.movement o JOIN deposits.movement i ON i.lot_id = o.lot_id
                 WHERE i.id = ? AND o.account_id = ? AND o.kind = 'FUNDING_PAID'
                """, in, from.id());
        return new Paid((UUID) m.get("id"), (UUID) m.get("lot_id"), (BigDecimal) m.get("paid"));
    }

    /** Why the account cannot take this credit now (a credit freeze); empty when it can. */
    List<String> creditProblems(Account to, BigDecimal amount) {
        return movementProblems(to, Direction.CREDIT, amount, false);
    }

    /** Why the paying account cannot pay this amount now; empty when it can. */
    List<String> debitProblems(Account from, BigDecimal amount) {
        return movementProblems(from, Direction.DEBIT, amount, false);
    }

    // ================================================================================================ the gateway (DepositOperations)

    @Override
    public AccountRef find(UUID accountId) {
        Account a = account(accountId, null, false);
        BigDecimal principal = null;
        String payoutTo = null;
        boolean bankRecorded = false;
        if (DepositProductService.TERM_DEPOSIT.equals(a.family())) {
            Map<String, Object> td = jdbc.queryForMap("""
                    SELECT principal::numeric AS principal, payout_to, payout_bank_ifsc IS NOT NULL AS recorded FROM deposits.term_deposit WHERE account_id = ?
                    """, accountId);
            principal = ((BigDecimal) td.get("principal")).stripTrailingZeros();
            payoutTo = (String) td.get("payout_to");
            bankRecorded = Boolean.TRUE.equals(td.get("recorded"));
        }
        return new AccountRef(a.id(), a.accountNo(), a.family(), a.status(), a.branch(), a.customerId(), principal, payoutTo, bankRecorded);
    }

    @Override
    public TransferOut transferOut(UUID movementId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT m.account_id, a.account_no, a.customer_id, a.branch_code, m.amount::numeric AS amount, m.party_name_cipher, m.party_account_cipher,
                       m.party_ifsc, g.status
                  FROM deposits.gateway_transfer g JOIN deposits.movement m ON m.id = g.movement_id JOIN deposits.account a ON a.id = m.account_id
                 WHERE g.movement_id = ?
                """, movementId);
        if (rows.isEmpty()) throw ApiException.notFound("gateway transfer " + movementId);
        Map<String, Object> r = rows.get(0);
        PiiCipher cipher = keys.forTenant(CurrentUser.requireTenant());
        Payee payee = new Payee(cipher.decrypt((byte[]) r.get("party_name_cipher"), NAME_AAD), cipher.decrypt((byte[]) r.get("party_account_cipher"), ACCOUNT_AAD),
                (String) r.get("party_ifsc"));
        return new TransferOut(movementId, (UUID) r.get("account_id"), (String) r.get("account_no"), (UUID) r.get("customer_id"),
                (String) r.get("branch_code"), ((BigDecimal) r.get("amount")).stripTrailingZeros(), payee, (String) r.get("status"));
    }

    @Override
    @Transactional
    public void settleTransferOut(UUID movementId, String utr) {
        Map<String, Object> g = gatewayTransfer(movementId);
        String status = (String) g.get("status");
        if ("PAID".equals(status)) return;
        if ("RETURNED".equals(status)) throw ApiException.conflict("the transfer has already come back to the account");
        Movement m = movement(movementId);
        Account a = account(m.accountId(), null, false);
        String ref = utr == null || utr.isBlank() ? a.accountNo() : utr.trim();
        TransactionLot lot = new DepositPostings(heads(), days.requireOpen()).gatewayPaid(a.branch(), a.accountNo(), m.amount(), ref);
        postings.post(lot, CurrentUser.username());
        jdbc.update("UPDATE deposits.gateway_transfer SET status = 'PAID', paid_lot_id = ?, utr = ? WHERE movement_id = ?", lot.id(),
                utr == null || utr.isBlank() ? null : utr.trim(), movementId);
    }

    @Override
    @Transactional
    public void returnTransferOut(UUID movementId, String reason) {
        Map<String, Object> g = gatewayTransfer(movementId);
        String status = (String) g.get("status");
        if ("RETURNED".equals(status)) return;
        String why = reason == null || reason.isBlank() ? "the gateway transfer did not reach the payee" : reason.trim();
        if (why.length() > 500) why = why.substring(0, 500);
        Movement m = movement(movementId);
        // a term deposit closed by this transfer waits as MATURED again for its money (P4-4)
        Account closed = account(m.accountId(), null, false);
        if (DepositProductService.TERM_DEPOSIT.equals(closed.family()) && "CLOSED".equals(closed.status())) {
            jdbc.update("UPDATE deposits.account SET status = 'MATURED' WHERE id = ?", closed.id());
            jdbc.update("UPDATE deposits.closure SET payout_returned_on = ? WHERE account_id = ? AND payout_returned_on IS NULL", days.requireOpen(), closed.id());
        }
        DepositAccountService.refuseIfAny("the transfer cannot be taken back", reversalProblems(m, true));
        LocalDate today = days.requireOpen();
        UUID paidReversal = null;
        if ("PAID".equals(status)) {
            // the bank sent it back: the paid posting is reversed first, then the money returns to the account
            TransactionLot back = TransactionLot.reversal(postings.load((UUID) g.get("paid_lot_id")), today, why);
            postings.post(back, CurrentUser.username());
            paidReversal = back.id();
        }
        reverseLot(m, why, null, CurrentUser.username());
        jdbc.update("UPDATE deposits.gateway_transfer SET status = 'RETURNED', return_lot_id = ?, return_reason = ? WHERE movement_id = ?",
                paidReversal, why, movementId);
        checkNow();
    }

    @Override
    @Transactional
    public UUID receiveFromGateway(UUID accountId, BigDecimal amount, String reference) {
        Account a = account(accountId, null, false);
        String ref = reference == null || reference.isBlank() ? null : reference.trim();
        if (ref != null && ref.length() > 60) ref = ref.substring(0, 60);
        if (amount == null || amount.signum() <= 0) throw ApiException.invalid("the amount is required");
        LocalDate today = days.requireOpen();
        DepositPostings post = new DepositPostings(heads(), today);
        if (DepositProductService.CASA.equals(a.family())) {
            DepositAccountService.refuseIfAny("the money cannot be credited", movementProblems(a, Direction.CREDIT, amount, false));
            TransactionLot lot = post.bankTransferIn(a.leg(), amount, ref == null ? a.accountNo() : ref, "Payment link");
            postings.post(lot, CurrentUser.username());
            UUID id = insertMovement(a.id(), BANK_TRANSFER_IN, "CREDIT", amount, lot.id(), ref, "Payment link", null, null, null, CurrentUser.username());
            checkNow();
            return id;
        }
        Map<String, Object> td = termDeposit(a.id());
        BigDecimal principal = ((BigDecimal) td.get("principal")).stripTrailingZeros();
        List<String> problems = new ArrayList<>(fundingProblems(a, td));
        if (principal.compareTo(amount) != 0) {
            problems.add("deposit " + a.accountNo() + " is funded with its principal of " + principal.toPlainString() + ", not " + amount.stripTrailingZeros().toPlainString());
        }
        if ("BANK_ACCOUNT".equals(td.get("payout_to")) && !Boolean.TRUE.equals(td.get("payout_bank_recorded"))) {
            problems.add("the deposit is paid out to a bank account that is not recorded");
        }
        problems.addAll(DepositRegister.capProblems(jdbc, principal));     // P4-6: an NBFC holds public deposits to 1.5 x its net owned fund
        DepositAccountService.refuseIfAny("the deposit cannot be funded", problems);
        TransactionLot lot = post.funding(a.leg(), principal, FundingSource.GATEWAY, null, ref == null ? a.accountNo() : ref);
        postings.post(lot, CurrentUser.username());
        UUID funding = insertMovement(a.id(), "FUNDING", "CREDIT", principal, lot.id(), ref, "Payment link", null, null, null, CurrentUser.username());
        Tenure tenure = new Tenure((Integer) td.get("tenure_months"), (Integer) td.get("tenure_days"));
        jdbc.update("""
                UPDATE deposits.term_deposit SET start_date = ?, maturity_date = ?, funding_source = 'GATEWAY', funding_account_id = NULL,
                       funding_movement_id = ?, funding_reference = ?
                 WHERE account_id = ?
                """, today, tenure.addTo(today), funding, ref, a.id());
        jdbc.update("UPDATE deposits.account SET status = 'ACTIVE' WHERE id = ?", a.id());
        RecurringDeposits.recordFunding(jdbc, a.id(), principal, today, "GATEWAY", funding, null, CurrentUser.username());
        checkNow();
        return funding;
    }

    @Override
    @Transactional
    public void recordPayoutBankAccount(UUID depositId, Payee in) {
        Account d = account(depositId, DepositProductService.TERM_DEPOSIT, false);
        Map<String, Object> td = termDeposit(depositId);
        List<String> problems = new ArrayList<>();
        if (!"BOOKED".equals(d.status())) problems.add("deposit " + d.accountNo() + " is " + d.status() + "; its payout account is recorded before it is funded");
        if (!"BANK_ACCOUNT".equals(td.get("payout_to"))) problems.add("the deposit is paid out to account " + td.get("payout_account_no") + " here; it takes no bank account");
        SealedBankAccount bank = in == null ? null : seal(new BankAccountInput(in.holderName(), in.accountNumber(), in.ifsc()), problems);
        if (in == null) problems.add("the bank account is required");
        DepositAccountService.refuseIfAny("the payout account cannot be recorded", problems);
        jdbc.update("""
                UPDATE deposits.term_deposit SET payout_bank_name_cipher = ?, payout_bank_account_cipher = ?, payout_bank_account_last4 = ?, payout_bank_ifsc = ?
                 WHERE account_id = ?
                """, Base64.getDecoder().decode(bank.holderNameSealed()), Base64.getDecoder().decode(bank.accountNumberSealed()), bank.last4(), bank.ifsc(),
                depositId);
    }

    private Map<String, Object> gatewayTransfer(UUID movementId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status, paid_lot_id FROM deposits.gateway_transfer WHERE movement_id = ? FOR UPDATE", movementId);
        if (rows.isEmpty()) throw ApiException.notFound("gateway transfer " + movementId);
        return rows.get(0);
    }

    /**
     * Interest the day-end accrued on a deposit whose funding is being reversed, and that was never credited (anything
     * credited would have stopped the reversal): booked back to the expense head; the deposit starts afresh if funded
     * again.
     */
    private void reverseAccruedInterest(Account d, String reason) {
        List<Map<String, Object>> st = jdbc.queryForList("SELECT accrued::numeric AS accrued FROM deposits.interest_state WHERE account_id = ?", d.id());
        if (st.isEmpty()) return;
        BigDecimal accrued = (BigDecimal) st.get(0).get("accrued");
        if (accrued.signum() > 0) {
            String expense = jdbc.queryForObject("SELECT p.gl_interest_expense FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code WHERE a.id = ?",
                    String.class, d.id());
            LocalDate today = days.requireOpen();
            TransactionLot lot = new DepositPostings(heads(), today).interestAccrual(d.branch(), expense, DepositEod.payableGl(jdbc), d.accountNo(),
                    accrued.negate(), d.accountNo());
            postings.post(lot, CurrentUser.username());
            jdbc.update("""
                    INSERT INTO deposits.interest_event (account_id, kind, amount, lot_id, business_date, note, created_by)
                    VALUES (?, 'REVERSAL', ?, ?, ?, ?, ?)
                    """, d.id(), accrued.negate(), lot.id(), today, "funding reversed: " + reason, CurrentUser.username());
        }
        jdbc.update("DELETE FROM deposits.interest_state WHERE account_id = ?", d.id());
    }

    /**
     * Where an account's interest stands: accrued through which day, accrued and not yet credited, credited so far,
     * the next credit date, the day-end's events (newest first) and, for a term deposit, its interest periods.
     */
    public Map<String, Object> interestView(String family, UUID accountId) {
        Account a = account(accountId, family, true);
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> st = jdbc.queryForList("""
                SELECT accrued_through::text AS through, accrued::numeric AS accrued, credited::numeric AS credited, periods_credited,
                       last_credit_on::text AS last_credit FROM deposits.interest_state WHERE account_id = ?
                """, accountId);
        Map<String, Object> s = st.isEmpty() ? null : st.get(0);
        m.put("accruedThrough", s == null ? null : s.get("through"));
        m.put("accrued", DepositRuleSets.plain(s == null ? BigDecimal.ZERO : (BigDecimal) s.get("accrued")));
        m.put("credited", DepositRuleSets.plain(s == null ? BigDecimal.ZERO : (BigDecimal) s.get("credited")));
        m.put("lastCreditOn", s == null ? null : s.get("last_credit"));
        m.put("taxDeducted", DepositRuleSets.plain(jdbc.queryForObject(
                "SELECT coalesce(sum(tax), 0)::numeric FROM deposits.tds_ledger WHERE account_id = ?", BigDecimal.class, accountId)));
        if (DepositProductService.CASA.equals(a.family())) {
            m.put("nextCreditDate", jdbc.queryForObject("SELECT next_interest_date::text FROM deposits.casa_account WHERE account_id = ?", String.class, accountId));
            m.put("schedule", List.of());
        } else {
            Map<String, Object> t = jdbc.queryForMap(DepositEod.TERMS, accountId);
            List<Map<String, Object>> schedule = new ArrayList<>();
            String next = null;
            boolean rd = RecurringDeposits.isRd(jdbc, accountId);
            if (t.get("start_date") != null && rd) {
                // a recurring deposit (P4-5): the credits of a deposit paid on time; months worked out by the day-end so far
                int months = jdbc.queryForObject("SELECT tenure_months FROM deposits.term_deposit WHERE account_id = ?", Integer.class, accountId);
                int done = s == null ? 0 : ((Number) s.get("periods_credited")).intValue();
                TermDeposit.Terms terms = DepositEod.terms(t);
                int k = terms.periodMonths();
                for (TermDeposit.Period p : com.corebanking.deposits.RecurringDeposit.periods(terms.start(), months, terms.principal(), terms.ratePercent(), k,
                        terms.rounding())) {
                    int lastMonth = Math.min(p.number() * k, months);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("number", p.number());
                    row.put("from", p.from().toString());
                    row.put("creditOn", p.to().toString());
                    row.put("balance", DepositRuleSets.plain(p.balance()));
                    row.put("interest", DepositRuleSets.plain(p.interest()));
                    row.put("status", lastMonth <= done ? "CREDITED" : "DUE");
                    if (next == null && lastMonth > done) next = p.to().toString();
                    schedule.add(row);
                }
                m.put("monthsWorkedOut", done);
            } else if (t.get("start_date") != null) {
                int credited = s == null ? 0 : ((Number) s.get("periods_credited")).intValue();
                for (TermDeposit.Period p : TermDeposit.periods(DepositEod.terms(t))) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("number", p.number());
                    row.put("from", p.from().toString());
                    row.put("creditOn", p.to().toString());
                    row.put("balance", DepositRuleSets.plain(p.balance()));
                    row.put("interest", DepositRuleSets.plain(p.interest()));
                    row.put("status", p.number() <= credited ? "CREDITED" : "DUE");
                    if (next == null && p.number() > credited) next = p.to().toString();
                    schedule.add(row);
                }
            }
            m.put("nextCreditDate", next);
            m.put("schedule", schedule);
        }
        m.put("events", jdbc.query("""
                SELECT id, kind, period_from::text, period_to::text, period_no, amount::numeric, business_date::text, note, movement_id::text
                  FROM deposits.interest_event WHERE account_id = ? ORDER BY id DESC LIMIT 200
                """, (rs, i) -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("id", rs.getLong(1));
                    e.put("kind", rs.getString(2));
                    e.put("periodFrom", rs.getString(3));
                    e.put("periodTo", rs.getString(4));
                    e.put("periodNo", rs.getObject(5) == null ? null : rs.getInt(5));
                    e.put("amount", DepositRuleSets.plain(rs.getBigDecimal(6)));
                    e.put("businessDate", rs.getString(7));
                    e.put("note", rs.getString(8));
                    e.put("movementId", rs.getString(9));
                    return e;
                }, accountId));
        return m;
    }

    // ================================================================================================ reading

    /** The movements of an account, newest first, with the balance after each; optionally between two business dates. */
    public List<Map<String, Object>> movements(String family, UUID accountId, LocalDate from, LocalDate to) {
        account(accountId, family, true);
        return jdbc.query("""
                SELECT m.id, m.seq, m.business_date, m.value_date, m.kind, m.direction, m.amount, m.balance_after, m.reference, m.narration,
                       c.account_no, m.party_account_last4, m.party_ifsc, m.reverses, m.reversed_by, m.reversal_reason, m.created_by, m.approval_id,
                       m.created_at, g.status, g.utr
                  FROM deposits.movement m LEFT JOIN deposits.account c ON c.id = m.counterparty_id
                  LEFT JOIN deposits.gateway_transfer g ON g.movement_id = m.id
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
                    m.put("gatewayStatus", rs.getString(20));
                    m.put("gatewayUtr", rs.getString(21));
                    return m;
                }, accountId, from, from, to, to);
    }

    /** Every freeze and lien of an account, active first. */
    public List<Map<String, Object>> holdList(String family, UUID accountId) {
        account(accountId, family, true);
        return jdbc.query("""
                SELECT h.id, h.kind, h.amount, h.reason, h.reference, h.status, h.placed_on, h.placed_by, h.released_on, h.released_by, h.release_reason,
                       h.loan_id::text, l.loan_no, l.status
                  FROM deposits.account_hold h LEFT JOIN lending.loan_account l ON l.id = h.loan_id
                 WHERE h.account_id = ? ORDER BY h.status = 'ACTIVE' DESC, h.created_at DESC
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
                    m.put("loanId", rs.getString(12));
                    m.put("loanNo", rs.getString(13));
                    m.put("loanStatus", rs.getString(14));
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
    List<String> movementProblems(Account a, Direction d, BigDecimal amount, boolean correction) {
        List<String> out = new ArrayList<>();
        if (DepositProductService.CASA.equals(a.family()) && !"ACTIVE".equals(a.status())) out.add("account " + a.accountNo() + " is " + a.status());
        if (amount == null) {
            out.add("the amount is required");
            return out;
        }
        if (!correction && !jdbc.queryForList("SELECT 1 FROM deposits.unclaimed WHERE account_id = ? AND refunded_on IS NULL", a.id()).isEmpty()) {
            out.add("account " + a.accountNo() + ": its balance was transferred as unclaimed; it is refunded to the claimant first");
            return out;
        }
        if (!correction && d == Direction.DEBIT && DepositProductService.CASA.equals(a.family())) {
            List<String> since = jdbc.queryForList("SELECT inoperative_since::text FROM deposits.casa_account WHERE account_id = ? AND inoperative_since IS NOT NULL",
                    String.class, a.id());
            if (!since.isEmpty()) out.add("account " + a.accountNo() + " is inoperative since " + since.get(0) + ": it is reactivated (KYC updated) before money is taken out of it");
        }
        out.addAll(AccountHolds.checkMovement(balance(a.id()), holds(a.id()), d, amount, correction));
        return out;
    }

    private List<String> reversalProblems(Movement m) {
        return reversalProblems(m, false);
    }

    /** @param gateway the system takes back a gateway transfer that failed or came back */
    private List<String> reversalProblems(Movement m, boolean gateway) {
        List<String> out = new ArrayList<>();
        if ("REVERSAL".equals(m.kind())) out.add("a reversal is not reversed");
        else if (m.reversedBy() != null) out.add("the movement is already reversed");
        else if ("FUNDING".equals(m.kind()) || "FUNDING_PAID".equals(m.kind())) {
            out.add("this movement funded a term deposit: reverse the funding of the deposit instead");
        } else if (!gateway && !jdbc.queryForList("SELECT 1 FROM deposits.gateway_transfer WHERE movement_id = ?", m.id()).isEmpty()) {
            out.add("this transfer was sent through the payment gateway: the money comes back only when the bank fails or returns it");
        }
        if (!out.isEmpty()) return out;
        List<Movement> lot = lotMovements(m.lotId());
        if (lot.stream().anyMatch(x -> Set.of("INTEREST_CREDIT", "INTEREST_PAYOUT", "TAX_DEDUCTED", "INTEREST_RECOVERY").contains(x.kind()))) {
            out.add("interest credited or paid out, and tax deducted, by the day-end are not reversed by hand");
            return out;
        }
        if (!gateway && lot.stream().anyMatch(x -> Set.of("CLOSURE", "RENEWAL").contains(x.kind()))) {
            out.add("a closure or a renewal is not reversed; the account is closed");
            return out;
        }
        for (Movement each : lot) {
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
        if ("RENEWAL".equals(td.get("funding_source"))) {
            out.add("deposit " + d.accountNo() + " was funded by renewing another; a renewal is not reversed");
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
    void checkNow() {
        jdbc.execute("SET CONSTRAINTS deposits.movement_matches_lot, deposits.movement_funded, deposits.account_funded, deposits.term_deposit_funded,"
                + " deposits.movement_rd_instalment IMMEDIATE");
        jdbc.execute("SET CONSTRAINTS deposits.movement_matches_lot, deposits.movement_funded, deposits.account_funded, deposits.term_deposit_funded,"
                + " deposits.movement_rd_instalment DEFERRED");
    }

    // ================================================================================================ data

    /**
     * An account of the family (any family when null). With {@code scoped}, it must be in the caller's branches;
     * outside them it is not found.
     */
    Account account(UUID id, String family, boolean scoped) {
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

    BigDecimal balance(UUID accountId) {
        List<BigDecimal> b = jdbc.queryForList("SELECT balance FROM deposits.account_balance WHERE account_id = ?", BigDecimal.class, accountId);
        return b.isEmpty() ? BigDecimal.ZERO : b.get(0);
    }

    private List<Hold> holds(UUID accountId) {
        return jdbc.query("SELECT kind, amount, reason FROM deposits.account_hold WHERE account_id = ? AND status = 'ACTIVE'",
                (rs, i) -> new Hold(AccountHolds.Kind.valueOf(rs.getString(1)), rs.getBigDecimal(2), rs.getString(3)), accountId);
    }

    boolean holds(UUID accountId, UUID customerId) {
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
                       t.funding_movement_id, t.payout_bank_ifsc IS NOT NULL AS payout_bank_recorded
                  FROM deposits.term_deposit t LEFT JOIN deposits.account pay ON pay.id = t.payout_account_id WHERE t.account_id = ?
                """, depositId);
    }

    UUID insertMovement(UUID accountId, String kind, String direction, BigDecimal amount, UUID lotId, String reference, String narration,
                                UUID counterparty, SealedBankAccount party, UUID approvalId, String maker) {
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
                party == null ? null : party.ifsc(), approvalId, maker);
        return id;
    }

    /** The settlement heads from the tenant's settings (tenant V30), with the starter chart's as defaults. */
    DepositPostings.Heads heads() {
        return headsOf(jdbc);
    }

    /** The same through any JdbcTemplate (the day-end's is bound to the tenant's own data source). */
    static DepositPostings.Heads headsOf(JdbcTemplate jdbc) {
        DepositPostings.Heads d = DepositPostings.Heads.starter();
        Map<String, String> p = new LinkedHashMap<>();
        jdbc.query("SELECT key, value FROM platform.system_property WHERE key IN ('deposits.gl.cash','deposits.gl.bank-in','deposits.gl.bank-out','deposits.gl.in-transit')",
                rs -> { p.put(rs.getString(1), rs.getString(2).trim()); });
        return new DepositPostings.Heads(p.getOrDefault("deposits.gl.cash", d.cash()), p.getOrDefault("deposits.gl.bank-in", d.bankIn()),
                p.getOrDefault("deposits.gl.bank-out", d.bankOut()), new LedgerSettings(jdbc).interBranchGl(), p.getOrDefault("deposits.gl.in-transit", d.inTransit()));
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
        private final DepositPledges pledges;

        HoldApplier(@Lazy DepositMoneyService money, @Lazy DepositPledges pledges) {
            this.money = money;
            this.pledges = pledges;
        }

        @Override public String entityType() { return HOLD_ENTITY; }

        @Override public String apply(ApprovalRequest r) {
            return DepositPledges.PLEDGE.equals(r.action()) ? pledges.apply(r) : money.applyHold(r);
        }
    }
}
