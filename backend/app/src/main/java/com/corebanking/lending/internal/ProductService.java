package com.corebanking.lending.internal;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.FeeCalculator;
import com.corebanking.calc.Rounding;
import com.corebanking.lending.engine.Appropriation;
import com.corebanking.lending.engine.FeeRule;
import com.corebanking.lending.engine.LoanAccount;
import com.corebanking.lending.engine.LoanPostings;
import com.corebanking.lending.engine.RepaymentMethod;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.CurrentUser;
import com.corebanking.platform.Json;
import java.math.BigDecimal;
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
                          LoanPostings.GlMap glMap, Integer version) {}

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
                       prepayment_mode, status, gl_map::text AS gl_map, version
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
                gl == null ? LoanPostings.GlMap.starter() : json.read(gl, LoanPostings.GlMap.class), (Integer) r.get("version"));
    }

    /** Validates the product with the engine (fee rules must compute) and raises a maker-checker request. */
    @Transactional
    public ApprovalRequest propose(Product p) {
        validate(p);
        List<String> existing = jdbc.queryForList("SELECT code FROM lending.loan_product WHERE code = ?", String.class, p.code());
        Map<String, Object> current = existing.isEmpty() ? null : json.toMap(get(jdbc, p.code()));
        return approvals.propose("LOAN_PRODUCT", existing.isEmpty() ? "CREATE" : "UPDATE", p.code(), json.toMap(p), current, null, null, null);
    }

    static void validate(Product p) {
        if (p.code() == null || !p.code().matches("[A-Z0-9]{2,12}")) throw ApiException.invalid("product code must be 2-12 capitals/digits");
        if (p.name() == null || p.name().isBlank()) throw ApiException.invalid("name is required");
        try {
            RepaymentMethod.valueOf(p.repaymentMethod());
            if (p.dayCount() != null) DayCount.valueOf(p.dayCount());
            if (p.rounding() != null) Rounding.valueOf(p.rounding());
            if (p.appropriationSequence() != null) p.appropriationSequence().forEach(Appropriation.Component::valueOf);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.invalid("invalid repayment method, day count, rounding or appropriation component");
        }
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
    public LoanAccount.Params params(Product p, String loanNo, String branch, String supplierState, String recipientState,
                                     BigDecimal rate, BigDecimal securedPortion) {
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
            String seq = "{" + String.join(",", p.appropriationSequence() == null ? List.of("INTEREST", "PRINCIPAL", "PENAL", "FEE")
                    : p.appropriationSequence()) + "}";
            jdbc.update("""
                    INSERT INTO lending.loan_product (code, name, repayment_method, min_amount, max_amount, min_tenor_months,
                        max_tenor_months, min_rate, max_rate, interest_table_code, rate_type, day_count, rounding, penal_charge_rate,
                        max_moratorium_months, cooling_off_days, secured, appropriation_sequence, appropriation_mode, prepayment_mode,
                        gl_principal, gl_interest_income, gl_interest_receivable, gl_map, status, version, effective_from, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?::jsonb, ?, 1,
                            (SELECT business_date FROM platform.business_day WHERE id = 1), ?)
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
                        version = lending.loan_product.version + 1, effective_from = EXCLUDED.effective_from, updated_by = EXCLUDED.updated_by
                    """, p.code(), p.name(), p.repaymentMethod(), p.minAmount(), p.maxAmount(), p.minTenorMonths(), p.maxTenorMonths(),
                    p.minRate(), p.maxRate(), p.interestTableCode(), p.rateType() == null ? "FIXED" : p.rateType(),
                    p.dayCount() == null ? "ACTUAL_365" : p.dayCount(), p.rounding() == null ? "RUPEE_HALF_UP" : p.rounding(),
                    p.penalChargeRate(), p.maxMoratoriumMonths() == null ? 0 : p.maxMoratoriumMonths(),
                    p.coolingOffDays() == null ? 3 : p.coolingOffDays(), Boolean.TRUE.equals(p.secured()), seq,
                    p.appropriationMode() == null ? "BY_DEMAND" : p.appropriationMode(),
                    p.prepaymentMode() == null ? "REDUCE_EMI" : p.prepaymentMode(), gl.principal(), gl.interestIncome(),
                    gl.interestReceivable(), json.write(gl), p.status() == null ? "ACTIVE" : p.status(), r.maker());
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
