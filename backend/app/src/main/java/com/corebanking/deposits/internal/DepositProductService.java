package com.corebanking.deposits.internal;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import com.corebanking.deposits.DemandDepositProduct;
import com.corebanking.deposits.DepositCharges;
import com.corebanking.deposits.DepositRateTable;
import com.corebanking.deposits.DepositRules;
import com.corebanking.deposits.SavingsInterest;
import com.corebanking.deposits.Tenure;
import com.corebanking.deposits.TermDepositProduct;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.Json;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deposit products (P4-1): savings and current products (family CASA) and fixed deposit products (family
 * TERM_DEPOSIT). A product is proposed whole and replaces the stored one on approval; every version is kept in
 * deposits.product_history. A product is accepted only when the engine finds nothing wrong with it under the rule
 * set in force: for a term deposit product that includes a rate for every amount and tenure it accepts. The same
 * check runs when the product is proposed and again when it is approved, and the database holds it too (tenant V29).
 */
@Service
public class DepositProductService {

    static final String ENTITY = "DEPOSIT_PRODUCT";
    public static final String CASA = "CASA";
    public static final String TERM_DEPOSIT = "TERM_DEPOSIT";

    static final int MAX_BANDS = 20;
    static final int MAX_RATES = 400;
    static final int MAX_ADD_ONS = 10;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");
    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final int MAX_TENURE_MONTHS = 600;
    static final int MAX_TENURE_DAYS = 3660;
    private static final Set<String> CASA_TYPES = Set.of("SAVINGS", "CURRENT");
    private static final Set<String> TD_TYPES = Set.of("FD_CUMULATIVE", "FD_PAYOUT");

    // ---- what the API accepts (openapi.yaml#/components/schemas/DepositProductInput) ---------------------------
    public record BandInput(BigDecimal fromBalance, BigDecimal ratePercent) {}

    public record RateInput(BigDecimal fromAmount, Integer fromMonths, Integer fromDays, BigDecimal ratePercent) {}

    public record AddOnInput(String code, BigDecimal percent) {}

    public record ProductInput(String code, String name, String productType, LocalDate effectiveFrom, String dayCount, String rounding,
                               Boolean tdsApplicable,
                               BigDecimal minBalance, List<BandInput> interestBands, String bandMode, Integer interestPostingMonths,
                               Boolean chequeFacility,
                               BigDecimal minAmount, BigDecimal maxAmount, Integer minTenureMonths, Integer minTenureDays,
                               Integer maxTenureMonths, Integer maxTenureDays, Integer interestPeriodMonths, List<RateInput> rateCard,
                               List<AddOnInput> addOns, Boolean prematureAllowed, BigDecimal prematurePenaltyPercent,
                               Integer prematureMinDays, Boolean autoRenewalAllowed,
                               String glLiability, String glInterestExpense, ChargesInput charges) {}

    /** openapi.yaml#/components/schemas/DepositChargesInput: a savings or current product's schedule of charges (P4-4). */
    public record ChargesInput(BigDecimal gstRatePercent, BigDecimal minBalancePercent, BigDecimal minBalanceMax, BigDecimal closureAmount,
                               Integer closureFreeWithinDays, Integer closureWithinDays, List<ServiceChargeInput> service) {}

    public record ServiceChargeInput(String code, String name, BigDecimal amount) {}

    // ---- what the API returns and what an approval request carries: amounts and rates as plain decimal strings ----
    public record Band(String fromBalance, String ratePercent) {}

    public record Rate(String fromAmount, Integer fromMonths, Integer fromDays, String ratePercent) {}

    public record AddOn(String code, String percent) {}

    /** openapi.yaml#/components/schemas/DepositCharges. */
    public record Charges(String gstRatePercent, String minBalancePercent, String minBalanceMax, String closureAmount,
                          Integer closureFreeWithinDays, Integer closureWithinDays, List<ServiceCharge> service) {}

    public record ServiceCharge(String code, String name, String amount) {}

    /** openapi.yaml#/components/schemas/DepositProduct. Settings of the other family are null. */
    public record Product(String code, String name, String family, String productType, String status, Integer version,
                          String effectiveFrom, String dayCount, String rounding, Boolean tdsApplicable,
                          String minBalance, List<Band> interestBands, String bandMode, Integer interestPostingMonths,
                          Boolean chequeFacility,
                          String minAmount, String maxAmount, Integer minTenureMonths, Integer minTenureDays,
                          Integer maxTenureMonths, Integer maxTenureDays, Integer interestPeriodMonths, List<Rate> rateCard,
                          List<AddOn> addOns, Boolean prematureAllowed, String prematurePenaltyPercent, Integer prematureMinDays,
                          Boolean autoRenewalAllowed,
                          String glLiability, String glInterestExpense, Charges charges, String updatedBy, String updatedAt) {}

    private static final String SELECT = """
            SELECT code, name, family, product_type, status, version, effective_from::text AS effective_from, day_count, rounding,
                   tds_applicable, min_balance::text AS min_balance,
                   (SELECT jsonb_agg(jsonb_build_object('fromBalance', b->>'fromBalance', 'ratePercent', b->>'ratePercent')
                                     ORDER BY (b->>'fromBalance')::numeric)
                      FROM jsonb_array_elements(interest_bands) b)::text AS interest_bands,
                   band_mode, interest_posting_months, cheque_facility, min_amount::text AS min_amount, max_amount::text AS max_amount,
                   min_tenure_months, min_tenure_days, max_tenure_months, max_tenure_days, interest_period_months,
                   (SELECT jsonb_agg(jsonb_build_object('fromAmount', c->>'fromAmount', 'fromMonths', (c->>'fromMonths')::int,
                                                        'fromDays', (c->>'fromDays')::int, 'ratePercent', c->>'ratePercent')
                                     ORDER BY (c->>'fromAmount')::numeric, deposits.tenure_rank((c->>'fromMonths')::int, (c->>'fromDays')::int))
                      FROM jsonb_array_elements(rate_card) c)::text AS rate_card,
                   CASE WHEN add_ons IS NULL THEN NULL ELSE
                     (SELECT coalesce(jsonb_agg(jsonb_build_object('code', a->>'code', 'percent', a->>'percent') ORDER BY a->>'code'), '[]'::jsonb)
                        FROM jsonb_array_elements(add_ons) a)::text END AS add_ons,
                   premature_allowed, premature_penalty_percent::text AS premature_penalty_percent, premature_min_days,
                   auto_renewal_allowed, gl_liability, gl_interest_expense, updated_by, to_json(updated_at)#>>'{}' AS updated_at,
                   CASE WHEN charges IS NULL THEN NULL ELSE jsonb_build_object(
                     'gstRatePercent', charges->>'gstRatePercent', 'minBalancePercent', charges->>'minBalancePercent',
                     'minBalanceMax', charges->>'minBalanceMax', 'closureAmount', charges->>'closureAmount',
                     'closureFreeWithinDays', (charges->>'closureFreeWithinDays')::int, 'closureWithinDays', (charges->>'closureWithinDays')::int,
                     'service', (SELECT coalesce(jsonb_agg(jsonb_build_object('code', x->>'code', 'name', x->>'name', 'amount', x->>'amount') ORDER BY x->>'code'), '[]'::jsonb)
                                   FROM jsonb_array_elements(coalesce(charges->'service', '[]'::jsonb)) x))::text END AS charges
              FROM deposits.product
            """;

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final BusinessDays days;
    private final DepositRuleSets ruleSets;
    private final Json json;

    DepositProductService(JdbcTemplate jdbc, ApprovalService approvals, BusinessDays days, DepositRuleSets ruleSets, Json json) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.days = days;
        this.ruleSets = ruleSets;
        this.json = json;
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    public List<Product> list(String family) {
        return jdbc.query(SELECT + " WHERE family = ? ORDER BY code", (rs, i) -> map(json, rs), family);
    }

    /** The product of this family, or 404: a savings product is not found under the term deposit paths. */
    public Product get(String family, String code) {
        Product p = find(code);
        if (p == null || !p.family().equals(family)) throw ApiException.notFound("deposit product " + code);
        return p;
    }

    Product find(String code) {
        return load(jdbc, json, code);
    }

    /** A product read through any JdbcTemplate (the day-end's is bound to the tenant's own data source); null when there is none. */
    static Product load(JdbcTemplate jdbc, Json json, String code) {
        List<Product> found = jdbc.query(SELECT + " WHERE code = ?", (rs, i) -> map(json, rs), code);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Every version of the product, newest first: who changed it, when, and under which approval. */
    public List<Map<String, Object>> history(String family, String code) {
        get(family, code);
        return jdbc.query("""
                SELECT version, changed_by, to_json(changed_at)#>>'{}', approval_id::text, snapshot->>'name', snapshot->>'status'
                  FROM deposits.product_history WHERE product_code = ? ORDER BY version DESC
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("version", rs.getInt(1));
                    m.put("changedBy", rs.getString(2));
                    m.put("changedAt", rs.getString(3));
                    m.put("approvalId", rs.getString(4));
                    m.put("name", rs.getString(5));
                    m.put("status", rs.getString(6));
                    return m;
                }, code);
    }

    private static Product map(Json json, ResultSet rs) throws SQLException {
        String charges = rs.getString("charges");
        String bands = rs.getString("interest_bands");
        String card = rs.getString("rate_card");
        String addOns = rs.getString("add_ons");
        return new Product(rs.getString("code"), rs.getString("name"), rs.getString("family"), rs.getString("product_type"),
                rs.getString("status"), rs.getInt("version"), rs.getString("effective_from"), rs.getString("day_count"),
                rs.getString("rounding"), bool(rs, "tds_applicable"),
                DepositRuleSets.plain(rs.getString("min_balance")), bands == null ? null : List.of(json.read(bands, Band[].class)),
                rs.getString("band_mode"), whole(rs, "interest_posting_months"), bool(rs, "cheque_facility"),
                DepositRuleSets.plain(rs.getString("min_amount")), DepositRuleSets.plain(rs.getString("max_amount")),
                whole(rs, "min_tenure_months"), whole(rs, "min_tenure_days"), whole(rs, "max_tenure_months"), whole(rs, "max_tenure_days"),
                whole(rs, "interest_period_months"), card == null ? null : List.of(json.read(card, Rate[].class)),
                addOns == null ? null : List.of(json.read(addOns, AddOn[].class)), bool(rs, "premature_allowed"),
                DepositRuleSets.plain(rs.getString("premature_penalty_percent")), whole(rs, "premature_min_days"),
                bool(rs, "auto_renewal_allowed"), rs.getString("gl_liability"), rs.getString("gl_interest_expense"),
                charges == null ? null : plainCharges(json.read(charges, Charges.class)), rs.getString("updated_by"), rs.getString("updated_at"));
    }

    private static Charges plainCharges(Charges c) {
        List<ServiceCharge> service = new ArrayList<>();
        for (ServiceCharge x : c.service() == null ? List.<ServiceCharge>of() : c.service()) {
            service.add(new ServiceCharge(x.code(), x.name(), DepositRuleSets.plain(x.amount())));
        }
        return new Charges(DepositRuleSets.plain(c.gstRatePercent()), DepositRuleSets.plain(c.minBalancePercent()), DepositRuleSets.plain(c.minBalanceMax()),
                DepositRuleSets.plain(c.closureAmount()), c.closureFreeWithinDays(), c.closureWithinDays(), List.copyOf(service));
    }

    /** The engine's view of a schedule of charges; a product without one charges nothing (GST at 18% if ever it did). */
    static DepositCharges.Schedule schedule(Charges c) {
        if (c == null) return DepositCharges.Schedule.none();
        List<DepositCharges.Service> service = new ArrayList<>();
        for (ServiceCharge x : c.service() == null ? List.<ServiceCharge>of() : c.service()) {
            service.add(new DepositCharges.Service(x.code(), x.name(), decimal(x.amount())));
        }
        return new DepositCharges.Schedule(c.gstRatePercent() == null ? BigDecimal.valueOf(18) : new BigDecimal(c.gstRatePercent()),
                decimal(c.minBalancePercent()), decimal(c.minBalanceMax()), decimal(c.closureAmount()), c.closureFreeWithinDays(),
                c.closureWithinDays(), service);
    }

    static String chargesJson(Charges c) {
        if (c == null) return null;
        StringBuilder sb = new StringBuilder("{\"gstRatePercent\": ").append(number(c.gstRatePercent()));
        if (c.minBalancePercent() != null) sb.append(", \"minBalancePercent\": ").append(number(c.minBalancePercent()));
        if (c.minBalanceMax() != null) sb.append(", \"minBalanceMax\": ").append(number(c.minBalanceMax()));
        if (c.closureAmount() != null) sb.append(", \"closureAmount\": ").append(number(c.closureAmount()));
        if (c.closureFreeWithinDays() != null) sb.append(", \"closureFreeWithinDays\": ").append(c.closureFreeWithinDays().intValue());
        if (c.closureWithinDays() != null) sb.append(", \"closureWithinDays\": ").append(c.closureWithinDays().intValue());
        sb.append(", \"service\": [");
        boolean first = true;
        for (ServiceCharge x : c.service()) {
            if (!x.code().matches("[A-Z][A-Z0-9_]{2,29}")) throw ApiException.invalid("service charge code " + x.code() + " is not valid");
            if (!first) sb.append(", ");
            first = false;
            sb.append("{\"code\": \"").append(x.code()).append("\", \"name\": ").append(jsonText(x.name())).append(", \"amount\": ").append(number(x.amount())).append('}');
        }
        return sb.append("]}").toString();
    }

    /** A JSON string: quotes, backslashes and control characters escaped. */
    private static String jsonText(String v) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : v.toCharArray()) {
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
            else sb.append(ch);
        }
        return sb.append('"').toString();
    }

    static Integer whole(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    static Boolean bool(ResultSet rs, String column) throws SQLException {
        boolean v = rs.getBoolean(column);
        return rs.wasNull() ? null : v;
    }

    // ---- the engine's view of a product ---------------------------------------------------------------------------

    /** A stored or normalised savings or current product as the engine takes it. */
    static DemandDepositProduct.Settings demand(Product p) {
        List<SavingsInterest.Band> bands = new ArrayList<>();
        if (p.interestBands() != null) {
            for (Band b : p.interestBands()) bands.add(new SavingsInterest.Band(new BigDecimal(b.fromBalance()), new BigDecimal(b.ratePercent())));
        }
        return new DemandDepositProduct.Settings(DemandDepositProduct.Type.valueOf(p.productType()), new BigDecimal(p.minBalance()), bands,
                p.bandMode() == null ? null : SavingsInterest.BandMode.valueOf(p.bandMode()),
                p.dayCount() == null ? null : DayCount.valueOf(p.dayCount()),
                p.interestPostingMonths() == null ? 0 : p.interestPostingMonths(), Rounding.valueOf(p.rounding()));
    }

    /** A stored or normalised term deposit product as the engine takes it. */
    static TermDepositProduct.Settings term(Product p) {
        List<DepositRateTable.Cell> cells = new ArrayList<>();
        for (Rate r : p.rateCard()) {
            cells.add(new DepositRateTable.Cell(new BigDecimal(r.fromAmount()), new Tenure(r.fromMonths(), r.fromDays()), new BigDecimal(r.ratePercent())));
        }
        List<DepositRateTable.AddOn> addOns = new ArrayList<>();
        for (AddOn a : p.addOns()) addOns.add(new DepositRateTable.AddOn(a.code(), new BigDecimal(a.percent())));
        return new TermDepositProduct.Settings(TermDepositProduct.Type.valueOf(p.productType()), new BigDecimal(p.minAmount()),
                p.maxAmount() == null ? null : new BigDecimal(p.maxAmount()), new Tenure(p.minTenureMonths(), p.minTenureDays()),
                new Tenure(p.maxTenureMonths(), p.maxTenureDays()), p.interestPeriodMonths(), DayCount.valueOf(p.dayCount()),
                Rounding.valueOf(p.rounding()), cells, addOns, Boolean.TRUE.equals(p.prematureAllowed()),
                p.prematurePenaltyPercent() == null ? null : new BigDecimal(p.prematurePenaltyPercent()), p.prematureMinDays(),
                Boolean.TRUE.equals(p.tdsApplicable()));
    }

    /** Everything that stops the product from being approved under the rule set in force; empty when nothing does. */
    List<String> problems(Product p, DepositRules rules) {
        List<String> out = new ArrayList<>(CASA.equals(p.family())
                ? DemandDepositProduct.problems(rules, demand(p))
                : TermDepositProduct.problems(rules, term(p)));
        if (CASA.equals(p.family())) out.addAll(DepositCharges.problems(schedule(p.charges())));
        glProblem(p.glLiability(), "LIABILITY", "deposits are liabilities", out);
        if (p.glInterestExpense() != null) glProblem(p.glInterestExpense(), "EXPENSE", "interest on deposits is an expense", out);
        return out;
    }

    private void glProblem(String code, String category, String why, List<String> out) {
        List<Map<String, Object>> head = jdbc.queryForList("SELECT category, is_posting FROM ledger.gl_head WHERE code = ?", code);
        if (head.isEmpty()) {
            out.add("GL head " + code + " does not exist");
        } else if (!category.equals(head.get(0).get("category")) || !Boolean.TRUE.equals(head.get(0).get("is_posting"))) {
            out.add("GL head " + code + " is not " + (category.equals("EXPENSE") ? "an expense" : "a liability") + " posting head; " + why);
        }
    }

    // ---- proposing ------------------------------------------------------------------------------------------------

    /** The problems of a product as entered, without proposing it (the product form checks as the user types). */
    public List<String> check(String family, ProductInput in) {
        DepositRules rules = ruleSets.current();
        return problems(normalise(family, in, rules), rules);
    }

    @Transactional
    public ApprovalRequest propose(String family, ProductInput in) {
        DepositRules rules = ruleSets.current();
        Product p = normalise(family, in, rules);
        Product existing = find(p.code());
        if (existing != null) {
            if (!existing.family().equals(family) || !existing.productType().equals(p.productType())) {
                throw ApiException.invalid("product " + p.code() + " is a " + existing.productType()
                        + " product; the family and type of a deposit product never change");
            }
            if (!"ACTIVE".equals(existing.status())) {
                throw ApiException.conflict("product " + p.code() + " is " + existing.status() + "; a withdrawn product is not changed");
            }
        }
        refuseIfAny(p.code(), problems(p, rules));
        return approvals.propose(ENTITY, existing == null ? "CREATE" : "UPDATE", p.code(), json.toMap(p),
                existing == null ? null : json.toMap(existing), null, null, null);
    }

    /** A withdrawn product takes no new account; its accounts go on as they are. */
    @Transactional
    public ApprovalRequest proposeWithdrawal(String family, String code, String reason) {
        Product existing = get(family, code);
        if (!"ACTIVE".equals(existing.status())) throw ApiException.conflict("product " + code + " is already " + existing.status());
        if (reason == null || reason.isBlank() || reason.length() > 500) throw ApiException.invalid("a reason is required (at most 500 characters)");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("status", "WITHDRAWN");
        payload.put("reason", reason.trim());
        return approvals.propose(ENTITY, "WITHDRAW", code, payload, json.toMap(existing), null, null, null);
    }

    static void refuseIfAny(String code, List<String> problems) {
        if (problems.isEmpty()) return;
        throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "deposit product " + code + " cannot be approved: " + String.join("; ", problems),
                Map.of("problems", problems));
    }

    /**
     * Checks the shape of the input (what is required, what is a number in range) and returns the product with its
     * defaults filled in. Whether the product is allowed is the engine's question, asked afterwards.
     */
    Product normalise(String family, ProductInput in, DepositRules rules) {
        if (in == null || in.code() == null || !in.code().matches("[A-Z0-9]{2,12}")) {
            throw ApiException.invalid("code is 2 to 12 capital letters or digits");
        }
        if (in.name() == null || in.name().isBlank() || in.name().length() > 200) throw ApiException.invalid("name is required (at most 200 characters)");
        boolean casa = CASA.equals(family);
        String type = in.productType() == null ? "" : in.productType().trim().toUpperCase(java.util.Locale.ROOT);
        if (!(casa ? CASA_TYPES : TD_TYPES).contains(type)) {
            throw ApiException.invalid(casa ? "productType is SAVINGS or CURRENT" : "productType is FD_CUMULATIVE or FD_PAYOUT");
        }
        String rounding = in.rounding() == null ? "PAISE_HALF_UP" : in.rounding();
        if (!Set.of("PAISE_HALF_UP", "PAISE_HALF_EVEN", "RUPEE_HALF_UP", "RUPEE_DOWN", "RUPEE_UP").contains(rounding)) {
            throw ApiException.invalid("rounding is PAISE_HALF_UP, PAISE_HALF_EVEN, RUPEE_HALF_UP, RUPEE_DOWN or RUPEE_UP");
        }
        String dayCount = in.dayCount();
        if (dayCount != null && !Set.of("ACTUAL_365", "ACTUAL_360", "ACTUAL_ACTUAL").contains(dayCount)) {
            throw ApiException.invalid("dayCount is ACTUAL_365, ACTUAL_360 or ACTUAL_ACTUAL");
        }
        LocalDate from = in.effectiveFrom();
        if (from == null) {
            BusinessDays.BusinessDay bd = days.current();
            from = bd == null ? LocalDate.now() : bd.businessDate();
        }
        return casa ? demandProduct(in, type, rounding, dayCount, from, rules) : termProduct(in, type, rounding, dayCount, from);
    }

    private Product demandProduct(ProductInput in, String type, String rounding, String dayCount, LocalDate from, DepositRules rules) {
        if (in.minAmount() != null || in.maxAmount() != null || in.rateCard() != null || in.addOns() != null
                || in.minTenureMonths() != null || in.maxTenureMonths() != null || in.interestPeriodMonths() != null
                || in.prematureAllowed() != null || in.prematurePenaltyPercent() != null || in.prematureMinDays() != null
                || in.autoRenewalAllowed() != null) {
            throw ApiException.invalid("amount and tenure limits, the rate card, add-ons, premature withdrawal and renewal are settings of a term deposit product");
        }
        BigDecimal minBalance = in.minBalance() == null ? BigDecimal.ZERO : in.minBalance();
        money("minBalance", minBalance, true);
        List<BandInput> rows = in.interestBands() == null ? List.of() : in.interestBands();
        if (rows.size() > MAX_BANDS) throw ApiException.invalid("at most " + MAX_BANDS + " interest bands");
        List<Band> bands = new ArrayList<>();
        int n = 0;
        for (BandInput b : rows.stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(BandInput::fromBalance, Comparator.nullsFirst(Comparator.naturalOrder()))).toList()) {
            n++;
            if (b.fromBalance() == null || b.ratePercent() == null) throw ApiException.invalid("interest band " + n + ": fromBalance and ratePercent are required");
            money("interest band " + n + ": fromBalance", b.fromBalance(), true);
            percent("interest band " + n + ": ratePercent", b.ratePercent(), false);
            bands.add(new Band(DepositRuleSets.plain(b.fromBalance()), DepositRuleSets.plain(b.ratePercent())));
        }
        if (rows.size() != bands.size()) throw ApiException.invalid("an interest band is empty");
        boolean interest = !bands.isEmpty();
        if (!interest && (in.bandMode() != null || in.interestPostingMonths() != null)) {
            throw ApiException.invalid("bandMode and interestPostingMonths go with interest bands; this product has none");
        }
        String bandMode = !interest ? null : in.bandMode() == null ? "WHOLE_BALANCE" : in.bandMode();
        if (interest && !Set.of("WHOLE_BALANCE", "INCREMENTAL").contains(bandMode)) throw ApiException.invalid("bandMode is WHOLE_BALANCE or INCREMENTAL");
        // decision 5 (10-Oct-2026): the posting frequency is a product setting, quarterly unless the product says otherwise
        Integer posting = !interest ? null : in.interestPostingMonths() == null ? Integer.valueOf(3) : in.interestPostingMonths();
        // D-17: no tax is deducted from savings interest unless the rule set says it is
        boolean tds = in.tdsApplicable() != null ? in.tdsApplicable() : interest && rules.flag(DepositRules.TDS_ON_SAVINGS, false);
        String liability = in.glLiability() != null ? in.glLiability() : "SAVINGS".equals(type) ? "2401" : "2402";
        String expense = !interest ? null : in.glInterestExpense() != null ? in.glInterestExpense() : "5108";
        if (!interest && in.glInterestExpense() != null) throw ApiException.invalid("glInterestExpense goes with interest bands; this product has none");
        glCode(liability);
        if (expense != null) glCode(expense);
        Charges charges = charges(in.charges());
        return new Product(in.code(), in.name().trim(), CASA, type, "ACTIVE", null, from.toString(),
                interest ? (dayCount == null ? "ACTUAL_365" : dayCount) : null, rounding, tds,
                DepositRuleSets.plain(minBalance), interest ? bands : null, bandMode, posting, Boolean.TRUE.equals(in.chequeFacility()),
                null, null, null, null, null, null, null, null, null, null, null, null, null, liability, expense, charges, null, null);
    }

    /** The schedule as entered: shapes checked here, figures by the engine ({@link DepositCharges#problems}). */
    private static Charges charges(ChargesInput in) {
        if (in == null) return null;
        List<ServiceChargeInput> rows = in.service() == null ? List.of() : in.service();
        if (rows.size() > 30) throw ApiException.invalid("at most 30 service charges");
        List<ServiceCharge> service = new ArrayList<>();
        for (ServiceChargeInput x : rows) {
            if (x == null || x.code() == null || x.amount() == null || x.name() == null) throw ApiException.invalid("each service charge has a code, a name and an amount");
            service.add(new ServiceCharge(x.code().trim(), x.name().trim(), DepositRuleSets.plain(x.amount())));
        }
        for (Integer d : java.util.Arrays.asList(in.closureFreeWithinDays(), in.closureWithinDays())) {
            if (d != null && (d < 0 || d > MAX_TENURE_DAYS)) throw ApiException.invalid("a number of days for the closure charge is from 0 to " + MAX_TENURE_DAYS);
        }
        BigDecimal gst = in.gstRatePercent() == null ? BigDecimal.valueOf(18) : in.gstRatePercent();
        percent("gstRatePercent", gst, false);
        if (in.minBalancePercent() != null) percent("minBalancePercent", in.minBalancePercent(), false);
        return new Charges(DepositRuleSets.plain(gst), DepositRuleSets.plain(in.minBalancePercent()), DepositRuleSets.plain(in.minBalanceMax()),
                DepositRuleSets.plain(in.closureAmount()), in.closureFreeWithinDays(), in.closureWithinDays(), List.copyOf(service));
    }

    private Product termProduct(ProductInput in, String type, String rounding, String dayCount, LocalDate from) {
        if (in.minBalance() != null || in.interestBands() != null || in.bandMode() != null || in.interestPostingMonths() != null
                || in.chequeFacility() != null || in.charges() != null) {
            throw ApiException.invalid("the minimum balance, interest bands, the cheque facility and charges are settings of a savings or current product");
        }
        if (in.minAmount() == null) throw ApiException.invalid("minAmount is required");
        money("minAmount", in.minAmount(), false);
        if (in.maxAmount() != null) money("maxAmount", in.maxAmount(), false);
        int minMonths = tenurePart("minTenureMonths", in.minTenureMonths(), MAX_TENURE_MONTHS);
        int minDays = tenurePart("minTenureDays", in.minTenureDays(), MAX_TENURE_DAYS);
        int maxMonths = tenurePart("maxTenureMonths", in.maxTenureMonths(), MAX_TENURE_MONTHS);
        int maxDays = tenurePart("maxTenureDays", in.maxTenureDays(), MAX_TENURE_DAYS);
        if (in.interestPeriodMonths() == null) {
            throw ApiException.invalid("interestPeriodMonths is required: 1, 3, 6 or 12, or 0 for interest paid once at maturity");
        }
        List<RateInput> rows = in.rateCard() == null ? List.of() : in.rateCard();
        if (rows.size() > MAX_RATES) throw ApiException.invalid("at most " + MAX_RATES + " rows in the rate card");
        List<Rate> card = new ArrayList<>();
        int n = 0;
        for (RateInput r : rows) {
            n++;
            if (r == null || r.fromAmount() == null || r.ratePercent() == null) {
                throw ApiException.invalid("rate card row " + n + ": fromAmount, a tenure (fromMonths, fromDays) and ratePercent are required");
            }
            money("rate card row " + n + ": fromAmount", r.fromAmount(), true);
            percent("rate card row " + n + ": ratePercent", r.ratePercent(), false);
            card.add(new Rate(DepositRuleSets.plain(r.fromAmount()), tenurePart("rate card row " + n + ": fromMonths", r.fromMonths(), MAX_TENURE_MONTHS),
                    tenurePart("rate card row " + n + ": fromDays", r.fromDays(), MAX_TENURE_DAYS), DepositRuleSets.plain(r.ratePercent())));
        }
        card.sort(Comparator.comparing((Rate r) -> new BigDecimal(r.fromAmount())).thenComparing(r -> r.fromMonths() * 31 + r.fromDays()));
        List<AddOnInput> extras = in.addOns() == null ? List.of() : in.addOns();
        if (extras.size() > MAX_ADD_ONS) throw ApiException.invalid("at most " + MAX_ADD_ONS + " add-ons");
        List<AddOn> addOns = new ArrayList<>();
        for (AddOnInput a : extras) {
            if (a == null || a.code() == null || !a.code().matches("[A-Z][A-Z0-9_]{1,29}")) {
                throw ApiException.invalid("an add-on code is 2 to 30 capitals, digits or underscores, starting with a letter");
            }
            if (a.percent() == null) throw ApiException.invalid("add-on " + a.code() + ": percent is required");
            percent("add-on " + a.code() + ": percent", a.percent(), true);
            if (addOns.stream().anyMatch(x -> x.code().equals(a.code()))) throw ApiException.invalid("add-on " + a.code() + " is listed twice");
            addOns.add(new AddOn(a.code(), DepositRuleSets.plain(a.percent())));
        }
        addOns.sort(Comparator.comparing(AddOn::code));
        if (in.prematurePenaltyPercent() != null) percent("prematurePenaltyPercent", in.prematurePenaltyPercent(), false);
        if (in.prematureMinDays() != null && (in.prematureMinDays() < 0 || in.prematureMinDays() > MAX_TENURE_DAYS)) {
            throw ApiException.invalid("prematureMinDays is from 0 to " + MAX_TENURE_DAYS);
        }
        String liability = in.glLiability() != null ? in.glLiability() : "2403";
        String expense = in.glInterestExpense() != null ? in.glInterestExpense() : "5107";
        glCode(liability);
        glCode(expense);
        return new Product(in.code(), in.name().trim(), TERM_DEPOSIT, type, "ACTIVE", null, from.toString(),
                dayCount == null ? "ACTUAL_365" : dayCount, rounding, in.tdsApplicable() == null || in.tdsApplicable(),
                null, null, null, null, null,
                DepositRuleSets.plain(in.minAmount()), DepositRuleSets.plain(in.maxAmount()), minMonths, minDays, maxMonths, maxDays,
                in.interestPeriodMonths(), card, addOns, in.prematureAllowed() == null || in.prematureAllowed(),
                DepositRuleSets.plain(in.prematurePenaltyPercent()), in.prematureMinDays(), Boolean.TRUE.equals(in.autoRenewalAllowed()),
                liability, expense, null, null, null);
    }

    private static void money(String what, BigDecimal v, boolean zeroAllowed) {
        if (v.signum() < 0 || (!zeroAllowed && v.signum() == 0) || v.compareTo(MAX_AMOUNT) > 0 || v.stripTrailingZeros().scale() > 2) {
            throw ApiException.invalid(what + " is an amount " + (zeroAllowed ? "from 0" : "above 0") + " with at most two decimals");
        }
    }

    private static void percent(String what, BigDecimal v, boolean signed) {
        if ((!signed && v.signum() < 0) || v.abs().compareTo(HUNDRED) > 0 || v.stripTrailingZeros().scale() > 4) {
            throw ApiException.invalid(what + " is a percentage " + (signed ? "from -100 to 100" : "from 0 to 100") + " with at most four decimals");
        }
    }

    private static int tenurePart(String what, Integer v, int max) {
        int n = v == null ? 0 : v;
        if (n < 0 || n > max) throw ApiException.invalid(what + " is a whole number from 0 to " + max);
        return n;
    }

    private static void glCode(String code) {
        if (!code.matches("[A-Za-z0-9._-]{1,20}")) throw ApiException.invalid("a GL head code is 1 to 20 letters, digits, dots, hyphens or underscores");
    }

    // ---- JSON for the jsonb columns: numbers, written from values already checked ------------------------------------

    static String bandsJson(List<Band> bands) {
        if (bands == null) return null;
        StringBuilder sb = new StringBuilder("[");
        for (Band b : bands) {
            if (sb.length() > 1) sb.append(", ");
            sb.append("{\"fromBalance\": ").append(number(b.fromBalance())).append(", \"ratePercent\": ").append(number(b.ratePercent())).append('}');
        }
        return sb.append(']').toString();
    }

    static String cardJson(List<Rate> card) {
        if (card == null) return null;
        StringBuilder sb = new StringBuilder("[");
        for (Rate r : card) {
            if (sb.length() > 1) sb.append(", ");
            sb.append("{\"fromAmount\": ").append(number(r.fromAmount())).append(", \"fromMonths\": ").append(r.fromMonths().intValue())
                    .append(", \"fromDays\": ").append(r.fromDays().intValue()).append(", \"ratePercent\": ").append(number(r.ratePercent())).append('}');
        }
        return sb.append(']').toString();
    }

    static String addOnsJson(List<AddOn> addOns) {
        if (addOns == null) return null;
        StringBuilder sb = new StringBuilder("[");
        for (AddOn a : addOns) {
            if (!a.code().matches("[A-Z][A-Z0-9_]{1,29}")) throw ApiException.invalid("add-on code " + a.code() + " is not valid");
            if (sb.length() > 1) sb.append(", ");
            sb.append("{\"code\": \"").append(a.code()).append("\", \"percent\": ").append(number(a.percent())).append('}');
        }
        return sb.append(']').toString();
    }

    /** A decimal string as a JSON number; anything else is refused, so nothing but digits reaches the SQL text. */
    private static String number(String v) {
        return new BigDecimal(v).toPlainString();
    }

    private static BigDecimal decimal(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    // ---- applying an approved proposal ----------------------------------------------------------------------------

    @Service
    static class Applier implements ApprovalApplier {
        private final JdbcTemplate jdbc;
        private final DepositProductService products;
        private final DepositRuleSets ruleSets;
        private final Json json;

        Applier(JdbcTemplate jdbc, @Lazy DepositProductService products, DepositRuleSets ruleSets, Json json) {
            this.jdbc = jdbc;
            this.products = products;
            this.ruleSets = ruleSets;
            this.json = json;
        }

        @Override public String entityType() { return ENTITY; }

        @Override
        public String apply(ApprovalRequest r) {
            String code = r.entityId();
            Product existing = products.find(code);
            if ("WITHDRAW".equals(r.action())) {
                if (existing == null || !"ACTIVE".equals(existing.status())) throw ApiException.conflict("product " + code + " is not active any more");
                jdbc.update("UPDATE deposits.product SET status = 'WITHDRAWN', approval_id = ?, updated_by = ? WHERE code = ?", r.id(), r.maker(), code);
                return code;
            }
            Product p = json.convert(r.payload(), Product.class);
            if ("CREATE".equals(r.action()) && existing != null) throw ApiException.conflict("deposit product " + code + " already exists");
            if (!"CREATE".equals(r.action()) && (existing == null || !"ACTIVE".equals(existing.status()))) {
                throw ApiException.conflict("deposit product " + code + " is not active any more");
            }
            // the rules may have changed since the product was proposed: the check is made again, on today's rule set
            refuseIfAny(code, products.problems(p, ruleSets.current()));
            Object[] values = {
                    p.name(), p.dayCount(), p.rounding(), p.tdsApplicable(), decimal(p.minBalance()), bandsJson(p.interestBands()), p.bandMode(),
                    p.interestPostingMonths(), p.chequeFacility(), decimal(p.minAmount()), decimal(p.maxAmount()), p.minTenureMonths(),
                    p.minTenureDays(), p.maxTenureMonths(), p.maxTenureDays(), p.interestPeriodMonths(), cardJson(p.rateCard()),
                    addOnsJson(p.addOns()), p.prematureAllowed(), decimal(p.prematurePenaltyPercent()), p.prematureMinDays(),
                    p.autoRenewalAllowed(), p.glLiability(), p.glInterestExpense(), chargesJson(p.charges()), LocalDate.parse(p.effectiveFrom()),
                    r.id(), r.maker()};
            if (existing == null) {
                List<Object> args = new ArrayList<>(List.of(code, p.family(), p.productType()));
                args.addAll(java.util.Arrays.asList(values));
                jdbc.update("""
                        INSERT INTO deposits.product (code, family, product_type, name, day_count, rounding, tds_applicable, min_balance,
                            interest_bands, band_mode, interest_posting_months, cheque_facility, min_amount, max_amount, min_tenure_months,
                            min_tenure_days, max_tenure_months, max_tenure_days, interest_period_months, rate_card, add_ons, premature_allowed,
                            premature_penalty_percent, premature_min_days, auto_renewal_allowed, gl_liability, gl_interest_expense,
                            charges, effective_from, approval_id, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                        """, args.toArray());
            } else {
                List<Object> args = new ArrayList<>(java.util.Arrays.asList(values));
                args.add(code);
                jdbc.update("""
                        UPDATE deposits.product
                           SET name = ?, day_count = ?, rounding = ?, tds_applicable = ?, min_balance = ?, interest_bands = ?::jsonb, band_mode = ?,
                               interest_posting_months = ?, cheque_facility = ?, min_amount = ?, max_amount = ?, min_tenure_months = ?,
                               min_tenure_days = ?, max_tenure_months = ?, max_tenure_days = ?, interest_period_months = ?, rate_card = ?::jsonb,
                               add_ons = ?::jsonb, premature_allowed = ?, premature_penalty_percent = ?, premature_min_days = ?,
                               auto_renewal_allowed = ?, gl_liability = ?, gl_interest_expense = ?, charges = ?::jsonb, effective_from = ?, approval_id = ?,
                               updated_by = ?
                         WHERE code = ?
                        """, args.toArray());
            }
            return code;
        }
    }
}
