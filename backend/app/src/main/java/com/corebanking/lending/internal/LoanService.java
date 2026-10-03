package com.corebanking.lending.internal;

import com.corebanking.audit.AuditLog;
import com.corebanking.calc.DayCount;
import com.corebanking.calc.RateSolver;
import com.corebanking.calc.Rounding;
import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.lending.engine.Amendment;
import com.corebanking.lending.engine.Apr;
import com.corebanking.lending.engine.Delinquency.AssetClass;
import com.corebanking.lending.engine.FeeRule;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.lending.engine.LoanTerms;
import com.corebanking.lending.engine.Provisioning;
import com.corebanking.lending.engine.RepaymentMethod;
import com.corebanking.lending.engine.RestructureSimulation;
import com.corebanking.lending.engine.RestructureTerms;
import com.corebanking.lending.engine.ScheduleBuilder;
import com.corebanking.ledger.NumberSeries;
import com.corebanking.ledger.NumberSeriesService;
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

    /** A co-applicant or guarantor of the loan (US-034); the borrower is {@link Application#customerId()}. */
    public record Party(UUID customerId, String role) {}

    /**
     * openapi.yaml#/components/schemas/LoanApplication. {@code tenorMonths} counts instalment periods of the product's
     * frequency (months for a monthly product). Needed by some products only: {@code instalment} - the agreed
     * instalment, from which the rate follows ("Tenure Amount and Installment"); {@code maturityAmount} - the amount
     * repayable at maturity of a bullet loan, from which the rate follows ("Simple Interest Rate Annual From Future
     * Value"); {@code scheduleRows} - the principal by date of a STRUCTURED loan.
     */
    public record Application(String productCode, UUID customerId, String branch, BigDecimal amount, Integer tenorMonths,
                              BigDecimal rate, LocalDate disbursalDate, LocalDate firstDueDate, Integer moratoriumMonths,
                              BigDecimal balloon, BigDecimal securedPortion, String externalRef, List<Party> parties,
                              BigDecimal instalment, BigDecimal maturityAmount, List<ProductService.ScheduleRow> scheduleRows) {}

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
    private final AmountLimitService limits;
    private final LoanEventPublisher events;

    public LoanService(JdbcTemplate jdbc, ProductService products, LoanStore store, PostingService posting,
                       NumberSeriesService numbers, BusinessDays days, ApprovalService approvals, AuditLog audit, Json json,
                       BranchScope scope, AmountLimitService limits, LoanEventPublisher events) {
        this.scope = scope;
        this.limits = limits;
        this.events = events;
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
    /**
     * @param rate the rate as quoted and booked on the account (a flat rate for a flat-rate product); the rate the
     *             account accrues at is {@code plan.accrualRatePercent()}
     */
    record Resolved(ProductService.Product product, LoanAccount.Params params, LoanTerms terms, BigDecimal rate,
                    String rateExplanation, String supplierState, String recipientState, ScheduleBuilder.Plan plan) {}

    /** The figures of an application that do not depend on the customer: tenor, rate and terms on the product. */
    private record Priced(LoanTerms terms, ScheduleBuilder.Plan plan, BigDecimal rate, String explanation) {}

    /**
     * Terms and rate of an application on a product. The rate comes, in this order, from the agreed instalment, the
     * agreed maturity amount, the application, the product's benchmark + spread, or its interest table (rate slabs).
     */
    private Priced price(ProductService.Product product, Application a, LocalDate disbursal) {
        ProductService.Product p = product.withDefaults();
        if (a.amount() == null) throw ApiException.invalid("amount is required");
        if (a.amount().compareTo(p.minAmount()) < 0 || a.amount().compareTo(p.maxAmount()) > 0) {
            throw ApiException.invalid("amount must be between " + p.minAmount().toPlainString() + " and " + p.maxAmount().toPlainString());
        }
        boolean structured = "STRUCTURED".equals(p.repaymentMethod());
        if (structured && (a.scheduleRows() == null || a.scheduleRows().isEmpty())) {
            throw ApiException.invalid("a structured loan needs scheduleRows: the principal falling due on each date");
        }
        if (!structured && a.scheduleRows() != null && !a.scheduleRows().isEmpty()) {
            throw ApiException.invalid("scheduleRows apply to STRUCTURED products only");
        }
        Integer tenor = a.tenorMonths() == null && structured ? Integer.valueOf(a.scheduleRows().size()) : a.tenorMonths();
        if (tenor == null) throw ApiException.invalid("tenorMonths is required");
        String unit = "MONTHLY".equals(p.frequency()) ? "months" : p.frequency().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + " periods";
        if (tenor < p.minTenorMonths() || tenor > p.maxTenorMonths()) {
            throw ApiException.invalid("tenor must be " + p.minTenorMonths() + " to " + p.maxTenorMonths() + " " + unit);
        }
        int moratorium = a.moratoriumMonths() == null ? 0 : a.moratoriumMonths();
        if (moratorium > p.maxMoratoriumMonths()) throw ApiException.invalid("moratorium above product limit " + p.maxMoratoriumMonths());
        if (a.instalment() != null && a.maturityAmount() != null) throw ApiException.invalid("give either instalment or maturityAmount, not both");
        BigDecimal rate = a.rate();
        String explanation;
        if (a.instalment() != null) {
            if (rate != null) throw ApiException.invalid("give either the rate or the instalment: the rate follows from the instalment");
            rate = BigDecimal.ZERO;                // ignored by the engine; replaced below by the rate the instalment implies
            explanation = "rate implied by the agreed instalment of " + plain(a.instalment());
        } else if (a.maturityAmount() != null) {
            if (rate != null) throw ApiException.invalid("give either the rate or the maturityAmount: the rate follows from it");
            if (!"BULLET_TOTAL_INTEREST".equals(p.repaymentMethod())) {
                throw ApiException.invalid("maturityAmount applies to BULLET_TOTAL_INTEREST products only");
            }
            LoanTerms shape = ProductService.terms(p, a.amount(), BigDecimal.ZERO, tenor, disbursal, a.firstDueDate(), 0, null, null, null);
            LocalDate maturity = ScheduleBuilder.dueDate(shape, tenor);
            try {
                rate = RateSolver.simpleAnnualFromFutureValue(a.amount(), a.maturityAmount(), disbursal, maturity, DayCount.valueOf(p.dayCount()));
            } catch (IllegalArgumentException e) {
                throw ApiException.invalid(e.getMessage());
            }
            explanation = "simple annual rate that grows " + plain(a.amount()) + " to " + plain(a.maturityAmount()) + " by " + maturity;
        } else if (rate != null) {
            explanation = "rate set on the account";
        } else if (p.benchmarkCode() != null) {
            BigDecimal benchmark = jdbc.queryForObject("SELECT lending.benchmark_rate_on(?, ?)", BigDecimal.class, p.benchmarkCode(),
                    java.sql.Date.valueOf(disbursal));
            if (benchmark == null) {
                throw ApiException.invalid("no rate is recorded for benchmark " + p.benchmarkCode() + " on or before " + disbursal);
            }
            rate = benchmark.add(p.spread());
            explanation = p.benchmarkCode() + " " + plain(benchmark) + "% + spread " + plain(p.spread()) + "%";
        } else if (p.interestTableCode() != null) {
            Map<String, Object> r = jdbc.queryForMap("SELECT rate, explanation FROM lending.resolve_rate(?, ?, ?)",
                    p.interestTableCode(), a.amount(), tenor);
            rate = (BigDecimal) r.get("rate");
            explanation = (String) r.get("explanation");
        } else {
            throw ApiException.invalid("rate is required (the product has no interest table or benchmark)");
        }
        LoanTerms terms = ProductService.terms(p, a.amount(), rate, tenor, disbursal, a.firstDueDate(), moratorium, a.balloon(),
                a.instalment(), a.scheduleRows());
        ScheduleBuilder.Plan plan = ScheduleBuilder.plan(terms);
        if (a.instalment() != null) rate = plan.accrualRatePercent();
        if (rate.compareTo(p.minRate()) < 0 || rate.compareTo(p.maxRate()) > 0) {
            throw ApiException.invalid("rate " + plain(rate) + "% must be within the product band " + p.minRate().stripTrailingZeros().toPlainString()
                    + "% to " + p.maxRate().stripTrailingZeros().toPlainString() + "%");
        }
        return new Priced(terms, plan, rate, explanation);
    }

    private Resolved resolve(Application a, String loanNo) {
        if (a.productCode() == null || a.customerId() == null || a.amount() == null) {
            throw ApiException.invalid("productCode, customerId, amount and tenorMonths are required");
        }
        ProductService.Product p = products.get(jdbc, a.productCode());
        if (!"ACTIVE".equals(p.status())) throw ApiException.invalid("product " + p.code() + " is " + p.status());
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
        Priced priced = price(p, a, disbursal);
        LoanAccount.Params params = products.params(p, loanNo, branch, supplier, recipient, priced.plan().accrualRatePercent(),
                a.securedPortion() == null ? BigDecimal.ZERO : a.securedPortion());
        return new Resolved(p, params, priced.terms(), priced.rate(), priced.explanation(), supplier, recipient, priced.plan());
    }

    /** openapi.yaml#/components/schemas/ProductPreviewRequest: a draft product and the sample loan to simulate on it. */
    public record ProductPreview(ProductService.Product product, BigDecimal amount, Integer tenorMonths, BigDecimal rate,
                                 LocalDate disbursalDate, LocalDate firstDueDate, Integer moratoriumMonths, BigDecimal balloon,
                                 BigDecimal instalment, BigDecimal maturityAmount, List<ProductService.ScheduleRow> scheduleRows) {}

    /**
     * Product preview (US-044): simulates an account on a draft product - one not saved or approved yet - with the
     * engine that books loans: schedule, fees with GST, net disbursal and APR. Nothing is stored. GST is shown as an
     * intra-state supply; the sample defaults to the product's smallest amount, shortest tenor and lowest rate.
     */
    public Map<String, Object> previewProduct(ProductPreview q) {
        if (q == null || q.product() == null) throw ApiException.invalid("product is required");
        ProductService.validateTerms(q.product());
        products.requireBenchmark(q.product());
        ProductService.Product p = q.product().withDefaults();
        LocalDate disbursal = q.disbursalDate() == null ? days.current().businessDate() : q.disbursalDate();
        boolean derived = q.instalment() != null || q.maturityAmount() != null;
        boolean priced = p.benchmarkCode() != null || p.interestTableCode() != null;
        BigDecimal amount = q.amount() == null ? p.minAmount() : q.amount();
        List<ProductService.ScheduleRow> rows = q.scheduleRows();
        boolean sampleRows = "STRUCTURED".equals(p.repaymentMethod()) && (rows == null || rows.isEmpty());
        if (sampleRows) {
            // a structured product has no schedule of its own: show equal principal on each period date as an example
            int n = q.tenorMonths() == null ? p.minTenorMonths() : q.tenorMonths();
            if (n < 1 || n > 480) throw ApiException.invalid("tenorMonths must be 1..480");
            com.corebanking.lending.engine.Frequency f = com.corebanking.lending.engine.Frequency.valueOf(p.frequency());
            BigDecimal part = amount.divide(BigDecimal.valueOf(n), 0, RoundingMode.DOWN);
            rows = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                rows.add(new ProductService.ScheduleRow(f.plus(disbursal, i),
                        i < n ? part : amount.subtract(part.multiply(BigDecimal.valueOf(n - 1L)))));
            }
        }
        Application a = new Application(p.code(), null, null, amount,
                q.tenorMonths() == null && rows == null ? p.minTenorMonths() : q.tenorMonths(),
                q.rate() == null && !derived && !priced ? p.minRate() : q.rate(), disbursal, q.firstDueDate(), q.moratoriumMonths(),
                q.balloon(), null, null, null, q.instalment(), q.maturityAmount(), rows);
        Priced pr = price(p, a, disbursal);
        LoanAccount.Params params = products.params(p, "PREVIEW", "PREVIEW", "00", "00", pr.plan().accrualRatePercent(), BigDecimal.ZERO);
        Map<String, Object> m = kfs(new Resolved(p, params, pr.terms(), pr.rate(), pr.explanation(), "00", "00", pr.plan()));
        m.put("placeOfSupply", null);
        m.put("draft", true);
        m.put("sampleSchedule", sampleRows);
        return m;
    }

    /** Preview and KFS figures from the same engine that will post (US-044, US-048, US-049). */
    public Map<String, Object> preview(Application a) {
        Resolved r = resolve(a, "PREVIEW");
        checkParties(a);
        return kfs(r);
    }

    /**
     * Co-applicants and guarantors (US-034): ACTIVE customers, each named once, and never the borrower. The
     * database enforces the same rules on lending.loan_party (V17); this gives the caller a 422 before booking.
     */
    private void checkParties(Application a) {
        if (a.parties() == null) return;
        if (a.parties().size() > 10) throw ApiException.invalid("at most 10 co-applicants and guarantors");
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (Party p : a.parties()) {
            if (p == null || p.customerId() == null) throw ApiException.invalid("each party needs a customerId");
            if (p.role() == null || !p.role().matches("CO_APPLICANT|GUARANTOR")) {
                throw ApiException.invalid("party role must be CO_APPLICANT or GUARANTOR (the borrower is the loan's customer)");
            }
            if (p.customerId().equals(a.customerId())) {
                throw ApiException.invalid("a customer cannot be both borrower and "
                        + p.role().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + " on the same loan");
            }
            if (!seen.add(p.customerId())) throw ApiException.invalid("a customer can hold only one role on a loan");
            List<String> status = jdbc.queryForList("SELECT status FROM customer.customer WHERE id = ?", String.class, p.customerId());
            if (status.isEmpty()) throw ApiException.invalid("party customer " + p.customerId() + " not found");
            if (!"ACTIVE".equals(status.get(0))) {
                throw ApiException.invalid("guarantors and co-applicants must be ACTIVE customers; " + p.customerId() + " is " + status.get(0));
            }
        }
    }

    /**
     * KFS figures, as if the whole amount were disbursed at once (a loan disbursed in tranches is charged interest
     * only on what is drawn, so it costs no more than shown). The APR is the IRR of the actual cash flows
     * ({@link Apr}): for a flat-rate loan it is therefore the true reducing-balance cost, not the flat rate.
     */
    private Map<String, Object> kfs(Resolved r) {
        ScheduleBuilder.Plan plan = r.plan();
        List<Instalment> schedule = plan.schedule();
        List<FeeRule.Charge> upfront = new ArrayList<>();
        for (FeeRule f : r.params().fees()) {
            if (f.event() == FeeRule.Event.DISBURSEMENT || f.event() == FeeRule.Event.EVERY_DISBURSEMENT) {
                upfront.add(f.compute(r.terms().principal(), r.supplierState(), r.recipientState(), Rounding.PAISE_HALF_UP));
            }
        }
        BigDecimal deducted = upfront.stream().filter(c -> r.params().fees().stream()
                .anyMatch(f -> f.code().equals(c.code()) && f.deductFromDisbursal())).map(FeeRule.Charge::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal feesExGst = upfront.stream().map(FeeRule.Charge::fee).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal apr = Apr.of(r.terms(), plan, feesExGst);
        BigDecimal totalInterest = plan.totalInterest();
        BigDecimal interestDeducted = plan.bpiDeducted() ? plan.brokenPeriodInterest() : BigDecimal.ZERO;
        LoanTerms.Options o = r.terms().options();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", r.product().code());
        m.put("productName", r.product().name());
        m.put("productVersion", r.product().version());
        m.put("amount", plain(r.terms().principal()));
        m.put("tenorMonths", r.terms().tenorMonths());
        m.put("frequency", o.frequency().name());
        m.put("repaymentMethod", r.terms().method().name());
        m.put("rateType", r.product().rateType());
        m.put("interestBasis", o.interestBasis().name());
        m.put("interestRate", plain(r.rate()));
        m.put("effectiveRate", plain(plan.accrualRatePercent()));
        m.put("rateExplanation", r.rateExplanation());
        m.put("emi", plan.emi() == null ? null : plain(plan.emi()));
        m.put("bpiMode", o.bpiMode().name());
        m.put("brokenPeriodInterest", plain(plan.brokenPeriodInterest()));
        m.put("interestDeductedAtDisbursal", plain(interestDeducted));
        m.put("aprBasis", Apr.evenlySpaced(r.terms(), plan) ? "NOMINAL_PERIODIC_IRR" : "XIRR");
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
        m.put("netDisbursal", plain(r.terms().principal().subtract(deducted).subtract(interestDeducted)));
        m.put("totalRepayable", plain(r.terms().principal().add(totalInterest)));
        m.put("apr", plain(apr));
        m.put("penalChargeRate", r.product().penalChargeRate() == null ? null : plain(r.product().penalChargeRate()));
        m.put("penalChargeNote", "Penal charges apply only on overdue amounts, are not added to the interest rate and are not compounded.");
        m.put("coolingOffDays", r.product().coolingOffDays());
        m.put("placeOfSupply", r.recipientState());
        m.put("schedule", schedule.stream().map(LoanService::row).toList());
        return m;
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
        checkParties(a);
        List<Instalment> schedule = r.plan().schedule();
        Map<String, Object> k = kfs(r);
        ProductService.Product pd = r.product().withDefaults();
        UUID id = UUID.randomUUID();
        // The insert also checks the borrower's exposure limit and records the borrower as a party (V17 triggers).
        jdbc.update("""
                INSERT INTO lending.loan_account (id, loan_no, customer_id, product_code, branch_code, sanctioned_amount, rate,
                    tenor_months, open_date, first_due_date, emi, status, product_version, product_snapshot, repayment_method,
                    moratorium_months, penal_rate, apr, customer_state, secured_portion, source, external_ref, created_by,
                    frequency, undrawn_amount, multiple_disbursements, pre_emi, top_up_allowed, benchmark_code, spread,
                    reset_frequency_months, next_rate_reset)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SANCTIONED', ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, loanNo, a.customerId(), r.product().code(), r.params().branch(), r.terms().principal(), r.rate(),
                r.terms().tenorMonths(), r.terms().disbursalDate(), schedule.get(0).dueDate(),
                k.get("emi") == null ? null : new BigDecimal((String) k.get("emi")), r.product().version(), json.write(r.params()),
                r.terms().method().name(), r.terms().moratoriumMonths(), r.product().penalChargeRate(), new BigDecimal((String) k.get("apr")),
                r.recipientState(), r.params().securedPortion(), CurrentUser.get().has("loan:stp") ? "API" : "CONSOLE",
                a.externalRef(), CurrentUser.username(),
                pd.frequency(), r.terms().principal(), pd.multipleDisbursements(), pd.preEmi(), pd.topUpAllowed(), pd.benchmarkCode(),
                pd.spread(), pd.resetFrequencyMonths(), pd.benchmarkCode() == null ? null
                        : java.sql.Date.valueOf(r.terms().disbursalDate().plusMonths(pd.resetFrequencyMonths())));
        jdbc.update("INSERT INTO lending.loan_kfs (loan_id, kfs) VALUES (?, ?::jsonb)", id, json.write(k));
        jdbc.update("UPDATE lending.loan_account SET booked_terms = ?::jsonb WHERE id = ?", json.write(r.terms()), id);
        if (a.parties() != null) {
            for (Party p : a.parties()) {
                jdbc.update("INSERT INTO lending.loan_party (loan_id, customer_id, role, added_by) VALUES (?, ?, ?, ?)",
                        id, p.customerId(), p.role(), CurrentUser.username());
            }
        }
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
    /**
     * Staff: maker-checker. API clients with loan:stp (LOS straight-through): immediate.
     * {@code instruction.amount} is the amount to disburse now (US-050): absent, it is everything not yet drawn. A
     * part of the sanctioned amount can be drawn only on a product that allows disbursement in tranches; later
     * tranches go to the same endpoint while the account is ACTIVE and has an undrawn amount.
     */
    @Transactional
    public Map<String, Object> requestDisbursement(UUID loanId, Map<String, Object> instruction) {
        Map<String, Object> loan = jdbc.queryForMap("""
                SELECT loan_no, status, kfs_accepted_at, sanctioned_amount, undrawn_amount, multiple_disbursements, branch_code
                  FROM lending.loan_account WHERE id = ?
                """, loanId);
        boolean first = "SANCTIONED".equals(loan.get("status"));
        BigDecimal undrawn = (BigDecimal) loan.get("undrawn_amount");
        if (!first && !"ACTIVE".equals(loan.get("status"))) throw ApiException.conflict("loan is " + loan.get("status"));
        if (!first && undrawn.signum() == 0) throw ApiException.conflict("the loan is fully disbursed");
        if (loan.get("kfs_accepted_at") == null) throw ApiException.conflict("the borrower must accept the KFS before disbursement");
        Map<String, Object> payload = new LinkedHashMap<>(instruction == null ? Map.of() : instruction);
        BigDecimal amount;
        try {
            amount = payload.get("amount") == null ? undrawn : new BigDecimal(String.valueOf(payload.get("amount")));
        } catch (NumberFormatException e) {
            throw ApiException.invalid("amount must be a number");
        }
        if (amount.signum() <= 0 || amount.compareTo(undrawn) > 0) {
            throw ApiException.invalid("the amount must be above zero and at most the undrawn " + plain(undrawn));
        }
        if (first && amount.compareTo(undrawn) < 0 && !Boolean.TRUE.equals(loan.get("multiple_disbursements"))) {
            throw ApiException.invalid("this product is disbursed in one payment: the amount must be " + plain(undrawn));
        }
        payload.put("loanId", loanId.toString());
        payload.put("loanNo", loan.get("loan_no"));
        payload.put("amount", plain(amount));
        payload.put("preview", simulateDisbursement(loanId, amount));      // also refuses now what the engine would refuse later
        // maker's role amount limit (US-021); the checker's is applied when the request is approved
        limits.require("LOAN_DISBURSEMENT", AmountLimitService.MAKE, amount, (String) loan.get("loan_no"));
        if (CurrentUser.get().has("loan:stp")) {
            return disburse(loanId, CurrentUser.username(), amount);
        }
        return com.corebanking.platform.ApprovalView.of(approvals.propose("LOAN_DISBURSEMENT", "DISBURSE", (String) loan.get("loan_no"),
                payload, null, amount, (String) loan.get("branch_code"), null));
    }

    private record Booking(LoanAccount.Params params, LoanTerms terms, boolean preEmi, boolean tranches) {}

    /** The loan as it would be booked if disbursed on {@code bd}: the booked terms, with the schedule starting that day. */
    private Booking booking(UUID loanId, LocalDate bd, boolean lock) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, product_snapshot::text AS p, booked_terms::text AS t, multiple_disbursements, pre_emi "
                        + "FROM lending.loan_account WHERE id = ?" + (lock ? " FOR UPDATE" : ""), loanId);
        if (!"SANCTIONED".equals(row.get("status"))) throw ApiException.conflict("loan is " + row.get("status"));
        LoanAccount.Params params = json.read((String) row.get("p"), LoanAccount.Params.class);
        LoanTerms booked = json.read((String) row.get("t"), LoanTerms.class);
        // disbursal happens today: rebuild the schedule from today with the booked terms
        LoanTerms terms = engine(() -> new LoanTerms(booked.principal(), booked.ratePercent(), booked.tenorMonths(), bd,
                booked.firstDueDate() != null && booked.firstDueDate().isAfter(bd) ? booked.firstDueDate() : null,
                booked.method(), booked.moratoriumMonths(), booked.balloon(), booked.dayCount(), booked.rounding(), booked.extraDayOnFirst(),
                booked.options()));
        return new Booking(params, terms, Boolean.TRUE.equals(row.get("pre_emi")), Boolean.TRUE.equals(row.get("multiple_disbursements")));
    }

    /** First disbursement of a SANCTIONED loan, or a further tranche of an ACTIVE one. {@code amount} null = all undrawn. */
    Map<String, Object> disburse(UUID loanId, String user, BigDecimal amount) {
        LocalDate bd = days.requireOpen();
        String status = jdbc.queryForObject("SELECT status FROM lending.loan_account WHERE id = ? FOR UPDATE", String.class, loanId);
        if (!"SANCTIONED".equals(status)) return drawTranche(loanId, user, amount, bd);
        Booking b = booking(loanId, bd, true);
        BigDecimal first = amount == null ? b.terms().principal() : amount;
        if (first.compareTo(b.terms().principal()) < 0 && !b.tranches()) {
            throw ApiException.conflict("this product is disbursed in one payment");
        }
        LoanAccount.Book book = engine(() -> LoanAccount.open(b.params(), b.terms(), first, b.preEmi(), bd));
        for (TransactionLot lot : book.result().lots()) posting.post(lot, user);
        jdbc.update("""
                UPDATE lending.loan_account SET status = 'ACTIVE', state = '{}'::jsonb, disbursed_amount = ?, net_disbursed = ?,
                       disbursed_on = ?, first_due_date = ? WHERE id = ?
                """, first, book.netDisbursal(), bd, book.schedule().get(0).dueDate(), loanId);
        store.save(jdbc, loanId, book.account(), bd);
        store.replaceSchedule(jdbc, loanId, book.schedule());
        UUID txn = store.recordTxn(jdbc, loanId, "DISBURSEMENT", bd, bd, first, book.result().lots(), null, book.result().summary(), null, user);
        recordTranche(loanId, txn, book.account(), user);
        // in the same transaction as the postings (ADR-008): the payout instruction, webhooks and messages follow from it
        events.disbursed(jdbc, loanId, txn, bd, first, book.netDisbursal(), 1);
        audit.record(user, "LOAN_DISBURSE", "LOAN", b.params().loanNo(), Map.of("net", plain(book.netDisbursal()), "tranche", 1));
        return get(loanId);
    }

    private Map<String, Object> drawTranche(UUID loanId, String user, BigDecimal amount, LocalDate bd) {
        LoanStore.Loaded l = active(loanId);
        LoanAccount acc = l.account();
        LoanAccount.Snapshot before = acc.snapshot();
        BigDecimal draw = amount == null ? acc.undrawn() : amount;
        LoanAccount.Result result = engine(() -> acc.drawTranche(draw, bd));
        for (TransactionLot lot : result.lots()) posting.post(lot, user);
        LoanAccount.TrancheRow row = acc.tranches().get(acc.tranches().size() - 1);
        jdbc.update("UPDATE lending.loan_account SET net_disbursed = coalesce(net_disbursed, 0) + ? WHERE id = ?", row.net(), loanId);
        store.save(jdbc, loanId, acc, bd);
        store.replaceSchedule(jdbc, loanId, acc.futureSchedule());
        UUID txn = store.recordTxn(jdbc, loanId, "DISBURSEMENT", bd, bd, draw, result.lots(), before, result.summary(), null, user);
        recordTranche(loanId, txn, acc, user);
        // every tranche is money to pay out: the same event as the first disbursement, with its own transaction
        events.disbursed(jdbc, loanId, txn, bd, draw, row.net(), row.no());
        audit.record(user, "LOAN_DISBURSE", "LOAN", l.loanNo(), Map.of("net", plain(row.net()), "tranche", row.no()));
        return get(loanId);
    }

    /** The queryable copy of the engine's latest tranche (lending.loan_tranche, V18). */
    private void recordTranche(UUID loanId, UUID txnId, LoanAccount acc, String user) {
        LoanAccount.TrancheRow t = acc.tranches().get(acc.tranches().size() - 1);
        jdbc.update("""
                INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, fees_deducted, interest_deducted, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, loanId, t.no(), txnId, java.sql.Date.valueOf(t.date()), t.amount(), t.feesDeducted(), t.interestDeducted(), user);
    }

    /** Disbursements made so far and what is left to draw (US-050). */
    public Map<String, Object> tranches(UUID loanId) {
        Map<String, Object> m = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT sanctioned_amount::text AS "sanctionedAmount", coalesce(disbursed_amount, 0)::text AS "disbursedAmount",
                       undrawn_amount::text AS "undrawnAmount", multiple_disbursements AS "multipleDisbursements", pre_emi AS "preEmi"
                  FROM lending.loan_account WHERE id = ?
                """, loanId));
        m.put("tranches", jdbc.queryForList("""
                SELECT tranche_no AS "trancheNo", txn_id AS "txnId", business_date::text AS "businessDate", amount::text AS amount,
                       fees_deducted::text AS "feesDeducted", interest_deducted::text AS "interestDeducted",
                       net_disbursed::text AS "netDisbursed", created_by AS "createdBy"
                  FROM lending.loan_tranche WHERE loan_id = ? AND reversed_by IS NULL ORDER BY tranche_no
                """, loanId));
        return m;
    }

    // ------------------------------------------------------------------------------------------------ simulations (US-060)
    private static List<Map<String, Object>> chargeRows(List<FeeRule.Charge> charges) {
        return charges.stream().map(c -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("code", c.code());
            f.put("name", c.name());
            f.put("fee", plain(c.fee()));
            f.put("cgst", plain(c.gst().cgst()));
            f.put("sgst", plain(c.gst().sgst()));
            f.put("igst", plain(c.gst().igst()));
            f.put("total", plain(c.total()));
            return f;
        }).toList();
    }

    /**
     * What a disbursement of {@code amount} (null = everything undrawn) would do today, by the code that posts it:
     * fees and broken-period interest deducted, the net payout and the schedule after it. Nothing changes.
     */
    public Map<String, Object> simulateDisbursement(UUID loanId, BigDecimal amount) {
        LocalDate bd = days.current().businessDate();
        String status = jdbc.queryForObject("SELECT status FROM lending.loan_account WHERE id = ?", String.class, loanId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", bd.toString());
        if ("SANCTIONED".equals(status)) {
            Booking b = booking(loanId, bd, false);
            BigDecimal first = amount == null ? b.terms().principal() : amount;
            if (first.compareTo(b.terms().principal()) < 0 && !b.tranches()) {
                throw ApiException.invalid("this product is disbursed in one payment: the amount must be " + plain(b.terms().principal()));
            }
            LoanAccount.Book book = engine(() -> LoanAccount.open(b.params(), b.terms(), first, b.preEmi(), bd));
            LoanAccount acc = book.account();
            m.put("trancheNo", 1);
            m.put("amount", plain(first));
            m.put("deductedFees", chargeRows(book.deductedFees()));
            m.put("chargedFees", chargeRows(book.chargedFees()));
            m.put("interestDeducted", plain(book.interestDeducted()));
            m.put("netDisbursal", plain(book.netDisbursal()));
            m.put("disbursedAfter", plain(acc.disbursedAmount()));
            m.put("undrawnAfter", plain(acc.undrawn()));
            m.put("fullyDrawn", acc.fullyDrawn());
            m.put("preEmi", acc.preEmi());
            m.put("instalmentAfter", plain(acc.currentEmi()));
            m.put("schedule", book.schedule().stream().map(LoanService::row).toList());
            return m;
        }
        LoanAccount acc = active(loanId).account();
        BigDecimal draw = amount == null ? acc.undrawn() : amount;
        LoanAccount.TrancheEffect e = engine(() -> acc.simulateTranche(draw, bd));
        m.put("trancheNo", e.trancheNo());
        m.put("amount", plain(e.amount()));
        m.put("deductedFees", chargeRows(e.deductedFees()));
        m.put("chargedFees", chargeRows(e.chargedFees()));
        m.put("interestDeducted", plain(e.interestDeducted()));
        m.put("netDisbursal", plain(e.netDisbursal()));
        m.put("disbursedAfter", plain(e.disbursedAfter()));
        m.put("undrawnAfter", plain(e.undrawnAfter()));
        m.put("fullyDrawn", e.fullyDrawn());
        m.put("preEmi", acc.preEmi() && !e.fullyDrawn());
        m.put("instalmentAfter", plain(e.instalmentAfter()));
        m.put("schedule", e.scheduleAfter().stream().map(LoanService::row).toList());
        return m;
    }

    /** openapi.yaml#/components/schemas/TransactionSimulationRequest. */
    public record TransactionSimulation(String type, BigDecimal amount, String mode, LocalDate onDate) {}

    /**
     * What a receipt, part-prepayment or pre-closure would do on {@code onDate} (today, or up to a year ahead: the
     * day-ends until then are run first, on a copy). The engine code that posts the transaction computes the figures;
     * nothing is posted or stored.
     */
    public Map<String, Object> simulateTransaction(UUID loanId, TransactionSimulation q) {
        if (q == null || q.type() == null) throw ApiException.invalid("type is required: REPAYMENT, PREPAYMENT or PRECLOSURE");
        LocalDate bd = days.current().businessDate();
        LocalDate on = q.onDate() == null ? bd : q.onDate();
        if (on.isBefore(bd) || on.isAfter(bd.plusDays(366))) {
            throw ApiException.invalid("onDate must be between " + bd + " and " + bd.plusDays(366));
        }
        LoanAccount acc = active(loanId).account();
        Provisioning.Rates rates = rates(jdbc);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", q.type());
        m.put("asOf", bd.toString());
        m.put("onDate", on.toString());
        switch (q.type()) {
            case "REPAYMENT" -> {
                LoanAccount.ReceiptSimulation r = engine(() -> acc.simulateReceipt(q.amount(), on, rates));
                m.put("amount", plain(r.amount()));
                m.put("duesBefore", plain(r.duesBefore()));
                m.put("allocations", r.allocations().stream().map(x -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ref", x.ref());
                    row.put("component", x.component().name());
                    row.put("amount", plain(x.amount()));
                    return row;
                }).toList());
                m.put("principal", plain(r.principal()));
                m.put("interest", plain(r.interest()));
                m.put("fees", plain(r.fees()));
                m.put("penal", plain(r.penal()));
                m.put("advance", plain(r.advance()));
                m.put("duesAfter", plain(r.duesAfter()));
                m.put("principalOutstandingAfter", plain(r.principalOutstandingAfter()));
                m.put("dpdAfter", r.dpdAfter());
                m.put("assetClassAfter", r.assetClassAfter().name());
                m.put("statusAfter", r.statusAfter().name());
            }
            case "PREPAYMENT" -> {
                LoanAccount.PrepaymentMode mode;
                try {
                    mode = LoanAccount.PrepaymentMode.valueOf(q.mode() == null ? productPrepaymentMode(loanId) : q.mode());
                } catch (IllegalArgumentException e) {
                    throw ApiException.invalid("mode must be REDUCE_EMI or REDUCE_TENURE");
                }
                LoanAccount.PrepaymentSimulation r = engine(() -> acc.simulatePrepayment(q.amount(), mode, on, rates));
                m.put("amount", plain(r.amount()));
                m.put("mode", r.mode().name());
                m.put("feeCharged", plain(r.feeCharged()));
                m.put("principalOutstandingAfter", plain(r.principalOutstandingAfter()));
                m.put("instalmentBefore", plain(r.instalmentBefore()));
                m.put("instalmentAfter", plain(r.instalmentAfter()));
                m.put("remainingBefore", r.remainingBefore());
                m.put("remainingAfter", r.remainingAfter());
                m.put("schedule", r.scheduleAfter().stream().map(LoanService::row).toList());
            }
            case "PRECLOSURE" -> {
                LoanAccount.Quote quote = engine(() -> acc.simulatePreclosure(on, rates));
                m.put("principal", plain(quote.principal()));
                m.put("overdueDues", plain(quote.overdueDues()));
                m.put("accruedInterest", plain(quote.accruedInterest()));
                m.put("charges", plain(quote.charges()));
                m.put("foreclosureFee", quote.foreclosureFee() == null ? "0" : plain(quote.foreclosureFee().total()));
                m.put("advanceAdjusted", plain(quote.excess()));
                m.put("total", plain(quote.total()));
            }
            default -> throw ApiException.invalid("type must be REPAYMENT, PREPAYMENT or PRECLOSURE");
        }
        return m;
    }

    /**
     * Takes back a disbursement whose payout never reached the borrower (P2-2, US-051). The disbursement's lots
     * (principal, fees deducted, GST) and the day-end lots since are reversed in today's books, and the loan goes
     * back to SANCTIONED with its KFS acceptance intact, so that it can be disbursed again once the beneficiary
     * account is corrected, or left. Nothing is kept: no interest for the days in between, no fee.
     * <p>
     * Refused once the loan has any other transaction (a receipt, a charge, an amendment): from then on the
     * borrower has dealt with the loan and the ordinary reversal and cancellation rules apply.
     */
    @Transactional
    public String reverseDisbursement(UUID loanId, String reason, String user) {
        if (reason == null || reason.isBlank()) throw ApiException.invalid("a reason is required");
        LocalDate bd = days.requireOpen();
        LoanStore.Loaded l = store.lock(jdbc, loanId);
        if (l.account() == null) throw ApiException.conflict("loan is " + l.status() + ": there is no disbursement to reverse");
        Integer others = jdbc.queryForObject("""
                SELECT count(*) FROM lending.loan_txn
                 WHERE loan_id = ? AND reversed_by IS NULL AND txn_type NOT IN ('DISBURSEMENT','EOD','REVERSAL')
                """, Integer.class, loanId);
        if (others != null && others > 0) {
            throw ApiException.conflict("the loan has " + others + " transaction(s) after the disbursement; the disbursement can no"
                    + " longer be taken back as a whole — use cancellation or reversal of those transactions");
        }
        // Tranches (US-050): each has its own payout. Taking the loan back to SANCTIONED would also undo the tranches
        // that were paid, so a loan with more than one disbursement is left to operations (retry the payout).
        Integer drawn = jdbc.queryForObject("SELECT count(*) FROM lending.loan_tranche WHERE loan_id = ? AND reversed_by IS NULL",
                Integer.class, loanId);
        if (drawn != null && drawn > 1) {
            throw ApiException.conflict("the loan has " + drawn + " disbursements; one of several tranches cannot be taken back");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, txn_type, array_to_string(lot_ids, ',') AS lots FROM lending.loan_txn
                 WHERE loan_id = ? AND reversed_by IS NULL AND txn_type <> 'REVERSAL' ORDER BY seq DESC
                """, loanId);
        UUID disbursement = null;
        List<TransactionLot> lots = new ArrayList<>();
        for (Map<String, Object> t : rows) {
            if ("DISBURSEMENT".equals(t.get("txn_type"))) disbursement = (UUID) t.get("id");
            String ids = (String) t.get("lots");
            if (ids == null || ids.isBlank()) continue;
            for (String lotId : ids.split(",")) {
                TransactionLot rev = TransactionLot.reversal(posting.load(UUID.fromString(lotId)), bd, "Payout failed: " + reason);
                posting.post(rev, user);
                lots.add(rev);
            }
        }
        if (disbursement == null) throw ApiException.conflict("the loan has no disbursement to reverse");
        LoanAccount.Snapshot current = l.account().snapshot();
        jdbc.update("""
                UPDATE lending.loan_account
                   SET status = 'SANCTIONED', state = '{}'::jsonb, disbursed_amount = NULL, net_disbursed = NULL, disbursed_on = NULL,
                       principal_outstanding = 0, overdue_amount = 0, next_due_date = NULL, dpd = 0, asset_class = 'STANDARD',
                       npa_since = NULL, suspense = 0, provision_held = 0, undrawn_amount = sanctioned_amount, version = version + 1
                 WHERE id = ?
                """, loanId);
        UUID reversal = store.recordTxn(jdbc, loanId, "REVERSAL", bd, bd, null, lots, current,
                "Disbursement reversed (payout failed): " + reason, disbursement, user);
        // the tranche stays as history; the next disbursement is tranche 1 again (V21)
        jdbc.update("UPDATE lending.loan_tranche SET reversed_by = ? WHERE loan_id = ? AND reversed_by IS NULL", reversal, loanId);
        for (Map<String, Object> t : rows) {
            jdbc.update("UPDATE lending.loan_txn SET reversed_by = ? WHERE id = ?", reversal, t.get("id"));
        }
        events.disbursementReversed(jdbc, loanId, reversal, bd, reason);
        audit.record(user, "LOAN_DISBURSEMENT_REVERSED", "LOAN", l.loanNo(), Map.of("reason", reason, "lots", lots.size()));
        return l.loanNo();
    }

    // ------------------------------------------------------------------------------------------------ servicing
    interface Op {
        LoanAccount.Result run(LoanAccount a, LocalDate businessDate);
    }

    /** Transactions that are money received from the borrower: each raises a payment.received event. */
    private static final List<String> RECEIPT_TYPES = List.of("REPAYMENT", "PREPAYMENT", "PRECLOSURE", "CANCELLATION");

    private Map<String, Object> apply(UUID loanId, String type, BigDecimal amount, LocalDate valueDate, Op op) {
        return apply(loanId, type, amount, valueDate, null, op);
    }

    /** @param channel how a receipt came in (the repayment's mode), for the payment.received event */
    private Map<String, Object> apply(UUID loanId, String type, BigDecimal amount, LocalDate valueDate, String channel, Op op) {
        LocalDate bd = days.requireOpen();
        LoanStore.Loaded l = store.lock(jdbc, loanId);
        if (l.account() == null) throw ApiException.conflict("loan is " + l.status());
        LoanAccount.Snapshot before = l.account().snapshot();
        LoanAccount.Status statusBefore = l.account().status();
        boolean npaBefore = l.account().assetClass().isNpa();
        LoanAccount.Result result;
        try {
            result = op.run(l.account(), bd);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw ApiException.conflict(e.getMessage());
        }
        String user = CurrentUser.username();
        for (TransactionLot lot : result.lots()) posting.post(lot, user);
        store.save(jdbc, loanId, l.account(), bd);
        UUID txn = store.recordTxn(jdbc, loanId, type, valueDate == null ? bd : valueDate, bd, amount, result.lots(), before, result.summary(), null, user);
        if (amount != null && RECEIPT_TYPES.contains(type)) {
            events.payment(jdbc, loanId, txn, type, amount, valueDate == null ? bd : valueDate, bd, channel, l.account().status());
        }
        events.transitions(jdbc, loanId, statusBefore, npaBefore, l.account(), bd);
        audit.record(user, "LOAN_" + type, "LOAN", l.loanNo(), Map.of("summary", result.summary()));
        return get(loanId);
    }

    @Transactional
    public Map<String, Object> repay(UUID loanId, BigDecimal amount, LocalDate valueDate, String mode, String reference) {
        if (amount == null || amount.signum() <= 0) throw ApiException.invalid("amount must be positive");
        limits.require("LOAN_REPAYMENT", AmountLimitService.MAKE, amount, loanId.toString());
        return apply(loanId, "REPAYMENT", amount, valueDate, mode, (a, bd) -> a.pay(amount, valueDate == null ? bd : valueDate, bd,
                (mode == null ? "Receipt" : mode) + (reference == null ? "" : " " + reference)));
    }

    @Transactional
    public Map<String, Object> prepay(UUID loanId, BigDecimal amount, String mode) {
        LoanAccount.PrepaymentMode m = mode == null ? null : LoanAccount.PrepaymentMode.valueOf(mode);
        limits.require("LOAN_REPAYMENT", AmountLimitService.MAKE, amount, loanId.toString());
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
        limits.require("LOAN_PRECLOSURE", AmountLimitService.MAKE, amount, loanId.toString());
        return apply(loanId, "PRECLOSURE", amount, null, (a, bd) -> a.preclose(amount, bd));
    }

    public Map<String, Object> cancellationQuote(UUID loanId) {
        LoanAccount a = store.lock(jdbc, loanId).account();
        if (a == null) throw ApiException.conflict("loan is not active");
        return Map.of("total", plain(a.cancellationAmount()), "asOf", days.current().businessDate().toString());
    }

    @Transactional
    public Map<String, Object> cancel(UUID loanId, BigDecimal amount) {
        limits.require("LOAN_PRECLOSURE", AmountLimitService.MAKE, amount, loanId.toString());   // closes the loan like a pre-closure
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
        if (amount == null || amount.signum() <= 0) throw ApiException.invalid("amount must be positive");
        String loanNo = jdbc.queryForObject("SELECT loan_no FROM lending.loan_account WHERE id = ?", String.class, loanId);
        // Role amount limit (US-021): waiving a fee is FEE_WAIVER, waiving penal charges or anything else LOAN_WAIVER.
        LoanAccount account = store.lock(jdbc, loanId).account();
        boolean fee = account != null && account.charges().stream()
                .anyMatch(c -> c.id().equals(chargeId) && c.kind() == com.corebanking.lending.engine.Appropriation.Component.FEE);
        String limitType = fee ? "FEE_WAIVER" : "LOAN_WAIVER";
        limits.require(limitType, AmountLimitService.MAKE, amount, loanNo);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loanId.toString());
        payload.put("loanNo", loanNo);
        payload.put("chargeId", chargeId);
        payload.put("amount", plain(amount));
        payload.put("reason", reason);
        payload.put("limitType", limitType);
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
        if (restructuredSince(jdbc, loanId, t.get("seq"))) throw ApiException.conflict(RESTRUCTURE_NOT_REVERSIBLE);
        if (standsSince(jdbc, loanId, t.get("seq"))) throw ApiException.conflict(STANDING_NOT_REVERSIBLE);
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

    // ------------------------------------------------------------------------------------------------ amendments (P2-3)
    /** openapi.yaml#/components/schemas/AmendmentRequest. */
    public record AmendmentRequest(String kind, BigDecimal newRatePercent, String rateOption, Integer remainingInstalments,
                                   BigDecimal newEmi, Integer newDueDay, String reason, LocalDate newMaturityDate) {}

    /** openapi.yaml#/components/schemas/RestructureTerms. */
    public record RestructureRequest(BigDecimal newRatePercent, Integer remainingInstalments, Integer principalMoratoriumMonths,
                                     String overdueInterest, String reason) {}

    static final String RESTRUCTURE_NOT_REVERSIBLE = "a restructure cannot be reversed, nor can any transaction before it"
            + " (reversing that would undo the restructure too): the restructuring downgraded the account and may have"
            + " capitalised interest under RBI's prudential framework, and must stand; propose a fresh amendment or"
            + " restructure instead";

    /** Product limits that bound an amendment (the product the loan was booked on). */
    private record Limits(int maxTenorMonths, BigDecimal minRate, BigDecimal maxRate) {}

    /** Amendments and restructures rebuild an equated schedule: other methods and balloon loans are refused. */
    private Limits limits(UUID loanId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.repayment_method, coalesce((l.booked_terms->>'balloon')::numeric, 0) AS balloon,
                       p.max_tenor_months, p.min_rate, p.max_rate
                  FROM lending.loan_account l JOIN lending.loan_product p ON p.code = l.product_code
                 WHERE l.id = ?
                """, loanId);
        if (rows.isEmpty()) throw ApiException.notFound("loan " + loanId);
        Map<String, Object> r = rows.get(0);
        if (!"EQUATED".equals(r.get("repayment_method"))) {
            throw ApiException.conflict("amendments and restructures apply to equated (EMI) loans; this loan is " + r.get("repayment_method"));
        }
        if (((BigDecimal) r.get("balloon")).signum() > 0) throw ApiException.conflict("a loan with a balloon payment cannot be amended here");
        return new Limits(((Number) r.get("max_tenor_months")).intValue(), (BigDecimal) r.get("min_rate"), (BigDecimal) r.get("max_rate"));
    }

    private static Amendment amendment(AmendmentRequest q, Limits lim) {
        if (q == null || q.kind() == null) throw ApiException.invalid("kind is required");
        if (q.newRatePercent() != null && (q.newRatePercent().compareTo(lim.minRate()) < 0 || q.newRatePercent().compareTo(lim.maxRate()) > 0)) {
            throw ApiException.invalid("the new rate must be within the product band " + lim.minRate().stripTrailingZeros().toPlainString()
                    + "% to " + lim.maxRate().stripTrailingZeros().toPlainString() + "%");
        }
        try {
            Amendment.RateResetOption option = q.rateOption() == null ? null : Amendment.RateResetOption.valueOf(q.rateOption());
            return new Amendment(Amendment.Kind.valueOf(q.kind()), q.newRatePercent(), option, q.remainingInstalments(), q.newEmi(),
                    q.newDueDay(), lim.maxTenorMonths(), q.reason(), q.newMaturityDate());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(e.getMessage());
        }
    }

    private static RestructureTerms restructureTerms(RestructureRequest q) {
        if (q == null || q.remainingInstalments() == null) throw ApiException.invalid("remainingInstalments is required");
        if (q.overdueInterest() == null) throw ApiException.invalid("overdueInterest is required: CAPITALISE or KEEP_AS_ARREARS");
        try {
            return new RestructureTerms(q.newRatePercent(), q.remainingInstalments(),
                    q.principalMoratoriumMonths() == null ? 0 : q.principalMoratoriumMonths(),
                    RestructureTerms.OverdueInterest.valueOf(q.overdueInterest()), null, q.reason());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(e.getMessage());
        }
    }

    /** A live restructure at or after transaction {@code seq}: reversing from there would undo it. */
    static boolean restructuredSince(JdbcTemplate j, UUID loanId, Object seq) {
        Integer n = j.queryForObject("""
                SELECT count(*) FROM lending.loan_txn
                 WHERE loan_id = ? AND seq >= ? AND reversed_by IS NULL AND txn_type = 'RESTRUCTURE'
                """, Integer.class, loanId, seq);
        return n != null && n > 0;
    }

    static final String STANDING_NOT_REVERSIBLE = "a disbursement, a change of the sanctioned amount or an asset-class"
            + " override cannot be reversed, nor can any transaction before it (reversing that would undo it too): money"
            + " paid out and approved classification decisions stand; correct them with a new transaction";

    /**
     * A later tranche, sanction change or asset-class override at or after transaction {@code seq}. The first
     * disbursement is always before {@code seq} (it cannot itself be reversed), so only later tranches match.
     */
    static boolean standsSince(JdbcTemplate j, UUID loanId, Object seq) {
        Integer n = j.queryForObject("""
                SELECT count(*) FROM lending.loan_txn
                 WHERE loan_id = ? AND seq >= ? AND reversed_by IS NULL AND txn_type IN ('DISBURSEMENT','SANCTION_CHANGE','NPA_OVERRIDE')
                """, Integer.class, loanId, seq);
        return n != null && n > 0;
    }

    /** Engine refusals: bad parameters are 422, a loan state that does not allow the change is 409. */
    private static <T> T engine(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(e.getMessage());
        }
    }

    private LoanStore.Loaded active(UUID loanId) {
        LoanStore.Loaded l = store.lock(jdbc, loanId);
        if (l.account() == null) throw ApiException.conflict("loan is " + l.status());
        return l;
    }

    /** Before/after comparison and the new schedule (US: amendment preview; same engine as the posting). */
    public Map<String, Object> previewAmendment(UUID loanId, AmendmentRequest req) {
        LocalDate bd = days.current().businessDate();
        Amendment a = amendment(req, limits(loanId));
        LoanAccount acc = active(loanId).account();
        Amendment.Effect e = engine(() -> acc.previewAmendment(a, bd));
        Map<String, Object> m = figures(e);
        m.put("asOf", bd.toString());
        m.put("kind", e.kind().name());
        m.put("principal", plain(e.principal()));
        m.put("accruedInterest", plain(e.accruedCarried()));
        m.put("schedule", e.scheduleAfter().stream().map(LoanService::row).toList());
        m.put("currentSchedule", e.scheduleBefore().stream().map(LoanService::row).toList());
        return m;
    }

    /** Maker: records the amendment for a checker, with the figures previewed today. */
    @Transactional
    public Map<String, Object> proposeAmendment(UUID loanId, AmendmentRequest req) {
        if (req == null || req.reason() == null || req.reason().isBlank()) throw ApiException.invalid("a reason is required");
        LocalDate bd = days.current().businessDate();
        Amendment a = amendment(req, limits(loanId));
        LoanStore.Loaded l = active(loanId);
        Amendment.Effect e = engine(() -> l.account().previewAmendment(a, bd));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loanId.toString());
        payload.put("loanNo", l.loanNo());
        payload.put("request", requestMap(req));
        payload.put("proposedOn", bd.toString());
        payload.put("preview", figures(e));
        return com.corebanking.platform.ApprovalView.of(approvals.propose("LOAN_AMENDMENT", "AMEND", l.loanNo(), payload,
                currentTerms(l.account()), null, branchOf(loanId), null));
    }

    /**
     * Checker approved: the engine runs again on the loan as it is now (it may have changed since the proposal). If the
     * figures differ materially from those the checker saw, the current ones are applied and both are recorded.
     */
    String applyAmendment(ApprovalRequest r) {
        LocalDate bd = days.requireOpen();
        UUID loanId = UUID.fromString(String.valueOf(r.payload().get("loanId")));
        Map<?, ?> q = r.payload().get("request") instanceof Map<?, ?> m ? m : Map.of();
        AmendmentRequest req = new AmendmentRequest(str(q.get("kind")), dec(q.get("newRatePercent")), str(q.get("rateOption")),
                integer(q.get("remainingInstalments")), dec(q.get("newEmi")), integer(q.get("newDueDay")), str(q.get("reason")),
                q.get("newMaturityDate") == null ? null : LocalDate.parse(String.valueOf(q.get("newMaturityDate"))));
        Amendment a = amendment(req, limits(loanId));
        LoanStore.Loaded l = active(loanId);
        LoanAccount acc = l.account();
        LoanAccount.Snapshot before = acc.snapshot();
        Amendment.Effect e = engine(() -> acc.previewAmendment(a, bd));
        LoanAccount.Result result = engine(() -> acc.amend(a, bd));
        Map<?, ?> proposed = r.payload().get("preview") instanceof Map<?, ?> p ? p : Map.of();
        Map<String, Object> applied = figures(e);
        boolean differs = !same(proposed.get("emiAfter"), e.emiAfter()) || !same(proposed.get("rateAfter"), e.rateAfter())
                || !String.valueOf(e.remainingAfter()).equals(str(proposed.get("remainingAfter")))
                || !e.maturityAfter().toString().equals(str(proposed.get("maturityAfter")))
                || !near(proposed.get("interestAfter"), e.interestAfter());
        String summary = result.summary() + (differs ? " (figures differ from the proposal: applied on the loan as at approval)" : "");
        for (TransactionLot lot : result.lots()) posting.post(lot, r.maker());
        store.save(jdbc, loanId, acc, bd);
        store.replaceSchedule(jdbc, loanId, acc.futureSchedule());
        UUID txn = store.recordTxn(jdbc, loanId, "AMENDMENT", bd, bd, null, result.lots(), before, summary, null, r.maker());
        recordHistory(new History(loanId, txn, a.kind().name(), requestMap(req), e.emiBefore(), e.emiAfter(), e.remainingBefore(),
                e.remainingAfter(), e.rateBefore(), e.rateAfter(), e.maturityBefore(), e.maturityAfter(), e.interestBefore(),
                e.interestAfter(), proposed, applied, differs, r, bd, req.reason()));
        if (a.kind() == Amendment.Kind.RATE_CHANGE) {
            // RBI 18-Aug-2023: a reset of the rate, and what it does to EMI and tenure, is communicated to the borrower
            events.rateReset(jdbc, loanId, bd, applied, req.rateOption());
        }
        audit.record(CurrentUser.username(), "LOAN_AMENDMENT", "LOAN", l.loanNo(),
                Map.of("summary", summary, "approvalId", r.id().toString()));
        return l.loanNo();
    }

    /** Up to three restructuring options on today's state; nothing changes. */
    public Map<String, Object> simulateRestructure(UUID loanId, List<RestructureRequest> options) {
        if (options == null || options.isEmpty() || options.size() > 3) throw ApiException.invalid("give one to three options");
        LocalDate bd = days.current().businessDate();
        limits(loanId);
        List<RestructureTerms> terms = options.stream().map(LoanService::restructureTerms).toList();
        LoanAccount acc = active(loanId).account();
        List<RestructureSimulation> sims = engine(() -> acc.simulateRestructure(terms, bd));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", bd.toString());
        m.put("current", currentTerms(acc));
        m.put("options", sims.stream().map(s -> {
            Map<String, Object> o = simulationFigures(s);
            o.put("schedule", s.schedule().stream().map(LoanService::row).toList());
            return o;
        }).toList());
        return m;
    }

    /** Maker: records a restructure for two checkers (approval rule LOAN_RESTRUCTURE, V14). */
    @Transactional
    public Map<String, Object> proposeRestructure(UUID loanId, RestructureRequest req) {
        if (req == null || req.reason() == null || req.reason().isBlank()) throw ApiException.invalid("a reason is required");
        LocalDate bd = days.current().businessDate();
        limits(loanId);
        RestructureTerms t = restructureTerms(req);
        LoanStore.Loaded l = active(loanId);
        RestructureSimulation s = engine(() -> l.account().simulateRestructure(t, bd));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loanId.toString());
        payload.put("loanNo", l.loanNo());
        payload.put("terms", termsMap(req));
        payload.put("proposedOn", bd.toString());
        payload.put("preview", simulationFigures(s));
        return com.corebanking.platform.ApprovalView.of(approvals.propose("LOAN_RESTRUCTURE", "RESTRUCTURE", l.loanNo(), payload,
                currentTerms(l.account()), l.account().principalOutstanding(), branchOf(loanId), null));
    }

    /** Checkers approved: re-run on today's state, post, and record the restructure (not reversible). */
    String applyRestructure(ApprovalRequest r) {
        LocalDate bd = days.requireOpen();
        UUID loanId = UUID.fromString(String.valueOf(r.payload().get("loanId")));
        Map<?, ?> q = r.payload().get("terms") instanceof Map<?, ?> m ? m : Map.of();
        RestructureRequest req = new RestructureRequest(dec(q.get("newRatePercent")), integer(q.get("remainingInstalments")),
                integer(q.get("principalMoratoriumMonths")), str(q.get("overdueInterest")), str(q.get("reason")));
        limits(loanId);
        RestructureTerms t = restructureTerms(req);
        LoanStore.Loaded l = active(loanId);
        LoanAccount acc = l.account();
        LoanAccount.Snapshot before = acc.snapshot();
        RestructureSimulation s = engine(() -> acc.simulateRestructure(t, bd));
        LoanAccount.Result result = engine(() -> acc.restructure(t, bd));
        Map<?, ?> proposed = r.payload().get("preview") instanceof Map<?, ?> p ? p : Map.of();
        Map<String, Object> applied = simulationFigures(s);
        boolean differs = !same(proposed.get("emiAfter"), s.emiAfter()) || !same(proposed.get("principalAfter"), s.principalAfter())
                || !same(proposed.get("interestCapitalised"), s.interestCapitalised())
                || !s.maturityAfter().toString().equals(str(proposed.get("maturityAfter")));
        String summary = result.summary() + (differs ? " (figures differ from the proposal: applied on the loan as at approval)" : "");
        for (TransactionLot lot : result.lots()) posting.post(lot, r.maker());
        store.save(jdbc, loanId, acc, bd);
        store.replaceSchedule(jdbc, loanId, acc.futureSchedule());
        UUID txn = store.recordTxn(jdbc, loanId, "RESTRUCTURE", bd, bd, null, result.lots(), before, summary, null, r.maker());
        recordHistory(new History(loanId, txn, "RESTRUCTURE", termsMap(req), s.emiBefore(), s.emiAfter(), s.remainingBefore(),
                s.remainingAfter(), s.rateBefore(), s.rateAfter(), s.maturityBefore(), s.maturityAfter(), s.interestBefore(),
                s.interestAfter(), proposed, applied, differs, r, bd, req.reason()));
        audit.record(CurrentUser.username(), "LOAN_RESTRUCTURE", "LOAN", l.loanNo(),
                Map.of("summary", summary, "approvalId", r.id().toString()));
        return l.loanNo();
    }

    // ------------------------------------------------------------------------------------------------ sanctioned amount (US-050, US-059)
    /**
     * openapi.yaml#/components/schemas/SanctionChangeRequest: the new sanctioned amount, or {@code cancelUndrawn} to
     * bring it down to what has been disbursed.
     */
    public record SanctionChangeRequest(BigDecimal newAmount, Boolean cancelUndrawn, String reason) {}

    private BigDecimal newSanction(SanctionChangeRequest q, LoanAccount acc) {
        if (q == null) throw ApiException.invalid("newAmount or cancelUndrawn is required");
        boolean cancel = Boolean.TRUE.equals(q.cancelUndrawn());
        if (cancel == (q.newAmount() != null)) throw ApiException.invalid("give either newAmount or cancelUndrawn");
        return cancel ? acc.disbursedAmount() : q.newAmount();
    }

    /**
     * Checks beyond the engine's for a top-up in the same account: the product booked must allow it, the new amount
     * must be within the product's maximum, and the borrower's exposure limit (US-034) must hold with the extra amount.
     */
    private void checkTopUp(UUID loanId, LoanAccount.SanctionEffect e) {
        if (!e.topUp()) return;
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT l.top_up_allowed, l.customer_id, p.max_amount FROM lending.loan_account l
                  JOIN lending.loan_product p ON p.code = l.product_code WHERE l.id = ?
                """, loanId);
        if (!Boolean.TRUE.equals(row.get("top_up_allowed"))) {
            throw ApiException.conflict("this loan's product does not allow a top-up in the same account; book a new loan");
        }
        BigDecimal max = (BigDecimal) row.get("max_amount");
        if (e.sanctionedAfter().compareTo(max) > 0) {
            throw ApiException.invalid("the sanctioned amount cannot exceed the product maximum " + plain(max));
        }
        Map<String, Object> x = jdbc.queryForMap("SELECT exposure_limit, as_borrower FROM customer.exposure WHERE customer_id = ?",
                row.get("customer_id"));
        BigDecimal limit = (BigDecimal) x.get("exposure_limit");
        BigDecimal increase = e.sanctionedAfter().subtract(e.sanctionedBefore());
        if (limit != null && ((BigDecimal) x.get("as_borrower")).add(increase).compareTo(limit) > 0) {
            throw ApiException.conflict("customer exposure limit " + plain(limit) + " would be exceeded by a top-up of " + plain(increase));
        }
    }

    private static Map<String, Object> sanctionFigures(LoanAccount.SanctionEffect e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sanctionedBefore", plain(e.sanctionedBefore()));
        m.put("sanctionedAfter", plain(e.sanctionedAfter()));
        m.put("disbursed", plain(e.disbursed()));
        m.put("undrawnAfter", plain(e.undrawnAfter()));
        m.put("topUp", e.topUp());
        return m;
    }

    /** Before/after of a change of the sanctioned amount, and the schedule it leaves; nothing changes. */
    public Map<String, Object> previewSanctionChange(UUID loanId, SanctionChangeRequest req) {
        LocalDate bd = days.current().businessDate();
        LoanAccount acc = active(loanId).account();
        BigDecimal target = newSanction(req, acc);
        LoanAccount.SanctionEffect e = engine(() -> acc.previewSanctionChange(target, bd));
        checkTopUp(loanId, e);
        Map<String, Object> m = sanctionFigures(e);
        m.put("asOf", bd.toString());
        m.put("schedule", engine(() -> acc.dryRun(bd, null, x -> {
            x.changeSanction(target, bd);
            return x.futureSchedule();
        })).stream().map(LoanService::row).toList());
        m.put("note", e.topUp() ? "The extra amount is paid out by a disbursement after approval; the schedule changes then."
                : "Reducing the sanctioned amount to what is disbursed starts the EMIs of a pre-EMI loan.");
        return m;
    }

    /** Maker: top-up in the same account, reduction of the undrawn amount, or its cancellation (one checker, V18). */
    @Transactional
    public Map<String, Object> proposeSanctionChange(UUID loanId, SanctionChangeRequest req) {
        if (req == null || req.reason() == null || req.reason().isBlank()) throw ApiException.invalid("a reason is required");
        LocalDate bd = days.current().businessDate();
        LoanStore.Loaded l = active(loanId);
        BigDecimal target = newSanction(req, l.account());
        LoanAccount.SanctionEffect e = engine(() -> l.account().previewSanctionChange(target, bd));
        checkTopUp(loanId, e);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loanId.toString());
        payload.put("loanNo", l.loanNo());
        payload.put("newAmount", plain(target));
        payload.put("reason", req.reason());
        payload.put("proposedOn", bd.toString());
        payload.put("preview", sanctionFigures(e));
        return com.corebanking.platform.ApprovalView.of(approvals.propose("LOAN_SANCTION_CHANGE", "AMEND", l.loanNo(), payload,
                currentTerms(l.account()), e.topUp() ? e.sanctionedAfter().subtract(e.sanctionedBefore()) : null, branchOf(loanId), null));
    }

    /** Where the account stands, for the history row of a change that has no before/after schedule of its own. */
    private record Position(BigDecimal emi, int remaining, BigDecimal rate, LocalDate maturity, BigDecimal interest) {}

    private static Position position(LoanAccount a) {
        List<Instalment> f = a.futureSchedule();
        if (f.isEmpty()) throw ApiException.conflict("no instalments are left: the loan has matured");
        return new Position(a.currentEmi(), f.size(), a.ratePercent(), f.get(f.size() - 1).dueDate(),
                f.stream().map(Instalment::interest).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    String applySanctionChange(ApprovalRequest r) {
        LocalDate bd = days.requireOpen();
        UUID loanId = UUID.fromString(String.valueOf(r.payload().get("loanId")));
        BigDecimal target = dec(r.payload().get("newAmount"));
        String reason = str(r.payload().get("reason"));
        LoanStore.Loaded l = active(loanId);
        LoanAccount acc = l.account();
        LoanAccount.Snapshot before = acc.snapshot();
        Position was = position(acc);
        LoanAccount.SanctionEffect e = engine(() -> acc.previewSanctionChange(target, bd));
        checkTopUp(loanId, e);
        LoanAccount.Result result = engine(() -> acc.changeSanction(target, bd));
        Position now = position(acc);
        store.save(jdbc, loanId, acc, bd);
        if (!acc.futureSchedule().equals(before.futureSchedule())) store.replaceSchedule(jdbc, loanId, acc.futureSchedule());
        UUID txn = store.recordTxn(jdbc, loanId, "SANCTION_CHANGE", bd, bd, null, result.lots(), before, result.summary(), null, r.maker());
        Map<?, ?> proposed = r.payload().get("preview") instanceof Map<?, ?> p ? p : Map.of();
        Map<String, Object> applied = sanctionFigures(e);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("newAmount", plain(target));
        parameters.put("reason", reason);
        recordHistory(new History(loanId, txn, "SANCTION_CHANGE", parameters, was.emi(), now.emi(), was.remaining(), now.remaining(),
                was.rate(), now.rate(), was.maturity(), now.maturity(), was.interest(), now.interest(), proposed, applied,
                !same(proposed.get("sanctionedBefore"), e.sanctionedBefore()), r, bd, reason));
        audit.record(CurrentUser.username(), "LOAN_SANCTION_CHANGE", "LOAN", l.loanNo(),
                Map.of("summary", result.summary(), "approvalId", r.id().toString()));
        return l.loanNo();
    }

    // ------------------------------------------------------------------------------------------------ asset-class override (US-059)
    /**
     * openapi.yaml#/components/schemas/NpaOverrideRequest. Manual NPA mark: {@code assetClass} is the NPA class the
     * account is downgraded to or held at, {@code until} the date the override expires.
     */
    public record NpaOverrideRequest(String assetClass, LocalDate until, String reason) {}

    private static AssetClass npaClass(String name) {
        try {
            return AssetClass.valueOf(String.valueOf(name));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("assetClass must be SUBSTANDARD, DOUBTFUL1, DOUBTFUL2, DOUBTFUL3 or LOSS");
        }
    }

    private Map<String, Object> classification(LoanAccount a, LocalDate bd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assetClass", a.assetClass().name());
        m.put("dpd", a.dpd());
        m.put("overdueAmount", plain(a.overdueAmount(bd)));
        m.put("npaSince", a.npaSince() == null ? null : a.npaSince().toString());
        m.put("overrideClass", a.classFloor() == null ? null : a.classFloor().name());
        m.put("overrideUntil", a.classFloorUntil() == null ? null : a.classFloorUntil().toString());
        m.put("suspense", plain(a.suspense()));
        return m;
    }

    /**
     * Maker: manual NPA mark (two checkers, V18). An override can only downgrade the account or hold it at its NPA
     * class; it can never upgrade it (IRACP). The engine is run on a copy first, so a refusal is reported now.
     */
    @Transactional
    public Map<String, Object> proposeNpaOverride(UUID loanId, NpaOverrideRequest req) {
        if (req == null || req.reason() == null || req.reason().isBlank()) throw ApiException.invalid("a reason is required");
        LocalDate bd = days.current().businessDate();
        AssetClass cls = npaClass(req.assetClass());
        LoanStore.Loaded l = active(loanId);
        LoanAccount acc = l.account();
        Map<String, Object> current = classification(acc, bd);
        Map<String, Object> after = engine(() -> acc.dryRun(bd, null, x -> {
            x.overrideAssetClass(cls, req.until(), bd);
            return classification(x, bd);
        }));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loanId.toString());
        payload.put("loanNo", l.loanNo());
        payload.put("action", "OVERRIDE");
        payload.put("assetClass", cls.name());
        payload.put("until", req.until().toString());
        payload.put("reason", req.reason());
        payload.put("proposedOn", bd.toString());
        payload.put("preview", after);
        return com.corebanking.platform.ApprovalView.of(approvals.propose("LOAN_NPA_OVERRIDE", "OVERRIDE", l.loanNo(), payload, current,
                acc.principalOutstanding(), branchOf(loanId), null));
    }

    /**
     * Maker: un-mark - ends an override before its expiry (two checkers, V18). Refused while the account has unpaid
     * dues, at proposal and again at approval: an NPA is upgraded only when its arrears are cleared (RBI IRACP norms).
     * The upgrade itself is done by the next day-end, by the normal classification rules.
     */
    @Transactional
    public Map<String, Object> proposeNpaRelease(UUID loanId, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.invalid("a reason is required");
        LocalDate bd = days.current().businessDate();
        LoanStore.Loaded l = active(loanId);
        LoanAccount acc = l.account();
        Map<String, Object> current = classification(acc, bd);
        Map<String, Object> after = engine(() -> acc.dryRun(bd, null, x -> {
            x.releaseAssetClassOverride(bd);
            return classification(x, bd);
        }));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loanId.toString());
        payload.put("loanNo", l.loanNo());
        payload.put("action", "RELEASE");
        payload.put("reason", reason);
        payload.put("proposedOn", bd.toString());
        payload.put("preview", after);
        return com.corebanking.platform.ApprovalView.of(approvals.propose("LOAN_NPA_OVERRIDE", "RELEASE", l.loanNo(), payload, current,
                acc.principalOutstanding(), branchOf(loanId), null));
    }

    /** Checkers approved a mark or an un-mark: the engine runs on the loan as it is now. Not reversible. */
    String applyNpaOverride(ApprovalRequest r) {
        LocalDate bd = days.requireOpen();
        UUID loanId = UUID.fromString(String.valueOf(r.payload().get("loanId")));
        boolean release = "RELEASE".equals(str(r.payload().get("action")));
        String reason = str(r.payload().get("reason"));
        LoanStore.Loaded l = active(loanId);
        LoanAccount acc = l.account();
        LoanAccount.Snapshot before = acc.snapshot();
        Position was = position(acc);
        LoanAccount.Result result;
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("action", release ? "RELEASE" : "OVERRIDE");
        parameters.put("classBefore", acc.assetClass().name());
        if (release) {
            result = engine(() -> acc.releaseAssetClassOverride(bd));
        } else {
            AssetClass cls = npaClass(str(r.payload().get("assetClass")));
            LocalDate until = LocalDate.parse(String.valueOf(r.payload().get("until")));
            result = engine(() -> acc.overrideAssetClass(cls, until, bd));
            parameters.put("assetClass", cls.name());
            parameters.put("until", until.toString());
        }
        parameters.put("reason", reason);
        for (TransactionLot lot : result.lots()) posting.post(lot, r.maker());
        store.save(jdbc, loanId, acc, bd);
        UUID txn = store.recordTxn(jdbc, loanId, "NPA_OVERRIDE", bd, bd, null, result.lots(), before, result.summary(), null, r.maker());
        Map<?, ?> proposed = r.payload().get("preview") instanceof Map<?, ?> p ? p : Map.of();
        Map<String, Object> applied = classification(acc, bd);
        recordHistory(new History(loanId, txn, "NPA_OVERRIDE", parameters, was.emi(), was.emi(), was.remaining(), was.remaining(),
                was.rate(), was.rate(), was.maturity(), was.maturity(), was.interest(), was.interest(), proposed, applied,
                !String.valueOf(proposed.get("assetClass")).equals(acc.assetClass().name()), r, bd, reason));
        audit.record(CurrentUser.username(), release ? "LOAN_NPA_RELEASE" : "LOAN_NPA_OVERRIDE", "LOAN", l.loanNo(),
                Map.of("summary", result.summary(), "approvalId", r.id().toString()));
        return l.loanNo();
    }

    /** Amendment and restructure history, newest first. */
    public List<Map<String, Object>> amendments(UUID loanId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.seq, a.txn_id AS "txnId", a.kind, a.parameters::text AS parameters,
                       a.emi_before::text AS "emiBefore", a.emi_after::text AS "emiAfter",
                       a.tenure_before AS "tenureBefore", a.tenure_after AS "tenureAfter",
                       a.rate_before::text AS "rateBefore", a.rate_after::text AS "rateAfter",
                       a.maturity_before::text AS "maturityBefore", a.maturity_after::text AS "maturityAfter",
                       a.interest_before::text AS "interestBefore", a.interest_after::text AS "interestAfter",
                       a.proposed_figures::text AS "proposedFigures", a.applied_figures::text AS "appliedFigures",
                       a.differs_from_proposal AS "differsFromProposal", a.approval_id AS "approvalId",
                       a.made_by AS "madeBy", a.checked_by AS "checkedBy", a.business_date::text AS "businessDate",
                       a.reason, a.reversed_by AS "reversedBy", a.created_at AS "createdAt"
                  FROM lending.loan_amendment a WHERE a.loan_id = ? ORDER BY a.seq DESC
                """, loanId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            m.put("parameters", json.readMap((String) row.get("parameters")));
            m.put("proposedFigures", json.readMap((String) row.get("proposedFigures")));
            m.put("appliedFigures", json.readMap((String) row.get("appliedFigures")));
            out.add(m);
        }
        return out;
    }

    private record History(UUID loanId, UUID txnId, String kind, Map<String, Object> parameters, BigDecimal emiBefore,
                           BigDecimal emiAfter, int tenureBefore, int tenureAfter, BigDecimal rateBefore, BigDecimal rateAfter,
                           LocalDate maturityBefore, LocalDate maturityAfter, BigDecimal interestBefore, BigDecimal interestAfter,
                           Map<?, ?> proposed, Map<String, Object> applied, boolean differs, ApprovalRequest approval,
                           LocalDate businessDate, String reason) {}

    private void recordHistory(History h) {
        Integer seq = jdbc.queryForObject("SELECT coalesce(max(seq), 0) + 1 FROM lending.loan_amendment WHERE loan_id = ?",
                Integer.class, h.loanId());
        jdbc.update("""
                INSERT INTO lending.loan_amendment (id, loan_id, seq, txn_id, kind, parameters, emi_before, emi_after, tenure_before,
                    tenure_after, rate_before, rate_after, maturity_before, maturity_after, interest_before, interest_after,
                    proposed_figures, applied_figures, differs_from_proposal, approval_id, made_by, checked_by, business_date, reason)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), h.loanId(), seq, h.txnId(), h.kind(), json.write(h.parameters()), h.emiBefore(), h.emiAfter(),
                h.tenureBefore(), h.tenureAfter(), h.rateBefore(), h.rateAfter(), h.maturityBefore(), h.maturityAfter(),
                h.interestBefore(), h.interestAfter(), h.proposed().isEmpty() ? null : json.write(h.proposed()), json.write(h.applied()),
                h.differs(), h.approval().id(), h.approval().maker(), CurrentUser.username(), h.businessDate(), h.reason());
    }

    private static Map<String, Object> figures(Amendment.Effect e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rateBefore", plain(e.rateBefore()));
        m.put("rateAfter", plain(e.rateAfter()));
        m.put("emiBefore", plain(e.emiBefore()));
        m.put("emiAfter", plain(e.emiAfter()));
        m.put("remainingBefore", e.remainingBefore());
        m.put("remainingAfter", e.remainingAfter());
        m.put("nextDueBefore", e.nextDueBefore().toString());
        m.put("nextDueAfter", e.nextDueAfter().toString());
        m.put("maturityBefore", e.maturityBefore().toString());
        m.put("maturityAfter", e.maturityAfter().toString());
        m.put("interestBefore", plain(e.interestBefore()));
        m.put("interestAfter", plain(e.interestAfter()));
        m.put("brokenPeriodInterest", plain(e.brokenPeriodInterest()));
        return m;
    }

    private static Map<String, Object> simulationFigures(RestructureSimulation s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("newRatePercent", s.terms().newRatePercent() == null ? null : plain(s.terms().newRatePercent()));
        m.put("remainingInstalments", s.terms().remainingInstalments());
        m.put("principalMoratoriumMonths", s.terms().principalMoratoriumMonths());
        m.put("overdueInterestTreatment", s.terms().overdueInterest().name());
        m.put("classBefore", s.classBefore().name());
        m.put("classAfter", s.classAfter().name());
        m.put("principalBefore", plain(s.principalBefore()));
        m.put("overduePrincipalRescheduled", plain(s.overduePrincipalRescheduled()));
        m.put("overdueInterest", plain(s.overdueInterest()));
        m.put("interestCapitalised", plain(s.interestCapitalised()));
        m.put("arrearsKept", plain(s.arrearsKept()));
        m.put("principalAfter", plain(s.principalAfter()));
        m.put("rateBefore", plain(s.rateBefore()));
        m.put("rateAfter", plain(s.rateAfter()));
        m.put("emiBefore", plain(s.emiBefore()));
        m.put("emiAfter", plain(s.emiAfter()));
        m.put("remainingBefore", s.remainingBefore());
        m.put("remainingAfter", s.remainingAfter());
        m.put("maturityBefore", s.maturityBefore().toString());
        m.put("maturityAfter", s.maturityAfter().toString());
        m.put("interestBefore", plain(s.interestBefore()));
        m.put("interestAfter", plain(s.interestAfter()));
        m.put("npvBefore", plain(s.npvBefore()));
        m.put("npvAfter", plain(s.npvAfter()));
        m.put("npvLoss", plain(s.npvLoss()));
        m.put("upgradeNotBefore", s.specifiedPeriodMinEnd().toString());
        return m;
    }

    private static Map<String, Object> currentTerms(LoanAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rate", plain(a.ratePercent()));
        m.put("emi", plain(a.currentEmi()));
        m.put("remainingInstalments", a.futureSchedule().size());
        m.put("nextDueDate", a.nextDueDate() == null ? null : a.nextDueDate().toString());
        m.put("principalOutstanding", plain(a.principalOutstanding()));
        m.put("assetClass", a.assetClass().name());
        m.put("dpd", a.dpd());
        return m;
    }

    private static Map<String, Object> requestMap(AmendmentRequest q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", q.kind());
        m.put("newRatePercent", plain(q.newRatePercent()));
        m.put("rateOption", q.rateOption());
        m.put("remainingInstalments", q.remainingInstalments());
        m.put("newEmi", plain(q.newEmi()));
        m.put("newDueDay", q.newDueDay());
        m.put("newMaturityDate", q.newMaturityDate() == null ? null : q.newMaturityDate().toString());
        m.put("reason", q.reason());
        return m;
    }

    private static Map<String, Object> termsMap(RestructureRequest q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("newRatePercent", plain(q.newRatePercent()));
        m.put("remainingInstalments", q.remainingInstalments());
        m.put("principalMoratoriumMonths", q.principalMoratoriumMonths());
        m.put("overdueInterest", q.overdueInterest());
        m.put("reason", q.reason());
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        return o == null ? null : new BigDecimal(String.valueOf(o));
    }

    private static Integer integer(Object o) {
        return o == null ? null : Integer.valueOf(String.valueOf(o));
    }

    private static boolean same(Object proposed, BigDecimal actual) {
        return proposed != null && new BigDecimal(String.valueOf(proposed)).compareTo(actual) == 0;
    }

    /** Within a rupee (rounding of the interest total). */
    private static boolean near(Object proposed, BigDecimal actual) {
        return proposed != null && new BigDecimal(String.valueOf(proposed)).subtract(actual).abs().compareTo(BigDecimal.ONE) <= 0;
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
                       l.kfs_accepted_at AS "kfsAcceptedAt", l.external_ref AS "externalRef", l.closed_on::text AS "closedOn",
                       coalesce(l.current_rate, l.rate)::text AS "currentRate", l.restructured_on::text AS "restructuredOn",
                       l.restructure_count AS "restructureCount", l.upgrade_not_before::text AS "upgradeNotBefore",
                       l.frequency, coalesce(l.disbursed_amount, 0)::text AS "disbursedAmount", l.undrawn_amount::text AS "undrawnAmount",
                       l.multiple_disbursements AS "multipleDisbursements", l.pre_emi AS "preEmi", l.top_up_allowed AS "topUpAllowed",
                       l.class_floor AS "overrideClass", l.class_floor_until::text AS "overrideUntil",
                       l.benchmark_code AS "benchmarkCode", l.spread::text AS spread, l.next_rate_reset::text AS "nextRateReset"
                  FROM lending.loan_account l JOIN customer.customer c ON c.id = l.customer_id
                 WHERE l.id = ?
                """, id);
        if (rows.isEmpty()) throw ApiException.notFound("loan " + id);
        return rows.get(0);
    }

    /** Borrower, co-applicants and guarantors of the loan (US-034). */
    public List<Map<String, Object>> parties(UUID id) {
        return jdbc.queryForList("""
                SELECT p.customer_id AS "customerId", c.customer_no AS "customerNo", c.display_name AS "customerName", p.role,
                       c.status AS "customerStatus", p.added_by AS "addedBy", p.added_at AS "addedAt"
                  FROM lending.loan_party p JOIN customer.customer c ON c.id = p.customer_id
                 WHERE p.loan_id = ?
                 ORDER BY array_position(ARRAY['BORROWER','CO_APPLICANT','GUARANTOR'], p.role), c.customer_no
                """, id);
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
            r.put("principalRescheduled", plain(d.principalRescheduled()));
            r.put("interestCapitalised", plain(d.interestCapitalised()));
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
            Object amount = r.payload().get("amount");       // absent on requests raised before tranches (P2-6): the whole loan
            loans.disburse(loanId, r.maker(), amount == null ? null : new BigDecimal(String.valueOf(amount)));
            return String.valueOf(r.payload().get("loanNo"));
        }
    }

    /**
     * Not private: the applier holds the Spring proxy of this service, and a private method called on a proxy runs
     * on the proxy object itself, whose fields are not set.
     */
    void applyWaiver(UUID loanId, String chargeId, BigDecimal amount) {
        apply(loanId, "WAIVER", amount, null, (a, bd) -> a.waiveCharge(chargeId, amount, bd));
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
            loans.applyWaiver(loanId, chargeId, amount);
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
            // a restructure may have been applied after the reversal was proposed
            if (restructuredSince(jdbc, loanId, target.get("seq"))) throw ApiException.conflict(RESTRUCTURE_NOT_REVERSIBLE);
            if (standsSince(jdbc, loanId, target.get("seq"))) throw ApiException.conflict(STANDING_NOT_REVERSIBLE);
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
                // an amendment undone by the state restore is marked reversed in its history
                jdbc.update("UPDATE lending.loan_amendment SET reversed_by = ? WHERE txn_id = ? AND reversed_by IS NULL", reversalId, t.get("id"));
            }
            return String.valueOf(r.payload().get("loanNo"));
        }
    }

    @Service
    static class AmendmentApplier implements ApprovalApplier {
        private final LoanService loans;

        AmendmentApplier(@org.springframework.context.annotation.Lazy LoanService loans) {
            this.loans = loans;
        }

        @Override public String entityType() { return "LOAN_AMENDMENT"; }

        @Override
        public String apply(ApprovalRequest r) {
            return loans.applyAmendment(r);
        }
    }

    @Service
    static class SanctionChangeApplier implements ApprovalApplier {
        private final LoanService loans;

        SanctionChangeApplier(@org.springframework.context.annotation.Lazy LoanService loans) {
            this.loans = loans;
        }

        @Override public String entityType() { return "LOAN_SANCTION_CHANGE"; }

        @Override
        public String apply(ApprovalRequest r) {
            return loans.applySanctionChange(r);
        }
    }

    @Service
    static class NpaOverrideApplier implements ApprovalApplier {
        private final LoanService loans;

        NpaOverrideApplier(@org.springframework.context.annotation.Lazy LoanService loans) {
            this.loans = loans;
        }

        @Override public String entityType() { return "LOAN_NPA_OVERRIDE"; }

        @Override
        public String apply(ApprovalRequest r) {
            return loans.applyNpaOverride(r);
        }
    }

    @Service
    static class RestructureApplier implements ApprovalApplier {
        private final LoanService loans;

        RestructureApplier(@org.springframework.context.annotation.Lazy LoanService loans) {
            this.loans = loans;
        }

        @Override public String entityType() { return "LOAN_RESTRUCTURE"; }

        @Override
        public String apply(ApprovalRequest r) {
            return loans.applyRestructure(r);
        }
    }
}
