package com.corebanking.lending.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.calc.AprCalculator;
import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.lending.engine.Delinquency.AssetClass;
import com.corebanking.lending.engine.FeeRule;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.lending.engine.LoanTerms;
import com.corebanking.lending.engine.Provisioning;
import com.corebanking.lending.engine.RepaymentMethod;
import com.corebanking.lending.engine.ScheduleBuilder;
import com.corebanking.ledger.NumberSeries;
import com.corebanking.ledger.NumberSeriesService;
import com.corebanking.ledger.PostingService;
import com.corebanking.ledger.TransactionLot;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.Json;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan lifecycle (US-047 – US-061). Every money movement goes through the pure {@link LoanAccount} engine and the
 * single posting engine; every event is stored with the state before it so it can be reversed.
 */
@Service
public class LoanService {

    public record Application(String productCode, UUID customerId, String branch, BigDecimal amount, Integer tenorMonths,
                              BigDecimal rate, LocalDate disbursalDate, LocalDate firstDueDate, Integer moratoriumMonths,
                              BigDecimal balloon, BigDecimal securedPortion, String externalRef) {}

    private final JdbcTemplate jdbc;
    private final ProductService products;
    private final LoanStore store;
    private final PostingService posting;
    private final NumberSeriesService numbers;
    private final BusinessDays days;
    private final ApprovalService approvals;
    private final AuditLog audit;
    private final Json json;
    private final BranchScope scope;

    public LoanService(JdbcTemplate jdbc, ProductService products, LoanStore store, PostingService posting,
                       NumberSeriesService numbers, BusinessDays days, ApprovalService approvals, AuditLog audit, Json json,
                       BranchScope scope) {
        this.scope = scope;
        this.jdbc = jdbc;
        this.products = products;
        this.store = store;
        this.posting = posting;
        this.numbers = numbers;
        this.days = days;
        this.approvals = approvals;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------------------------------------ preview / KFS
    record Resolved(ProductService.Product product, LoanAccount.Params params, LoanTerms terms, BigDecimal rate,
                    String rateExplanation, String supplierState, String recipientState) {}

    private Resolved resolve(Application a, String loanNo) {
        if (a.productCode() == null || a.customerId() == null || a.amount() == null || a.tenorMonths() == null) {
            throw ApiException.invalid("productCode, customerId, amount and tenorMonths are required");
        }
        ProductService.Product p = products.get(jdbc, a.productCode());
        if (!"ACTIVE".equals(p.status())) throw ApiException.invalid("product " + p.code() + " is " + p.status());
        if (a.amount().compareTo(p.minAmount()) < 0 || a.amount().compareTo(p.maxAmount()) > 0) {
            throw ApiException.invalid("amount must be between " + p.minAmount().toPlainString() + " and " + p.maxAmount().toPlainString());
        }
        if (a.tenorMonths() < p.minTenorMonths() || a.tenorMonths() > p.maxTenorMonths()) {
            throw ApiException.invalid("tenor must be " + p.minTenorMonths() + " to " + p.maxTenorMonths() + " months");
        }
        int moratorium = a.moratoriumMonths() == null ? 0 : a.moratoriumMonths();
        if (moratorium > p.maxMoratoriumMonths()) throw ApiException.invalid("moratorium above product limit " + p.maxMoratoriumMonths());
        BigDecimal rate = a.rate();
        String explanation;
        if (rate == null && p.interestTableCode() != null) {
            Map<String, Object> r = jdbc.queryForMap("SELECT rate, explanation FROM lending.resolve_rate(?, ?, ?)",
                    p.interestTableCode(), a.amount(), a.tenorMonths());
            rate = (BigDecimal) r.get("rate");
            explanation = (String) r.get("explanation");
        } else if (rate == null) {
            throw ApiException.invalid("rate is required (the product has no interest table)");
        } else {
            explanation = "rate set on the account";
        }
        if (rate.compareTo(p.minRate()) < 0 || rate.compareTo(p.maxRate()) > 0) {
            throw ApiException.invalid("rate must be within the product band " + p.minRate().stripTrailingZeros().toPlainString()
                    + "% to " + p.maxRate().stripTrailingZeros().toPlainString() + "%");
        }
        List<Map<String, Object>> cust = jdbc.queryForList("""
                SELECT c.status, c.home_branch, a.state_code FROM customer.customer c
                  LEFT JOIN customer.address a ON a.customer_id = c.id AND a.address_type = 'COMMUNICATION'
                 WHERE c.id = ?
                """, a.customerId());
        if (cust.isEmpty()) throw ApiException.invalid("customer not found");
        if (!"ACTIVE".equals(cust.get(0).get("status"))) throw ApiException.invalid("customer is " + cust.get(0).get("status"));
        String branch = a.branch() == null ? (String) cust.get(0).get("home_branch") : a.branch();
        scope.require(branch);
        String supplier = jdbc.queryForObject("SELECT state_code FROM platform.branch WHERE code = ? AND status = 'ACTIVE'", String.class, branch);
        String recipient = cust.get(0).get("state_code") == null ? supplier : (String) cust.get(0).get("state_code");
        LocalDate disbursal = a.disbursalDate() == null ? days.current().businessDate() : a.disbursalDate();
        LoanTerms terms = new LoanTerms(a.amount(), rate, a.tenorMonths(), disbursal, a.firstDueDate(),
                RepaymentMethod.valueOf(p.repaymentMethod()), moratorium, a.balloon(), DayCount.valueOf(p.dayCount()),
                Rounding.valueOf(p.rounding()), false);
        LoanAccount.Params params = products.params(p, loanNo, branch, supplier, recipient, rate,
                a.securedPortion() == null ? BigDecimal.ZERO : a.securedPortion());
        return new Resolved(p, params, terms, rate, explanation, supplier, recipient);
    }

    /** Preview and KFS figures from the same engine that will post (US-044, US-048, US-049). */
    public Map<String, Object> preview(Application a) {
        Resolved r = resolve(a, "PREVIEW");
        return kfs(r, ScheduleBuilder.build(r.terms()));
    }

    private Map<String, Object> kfs(Resolved r, List<Instalment> schedule) {
        List<FeeRule.Charge> upfront = new ArrayList<>();
        for (FeeRule f : r.params().fees()) {
            if (f.event() == FeeRule.Event.DISBURSEMENT) upfront.add(f.compute(r.terms().principal(), r.supplierState(), r.recipientState(), Rounding.PAISE_HALF_UP));
        }
        BigDecimal deducted = upfront.stream().filter(c -> r.params().fees().stream()
                .anyMatch(f -> f.code().equals(c.code()) && f.deductFromDisbursal())).map(FeeRule.Charge::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal feesExGst = upfront.stream().map(FeeRule.Charge::fee).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal apr = apr(r, schedule, feesExGst);
        BigDecimal totalInterest = schedule.stream().map(Instalment::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", r.product().code());
        m.put("productName", r.product().name());
        m.put("productVersion", r.product().version());
        m.put("amount", plain(r.terms().principal()));
        m.put("tenorMonths", r.terms().tenorMonths());
        m.put("repaymentMethod", r.terms().method().name());
        m.put("rateType", r.product().rateType());
        m.put("interestRate", plain(r.rate()));
        m.put("rateExplanation", r.rateExplanation());
        m.put("emi", r.terms().method() == RepaymentMethod.EQUATED ? plain(ScheduleBuilder.emi(r.terms())) : null);
        m.put("instalments", schedule.size());
        m.put("totalInterest", plain(totalInterest));
        m.put("fees", upfront.stream().map(c -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("code", c.code());
            f.put("name", c.name());
            f.put("fee", plain(c.fee()));
            f.put("cgst", plain(c.gst().cgst()));
            f.put("sgst", plain(c.gst().sgst()));
            f.put("igst", plain(c.gst().igst()));
            f.put("total", plain(c.total()));
            return f;
        }).toList());
        m.put("netDisbursal", plain(r.terms().principal().subtract(deducted)));
        m.put("totalRepayable", plain(r.terms().principal().add(totalInterest)));
        m.put("apr", plain(apr));
        m.put("penalChargeRate", r.product().penalChargeRate() == null ? null : plain(r.product().penalChargeRate()));
        m.put("penalChargeNote", "Penal charges apply only on overdue amounts, are not added to the interest rate and are not compounded.");
        m.put("coolingOffDays", r.product().coolingOffDays());
        m.put("placeOfSupply", r.recipientState());
        m.put("schedule", schedule.stream().map(LoanService::row).toList());
        return m;
    }

    /** APR per product basis: IRR of net flows (fees excluding GST), monthly nominal; XIRR for bullet loans. */
    static BigDecimal apr(Resolved r, List<Instalment> schedule, BigDecimal feesExGst) {
        BigDecimal net = r.terms().principal().subtract(feesExGst);
        if (r.terms().method() == RepaymentMethod.BULLET_TOTAL_INTEREST) {
            List<AprCalculator.DatedFlow> flows = new ArrayList<>();
            flows.add(new AprCalculator.DatedFlow(r.terms().disbursalDate(), net.negate()));
            schedule.forEach(i -> flows.add(new AprCalculator.DatedFlow(i.dueDate(), i.instalment())));
            return AprCalculator.xirr(flows);
        }
        List<BigDecimal> flows = new ArrayList<>();
        flows.add(net.negate());
        schedule.forEach(i -> flows.add(i.instalment()));
        return AprCalculator.nominalAnnualIrr(flows, 12);
    }

    // ------------------------------------------------------------------------------------------------ create
    /** Books the loan as SANCTIONED with the product frozen into it; money moves only on disbursement. */
    @Transactional
    public Map<String, Object> create(Application a) {
        if (a.externalRef() != null) {
            List<UUID> existing = jdbc.queryForList("SELECT id FROM lending.loan_account WHERE external_ref = ?", UUID.class, a.externalRef());
            if (!existing.isEmpty()) return get(existing.get(0));           // idempotent create from the LOS
        }
        String loanNo = numbers.next(NumberSeries.Family.LOAN);
        Resolved r = resolve(a, loanNo);
        List<Instalment> schedule = ScheduleBuilder.build(r.terms());
        Map<String, Object> k = kfs(r, schedule);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO lending.loan_account (id, loan_no, customer_id, product_code, branch_code, sanctioned_amount, rate,
                    tenor_months, open_date, first_due_date, emi, status, product_version, product_snapshot, repayment_method,
                    moratorium_months, penal_rate, apr, customer_state, secured_portion, source, external_ref, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SANCTIONED', ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, loanNo, a.customerId(), r.product().code(), r.params().branch(), r.terms().principal(), r.rate(),
                r.terms().tenorMonths(), r.terms().disbursalDate(), schedule.get(0).dueDate(),
                k.get("emi") == null ? null : new BigDecimal((String) k.get("emi")), r.product().version(), json.write(r.params()),
                r.terms().method().name(), r.terms().moratoriumMonths(), r.product().penalChargeRate(), new BigDecimal((String) k.get("apr")),
                r.recipientState(), r.params().securedPortion(), CurrentUser.get().has("loan:stp") ? "API" : "CONSOLE",
                a.externalRef(), CurrentUser.username());
        jdbc.update("INSERT INTO lending.loan_kfs (loan_id, kfs) VALUES (?, ?::jsonb)", id, json.write(k));
        jdbc.update("UPDATE lending.loan_account SET booked_terms = ?::jsonb WHERE id = ?", json.write(r.terms()), id);
        audit.record(CurrentUser.username(), "LOAN_CREATE", "LOAN", loanNo, Map.of("amount", plain(a.amount()), "product", r.product().code()));
        return get(id);
    }

    /** Borrower accepted the KFS (RBI: before execution of the loan contract). */
    @Transactional
    public Map<String, Object> acceptKfs(UUID loanId, String channel, String evidenceRef) {
        int n = jdbc.update("""
                UPDATE lending.loan_account SET kfs_accepted_at = now()
                 WHERE id = ? AND status = 'SANCTIONED' AND kfs_accepted_at IS NULL
                """, loanId);
        if (n == 0) throw ApiException.conflict("KFS can be accepted once, on a sanctioned loan");
        jdbc.update("UPDATE lending.loan_kfs SET accepted_channel = ?, evidence_ref = ?, accepted_at = now() WHERE loan_id = ?",
                channel, evidenceRef, loanId);
        audit.record(CurrentUser.username(), "KFS_ACCEPTED", "LOAN", loanId.toString(), Map.of("channel", String.valueOf(channel)));
        return get(loanId);
    }

    // ------------------------------------------------------------------------------------------------ disbursement
    /** Staff: maker-checker. API clients with loan:stp (LOS straight-through): immediate. */
    @Transactional
    public Map<String, Object> requestDisbursement(UUID loanId, Map<String, Object> instruction) {
        Map<String, Object> loan = jdbc.queryForMap("SELECT loan_no, status, kfs_accepted_at, sanctioned_amount, branch_code FROM lending.loan_account WHERE id = ?", loanId);
        if (!"SANCTIONED".equals(loan.get("status"))) throw ApiException.conflict("loan is " + loan.get("status"));
        if (loan.get("kfs_accepted_at") == null) throw ApiException.conflict("the borrower must accept the KFS before disbursement");
        Map<String, Object> payload = new LinkedHashMap<>(instruction == null ? Map.of() : instruction);
        payload.put("loanId", loanId.toString());
        payload.put("loanNo", loan.get("loan_no"));
        if (CurrentUser.get().has("loan:stp")) {
            return disburse(loanId, CurrentUser.username());
        }
        return com.corebanking.platform.ApprovalView.of(approvals.propose("LOAN_DISBURSEMENT", "DISBURSE", (String) loan.get("loan_no"),
                payload, null, (BigDecimal) loan.get("sanctioned_amount"), (String) loan.get("branch_code"), null));
    }

    Map<String, Object> disburse(UUID loanId, String user) {
        LocalDate bd = days.requireOpen();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, product_snapshot::text AS p, booked_terms::text AS t FROM lending.loan_account WHERE id = ? FOR UPDATE", loanId);
        if (!"SANCTIONED".equals(row.get("status"))) throw ApiException.conflict("loan is " + row.get("status"));
        LoanAccount.Params params = json.read((String) row.get("p"), LoanAccount.Params.class);
        LoanTerms booked = json.read((String) row.get("t"), LoanTerms.class);
        // disbursal happens today: rebuild the schedule from today with the booked terms
        LoanTerms terms = new LoanTerms(booked.principal(), booked.ratePercent(), booked.tenorMonths(), bd,
                booked.firstDueDate() != null && booked.firstDueDate().isAfter(bd) ? booked.firstDueDate() : null,
                booked.method(), booked.moratoriumMonths(), booked.balloon(), booked.dayCount(), booked.rounding(), booked.extraDayOnFirst());
        LoanAccount.Book book = LoanAccount.disburse(params, terms, bd);
        for (TransactionLot lot : book.result().lots()) posting.post(lot, user);
        jdbc.update("""
                UPDATE lending.loan_account SET status = 'ACTIVE', state = '{}'::jsonb, disbursed_amount = ?, net_disbursed = ?,
                       disbursed_on = ?, first_due_date = ? WHERE id = ?
                """, terms.principal(), book.netDisbursal(), bd, book.schedule().get(0).dueDate(), loanId);
        store.save(jdbc, loanId, book.account(), bd);
        store.replaceSchedule(jdbc, loanId, book.schedule());
        store.recordTxn(jdbc, loanId, "DISBURSEMENT", bd, bd, terms.principal(), book.result().lots(), null, book.result().summary(), null, user);
        audit.record(user, "LOAN_DISBURSE", "LOAN", params.loanNo(), Map.of("net", plain(book.netDisbursal())));
        return get(loanId);
    }

    // ------------------------------------------------------------------------------------------------ servicing
    interface Op {
        LoanAccount.Result run(LoanAccount a, LocalDate businessDate);
    }

    private Map<String, Object> apply(UUID loanId, String type, BigDecimal amount, LocalDate valueDate, Op op) {
        LocalDate bd = days.requireOpen();
        LoanStore.Loaded l = store.lock(jdbc, loanId);
        if (l.account() == null) throw ApiException.conflict("loan is " + l.status());
        LoanAccount.Snapshot before = l.account().snapshot();
        LoanAccount.Result result;
        try {
            result = op.run(l.account(), bd);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw ApiException.conflict(e.getMessage());
        }
        String user = CurrentUser.username();
        for (TransactionLot lot : result.lots()) posting.post(lot, user);
        store.save(jdbc, loanId, l.account(), bd);
        store.recordTxn(jdbc, loanId, type, valueDate == null ? bd : valueDate, bd, amount, result.lots(), before, result.summary(), null, user);
        audit.record(user, "LOAN_" + type, "LOAN", l.loanNo(), Map.of("summary", result.summary()));
        return get(loanId);
    }

    @Transactional
    public Map<String, Object> repay(UUID loanId, BigDecimal amount, LocalDate valueDate, String mode, String reference) {
        if (amount == null || amount.signum() <= 0) throw ApiException.invalid("amount must be positive");
        return apply(loanId, "REPAYMENT", amount, valueDate, (a, bd) -> a.pay(amount, valueDate == null ? bd : valueDate, bd,
                (mode == null ? "Receipt" : mode) + (reference == null ? "" : " " + reference)));
    }

    @Transactional
    public Map<String, Object> prepay(UUID loanId, BigDecimal amount, String mode) {
        LoanAccount.PrepaymentMode m = mode == null ? null : LoanAccount.PrepaymentMode.valueOf(mode);
        return apply(loanId, "PREPAYMENT", amount, null, (a, bd) -> a.prepay(amount,
                m == null ? LoanAccount.PrepaymentMode.valueOf(productPrepaymentMode(loanId)) : m, bd));
    }

    private String productPrepaymentMode(UUID loanId) {
        return jdbc.queryForObject("""
                SELECT p.prepayment_mode FROM lending.loan_account l JOIN lending.loan_product p ON p.code = l.product_code WHERE l.id = ?
                """, String.class, loanId);
    }

    public Map<String, Object> preclosureQuote(UUID loanId) {
        LocalDate bd = days.current().businessDate();
        LoanAccount a = store.lock(jdbc, loanId).account();
        if (a == null) throw ApiException.conflict("loan is not active");
        LoanAccount.Quote q = a.preclosureQuote(bd);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", bd.toString());
        m.put("principal", plain(q.principal()));
        m.put("overdueDues", plain(q.overdueDues()));
        m.put("accruedInterest", plain(q.accruedInterest()));
        m.put("charges", plain(q.charges()));
        m.put("foreclosureFee", q.foreclosureFee() == null ? "0" : plain(q.foreclosureFee().total()));
        m.put("advanceAdjusted", plain(q.excess()));
        m.put("total", plain(q.total()));
        return m;
    }

    @Transactional
    public Map<String, Object> preclose(UUID loanId, BigDecimal amount) {
        return apply(loanId, "PRECLOSURE", amount, null, (a, bd) -> a.preclose(amount, bd));
    }

    public Map<String, Object> cancellationQuote(UUID loanId) {
        LoanAccount a = store.lock(jdbc, loanId).account();
        if (a == null) throw ApiException.conflict("loan is not active");
        return Map.of("total", plain(a.cancellationAmount()), "asOf", days.current().businessDate().toString());
    }

    @Transactional
    public Map<String, Object> cancel(UUID loanId, BigDecimal amount) {
        return apply(loanId, "CANCELLATION", amount, null, (a, bd) -> a.cancel(amount, bd));
    }

    @Transactional
    public Map<String, Object> chargeFee(UUID loanId, String feeCode, BigDecimal base) {
        return apply(loanId, "FEE_CHARGE", null, null, (a, bd) -> a.chargeFee(feeCode, base == null ? a.principalOutstanding() : base, bd));
    }

    @Transactional
    public Map<String, Object> setFrozen(UUID loanId, boolean frozen, String reason) {
        return apply(loanId, frozen ? "FREEZE" : "UNFREEZE", null, null, (a, bd) -> {
            if (frozen) a.freeze(); else a.unfreeze();
            return new LoanAccount.Result(List.of(), (frozen ? "Frozen: " : "Unfrozen: ") + reason);
        });
    }

    @Transactional
    public Map<String, Object> proposeWaiver(UUID loanId, String chargeId, BigDecimal amount, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.invalid("a reason is required");
        String loanNo = jdbc.queryForObject("SELECT loan_no FROM lending.loan_account WHERE id = ?", String.class, loanId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loanId.toString());
        payload.put("loanNo", loanNo);
        payload.put("chargeId", chargeId);
        payload.put("amount", plain(amount));
        payload.put("reason", reason);
        return com.corebanking.platform.ApprovalView.of(approvals.propose("LOAN_WAIVER", "WAIVE", loanNo, payload, null, amount, branchOf(loanId), null));
    }

    @Transactional
    public Map<String, Object> proposeReversal(UUID loanId, UUID txnId, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.invalid("a reason is required");
        Map<String, Object> t = jdbc.queryForMap("SELECT loan_id, seq, txn_type, reversed_by, amount FROM lending.loan_txn WHERE id = ?", txnId);
        if (!loanId.equals(t.get("loan_id"))) throw ApiException.notFound("transaction " + txnId);
        if (t.get("reversed_by") != null) throw ApiException.conflict("already reversed");
        if (List.of("DISBURSEMENT", "REVERSAL", "EOD").contains(t.get("txn_type"))) {
            throw ApiException.conflict(t.get("txn_type") + " cannot be reversed; use cancellation for a disbursement");
        }
        Integer later = jdbc.queryForObject("""
                SELECT count(*) FROM lending.loan_txn WHERE loan_id = ? AND seq > ? AND reversed_by IS NULL
                   AND txn_type NOT IN ('EOD','REVERSAL')
                """, Integer.class, loanId, t.get("seq"));
        String loanNo = jdbc.queryForObject("SELECT loan_no FROM lending.loan_account WHERE id = ?", String.class, loanId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loanId.toString());
        payload.put("loanNo", loanNo);
        payload.put("txnId", txnId.toString());
        payload.put("txnType", t.get("txn_type"));
        payload.put("reason", reason);
        payload.put("laterTransactionsAlsoReversed", later);
        return com.corebanking.platform.ApprovalView.of(approvals.propose("LOAN_REVERSAL", "REVERSE", loanNo, payload, null,
                (BigDecimal) t.get("amount"), branchOf(loanId), null));
    }

    // ------------------------------------------------------------------------------------------------ views
    public Map<String, Object> get(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.id, l.loan_no AS "loanNo", l.customer_id AS "customerId", c.customer_no AS "customerNo",
                       c.display_name AS "customerName", l.product_code AS "productCode", l.product_version AS "productVersion",
                       l.branch_code AS branch, l.status, l.sanctioned_amount::text AS amount, l.rate::text AS rate,
                       l.tenor_months AS "tenorMonths", l.repayment_method AS "repaymentMethod", l.emi::text AS emi,
                       l.apr::text AS apr, l.open_date::text AS "openDate", l.disbursed_on::text AS "disbursedOn",
                       l.net_disbursed::text AS "netDisbursed", l.principal_outstanding::text AS "principalOutstanding",
                       l.overdue_amount::text AS "overdueAmount", l.next_due_date::text AS "nextDueDate", l.dpd,
                       l.asset_class AS "assetClass", l.npa_since::text AS "npaSince", l.provision_held::text AS "provisionHeld",
                       l.kfs_accepted_at AS "kfsAcceptedAt", l.external_ref AS "externalRef", l.closed_on::text AS "closedOn"
                  FROM lending.loan_account l JOIN customer.customer c ON c.id = l.customer_id
                 WHERE l.id = ?
                """, id);
        if (rows.isEmpty()) throw ApiException.notFound("loan " + id);
        return rows.get(0);
    }

    /** The loan's branch, for the branch-scope check (US-020). */
    public String branchOf(UUID id) {
        List<String> b = jdbc.queryForList("SELECT branch_code FROM lending.loan_account WHERE id = ?", String.class, id);
        if (b.isEmpty()) throw ApiException.notFound("loan " + id);
        return b.get(0);
    }

    public List<Map<String, Object>> search(String q, String status, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        String like = q == null || q.isBlank() ? null : q.trim().replace("%", "") + "%";
        return jdbc.queryForList("""
                SELECT l.id, l.loan_no AS "loanNo", c.display_name AS "customerName", c.customer_no AS "customerNo",
                       l.product_code AS "productCode", l.status, l.sanctioned_amount::text AS amount,
                       l.principal_outstanding::text AS "principalOutstanding", l.overdue_amount::text AS "overdueAmount",
                       l.dpd, l.asset_class AS "assetClass", l.next_due_date::text AS "nextDueDate", l.branch_code AS branch
                  FROM lending.loan_account l JOIN customer.customer c ON c.id = l.customer_id
                 WHERE l.branch_code IN (SELECT branch_code FROM platform.visible_branches(?))
                   AND (?::text IS NULL OR l.status = ?)
                   AND (?::text IS NULL OR l.loan_no LIKE ? OR c.customer_no LIKE ? OR c.display_name ILIKE ? OR l.external_ref = ?)
                 ORDER BY l.open_date DESC, l.loan_no DESC LIMIT ? OFFSET ?
                """, scope.user(), status, status, like, like, like, like, q, limit, Math.max(page, 0) * limit);
    }

    /** Demands raised so far, charges, and the future schedule. */
    public Map<String, Object> schedule(UUID id) {
        LoanStore.Loaded l = store.lock(jdbc, id);
        Map<String, Object> m = new LinkedHashMap<>();
        if (l.account() == null) {
            m.put("demands", List.of());
            m.put("charges", List.of());
            m.put("future", jdbc.queryForList("""
                    SELECT instalment_no AS "instalmentNo", due_date::text AS "dueDate", opening_balance::text AS "openingBalance",
                           principal_due::text AS principal, interest_due::text AS interest, (principal_due + interest_due)::text AS instalment
                      FROM lending.repayment_schedule WHERE loan_id = ? ORDER BY instalment_no
                    """, id));
            return m;
        }
        LoanAccount a = l.account();
        m.put("demands", a.demands().stream().map(d -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("instalmentNo", d.instalmentNo());
            r.put("dueDate", d.dueDate().toString());
            r.put("principalDue", plain(d.principalDue()));
            r.put("interestDue", plain(d.interestDue()));
            r.put("principalPaid", plain(d.principalPaid()));
            r.put("interestPaid", plain(d.interestPaid()));
            return r;
        }).toList());
        m.put("charges", a.charges().stream().map(c -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", c.id());
            r.put("code", c.code());
            r.put("name", c.name());
            r.put("kind", c.kind().name());
            r.put("date", c.date().toString());
            r.put("amount", plain(c.amount()));
            r.put("paid", plain(c.paid()));
            r.put("waived", plain(c.waived()));
            r.put("unpaid", plain(c.unpaid()));
            return r;
        }).toList());
        m.put("future", a.futureSchedule().stream().map(LoanService::row).toList());
        m.put("accruedInterest", plain(a.accruedNotDemanded()));
        m.put("advance", plain(a.excess()));
        return m;
    }

    public List<Map<String, Object>> transactions(UUID id) {
        return jdbc.queryForList("""
                SELECT id, seq, txn_type AS type, value_date::text AS "valueDate", business_date::text AS "businessDate",
                       amount::text AS amount, summary, reversed_by AS "reversedBy", reverses, created_by AS "createdBy",
                       created_at AS "createdAt"
                  FROM lending.loan_txn WHERE loan_id = ? AND txn_type <> 'EOD' ORDER BY seq DESC
                """, id);
    }

    public Map<String, Object> kfsOf(UUID id) {
        String k = jdbc.queryForObject("SELECT kfs::text FROM lending.loan_kfs WHERE loan_id = ?", String.class, id);
        return json.readMap(k);
    }

    static Map<String, Object> row(Instalment i) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("instalmentNo", i.number());
        r.put("dueDate", i.dueDate().toString());
        r.put("days", i.days());
        r.put("openingBalance", plain(i.openingBalance()));
        r.put("interest", plain(i.interest()));
        r.put("principal", plain(i.principal()));
        r.put("instalment", plain(i.instalment()));
        r.put("closingBalance", plain(i.closingBalance()));
        return r;
    }

    static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().scale() < 0 ? v.setScale(0, RoundingMode.UNNECESSARY).toPlainString()
                : v.stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------------------------------------ appliers
    static Provisioning.Rates rates(JdbcTemplate j) {
        Map<AssetClass, BigDecimal> s = new EnumMap<>(AssetClass.class);
        Map<AssetClass, BigDecimal> u = new EnumMap<>(AssetClass.class);
        j.query("SELECT asset_class, secured, rate FROM lending.provisioning_rate", rs -> {
            (rs.getBoolean(2) ? s : u).put(AssetClass.valueOf(rs.getString(1)), rs.getBigDecimal(3));
        });
        return new Provisioning.Rates(s, u);
    }

    @Service
    static class DisbursementApplier implements ApprovalApplier {
        private final LoanService loans;

        /** {@code @Lazy}: LoanService needs ApprovalService, which needs every applier — break the cycle. */
        DisbursementApplier(@org.springframework.context.annotation.Lazy LoanService loans) {
            this.loans = loans;
        }

        @Override public String entityType() { return "LOAN_DISBURSEMENT"; }

        @Override
        public String apply(ApprovalRequest r) {
            UUID loanId = UUID.fromString(String.valueOf(r.payload().get("loanId")));
            loans.disburse(loanId, r.maker());
            return String.valueOf(r.payload().get("loanNo"));
        }
    }

    @Service
    static class WaiverApplier implements ApprovalApplier {
        private final LoanService loans;

        WaiverApplier(@org.springframework.context.annotation.Lazy LoanService loans) {
            this.loans = loans;
        }

        @Override public String entityType() { return "LOAN_WAIVER"; }

        @Override
        public String apply(ApprovalRequest r) {
            UUID loanId = UUID.fromString(String.valueOf(r.payload().get("loanId")));
            BigDecimal amount = new BigDecimal(String.valueOf(r.payload().get("amount")));
            String chargeId = String.valueOf(r.payload().get("chargeId"));
            loans.apply(loanId, "WAIVER", amount, null, (a, bd) -> a.waiveCharge(chargeId, amount, bd));
            return String.valueOf(r.payload().get("loanNo"));
        }
    }

    /**
     * Reverses a transaction and every later one (newest first), restores the loan to its state before the
     * transaction, then replays the end-of-day days since then into today's books (US-058).
     */
    @Service
    static class ReversalApplier implements ApprovalApplier {
        private final JdbcTemplate jdbc;
        private final LoanStore store;
        private final PostingService posting;
        private final BusinessDays days;
        private final Json json;

        ReversalApplier(JdbcTemplate jdbc, LoanStore store, PostingService posting, BusinessDays days, Json json) {
            this.jdbc = jdbc;
            this.store = store;
            this.posting = posting;
            this.days = days;
            this.json = json;
        }

        @Override public String entityType() { return "LOAN_REVERSAL"; }

        @Override
        public String apply(ApprovalRequest r) {
            LocalDate bd = days.requireOpen();
            UUID loanId = UUID.fromString(String.valueOf(r.payload().get("loanId")));
            UUID txnId = UUID.fromString(String.valueOf(r.payload().get("txnId")));
            String reason = String.valueOf(r.payload().get("reason"));
            LoanStore.Loaded l = store.lock(jdbc, loanId);
            Map<String, Object> target = jdbc.queryForMap("SELECT seq, state_before::text AS sb, reversed_by FROM lending.loan_txn WHERE id = ?", txnId);
            if (target.get("reversed_by") != null) throw ApiException.conflict("already reversed");
            LoanAccount.Snapshot current = l.account().snapshot();
            List<Map<String, Object>> toReverse = jdbc.queryForList("""
                    SELECT id, array_to_string(lot_ids, ',') AS lots FROM lending.loan_txn
                     WHERE loan_id = ? AND seq >= ? AND reversed_by IS NULL AND txn_type <> 'REVERSAL'
                     ORDER BY seq DESC
                    """, loanId, target.get("seq"));
            List<TransactionLot> lots = new ArrayList<>();
            for (Map<String, Object> t : toReverse) {
                String ids = (String) t.get("lots");
                if (ids == null || ids.isBlank()) continue;
                for (String lotId : ids.split(",")) {
                    TransactionLot rev = TransactionLot.reversal(posting.load(UUID.fromString(lotId)), bd, "Reversal: " + reason);
                    posting.post(rev, CurrentUser.username());
                    lots.add(rev);
                }
            }
            LoanAccount a = l.account();
            a.restore(json.read((String) target.get("sb"), LoanAccount.Snapshot.class));
            Provisioning.Rates rates = rates(jdbc);
            for (LocalDate d = a.lastAccrualDate().plusDays(1); d.isBefore(bd); d = d.plusDays(1)) {
                for (TransactionLot lot : a.endOfDay(d, rates, bd).lots()) {
                    posting.post(lot, CurrentUser.username());
                    lots.add(lot);
                }
            }
            store.save(jdbc, loanId, a, bd);
            UUID reversalId = store.recordTxn(jdbc, loanId, "REVERSAL", bd, bd, null, lots, current,
                    "Reversed " + toReverse.size() + " transaction(s): " + reason, txnId, r.maker());
            for (Map<String, Object> t : toReverse) {
                jdbc.update("UPDATE lending.loan_txn SET reversed_by = ? WHERE id = ?", reversalId, t.get("id"));
            }
            return String.valueOf(r.payload().get("loanNo"));
        }
    }
}
