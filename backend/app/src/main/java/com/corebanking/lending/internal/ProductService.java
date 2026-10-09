package com.corebanking.lending.internal;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.FeeCalculator;
import com.corebanking.calc.Rounding;
import com.corebanking.lending.engine.Appropriation;
import com.corebanking.lending.engine.FeeRule;
import com.corebanking.lending.engine.Frequency;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.lending.engine.LoanPostings;
import com.corebanking.lending.engine.LoanTerms;
import com.corebanking.lending.engine.RepaymentMethod;
import com.corebanking.lending.engine.ScheduleBuilder;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loan product factory (US-038 – US-043): versioned products with fee rules, maker-checker on every change. */
@Service
public class ProductService {

    public record Slab(BigDecimal from, BigDecimal to, BigDecimal fee) {}

    public record Fee(String code, String name, String event, String calcType, BigDecimal amount, BigDecimal percent,
                      List<Slab> slabs, BigDecimal minAmount, BigDecimal maxAmount, BigDecimal gstRate, String taxTreatment,
                      Boolean deductFromDisbursal) {}

    public record Product(String code, String name, String repaymentMethod, BigDecimal minAmount, BigDecimal maxAmount,
                          Integer minTenorMonths, Integer maxTenorMonths, BigDecimal minRate, BigDecimal maxRate,
                          String interestTableCode, String rateType, String dayCount, String rounding, BigDecimal penalChargeRate,
                          Integer maxMoratoriumMonths, Integer coolingOffDays, Boolean secured, List<String> appropriationSequence,
                          String appropriationMode, String prepaymentMode, String status, List<Fee> fees,
                          LoanPostings.GlMap glMap, Integer version,
                          // P2-6 (US-039, US-050, US-054); every one is optional and defaults as in V18
                          String frequency, String interestBasis, String bpiMode, BigDecimal stepPercent, Integer stepEvery,
                          Integer principalEvery, Boolean multipleDisbursements, Boolean preEmi, Boolean topUpAllowed,
                          String benchmarkCode, BigDecimal spread, Integer resetFrequencyMonths,
                          // V24: what a floating-rate reset changes unless the borrower chose otherwise (board policy)
                          String resetOption) {

        /** The product with every optional setting filled in with its default. */
        Product withDefaults() {
            return new Product(code, name, repaymentMethod, minAmount, maxAmount, minTenorMonths, maxTenorMonths, minRate, maxRate,
                    interestTableCode, rateType == null ? "FIXED" : rateType, dayCount == null ? "ACTUAL_365" : dayCount,
                    rounding == null ? "RUPEE_HALF_UP" : rounding, penalChargeRate, maxMoratoriumMonths == null ? 0 : maxMoratoriumMonths,
                    coolingOffDays == null ? 3 : coolingOffDays, Boolean.TRUE.equals(secured),
                    appropriationSequence == null ? List.of("INTEREST", "PRINCIPAL", "PENAL", "FEE") : appropriationSequence,
                    appropriationMode == null ? "BY_DEMAND" : appropriationMode, prepaymentMode == null ? "REDUCE_EMI" : prepaymentMode,
                    status == null ? "ACTIVE" : status, fees == null ? List.of() : fees,
                    glMap == null ? LoanPostings.GlMap.starter() : glMap, version,
                    frequency == null ? "MONTHLY" : frequency, interestBasis == null ? "DAILY_REDUCING" : interestBasis,
                    bpiMode == null ? "NONE" : bpiMode, stepPercent, stepEvery, principalEvery == null ? 1 : principalEvery,
                    Boolean.TRUE.equals(multipleDisbursements), Boolean.TRUE.equals(preEmi), Boolean.TRUE.equals(topUpAllowed),
                    benchmarkCode, spread, resetFrequencyMonths, resetOption == null ? "KEEP_TENURE_CHANGE_EMI" : resetOption);
        }
    }

    /** One row of a STRUCTURED schedule as entered on the loan: the principal falling due on a date. */
    public record ScheduleRow(LocalDate dueDate, BigDecimal principal) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final Json json;

    public ProductService(JdbcTemplate jdbc, ApprovalService approvals, Json json) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.json = json;
    }

    public List<Product> list() {
        return jdbc.queryForList("SELECT code FROM lending.loan_product ORDER BY code", String.class).stream().map(c -> get(jdbc, c)).toList();
    }

    public Product get(String code) {
        return get(jdbc, code);
    }

    public Product get(JdbcTemplate j, String code) {
        List<Map<String, Object>> rows = j.queryForList("""
                SELECT code, name, repayment_method, min_amount, max_amount, min_tenor_months, max_tenor_months, min_rate, max_rate,
                       interest_table_code, rate_type, day_count, rounding, penal_charge_rate, max_moratorium_months,
                       cooling_off_days, secured, array_to_string(appropriation_sequence, ',') AS seq, appropriation_mode,
                       prepayment_mode, status, gl_map::text AS gl_map, version, frequency, interest_basis, bpi_mode, step_percent,
                       step_every, principal_every, multiple_disbursements, pre_emi, top_up_allowed, benchmark_code, spread,
                       reset_frequency_months, rate_reset_option
                  FROM lending.loan_product WHERE code = ?
                """, code);
        if (rows.isEmpty()) throw ApiException.notFound("loan product " + code);
        Map<String, Object> r = rows.get(0);
        List<Fee> fees = j.queryForList("SELECT * , slabs::text AS slabs_json FROM lending.fee_rule WHERE product_code = ? ORDER BY code", code)
                .stream().map(f -> new Fee((String) f.get("code"), (String) f.get("name"), (String) f.get("event"), (String) f.get("calc_type"),
                        (BigDecimal) f.get("amount"), (BigDecimal) f.get("percent"),
                        f.get("slabs_json") == null ? List.of() : List.of(json.read((String) f.get("slabs_json"), Slab[].class)),
                        (BigDecimal) f.get("min_amount"), (BigDecimal) f.get("max_amount"), (BigDecimal) f.get("gst_rate"),
                        (String) f.get("tax_treatment"), (Boolean) f.get("deduct_from_disbursal"))).toList();
        String gl = (String) r.get("gl_map");
        return new Product((String) r.get("code"), (String) r.get("name"), (String) r.get("repayment_method"),
                (BigDecimal) r.get("min_amount"), (BigDecimal) r.get("max_amount"), (Integer) r.get("min_tenor_months"),
                (Integer) r.get("max_tenor_months"), (BigDecimal) r.get("min_rate"), (BigDecimal) r.get("max_rate"),
                (String) r.get("interest_table_code"), (String) r.get("rate_type"), (String) r.get("day_count"), (String) r.get("rounding"),
                (BigDecimal) r.get("penal_charge_rate"), (Integer) r.get("max_moratorium_months"), (Integer) r.get("cooling_off_days"),
                (Boolean) r.get("secured"), List.of(((String) r.get("seq")).split(",")), (String) r.get("appropriation_mode"),
                (String) r.get("prepayment_mode"), (String) r.get("status"), fees,
                gl == null ? LoanPostings.GlMap.starter() : json.read(gl, LoanPostings.GlMap.class), (Integer) r.get("version"),
                (String) r.get("frequency"), (String) r.get("interest_basis"), (String) r.get("bpi_mode"),
                (BigDecimal) r.get("step_percent"), (Integer) r.get("step_every"), (Integer) r.get("principal_every"),
                (Boolean) r.get("multiple_disbursements"), (Boolean) r.get("pre_emi"), (Boolean) r.get("top_up_allowed"),
                (String) r.get("benchmark_code"), (BigDecimal) r.get("spread"), (Integer) r.get("reset_frequency_months"),
                (String) r.get("rate_reset_option"));
    }

    /** Product templates (US-038): starting points for the product wizard, seeded in V18. Nothing here is a live product. */
    public List<Map<String, Object>> templates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT code, name, description, category, product::text AS product
                  FROM lending.loan_product_template WHERE active ORDER BY sort_order, code
                """)) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            m.put("product", json.readMap((String) row.get("product")));
            out.add(m);
        }
        return out;
    }

    /** Validates the product with the engine (fee rules must compute) and raises a maker-checker request. */
    @Transactional
    public ApprovalRequest propose(Product p) {
        validate(p);
        requireBenchmark(p);
        List<String> existing = jdbc.queryForList("SELECT code FROM lending.loan_product WHERE code = ?", String.class, p.code());
        Map<String, Object> current = existing.isEmpty() ? null : json.toMap(get(jdbc, p.code()));
        return approvals.propose("LOAN_PRODUCT", existing.isEmpty() ? "CREATE" : "UPDATE", p.code(), json.toMap(p), current, null, null, null);
    }

    void requireBenchmark(Product p) {
        if (p.benchmarkCode() == null) return;
        Integer n = jdbc.queryForObject("SELECT count(*) FROM lending.benchmark WHERE code = ?", Integer.class, p.benchmarkCode());
        if (n == null || n == 0) throw ApiException.invalid("unknown benchmark " + p.benchmarkCode());
    }

    static void validate(Product p) {
        if (p.code() == null || !p.code().matches("[A-Z0-9]{2,12}")) throw ApiException.invalid("product code must be 2-12 capitals/digits");
        validateTerms(p);
    }

    /** Everything but the code: also used for a draft product that has none yet (product preview, US-044). */
    static void validateTerms(Product p) {
        if (p.name() == null || p.name().isBlank()) throw ApiException.invalid("name is required");
        try {
            RepaymentMethod.valueOf(p.repaymentMethod());
            if (p.dayCount() != null) DayCount.valueOf(p.dayCount());
            if (p.rounding() != null) Rounding.valueOf(p.rounding());
            if (p.appropriationSequence() != null) p.appropriationSequence().forEach(Appropriation.Component::valueOf);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.invalid("invalid repayment method, day count, rounding or appropriation component");
        }
        try {
            if (p.frequency() != null) Frequency.valueOf(p.frequency());
            if (p.interestBasis() != null) LoanTerms.InterestBasis.valueOf(p.interestBasis());
            if (p.bpiMode() != null) LoanTerms.BpiMode.valueOf(p.bpiMode());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("invalid frequency, interest basis or broken-period interest mode");
        }
        checkMethodOptions(p.withDefaults());
        if (p.minAmount() == null || p.maxAmount() == null || p.minAmount().compareTo(p.maxAmount()) > 0 || p.minAmount().signum() <= 0) {
            throw ApiException.invalid("0 < minAmount <= maxAmount");
        }
        if (p.minTenorMonths() == null || p.maxTenorMonths() == null || p.minTenorMonths() < 1 || p.minTenorMonths() > p.maxTenorMonths()) {
            throw ApiException.invalid("1 <= minTenorMonths <= maxTenorMonths");
        }
        if (p.minRate() == null || p.maxRate() == null || p.minRate().signum() < 0 || p.minRate().compareTo(p.maxRate()) > 0) {
            throw ApiException.invalid("0 <= minRate <= maxRate");
        }
        for (Fee f : p.fees() == null ? List.<Fee>of() : p.fees()) toRule(f).compute(p.minAmount(), "00", "00", Rounding.PAISE_HALF_UP);
        Frequency f = Frequency.valueOf(p.withDefaults().frequency());
        if (p.maxTenorMonths() > f.maxPeriods()) {
            throw ApiException.invalid("the tenor is counted in " + f.name().toLowerCase(java.util.Locale.ROOT) + " periods: at most " + f.maxPeriods());
        }
    }

    /** The combinations the engine supports; the database has the same rules as constraints (V18). */
    private static void checkMethodOptions(Product d) {
        RepaymentMethod method = RepaymentMethod.valueOf(d.repaymentMethod());
        boolean step = d.stepPercent() != null || d.stepEvery() != null;
        if (method == RepaymentMethod.STEP_EQUATED) {
            if (d.stepPercent() == null || d.stepEvery() == null) throw ApiException.invalid("a step loan needs stepPercent and stepEvery");
            if (d.stepEvery() < 1) throw ApiException.invalid("stepEvery must be at least 1 instalment");
            if (d.stepPercent().signum() == 0 || d.stepPercent().compareTo(BigDecimal.valueOf(-50)) <= 0
                    || d.stepPercent().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw ApiException.invalid("stepPercent must be above -50 and at most 100, and not zero");
            }
        } else if (step) {
            throw ApiException.invalid("stepPercent and stepEvery apply to STEP_EQUATED products only");
        }
        if (d.principalEvery() < 1) throw ApiException.invalid("principalEvery must be at least 1");
        if (d.principalEvery() > 1 && method != RepaymentMethod.FIXED_PRINCIPAL) {
            throw ApiException.invalid("principalEvery applies to FIXED_PRINCIPAL products only");
        }
        boolean daily = "DAILY_REDUCING".equals(d.interestBasis());
        if ("FLAT".equals(d.interestBasis()) && method != RepaymentMethod.EQUATED) throw ApiException.invalid("a flat rate applies to EQUATED products only");
        if (method == RepaymentMethod.STRUCTURED && !daily) throw ApiException.invalid("a structured product accrues on the daily-reducing basis");
        if (d.multipleDisbursements() || d.topUpAllowed()) {
            boolean ok = daily && d.principalEvery() == 1 && (method == RepaymentMethod.EQUATED || method == RepaymentMethod.FIXED_PRINCIPAL
                    || method == RepaymentMethod.BULLET_TOTAL_INTEREST || method == RepaymentMethod.BULLET_PERIODIC_INTEREST);
            if (!ok) {
                throw ApiException.invalid("disbursement in tranches and top-up are available for equated, fixed-principal and bullet"
                        + " products on the daily-reducing basis");
            }
        }
        if (d.preEmi() && !(d.multipleDisbursements() && method == RepaymentMethod.EQUATED)) {
            throw ApiException.invalid("pre-EMI interest applies to EQUATED products disbursed in tranches");
        }
        boolean anyLink = d.benchmarkCode() != null || d.spread() != null || d.resetFrequencyMonths() != null;
        boolean fullLink = d.benchmarkCode() != null && d.spread() != null && d.resetFrequencyMonths() != null;
        if (anyLink && !(fullLink && "FLOATING".equals(d.rateType()))) {
            throw ApiException.invalid("a benchmark-linked product needs benchmarkCode, spread and resetFrequencyMonths, and rateType FLOATING");
        }
        if (d.resetFrequencyMonths() != null && (d.resetFrequencyMonths() < 1 || d.resetFrequencyMonths() > 60)) {
            throw ApiException.invalid("resetFrequencyMonths must be 1..60");
        }
        if (!List.of("KEEP_EMI_CHANGE_TENURE", "KEEP_TENURE_CHANGE_EMI").contains(d.resetOption())) {
            throw ApiException.invalid("resetOption must be KEEP_TENURE_CHANGE_EMI or KEEP_EMI_CHANGE_TENURE");
        }
        // a reset re-prices the balance by days: as the engine, only on the daily-reducing basis
        if (d.benchmarkCode() != null && !daily) throw ApiException.invalid("a benchmark-linked product accrues on the daily-reducing basis");
        for (Fee f : d.fees()) {
            if (Boolean.TRUE.equals(f.deductFromDisbursal()) && !"DISBURSEMENT".equals(f.event()) && !"EVERY_DISBURSEMENT".equals(f.event())) {
                throw ApiException.invalid("fee " + f.code() + ": only a DISBURSEMENT or EVERY_DISBURSEMENT fee can be deducted from the payout");
            }
        }
    }

    /**
     * Loan terms on this product: the one place that turns product settings into engine terms, for the loan preview,
     * the KFS, the booking and the product preview. Engine refusals come back as 422.
     *
     * @param instalment the agreed instalment, when the rate is to follow from it ("Tenure Amount and Installment")
     * @param rows       the principal by date of a STRUCTURED loan
     */
    static LoanTerms terms(Product product, BigDecimal amount, BigDecimal rate, int periods, LocalDate disbursal, LocalDate firstDue,
                           int moratorium, BigDecimal balloon, BigDecimal instalment, List<ScheduleRow> rows) {
        Product p = product.withDefaults();
        try {
            LoanTerms.Options options = new LoanTerms.Options(Frequency.valueOf(p.frequency()),
                    LoanTerms.InterestBasis.valueOf(p.interestBasis()), LoanTerms.BpiMode.valueOf(p.bpiMode()), p.stepPercent(),
                    p.stepEvery(), p.principalEvery(), instalment,
                    rows == null ? null : rows.stream().map(r -> new LoanTerms.CustomRow(r.dueDate(), r.principal())).toList());
            LoanTerms t = new LoanTerms(amount, rate, periods, disbursal, firstDue, RepaymentMethod.valueOf(p.repaymentMethod()),
                    moratorium, balloon, DayCount.valueOf(p.dayCount()), Rounding.valueOf(p.rounding()), false, options);
            ScheduleBuilder.plan(t);        // refusals that only show when the schedule is built (steps too steep, instalment too low)
            return t;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.invalid(e.getMessage() == null ? "invalid loan terms" : e.getMessage());
        }
    }

    static FeeRule toRule(Fee f) {
        try {
            return new FeeRule(f.code(), f.name(), FeeRule.Event.valueOf(f.event()), FeeRule.CalcType.valueOf(f.calcType()), f.amount(),
                    f.percent(), f.slabs() == null ? List.of() : f.slabs().stream().map(s -> new FeeRule.Slab(s.from(), s.to(), s.fee())).toList(),
                    f.minAmount(), f.maxAmount(), f.gstRate() == null ? BigDecimal.valueOf(18) : f.gstRate(),
                    f.taxTreatment() == null ? FeeCalculator.TaxTreatment.EXCLUSIVE : FeeCalculator.TaxTreatment.valueOf(f.taxTreatment()),
                    Boolean.TRUE.equals(f.deductFromDisbursal()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.invalid("fee " + f.code() + ": " + e.getMessage());
        }
    }

    /** Engine parameters for a new loan: the product as it is today is frozen into the loan (US-043). */
    public LoanAccount.Params params(Product product, String loanNo, String branch, String supplierState, String recipientState,
                                     BigDecimal rate, BigDecimal securedPortion) {
        Product p = product.withDefaults();
        return new LoanAccount.Params(loanNo, branch, supplierState, recipientState, rate, p.penalChargeRate(),
                DayCount.valueOf(p.dayCount()), Rounding.valueOf(p.rounding()),
                p.appropriationSequence().stream().map(Appropriation.Component::valueOf).toList(),
                Appropriation.Mode.valueOf(p.appropriationMode()), p.coolingOffDays(), securedPortion, p.glMap(),
                p.fees().stream().map(ProductService::toRule).toList());
    }

    /** Applies approved product changes: upsert, replace fee rules, bump version, keep history. */
    @Service
    static class Applier implements ApprovalApplier {
        private final JdbcTemplate jdbc;
        private final Json json;

        Applier(JdbcTemplate jdbc, Json json) {
            this.jdbc = jdbc;
            this.json = json;
        }

        @Override public String entityType() { return "LOAN_PRODUCT"; }

        @Override
        public String apply(ApprovalRequest r) {
            Product p = json.convert(r.payload(), Product.class);
            validate(p);
            LoanPostings.GlMap gl = p.glMap() == null ? LoanPostings.GlMap.starter() : p.glMap();
            Product d = p.withDefaults();
            String seq = "{" + String.join(",", p.appropriationSequence() == null ? List.of("INTEREST", "PRINCIPAL", "PENAL", "FEE")
                    : p.appropriationSequence()) + "}";
            jdbc.update("""
                    INSERT INTO lending.loan_product (code, name, repayment_method, min_amount, max_amount, min_tenor_months,
                        max_tenor_months, min_rate, max_rate, interest_table_code, rate_type, day_count, rounding, penal_charge_rate,
                        max_moratorium_months, cooling_off_days, secured, appropriation_sequence, appropriation_mode, prepayment_mode,
                        gl_principal, gl_interest_income, gl_interest_receivable, gl_map, status, version, effective_from, updated_by,
                        frequency, interest_basis, bpi_mode, step_percent, step_every, principal_every, multiple_disbursements,
                        pre_emi, top_up_allowed, benchmark_code, spread, reset_frequency_months, rate_reset_option)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?::jsonb, ?, 1,
                            (SELECT business_date FROM platform.business_day WHERE id = 1), ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, repayment_method = EXCLUDED.repayment_method,
                        min_amount = EXCLUDED.min_amount, max_amount = EXCLUDED.max_amount, min_tenor_months = EXCLUDED.min_tenor_months,
                        max_tenor_months = EXCLUDED.max_tenor_months, min_rate = EXCLUDED.min_rate, max_rate = EXCLUDED.max_rate,
                        interest_table_code = EXCLUDED.interest_table_code, rate_type = EXCLUDED.rate_type, day_count = EXCLUDED.day_count,
                        rounding = EXCLUDED.rounding, penal_charge_rate = EXCLUDED.penal_charge_rate,
                        max_moratorium_months = EXCLUDED.max_moratorium_months, cooling_off_days = EXCLUDED.cooling_off_days,
                        secured = EXCLUDED.secured, appropriation_sequence = EXCLUDED.appropriation_sequence,
                        appropriation_mode = EXCLUDED.appropriation_mode, prepayment_mode = EXCLUDED.prepayment_mode,
                        gl_principal = EXCLUDED.gl_principal, gl_interest_income = EXCLUDED.gl_interest_income,
                        gl_interest_receivable = EXCLUDED.gl_interest_receivable, gl_map = EXCLUDED.gl_map, status = EXCLUDED.status,
                        version = lending.loan_product.version + 1, effective_from = EXCLUDED.effective_from, updated_by = EXCLUDED.updated_by,
                        frequency = EXCLUDED.frequency, interest_basis = EXCLUDED.interest_basis, bpi_mode = EXCLUDED.bpi_mode,
                        step_percent = EXCLUDED.step_percent, step_every = EXCLUDED.step_every, principal_every = EXCLUDED.principal_every,
                        multiple_disbursements = EXCLUDED.multiple_disbursements, pre_emi = EXCLUDED.pre_emi,
                        top_up_allowed = EXCLUDED.top_up_allowed, benchmark_code = EXCLUDED.benchmark_code, spread = EXCLUDED.spread,
                        reset_frequency_months = EXCLUDED.reset_frequency_months, rate_reset_option = EXCLUDED.rate_reset_option
                    """, p.code(), p.name(), p.repaymentMethod(), p.minAmount(), p.maxAmount(), p.minTenorMonths(), p.maxTenorMonths(),
                    p.minRate(), p.maxRate(), p.interestTableCode(), p.rateType() == null ? "FIXED" : p.rateType(),
                    p.dayCount() == null ? "ACTUAL_365" : p.dayCount(), p.rounding() == null ? "RUPEE_HALF_UP" : p.rounding(),
                    p.penalChargeRate(), p.maxMoratoriumMonths() == null ? 0 : p.maxMoratoriumMonths(),
                    p.coolingOffDays() == null ? 3 : p.coolingOffDays(), Boolean.TRUE.equals(p.secured()), seq,
                    p.appropriationMode() == null ? "BY_DEMAND" : p.appropriationMode(),
                    p.prepaymentMode() == null ? "REDUCE_EMI" : p.prepaymentMode(), gl.principal(), gl.interestIncome(),
                    gl.interestReceivable(), json.write(gl), p.status() == null ? "ACTIVE" : p.status(), r.maker(),
                    d.frequency(), d.interestBasis(), d.bpiMode(), d.stepPercent(), d.stepEvery(), d.principalEvery(),
                    d.multipleDisbursements(), d.preEmi(), d.topUpAllowed(), d.benchmarkCode(), d.spread(), d.resetFrequencyMonths(),
                    d.resetOption());
            jdbc.update("DELETE FROM lending.fee_rule WHERE product_code = ?", p.code());
            for (Fee f : p.fees() == null ? List.<Fee>of() : p.fees()) {
                jdbc.update("""
                        INSERT INTO lending.fee_rule (product_code, code, name, event, calc_type, amount, percent, slabs, min_amount,
                            max_amount, gst_rate, tax_treatment, deduct_from_disbursal)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                        """, p.code(), f.code(), f.name(), f.event(), f.calcType(), f.amount(), f.percent(),
                        f.slabs() == null || f.slabs().isEmpty() ? null : json.write(f.slabs()), f.minAmount(), f.maxAmount(),
                        f.gstRate() == null ? BigDecimal.valueOf(18) : f.gstRate(),
                        f.taxTreatment() == null ? "EXCLUSIVE" : f.taxTreatment(), Boolean.TRUE.equals(f.deductFromDisbursal()));
            }
            Integer version = jdbc.queryForObject("SELECT version FROM lending.loan_product WHERE code = ?", Integer.class, p.code());
            Map<String, Object> snap = new LinkedHashMap<>(r.payload());
            snap.put("version", version);
            jdbc.update("INSERT INTO lending.loan_product_history (product_code, version, snapshot, changed_by, approval_id) VALUES (?, ?, ?::jsonb, ?, ?)",
                    p.code(), version, json.write(snap), CurrentUser.username(), r.id());
            return p.code() + " v" + version;
        }
    }

    static List<String> sequenceOf(Product p) {
        return p.appropriationSequence() == null ? new ArrayList<>() : p.appropriationSequence();
    }
}
