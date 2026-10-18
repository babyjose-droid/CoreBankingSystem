package com.corebanking.deposits.internal;

import com.corebanking.deposits.DepositCharges;
import com.corebanking.deposits.DepositPostings.Leg;
import com.corebanking.deposits.DepositPostings.PayTo;
import com.corebanking.deposits.DepositRefusal;
import com.corebanking.deposits.DepositRules;
import com.corebanking.deposits.TermDeposit;
import com.corebanking.ledger.PostingService;
import com.corebanking.platform.ApiException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The life of a deposit account after it is opened (P4-4), as staff ask for it: charges, closing a savings or current
 * account, closing a term deposit before or at maturity, its maturity instruction (with the depositor's consent on a
 * deposit-taking NBFC), and renewing a matured deposit. Every change goes through maker-checker: the maker sees the
 * figures and every reason for a refusal when proposing (422 with {@code problems}); the checks are made again when
 * the request is applied, by the same code the day-end uses ({@link DepositClosing}).
 */
@Service
public class DepositLifecycleService {

    static final String CHARGE = "CHARGE";
    static final String CLOSE = "CLOSE";
    static final String INSTRUCTION = "INSTRUCTION";
    static final String RENEW = "RENEW";

    /** openapi.yaml#/components/schemas/DepositPayoutInput: where proceeds go. */
    public record PayoutInput(String mode, UUID accountId, String reference) {}

    /** openapi.yaml#/components/schemas/DepositChargeInput. */
    public record ChargeInput(String code, String reason) {}

    /** openapi.yaml#/components/schemas/CasaClosureInput. */
    public record CasaClosureInput(PayoutInput payTo, String reason) {}

    /** openapi.yaml#/components/schemas/TermDepositClosureInput. {@code reason}: why a deposit is closed before maturity. */
    public record DepositClosureInput(String reason, PayoutInput payTo, String note) {}

    /** openapi.yaml#/components/schemas/RenewalConsentInput: the depositor's signed request to renew. */
    public record ConsentInput(String reference, LocalDate receivedOn) {}

    /** openapi.yaml#/components/schemas/MaturityInstructionInput. */
    public record InstructionInput(String maturityInstruction, ConsentInput consent) {}

    /** openapi.yaml#/components/schemas/TermDepositRenewalInput. */
    public record RenewalInput(String instruction, ConsentInput consent) {}

    /** What the requests carry. */
    record ChargeRequest(String accountId, String accountNo, String code, String description, String amount, String reason) {}

    record Closure(String family, String accountId, String accountNo, String reason, String mode, String toAccountId, String reference, String note,
                   String quotedPayable) {}

    record Instruction(String accountId, String accountNo, String instruction, String consentReference, String consentReceivedOn) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final PostingService postings;
    private final BusinessDays days;
    private final BranchScope scope;
    private final DepositRuleSets ruleSets;
    private final Json json;
    private final Outbox outbox;

    DepositLifecycleService(JdbcTemplate jdbc, ApprovalService approvals, PostingService postings, BusinessDays days, BranchScope scope,
                            DepositRuleSets ruleSets, Json json, Outbox outbox) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.postings = postings;
        this.days = days;
        this.scope = scope;
        this.ruleSets = ruleSets;
        this.json = json;
        this.outbox = outbox;
    }

    /** An account of the family, in the caller's branches (outside them it is not found). */
    private Map<String, Object> account(UUID id, String family, boolean scoped) {
        if (id == null) throw ApiException.invalid("the account is required");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.account_no, a.family, a.status, a.branch_code, a.customer_id, a.product_code, a.opened_on, p.gl_liability
                  FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code WHERE a.id = ?
                """, id);
        String what = (DepositProductService.TERM_DEPOSIT.equals(family) ? "term deposit " : "account ") + id;
        if (rows.isEmpty() || !family.equals(rows.get(0).get("family"))) throw ApiException.notFound(what);
        if (scoped) scope.requireRecord((String) rows.get(0).get("branch_code"), what);
        return rows.get(0);
    }

    private DepositBooks books(ApprovalRequest r) {
        return DepositBooks.request(jdbc, postings, days.requireOpen(), r.maker(), r.id(), CurrentUser.username());
    }

    /** Runs the work; a refusal by the closing code is a 422 with its problems; the deferred checks run before returning. */
    private <T> T applying(String what, Supplier<T> work) {
        T out;
        try {
            out = work.get();
        } catch (DepositRefusal e) {
            DepositAccountService.refuseIfAny(what, e.problems());
            throw e;
        }
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
        return out;
    }

    private static String text(String value, int max, String field, List<String> problems) {
        if (value == null || value.isBlank()) return null;
        String t = value.trim();
        if (t.length() > max) problems.add("the " + field + " is longer than " + max + " characters");
        return t;
    }

    private static DepositClosing.Payout payout(PayoutInput in, List<String> problems) {
        if (in == null || in.mode() == null) return null;
        PayTo to;
        try {
            to = PayTo.valueOf(in.mode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add("payTo.mode is ACCOUNT, CASH, BANK_TRANSFER or GATEWAY");
            return null;
        }
        if (to != PayTo.ACCOUNT && in.accountId() != null) problems.add("payTo.accountId goes with mode ACCOUNT");
        return new DepositClosing.Payout(to, in.accountId(), in.reference() == null || in.reference().isBlank() ? null : in.reference().trim());
    }

    private static DepositClosing.Payout payout(Closure c) {
        return c.mode() == null ? null : new DepositClosing.Payout(PayTo.valueOf(c.mode()), c.toAccountId() == null ? null : UUID.fromString(c.toAccountId()),
                c.reference());
    }

    // ================================================================================================ charges

    /** The charges levied on an account, newest first, with their invoices. */
    public List<Map<String, Object>> charges(UUID accountId) {
        account(accountId, DepositProductService.CASA, true);
        return jdbc.query("""
                SELECT c.id, c.charge_code, c.description, c.business_date, c.taxable_value, c.gst_rate, c.cgst, c.sgst, c.igst, c.total, c.invoice_no,
                       c.supplier_state, c.place_of_supply, c.status, c.cancelled_on, c.credit_note_no, c.movement_id, c.reason, c.created_by
                  FROM deposits.charge c WHERE c.account_id = ? ORDER BY c.created_at DESC LIMIT 500
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("code", rs.getString(2));
                    m.put("description", rs.getString(3));
                    m.put("businessDate", rs.getObject(4, LocalDate.class));
                    m.put("taxableValue", DepositRuleSets.plain(rs.getBigDecimal(5)));
                    m.put("gstRatePercent", DepositRuleSets.plain(rs.getBigDecimal(6)));
                    m.put("cgst", DepositRuleSets.plain(rs.getBigDecimal(7)));
                    m.put("sgst", DepositRuleSets.plain(rs.getBigDecimal(8)));
                    m.put("igst", DepositRuleSets.plain(rs.getBigDecimal(9)));
                    m.put("total", DepositRuleSets.plain(rs.getBigDecimal(10)));
                    m.put("invoiceNo", rs.getString(11));
                    m.put("supplierState", rs.getString(12));
                    m.put("placeOfSupply", rs.getString(13));
                    m.put("status", rs.getString(14));
                    m.put("cancelledOn", rs.getObject(15, LocalDate.class));
                    m.put("creditNoteNo", rs.getString(16));
                    m.put("movementId", rs.getString(17));
                    m.put("reason", rs.getString(18));
                    m.put("createdBy", rs.getString(19));
                    return m;
                }, accountId);
    }

    /** The month-ends evaluated against the product's minimum balance, newest first. */
    public List<Map<String, Object>> minimumBalance(UUID accountId) {
        account(accountId, DepositProductService.CASA, true);
        return jdbc.query("""
                SELECT m.month, m.from_date, m.to_date, m.required, m.average, m.shortfall, m.notified, c.invoice_no, c.total, m.note
                  FROM deposits.min_balance_month m LEFT JOIN deposits.charge c ON c.id = m.charge_id
                 WHERE m.account_id = ? ORDER BY m.month DESC LIMIT 120
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("month", rs.getObject(1, LocalDate.class));
                    m.put("from", rs.getObject(2, LocalDate.class));
                    m.put("to", rs.getObject(3, LocalDate.class));
                    m.put("required", DepositRuleSets.plain(rs.getBigDecimal(4)));
                    m.put("average", DepositRuleSets.plain(rs.getBigDecimal(5)));
                    m.put("shortfall", DepositRuleSets.plain(rs.getBigDecimal(6)));
                    m.put("notified", rs.getBoolean(7));
                    m.put("chargeInvoiceNo", rs.getString(8));
                    m.put("charged", DepositRuleSets.plain(rs.getBigDecimal(9)));
                    m.put("note", rs.getString(10));
                    return m;
                }, accountId);
    }

    /** Proposes one of the product's service charges on a savings or current account. */
    @Transactional
    public ApprovalRequest proposeCharge(UUID accountId, ChargeInput in, String idempotencyKey) {
        Map<String, Object> a = account(accountId, DepositProductService.CASA, true);
        List<String> problems = new ArrayList<>();
        if (in == null || in.code() == null || in.code().isBlank()) throw ApiException.invalid("code is required: one of the product's service charges");
        String reason = text(in.reason(), 500, "reason", problems);
        if (reason == null) problems.add("a reason is required");
        DepositCharges.Schedule s = DepositClosing.schedule(jdbc, json, accountId);
        DepositCharges.Service service = s.service(in.code().trim()).orElse(null);
        if (service == null) {
            problems.add("product " + a.get("product_code") + " has no service charge " + in.code().trim()
                    + (s.service().isEmpty() ? "" : "; it has " + String.join(", ", s.service().stream().map(DepositCharges.Service::code).toList())));
        }
        DepositCharges.Charge c = null;
        if (service != null) {
            c = DepositClosing.charge(jdbc, s, accountId, service.code(), service.name(), service.amount());
            problems.addAll(chargeProblems(a, c));
        }
        DepositAccountService.refuseIfAny("the charge cannot be levied", problems);
        ChargeRequest r = new ChargeRequest(accountId.toString(), (String) a.get("account_no"), c.code(), c.description(), c.taxable().toPlainString(), reason);
        return approvals.propose(DepositMoneyService.TXN_ENTITY, CHARGE, accountId.toString(), json.toMap(r), null, c.total(), (String) a.get("branch_code"),
                idempotencyKey);
    }

    private List<String> chargeProblems(Map<String, Object> a, DepositCharges.Charge c) {
        List<String> out = new ArrayList<>();
        UUID id = (UUID) a.get("id");
        if (!"ACTIVE".equals(a.get("status"))) out.add("account " + a.get("account_no") + " is " + a.get("status"));
        BigDecimal free = DepositClosing.available(jdbc, id);
        if (free.compareTo(c.total()) < 0) {
            out.add("account " + a.get("account_no") + " has " + DepositRuleSets.plain(free) + " free (after liens, and nothing when frozen for debits); the charge with GST is "
                    + DepositRuleSets.plain(c.total()));
        }
        return out;
    }

    String applyCharge(ApprovalRequest r) {
        ChargeRequest q = json.convert(r.payload(), ChargeRequest.class);
        UUID id = UUID.fromString(q.accountId());
        Map<String, Object> a = account(id, DepositProductService.CASA, false);
        DepositCharges.Schedule s = DepositClosing.schedule(jdbc, json, id);
        DepositCharges.Charge c = DepositClosing.charge(jdbc, s, id, q.code(), q.description(), new BigDecimal(q.amount()));
        DepositAccountService.refuseIfAny("the charge cannot be levied", chargeProblems(a, c));
        return applying("the charge cannot be levied", () -> {
            Leg leg = new Leg((String) a.get("branch_code"), (String) a.get("gl_liability"), (String) a.get("account_no"));
            DepositBooks b = books(r);
            UUID charge = DepositClosing.levy(b, id, leg, c, q.reason());
            return jdbc.queryForObject("SELECT movement_id::text FROM deposits.charge WHERE id = ?", String.class, charge);
        });
    }

    /** After the reversal of a charge's movement: the charge is cancelled with a credit note. */
    void cancelCharges(UUID reversedMovement, UUID reversal, LocalDate today) {
        jdbc.update("""
                UPDATE deposits.charge SET status = 'CANCELLED', cancelled_on = ?, credit_note_no = platform.next_number('GST_CREDIT_NOTE'), cancel_movement_id = ?
                 WHERE movement_id = ? AND status = 'ISSUED'
                """, today, reversal, reversedMovement);
    }

    // ================================================================================================ savings and current accounts

    /** What closing the account today would pay, and why it cannot be closed (empty when it can). */
    public Map<String, Object> casaClosureQuote(UUID id) {
        Map<String, Object> a = account(id, DepositProductService.CASA, true);
        DepositClosing.CasaQuote q = DepositClosing.casaQuote(jdbc, json, id, days.requireOpen());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountNo", a.get("account_no"));
        m.put("closeOn", days.requireOpen());
        m.put("balance", DepositRuleSets.plain(q.balance()));
        m.put("interest", DepositRuleSets.plain(q.interest()));
        m.put("closureCharge", q.charge() == null ? null : chargeView(q.charge()));
        m.put("payable", DepositRuleSets.plain(q.payable()));
        m.put("problems", q.problems());
        return m;
    }

    private static Map<String, Object> chargeView(DepositCharges.Charge c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", c.code());
        m.put("description", c.description());
        m.put("taxableValue", DepositRuleSets.plain(c.taxable()));
        m.put("gst", DepositRuleSets.plain(c.tax().total()));
        m.put("total", DepositRuleSets.plain(c.total()));
        return m;
    }

    @Transactional
    public ApprovalRequest proposeCasaClosure(UUID id, CasaClosureInput in, String idempotencyKey) {
        Map<String, Object> a = account(id, DepositProductService.CASA, true);
        List<String> problems = new ArrayList<>();
        String reason = in == null ? null : text(in.reason(), 500, "reason", problems);
        if (reason == null) problems.add("a reason is required");
        DepositClosing.Payout p = payout(in == null ? null : in.payTo(), problems);
        DepositClosing.CasaQuote q = DepositClosing.casaQuote(jdbc, json, id, days.requireOpen());
        problems.addAll(q.problems());
        if (q.payable().signum() > 0) problems.addAll(DepositClosing.payoutProblems(jdbc, id, "CASA", p, null, null));
        DepositAccountService.refuseIfAny("the account cannot be closed", problems);
        Closure c = new Closure(DepositProductService.CASA, id.toString(), (String) a.get("account_no"), "DEPOSITOR_REQUEST",
                p == null ? null : p.to().name(), p == null || p.accountId() == null ? null : p.accountId().toString(), p == null ? null : p.reference(), reason,
                DepositRuleSets.plain(q.payable()));
        return approvals.propose(DepositAccountService.CASA_ENTITY, CLOSE, id.toString(), json.toMap(c), null, q.payable(), (String) a.get("branch_code"),
                idempotencyKey);
    }

    String applyCasaClosure(ApprovalRequest r) {
        Closure c = json.convert(r.payload(), Closure.class);
        UUID id = UUID.fromString(c.accountId());
        account(id, DepositProductService.CASA, false);
        return applying("the account cannot be closed",
                () -> DepositClosing.closeCasa(books(r), ruleSets.current(), json, outbox, id, payout(c), c.note()));
    }

    // ================================================================================================ term deposits

    /** What closing the deposit today would pay: premature (with the rule that gives the rate) or at maturity. */
    public Map<String, Object> depositClosureQuote(UUID id, String reason) {
        account(id, DepositProductService.TERM_DEPOSIT, true);
        DepositClosing.DepositQuote q;
        try {
            q = DepositClosing.depositQuote(jdbc, ruleSets.current(), id, reason, days.requireOpen());
        } catch (DepositRefusal e) {
            throw ApiException.notFound("term deposit " + id);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", q.kind());
        m.put("reason", q.reason());
        m.put("closeOn", q.on());
        m.put("maturityDate", q.maturityDate());
        m.put("principal", DepositRuleSets.plain(q.principal()));
        m.put("balance", DepositRuleSets.plain(q.balance()));
        m.put("ratePercent", DepositRuleSets.plain(q.ratePercent()));
        m.put("interestDue", DepositRuleSets.plain(q.interestDue()));
        m.put("interestCredited", DepositRuleSets.plain(q.interestCredited()));
        m.put("toCredit", DepositRuleSets.plain(q.toCredit()));
        m.put("toRecover", DepositRuleSets.plain(q.toRecover()));
        m.put("overdueInterest", DepositRuleSets.plain(q.overdueInterest()));
        m.put("basis", "MATURITY".equals(q.kind()) ? q.overdueBasis() : q.basis());
        m.put("payableBeforeTax", DepositRuleSets.plain(q.payableBeforeTax()));
        m.put("taxNote", "Tax at source is deducted from interest credited at closure, as from any interest credit.");
        m.put("problems", q.problems());
        return m;
    }

    @Transactional
    public ApprovalRequest proposeDepositClosure(UUID id, DepositClosureInput in, String idempotencyKey) {
        Map<String, Object> a = account(id, DepositProductService.TERM_DEPOSIT, true);
        List<String> problems = new ArrayList<>();
        String reason = in == null || in.reason() == null || in.reason().isBlank() ? null : in.reason().trim().toUpperCase(Locale.ROOT);
        String note = in == null ? null : text(in.note(), 500, "note", problems);
        DepositClosing.Payout p = payout(in == null ? null : in.payTo(), problems);
        DepositClosing.DepositQuote q = DepositClosing.depositQuote(jdbc, ruleSets.current(), id, reason, days.requireOpen());
        problems.addAll(q.problems());
        if (problems.isEmpty() && q.payableBeforeTax() != null && q.payableBeforeTax().signum() > 0) {
            DepositClosing.Deposit d = DepositClosing.deposit(jdbc, id, false);
            problems.addAll(DepositClosing.payoutProblems(jdbc, id, "TERM_DEPOSIT", p, "DEATH".equals(q.reason()) ? null : d.customerId(), d.row()));
        }
        if ("PREMATURE".equals(q.kind()) && !"DEPOSITOR_REQUEST".equals(q.reason()) && note == null) {
            problems.add("say what supports a closure for " + q.reason().toLowerCase(Locale.ROOT).replace('_', ' ') + " (the note: the certificate or claim seen)");
        }
        DepositAccountService.refuseIfAny("the deposit cannot be closed", problems);
        Closure c = new Closure(DepositProductService.TERM_DEPOSIT, id.toString(), (String) a.get("account_no"), q.reason(), p == null ? null : p.to().name(),
                p == null || p.accountId() == null ? null : p.accountId().toString(), p == null ? null : p.reference(), note,
                DepositRuleSets.plain(q.payableBeforeTax()));
        return approvals.propose(DepositAccountService.TD_ENTITY, CLOSE, id.toString(), json.toMap(c), null, q.payableBeforeTax(), (String) a.get("branch_code"),
                idempotencyKey);
    }

    String applyDepositClosure(ApprovalRequest r) {
        Closure c = json.convert(r.payload(), Closure.class);
        UUID id = UUID.fromString(c.accountId());
        account(id, DepositProductService.TERM_DEPOSIT, false);
        return applying("the deposit cannot be closed", () -> DepositClosing.closeDeposit(books(r), ruleSets.current(), outbox, id,
                "MATURITY".equals(c.reason()) ? null : c.reason(), payout(c), c.note()));
    }

    // ---- maturity instruction and renewal -------------------------------------------------------------------------

    private static final Set<String> INSTRUCTIONS = Set.of("PAY_OUT", "RENEW_PRINCIPAL", "RENEW_WITH_INTEREST");

    /** Why a consent cannot be recorded; adds to {@code problems}. */
    private List<String> consentProblems(DepositRules rules, ConsentInput c, String instruction, LocalDate bookedOn, LocalDate today) {
        List<String> out = new ArrayList<>();
        boolean needed = rules.flag(DepositRules.TD_RENEWAL_NEEDS_CONSENT, false) && !"PAY_OUT".equals(instruction);
        if (!needed) {
            if (c != null) out.add("a consent is recorded only for a renewal on a deposit-taking NBFC");
            return out;
        }
        if (c == null || c.reference() == null || c.reference().isBlank() || c.receivedOn() == null) {
            out.add("a renewal needs the depositor's express consent (NBFC Directions para 17): give the reference of their signed request and the date it was received");
            return out;
        }
        if (c.reference().trim().length() > 60) out.add("the consent reference is longer than 60 characters");
        if (c.receivedOn().isAfter(today) || c.receivedOn().isBefore(bookedOn)) out.add("the consent was received between booking (" + bookedOn + ") and today");
        return out;
    }

    @Transactional
    public ApprovalRequest proposeInstruction(UUID id, InstructionInput in, String idempotencyKey) {
        Map<String, Object> a = account(id, DepositProductService.TERM_DEPOSIT, true);
        DepositClosing.Deposit d = DepositClosing.deposit(jdbc, id, false);
        DepositRules rules = ruleSets.current();
        LocalDate today = days.requireOpen();
        String instruction = in == null || in.maturityInstruction() == null ? null : in.maturityInstruction().trim().toUpperCase(Locale.ROOT);
        List<String> problems = new ArrayList<>();
        if (!INSTRUCTIONS.contains(instruction)) problems.add("maturityInstruction is PAY_OUT, RENEW_PRINCIPAL or RENEW_WITH_INTEREST");
        if (!"ACTIVE".equals(d.status()) || !d.funded() || !today.isBefore(d.maturity())) {
            problems.add("the maturity instruction of deposit " + d.accountNo() + " is changed while it is funded and before its maturity date");
        }
        if (instruction != null && instruction.equals(d.instruction())) problems.add("the deposit is already to " + instruction.toLowerCase(Locale.ROOT).replace('_', ' '));
        if (instruction != null && instruction.startsWith("RENEW")) {
            DepositProductService.Product p = DepositProductService.load(jdbc, json, d.productCode());
            if (p == null || !Boolean.TRUE.equals(p.autoRenewalAllowed())) problems.add("product " + d.productCode() + " does not allow renewal at maturity");
        }
        problems.addAll(consentProblems(rules, in == null ? null : in.consent(), instruction, d.bookedOn(), today));
        DepositAccountService.refuseIfAny("the maturity instruction cannot be changed", problems);
        Instruction r = new Instruction(id.toString(), d.accountNo(), instruction, in.consent() == null ? null : in.consent().reference().trim(),
                in.consent() == null ? null : in.consent().receivedOn().toString());
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("maturityInstruction", d.instruction());
        return approvals.propose(DepositAccountService.TD_ENTITY, INSTRUCTION, id.toString(), json.toMap(r), current, null, (String) a.get("branch_code"),
                idempotencyKey);
    }

    String applyInstruction(ApprovalRequest r) {
        Instruction q = json.convert(r.payload(), Instruction.class);
        UUID id = UUID.fromString(q.accountId());
        DepositClosing.Deposit d = DepositClosing.deposit(jdbc, id, true);
        LocalDate today = days.requireOpen();
        if (!"ACTIVE".equals(d.status()) || !today.isBefore(d.maturity())) {
            throw ApiException.conflict("deposit " + d.accountNo() + " is " + d.status() + (today.isBefore(d.maturity()) ? "" : ", at or past maturity")
                    + "; its instruction is not changed now");
        }
        jdbc.update("UPDATE deposits.renewal_consent SET status = 'WITHDRAWN', withdrawn_on = ? WHERE account_id = ? AND status = 'ACTIVE'", today, id);
        if (q.consentReference() != null) {
            jdbc.update("INSERT INTO deposits.renewal_consent (account_id, instruction, reference, received_on, recorded_by, approval_id) VALUES (?, ?, ?, ?, ?, ?)",
                    id, q.instruction(), q.consentReference(), LocalDate.parse(q.consentReceivedOn()), r.maker(), r.id());
        }
        jdbc.update("UPDATE deposits.term_deposit SET maturity_instruction = ? WHERE account_id = ?", q.instruction(), id);
        return d.accountNo();
    }

    /** Proposes renewing a MATURED deposit (one the day-end could not pay or renew). */
    @Transactional
    public ApprovalRequest proposeRenewal(UUID id, RenewalInput in, String idempotencyKey) {
        Map<String, Object> a = account(id, DepositProductService.TERM_DEPOSIT, true);
        DepositClosing.Deposit d = DepositClosing.deposit(jdbc, id, false);
        DepositRules rules = ruleSets.current();
        LocalDate today = days.requireOpen();
        String instruction = in == null || in.instruction() == null ? null : in.instruction().trim().toUpperCase(Locale.ROOT);
        List<String> problems = new ArrayList<>();
        if (!"RENEW_PRINCIPAL".equals(instruction) && !"RENEW_WITH_INTEREST".equals(instruction)) problems.add("instruction is RENEW_PRINCIPAL or RENEW_WITH_INTEREST");
        if (!"MATURED".equals(d.status())) problems.add("deposit " + d.accountNo() + " is " + d.status() + "; a deposit is renewed on request once it has MATURED");
        problems.addAll(consentProblems(rules, in == null ? null : in.consent(), instruction == null ? "RENEW_PRINCIPAL" : instruction, d.bookedOn(), today));
        DepositAccountService.refuseIfAny("the deposit cannot be renewed", problems);
        Instruction r = new Instruction(id.toString(), d.accountNo(), instruction, in.consent() == null ? null : in.consent().reference().trim(),
                in.consent() == null ? null : in.consent().receivedOn().toString());
        return approvals.propose(DepositAccountService.TD_ENTITY, RENEW, id.toString(), json.toMap(r), null, DepositClosing.balance(jdbc, id),
                (String) a.get("branch_code"), idempotencyKey);
    }

    String applyRenewal(ApprovalRequest r) {
        Instruction q = json.convert(r.payload(), Instruction.class);
        UUID id = UUID.fromString(q.accountId());
        return applying("the deposit cannot be renewed", () -> {
            UUID consent = null;
            if (q.consentReference() != null) {
                consent = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO deposits.renewal_consent (id, account_id, instruction, reference, received_on, recorded_by, approval_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, consent, id, q.instruction(), q.consentReference(), LocalDate.parse(q.consentReceivedOn()), r.maker(), r.id());
            }
            return DepositClosing.renew(books(r), ruleSets.current(), json, outbox, id, q.instruction(), consent);
        });
    }

    // ================================================================================================ reading

    /** How an account was closed (the closure in force), or null. */
    Map<String, Object> closure(UUID id) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT c.kind, c.reason, c.business_date, c.rate_percent, c.interest_due, c.interest_credited, c.interest_recovered, c.overdue_interest,
                       c.charges, c.tax, c.paid_amount, c.pay_to, pa.account_no, c.pay_reference, r.account_no, c.basis, c.note, c.created_by
                  FROM deposits.closure c LEFT JOIN deposits.account pa ON pa.id = c.pay_account_id LEFT JOIN deposits.account r ON r.id = c.renewed_to
                 WHERE c.account_id = ? AND c.payout_returned_on IS NULL
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", rs.getString(1));
                    m.put("reason", rs.getString(2));
                    m.put("closedOn", rs.getObject(3, LocalDate.class));
                    m.put("ratePercent", DepositRuleSets.plain(rs.getBigDecimal(4)));
                    m.put("interestDue", DepositRuleSets.plain(rs.getBigDecimal(5)));
                    m.put("interestCredited", DepositRuleSets.plain(rs.getBigDecimal(6)));
                    m.put("interestRecovered", DepositRuleSets.plain(rs.getBigDecimal(7)));
                    m.put("overdueInterest", DepositRuleSets.plain(rs.getBigDecimal(8)));
                    m.put("charges", DepositRuleSets.plain(rs.getBigDecimal(9)));
                    m.put("tax", DepositRuleSets.plain(rs.getBigDecimal(10)));
                    m.put("paidAmount", DepositRuleSets.plain(rs.getBigDecimal(11)));
                    m.put("payTo", rs.getString(12));
                    m.put("payAccountNo", rs.getString(13));
                    m.put("payReference", rs.getString(14));
                    m.put("renewedToAccountNo", rs.getString(15));
                    m.put("basis", rs.getString(16));
                    m.put("note", rs.getString(17));
                    m.put("closedBy", rs.getString(18));
                    return m;
                }, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Deposits maturing between two dates (default: the business date and 30 days on) and every deposit MATURED and
     * waiting, in the caller's branches: the expected amount, the instruction, the consent, the notice and why a
     * matured deposit is waiting.
     */
    public List<Map<String, Object>> maturities(LocalDate from, LocalDate to) {
        LocalDate today = days.requireOpen();
        LocalDate f = from == null ? today : from;
        LocalDate t = to == null ? today.plusDays(30) : to;
        if (t.isBefore(f)) throw ApiException.invalid("to is on or after from");
        return jdbc.query("""
                SELECT a.id, a.account_no, a.status, a.branch_code, c.customer_no, c.display_name, a.product_code, t.principal, t.rate_percent,
                       t.start_date, t.maturity_date, t.maturity_instruction, t.payout_to, pay.account_no, t.payout_bank_account_last4,
                       coalesce((SELECT b.balance FROM deposits.account_balance b WHERE b.account_id = a.id), 0),
                       n.notice_on, n.expected_amount,
                       (SELECT x.reference FROM deposits.renewal_consent x WHERE x.account_id = a.id AND x.status = 'ACTIVE'),
                       (SELECT e.note FROM deposits.interest_event e WHERE e.account_id = a.id AND e.kind = 'PAYOUT_HELD' ORDER BY e.id DESC LIMIT 1),
                       t.interest_period_months, t.cumulative, t.day_count, t.rounding, t.tenure_months,
                       (SELECT pr.product_type FROM deposits.product pr WHERE pr.code = a.product_code)
                  FROM deposits.account a JOIN deposits.term_deposit t ON t.account_id = a.id JOIN customer.customer c ON c.id = a.customer_id
                  LEFT JOIN deposits.account pay ON pay.id = t.payout_account_id
                  LEFT JOIN deposits.maturity_notice n ON n.account_id = a.id AND n.maturity_date = t.maturity_date
                 WHERE a.family = 'TERM_DEPOSIT' AND a.branch_code""" + BranchScope.SQL_VISIBLE + """
                   AND (a.status = 'MATURED' OR (a.status = 'ACTIVE' AND t.maturity_date BETWEEN ? AND ?))
                 ORDER BY a.status = 'MATURED' DESC, t.maturity_date, a.account_no LIMIT 1000
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("accountNo", rs.getString(2));
                    m.put("status", rs.getString(3));
                    m.put("branchCode", rs.getString(4));
                    m.put("customerNo", rs.getString(5));
                    m.put("customerName", rs.getString(6));
                    m.put("productCode", rs.getString(7));
                    BigDecimal principal = rs.getBigDecimal(8);
                    BigDecimal rate = rs.getBigDecimal(9);
                    LocalDate start = rs.getObject(10, LocalDate.class);
                    LocalDate maturity = rs.getObject(11, LocalDate.class);
                    m.put("principal", DepositRuleSets.plain(principal));
                    m.put("ratePercent", DepositRuleSets.plain(rate));
                    m.put("startDate", start);
                    m.put("maturityDate", maturity);
                    m.put("maturityInstruction", rs.getString(12));
                    m.put("payoutTo", rs.getString(13));
                    m.put("payoutAccountNo", rs.getString(14));
                    m.put("payoutBankAccountMasked", rs.getString(15) == null ? null : "XXXXXXXX" + rs.getString(15));
                    m.put("balance", DepositRuleSets.plain(rs.getBigDecimal(16)));
                    m.put("noticeOn", rs.getObject(17, LocalDate.class));
                    TermDeposit.Terms terms = new TermDeposit.Terms(principal, rate, start, maturity, rs.getInt(21), rs.getBoolean(22),
                            com.corebanking.calc.DayCount.valueOf(rs.getString(23)), com.corebanking.calc.Rounding.valueOf(rs.getString(24)));
                    m.put("expectedAmount", DepositAccountService.amount("RD".equals(rs.getString(26))
                            ? RecurringDeposits.maturityValue(principal, rate, rs.getInt(25), rs.getInt(21), com.corebanking.calc.Rounding.valueOf(rs.getString(24)))
                            : TermDeposit.maturityValue(terms)));
                    m.put("consentReference", rs.getString(19));
                    m.put("waitingBecause", rs.getString(20));
                    return m;
                }, scope.user(), f, t);
    }
}
