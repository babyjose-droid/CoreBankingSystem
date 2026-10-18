package com.corebanking.deposits.internal;

import com.corebanking.deposits.AccountHolders;
import com.corebanking.deposits.DepositRateTable;
import com.corebanking.deposits.DepositRefusal;
import com.corebanking.deposits.DepositRules;
import com.corebanking.deposits.Nomination;
import com.corebanking.deposits.RecurringDeposit;
import com.corebanking.deposits.SavingsInterest;
import com.corebanking.deposits.Tenure;
import com.corebanking.deposits.TermDeposit;
import com.corebanking.deposits.TermDepositBooking;
import com.corebanking.deposits.TermDepositProduct;
import com.corebanking.deposits.internal.DepositProductService.Product;
import com.corebanking.platform.ApiException;
import com.corebanking.platform.ApprovalApplier;
import com.corebanking.platform.ApprovalRequest;
import com.corebanking.platform.ApprovalService;
import com.corebanking.platform.BranchScope;
import com.corebanking.platform.BusinessDays;
import com.corebanking.platform.Json;
import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deposit accounts (P4-1): opening a savings or current account and booking a term deposit, each with its holders
 * and nominees, through maker-checker. No money moves here: a savings or current account opens with no balance and
 * a term deposit is BOOKED, waiting for its funds (P4-2).
 * <p>
 * The preview, the proposal and the approval all take the rate, the maturity date and the interest periods from
 * {@link TermDepositBooking}, so they cannot disagree. Every reason for a refusal is reported at once (422 with
 * {@code problems}). The same rules are held by the database (tenant V29), whatever this class does.
 */
@Service
public class DepositAccountService {

    static final String CASA_ENTITY = "CASA_ACCOUNT";
    static final String TD_ENTITY = "TERM_DEPOSIT";
    static final Set<String> RELATIONSHIPS = Set.of("SPOUSE", "CHILD", "PARENT", "SIBLING", "GRANDCHILD", "OTHER_RELATIVE", "OTHER");
    static final Set<String> PAYOUT_TO = Set.of("CASA", "BANK_ACCOUNT");
    static final Set<String> INSTRUCTIONS = Set.of("PAY_OUT", "RENEW_PRINCIPAL", "RENEW_WITH_INTEREST");
    static final int ADULT_AGE = 18;

    /** openapi.yaml#/components/schemas/DepositNomineeInput. */
    public record NomineeInput(String name, String relationship, BigDecimal sharePercent, Boolean minor, String guardianName) {}

    /**
     * openapi.yaml#/components/schemas/CasaAccountInput and TermDepositInput. The amount, tenure, add-ons, payout and
     * maturity instruction are for a term deposit only.
     *
     * @param jointHolders the other holders, in the order they are named; empty for an account in one name
     */
    public record AccountInput(String productCode, UUID customerId, List<UUID> jointHolders, String branchCode, String operatingMode,
                               String nominationMode, List<NomineeInput> nominees,
                               BigDecimal amount, Integer tenureMonths, Integer tenureDays, List<String> addOns, String payoutTo,
                               UUID payoutAccountId, String maturityInstruction) {}

    /** A nominee as carried by the approval request. */
    public record Nominee(String name, String relationship, String sharePercent, Boolean minor, String guardianName) {}

    /**
     * What an approval request carries: the opening as proposed, ids and decimals as strings.
     *
     * @param holders          every holder's customer id, the first-named first
     * @param quotedRatePercent the rate shown to the maker (term deposits); the approval is refused when the product
     *                          no longer gives it
     */
    public record Opening(String family, String productCode, List<String> holders, String branchCode, String operatingMode,
                          String nominationMode, List<Nominee> nominees,
                          String amount, Integer tenureMonths, Integer tenureDays, List<String> addOns, String payoutTo,
                          String payoutAccountId, String maturityInstruction, String quotedRatePercent, String customerNo,
                          String customerName) {}

    private record Holder(UUID id, String customerNo, String name, String type, LocalDate dateOfBirth, boolean panOnFile, String status,
                          String kycStatus) {}

    /** An opening that passed every check, with what the checks looked up. */
    private record Checked(Opening opening, Product product, DepositRules rules, List<Holder> holders, LocalDate today,
                           TermDepositBooking.Quote quote) {}

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final BusinessDays days;
    private final BranchScope scope;
    private final DepositRuleSets ruleSets;
    private final DepositProductService products;
    private final Json json;
    private final DepositMoneyService money;
    private final DepositLifecycleService lifecycle;

    DepositAccountService(JdbcTemplate jdbc, ApprovalService approvals, BusinessDays days, BranchScope scope, DepositRuleSets ruleSets,
                          DepositProductService products, Json json, @Lazy DepositMoneyService money, @Lazy DepositLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.days = days;
        this.scope = scope;
        this.ruleSets = ruleSets;
        this.products = products;
        this.json = json;
        this.money = money;
    }

    // ---- proposing ------------------------------------------------------------------------------------------------

    /** Proposes a savings or current account. */
    @Transactional
    public ApprovalRequest proposeCasa(AccountInput in, String idempotencyKey) {
        Checked c = check(opening(DepositProductService.CASA, in), true);
        return approvals.propose(CASA_ENTITY, "OPEN", null, json.toMap(c.opening()), null, null, c.opening().branchCode(), idempotencyKey);
    }

    /** Proposes a term deposit. The rate the maker saw is kept on the request. */
    @Transactional
    public ApprovalRequest proposeTermDeposit(AccountInput in, String idempotencyKey) {
        Checked c = check(opening(DepositProductService.TERM_DEPOSIT, in), true);
        Opening o = c.opening();
        Opening quoted = new Opening(o.family(), o.productCode(), o.holders(), o.branchCode(), o.operatingMode(), o.nominationMode(),
                o.nominees(), o.amount(), o.tenureMonths(), o.tenureDays(), o.addOns(), o.payoutTo(), o.payoutAccountId(),
                o.maturityInstruction(), DepositRuleSets.plain(c.quote().rate().ratePercent()), o.customerNo(), o.customerName());
        return approvals.propose(TD_ENTITY, "BOOK", null, json.toMap(quoted), null, new BigDecimal(o.amount()), o.branchCode(), idempotencyKey);
    }

    /**
     * The figures of a term deposit before it is booked: rate with its parts, maturity date and value, every
     * interest credit and the tax expected on it. Interest starts when the deposit is funded; the preview takes the
     * business date as that day. Without a customer the depositor is taken to be an individual with a PAN and no
     * known age, so the senior citizen add-on cannot be previewed.
     */
    public Map<String, Object> preview(AccountInput in) {
        if (in == null || in.productCode() == null) throw ApiException.invalid("productCode is required");
        Product product = products.get(DepositProductService.TERM_DEPOSIT, in.productCode());
        DepositRules rules = ruleSets.current();
        BusinessDays.BusinessDay day = days.current();
        if (day == null) throw ApiException.conflict("business date not initialised");
        Holder depositor = null;
        if (in.customerId() != null) {
            depositor = holder(in.customerId());
            if (depositor == null) throw ApiException.notFound("customer " + in.customerId());
        }
        TermDepositBooking.Request request = new TermDepositBooking.Request(in.amount(), tenure(in.tenureMonths(), in.tenureDays()),
                day.businessDate(), in.addOns() == null ? Set.of() : new LinkedHashSet<>(in.addOns()),
                in.nominees() == null ? 0 : in.nominees().size(), depositor == null || "INDIVIDUAL".equals(depositor.type()),
                depositor == null ? null : depositor.dateOfBirth(), depositor == null || depositor.panOnFile());
        TermDepositProduct.Settings settings = DepositProductService.term(product);
        List<String> problems = new ArrayList<>();
        if (!"ACTIVE".equals(product.status())) problems.add("product " + product.code() + " is " + product.status() + "; it takes no new deposit");
        problems.addAll(TermDepositBooking.problems(rules, settings, request));
        refuseIfAny("the deposit cannot be booked", problems);
        return quoteView(product, rules, request, quote(rules, settings, request));
    }

    private static TermDepositBooking.Quote quote(DepositRules rules, TermDepositProduct.Settings settings, TermDepositBooking.Request request) {
        try {
            return TermDepositBooking.quote(rules, settings, request);
        } catch (DepositRefusal e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "the deposit cannot be booked: " + e.getMessage(), Map.of("problems", e.problems()));
        }
    }

    private static Tenure tenure(Integer months, Integer days) {
        int m = months == null ? 0 : months;
        int d = days == null ? 0 : days;
        if (m < 0 || d < 0 || m > DepositProductService.MAX_TENURE_MONTHS || d > DepositProductService.MAX_TENURE_DAYS) {
            throw ApiException.invalid("the tenure is whole months (0 to " + DepositProductService.MAX_TENURE_MONTHS + ") and days (0 to "
                    + DepositProductService.MAX_TENURE_DAYS + ")");
        }
        return new Tenure(m, d);
    }

    /** A money figure for the API: two decimals, whatever scale the stored principal had. */
    static String amount(BigDecimal v) {
        BigDecimal plain = v.stripTrailingZeros();
        return (plain.scale() < 2 ? plain.setScale(2) : plain).toPlainString();
    }

    static void refuseIfAny(String what, List<String> problems) {
        if (problems.isEmpty()) return;
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(problems));
        throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, what + ": " + String.join("; ", distinct), Map.of("problems", distinct));
    }

    /** The input as an opening: defaults filled in, text trimmed, shapes checked. */
    private Opening opening(String family, AccountInput in) {
        if (in == null || in.productCode() == null || in.productCode().isBlank()) throw ApiException.invalid("productCode is required");
        if (in.customerId() == null) throw ApiException.invalid("customerId is required");
        if (in.branchCode() == null || in.branchCode().isBlank()) throw ApiException.invalid("branchCode is required");
        List<String> holders = new ArrayList<>();
        holders.add(in.customerId().toString());
        if (in.jointHolders() != null) {
            for (UUID h : in.jointHolders()) {
                if (h == null) throw ApiException.invalid("a joint holder is empty");
                holders.add(h.toString());
            }
        }
        if (holders.size() > AccountHolders.MAX_HOLDERS) throw ApiException.invalid("an account has at most " + AccountHolders.MAX_HOLDERS + " holders");
        String mode = in.operatingMode() == null ? (holders.size() == 1 ? "SINGLE" : null) : in.operatingMode().trim().toUpperCase(java.util.Locale.ROOT);
        String nomination = in.nominationMode() == null
                ? (in.nominees() == null || in.nominees().isEmpty() ? "NONE" : null)
                : in.nominationMode().trim().toUpperCase(java.util.Locale.ROOT);
        List<Nominee> nominees = new ArrayList<>();
        if (in.nominees() != null) {
            if (in.nominees().size() > 10) throw ApiException.invalid("at most 10 nominees can be sent");
            for (NomineeInput n : in.nominees()) {
                if (n == null) throw ApiException.invalid("a nominee is empty");
                if ((n.name() != null && n.name().length() > 200) || (n.guardianName() != null && n.guardianName().length() > 200)) {
                    throw ApiException.invalid("a nominee's name and the guardian's name are at most 200 characters");
                }
                nominees.add(new Nominee(n.name() == null ? null : n.name().trim(),
                        n.relationship() == null ? null : n.relationship().trim().toUpperCase(java.util.Locale.ROOT),
                        DepositRuleSets.plain(n.sharePercent()), Boolean.TRUE.equals(n.minor()),
                        n.guardianName() == null || n.guardianName().isBlank() ? null : n.guardianName().trim()));
            }
        }
        boolean term = DepositProductService.TERM_DEPOSIT.equals(family);
        if (!term && (in.amount() != null || in.tenureMonths() != null || in.tenureDays() != null || in.addOns() != null || in.payoutTo() != null
                || in.payoutAccountId() != null || in.maturityInstruction() != null)) {
            throw ApiException.invalid("an amount, a tenure, add-ons, a payout and a maturity instruction belong to a term deposit");
        }
        if (term) tenure(in.tenureMonths(), in.tenureDays());
        List<String> addOns = !term ? null : in.addOns() == null ? List.of() : new ArrayList<>(new java.util.TreeSet<>(in.addOns()));
        return new Opening(family, in.productCode().trim(), holders, in.branchCode().trim(), mode, nomination, nominees,
                !term || in.amount() == null ? null : in.amount().toPlainString(),
                !term ? null : Integer.valueOf(in.tenureMonths() == null ? 0 : in.tenureMonths()),
                !term ? null : Integer.valueOf(in.tenureDays() == null ? 0 : in.tenureDays()), addOns,
                !term ? null : in.payoutTo() == null ? "BANK_ACCOUNT" : in.payoutTo().trim().toUpperCase(java.util.Locale.ROOT),
                !term || in.payoutAccountId() == null ? null : in.payoutAccountId().toString(),
                !term ? null : in.maturityInstruction() == null ? "PAY_OUT" : in.maturityInstruction().trim().toUpperCase(java.util.Locale.ROOT),
                null, null, null);
    }

    /**
     * Every check on an opening, with all the reasons for a refusal reported together. Run when the opening is
     * proposed ({@code byMaker}: the branch must be in the maker's branch scope) and again when it is approved.
     */
    private Checked check(Opening o, boolean byMaker) {
        LocalDate today = days.requireOpen();
        DepositRules rules = ruleSets.current();
        boolean term = DepositProductService.TERM_DEPOSIT.equals(o.family());
        Product product = products.find(o.productCode());
        if (product == null || !product.family().equals(o.family())) {
            throw ApiException.invalid((term ? "term deposit" : "savings or current") + " product " + o.productCode() + " not found");
        }
        if (byMaker) scope.require(o.branchCode());
        List<String> problems = new ArrayList<>();
        if (!"ACTIVE".equals(product.status())) problems.add("product " + product.code() + " is " + product.status() + "; it takes no new account");
        List<String> branch = jdbc.queryForList("SELECT status FROM platform.branch WHERE code = ?", String.class, o.branchCode());
        if (branch.isEmpty()) problems.add("branch " + o.branchCode() + " does not exist");
        else if (!"ACTIVE".equals(branch.get(0))) problems.add("branch " + o.branchCode() + " is not active");

        // holders: customers who are active, with verified KYC; a joint account is held by individuals
        List<Holder> holders = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : o.holders()) {
            if (!seen.add(id)) {
                problems.add("a customer is named as holder twice");
                continue;
            }
            Holder h = holder(UUID.fromString(id));
            if (h == null) {
                problems.add("customer " + id + " not found");
                continue;
            }
            holders.add(h);
            if (!"ACTIVE".equals(h.status())) problems.add("customer " + h.customerNo() + " is " + h.status() + "; an account is opened only for an ACTIVE customer");
            if (!"VERIFIED".equals(h.kycStatus())) {
                problems.add("the KYC of customer " + h.customerNo() + " is " + h.kycStatus() + "; an account is opened only for a customer whose KYC is verified");
            }
            if (o.holders().size() > 1 && !"INDIVIDUAL".equals(h.type())) problems.add("customer " + h.customerNo() + " is not an individual; a joint account is held by individuals");
            // decision D-20 (10-Oct-2026): accounts for minors come later
            if ("INDIVIDUAL".equals(h.type()) && h.dateOfBirth() != null && Period.between(h.dateOfBirth(), today).getYears() < ADULT_AGE) {
                problems.add("customer " + h.customerNo() + " is under " + ADULT_AGE + "; accounts for minors are not opened yet");
            }
        }
        AccountHolders.OperatingMode mode = constant(AccountHolders.OperatingMode.class, o.operatingMode(), "operatingMode", problems);
        if (o.operatingMode() == null || mode != null) {
            for (DepositRules.Violation v : AccountHolders.check(mode, o.holders().size())) problems.add(v.message());
        }

        // nominees
        Nomination.Mode nomination = constant(Nomination.Mode.class, o.nominationMode(), "nominationMode", problems);
        List<Nomination.Nominee> named = new ArrayList<>();
        int n = 0;
        for (Nominee x : o.nominees()) {
            n++;
            if (x.relationship() != null && !x.relationship().isBlank() && !RELATIONSHIPS.contains(x.relationship())) {
                problems.add("nominee " + n + ": the relationship is one of " + new java.util.TreeSet<>(RELATIONSHIPS));
            }
            named.add(new Nomination.Nominee(x.name(), x.relationship(), x.sharePercent() == null ? null : new BigDecimal(x.sharePercent()),
                    Boolean.TRUE.equals(x.minor()), x.guardianName()));
        }
        if (o.nominationMode() == null || nomination != null) {
            for (DepositRules.Violation v : Nomination.check(rules, nomination, named)) problems.add(v.message());
        }

        TermDepositBooking.Quote quote = null;
        if (term) {
            Holder first = holders.isEmpty() || !holders.get(0).id().toString().equals(o.holders().get(0)) ? null : holders.get(0);
            TermDepositProduct.Settings settings = DepositProductService.term(product);
            TermDepositBooking.Request request = new TermDepositBooking.Request(o.amount() == null ? null : new BigDecimal(o.amount()),
                    new Tenure(o.tenureMonths(), o.tenureDays()), today, new LinkedHashSet<>(o.addOns()), named.size(),
                    first != null && "INDIVIDUAL".equals(first.type()), first == null ? null : first.dateOfBirth(),
                    first != null && first.panOnFile());
            List<String> booking = TermDepositBooking.problems(rules, settings, request);
            problems.addAll(booking);
            if (!PAYOUT_TO.contains(o.payoutTo())) {
                problems.add("payoutTo is CASA (a savings or current account of the depositor) or BANK_ACCOUNT");
            } else if ("CASA".equals(o.payoutTo())) {
                if (o.payoutAccountId() == null) problems.add("name the savings or current account that interest and maturity proceeds are paid to");
                else payoutProblem(UUID.fromString(o.payoutAccountId()), o.holders().get(0), problems);
            } else if (o.payoutAccountId() != null) {
                problems.add("payoutAccountId goes with payoutTo CASA");
            }
            if (!INSTRUCTIONS.contains(o.maturityInstruction())) {
                problems.add("maturityInstruction is PAY_OUT, RENEW_PRINCIPAL or RENEW_WITH_INTEREST");
            } else if (!"PAY_OUT".equals(o.maturityInstruction()) && "RD".equals(product.productType())) {
                problems.add("a recurring deposit is paid out at maturity; it is not renewed");
            } else if (!"PAY_OUT".equals(o.maturityInstruction())) {
                if (!Boolean.TRUE.equals(product.autoRenewalAllowed())) problems.add("product " + product.code() + " does not allow renewal at maturity");
                // NBFC Directions para 17: no renewal without the depositor's express consent, recorded once the deposit
                // is funded (its maturity instruction, P4-4); an instruction given at booking is not that consent
                if (rules.flag(DepositRules.TD_RENEWAL_NEEDS_CONSENT, false)) {
                    problems.add("a renewal needs the depositor's express consent, recorded once the deposit is funded; book the deposit to be paid out");
                }
            }
            if (problems.isEmpty()) quote = quote(rules, settings, request);
        }
        refuseIfAny(term ? "the deposit cannot be booked" : "the account cannot be opened", problems);
        Holder first = holders.get(0);
        Opening named0 = new Opening(o.family(), o.productCode(), o.holders(), o.branchCode(), o.operatingMode(), o.nominationMode(), o.nominees(),
                o.amount(), o.tenureMonths(), o.tenureDays(), o.addOns(), o.payoutTo(), o.payoutAccountId(), o.maturityInstruction(),
                o.quotedRatePercent(), first.customerNo(), first.name());
        return new Checked(named0, product, rules, holders, today, quote);
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String value, String field, List<String> problems) {
        if (value == null) return null;
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(value)) return e;
        }
        List<String> names = new ArrayList<>();
        for (E e : type.getEnumConstants()) names.add(e.name());
        problems.add(field + " is one of " + String.join(", ", names));
        return null;
    }

    private void payoutProblem(UUID payoutAccount, String depositor, List<String> problems) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.account_no, a.family, a.status,
                       EXISTS (SELECT 1 FROM deposits.account_holder h WHERE h.account_id = a.id AND h.customer_id = ?::uuid) AS held
                  FROM deposits.account a WHERE a.id = ?
                """, depositor, payoutAccount);
        if (rows.isEmpty()) {
            problems.add("the payout account does not exist");
            return;
        }
        Map<String, Object> a = rows.get(0);
        if (!"CASA".equals(a.get("family")) || !"ACTIVE".equals(a.get("status"))) {
            problems.add("interest and maturity proceeds are paid to an active savings or current account; " + a.get("account_no") + " is not one");
        } else if (!Boolean.TRUE.equals(a.get("held"))) {
            problems.add("account " + a.get("account_no") + " is not held by the depositor");
        }
    }

    private Holder holder(UUID id) {
        List<Holder> found = jdbc.query("""
                SELECT id, customer_no, display_name, customer_type, date_of_birth, pan_hash IS NOT NULL, status, kyc_status
                  FROM customer.customer WHERE id = ?
                """, (rs, i) -> new Holder(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDate.class), rs.getBoolean(6), rs.getString(7), rs.getString(8)), id);
        return found.isEmpty() ? null : found.get(0);
    }

    // ---- applying an approved proposal ----------------------------------------------------------------------------

    /** Writes the account with its holders, nominees and the row of its family; returns the account number. */
    String open(ApprovalRequest r) {
        Checked c = check(json.convert(r.payload(), Opening.class), false);
        Opening o = c.opening();
        boolean term = DepositProductService.TERM_DEPOSIT.equals(o.family());
        if (term && o.quotedRatePercent() != null
                && new BigDecimal(o.quotedRatePercent()).compareTo(c.quote().rate().ratePercent()) != 0) {
            throw ApiException.conflict("the rate of product " + o.productCode() + " for this deposit is now "
                    + DepositRuleSets.plain(c.quote().rate().ratePercent()) + "%, not the " + o.quotedRatePercent()
                    + "% shown when it was proposed; reject this request and propose the deposit again");
        }
        UUID id = UUID.randomUUID();
        // the number, the product version, the status and the opening date are set by the database (deposits.guard_account)
        jdbc.update("""
                INSERT INTO deposits.account (id, family, product_code, product_version, customer_id, branch_code, operating_mode,
                                              nomination_mode, status, opened_on, approval_id, opened_by)
                VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, o.family(), o.productCode(), c.holders().get(0).id(), o.branchCode(), o.operatingMode(), o.nominationMode(),
                term ? "BOOKED" : "ACTIVE", c.today(), r.id(), r.maker());
        int no = 0;
        for (Holder h : c.holders()) {
            jdbc.update("INSERT INTO deposits.account_holder (account_id, holder_no, customer_id) VALUES (?, ?, ?)", id, ++no, h.id());
        }
        no = 0;
        for (Nominee n : o.nominees()) {
            jdbc.update("""
                    INSERT INTO deposits.nominee (account_id, nominee_no, name, relationship, share_percent, is_minor, guardian_name)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, id, ++no, n.name(), n.relationship(), n.sharePercent() == null ? null : new BigDecimal(n.sharePercent()),
                    Boolean.TRUE.equals(n.minor()), n.guardianName());
        }
        if (term) {
            insertTermDeposit(id, c);
        } else {
            LocalDate nextInterest = null;
            if (c.product().interestBands() != null) {
                Integer yearStart = jdbc.queryForObject("SELECT financial_year_start_month FROM platform.legal_entity WHERE id = 1", Integer.class);
                nextInterest = SavingsInterest.periodEnd(c.today(), c.product().interestPostingMonths(), yearStart == null ? 4 : yearStart);
            }
            jdbc.update("INSERT INTO deposits.casa_account (account_id, next_interest_date) VALUES (?, ?)", id, nextInterest);
        }
        // the whole-account checks are deferred to commit; run them here, so a refusal is this request's answer,
        // and defer them again for whatever else this transaction writes
        jdbc.execute("SET CONSTRAINTS deposits.account_complete, deposits.holder_complete, deposits.nominee_complete IMMEDIATE");
        jdbc.execute("SET CONSTRAINTS deposits.account_complete, deposits.holder_complete, deposits.nominee_complete DEFERRED");
        return jdbc.queryForObject("SELECT account_no FROM deposits.account WHERE id = ?", String.class, id);
    }

    private void insertTermDeposit(UUID id, Checked c) {
        Opening o = c.opening();
        Product p = c.product();
        DepositRateTable.ResolvedRate rate = c.quote().rate();
        List<DepositProductService.AddOn> given = new ArrayList<>();
        for (DepositRateTable.AddOn a : rate.addOns()) given.add(new DepositProductService.AddOn(a.code(), a.percent().toPlainString()));
        jdbc.update("""
                INSERT INTO deposits.term_deposit (account_id, principal, tenure_months, tenure_days, booked_on, rate_percent, card_rate_percent,
                    card_from_amount, card_from_months, card_from_days, add_ons, interest_period_months, cumulative, day_count, rounding,
                    tds_applicable, premature_allowed, premature_penalty_percent, premature_min_days, payout_to, payout_account_id,
                    maturity_instruction)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, new BigDecimal(o.amount()), o.tenureMonths(), o.tenureDays(), c.today(), rate.ratePercent(), rate.cardRatePercent(),
                rate.fromAmount(), rate.fromTenure().months(), rate.fromTenure().days(), DepositProductService.addOnsJson(given),
                p.interestPeriodMonths(), "FD_CUMULATIVE".equals(p.productType()) || "RD".equals(p.productType()), p.dayCount(), p.rounding(), p.tdsApplicable(),
                p.prematureAllowed(), p.prematurePenaltyPercent() == null ? null : new BigDecimal(p.prematurePenaltyPercent()),
                p.prematureMinDays(), o.payoutTo(), o.payoutAccountId() == null ? null : UUID.fromString(o.payoutAccountId()),
                o.maturityInstruction());
    }

    /** A savings or current account request: its opening (P4-1) and its closure (P4-4). */
    @Service
    static class CasaApplier implements ApprovalApplier {
        private final DepositAccountService accounts;
        private final DepositLifecycleService lifecycle;

        CasaApplier(@Lazy DepositAccountService accounts, @Lazy DepositLifecycleService lifecycle) {
            this.accounts = accounts;
            this.lifecycle = lifecycle;
        }

        @Override public String entityType() { return CASA_ENTITY; }

        @Override
        public String apply(ApprovalRequest r) {
            return DepositLifecycleService.CLOSE.equals(r.action()) ? lifecycle.applyCasaClosure(r) : accounts.open(r);
        }
    }

    /**
     * A term deposit request: its booking (P4-1), its funding and the reversal of its funding (P4-2), its closure, its
     * maturity instruction and its renewal after maturity (P4-4).
     */
    @Service
    static class TermDepositApplier implements ApprovalApplier {
        private final DepositAccountService accounts;
        private final DepositMoneyService money;
        private final DepositLifecycleService lifecycle;

        TermDepositApplier(@Lazy DepositAccountService accounts, @Lazy DepositMoneyService money, @Lazy DepositLifecycleService lifecycle) {
            this.accounts = accounts;
            this.money = money;
            this.lifecycle = lifecycle;
        }

        @Override public String entityType() { return TD_ENTITY; }

        @Override
        public String apply(ApprovalRequest r) {
            return switch (r.action()) {
                case DepositMoneyService.FUND, DepositMoneyService.REVERSE_FUNDING -> money.applyFunding(r);
                case DepositLifecycleService.CLOSE -> lifecycle.applyDepositClosure(r);
                case DepositLifecycleService.INSTRUCTION -> lifecycle.applyInstruction(r);
                case DepositLifecycleService.RENEW -> lifecycle.applyRenewal(r);
                case DepositMoneyService.INSTALMENT -> money.applyInstalment(r);
                default -> accounts.open(r);
            };
        }
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    /** Accounts of one family in the caller's branches, newest first; optionally of one customer (as any holder) or one status. */
    public List<Map<String, Object>> list(String family, UUID customerId, String status) {
        return jdbc.query("""
                SELECT a.id, a.account_no, a.product_code, p.name, p.product_type, a.customer_id, c.customer_no, c.display_name,
                       a.branch_code, a.operating_mode, a.status, a.opened_on, t.principal::text, t.tenure_months, t.tenure_days,
                       t.rate_percent::text, t.start_date, t.maturity_date,
                       (SELECT count(*) FROM deposits.account_holder h WHERE h.account_id = a.id),
                       coalesce((SELECT b.balance FROM deposits.account_balance b WHERE b.account_id = a.id), 0)::text,
                       EXISTS (SELECT 1 FROM deposits.account_hold x WHERE x.account_id = a.id AND x.status = 'ACTIVE')
                  FROM deposits.account a
                  JOIN deposits.product p ON p.code = a.product_code
                  JOIN customer.customer c ON c.id = a.customer_id
                  LEFT JOIN deposits.term_deposit t ON t.account_id = a.id
                 WHERE a.family = ? AND a.branch_code""" + BranchScope.SQL_VISIBLE + """
                   AND (?::uuid IS NULL OR EXISTS (SELECT 1 FROM deposits.account_holder h WHERE h.account_id = a.id AND h.customer_id = ?::uuid))
                   AND (?::text IS NULL OR a.status = ?)
                 ORDER BY a.created_at DESC, a.account_no DESC LIMIT 500
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("accountNo", rs.getString(2));
                    m.put("productCode", rs.getString(3));
                    m.put("productName", rs.getString(4));
                    m.put("productType", rs.getString(5));
                    m.put("customerId", rs.getString(6));
                    m.put("customerNo", rs.getString(7));
                    m.put("customerName", rs.getString(8));
                    m.put("branchCode", rs.getString(9));
                    m.put("operatingMode", rs.getString(10));
                    m.put("status", rs.getString(11));
                    m.put("openedOn", rs.getObject(12, LocalDate.class));
                    if (DepositProductService.TERM_DEPOSIT.equals(family)) {
                        m.put("principal", DepositRuleSets.plain(rs.getString(13)));
                        m.put("tenureMonths", rs.getInt(14));
                        m.put("tenureDays", rs.getInt(15));
                        m.put("ratePercent", DepositRuleSets.plain(rs.getString(16)));
                        m.put("startDate", rs.getObject(17, LocalDate.class));
                        m.put("maturityDate", rs.getObject(18, LocalDate.class));
                    }
                    m.put("holders", rs.getInt(19));
                    m.put("balance", DepositRuleSets.plain(rs.getString(20)));
                    m.put("onHold", rs.getBoolean(21));
                    return m;
                }, family, scope.user(), customerId == null ? null : customerId.toString(), customerId == null ? null : customerId.toString(),
                status, status);
    }

    /** One account with its holders, nominees and the row of its family. Outside the caller's branches it is not found. */
    public Map<String, Object> get(String family, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id::text AS id, a.account_no, a.family, a.product_code, a.product_version, p.name AS product_name, p.product_type,
                       a.branch_code, a.operating_mode, a.nomination_mode, a.status, a.opened_on::text AS opened_on, a.opened_by,
                       a.approval_id::text AS approval_id, a.closed_on::text AS closed_on
                  FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code
                 WHERE a.id = ? AND a.family = ?
                """, id, family);
        String what = (DepositProductService.CASA.equals(family) ? "account " : "term deposit ") + id;
        if (rows.isEmpty()) throw ApiException.notFound(what);
        Map<String, Object> a = rows.get(0);
        scope.requireRecord((String) a.get("branch_code"), what);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.get("id"));
        out.put("accountNo", a.get("account_no"));
        out.put("family", a.get("family"));
        out.put("productCode", a.get("product_code"));
        out.put("productVersion", a.get("product_version"));
        out.put("productName", a.get("product_name"));
        out.put("productType", a.get("product_type"));
        out.put("branchCode", a.get("branch_code"));
        out.put("operatingMode", a.get("operating_mode"));
        out.put("nominationMode", a.get("nomination_mode"));
        out.put("status", a.get("status"));
        out.put("openedOn", a.get("opened_on"));
        out.put("openedBy", a.get("opened_by"));
        out.put("approvalId", a.get("approval_id"));
        out.put("closedOn", a.get("closed_on"));
        out.put("closure", lifecycle.closure(id));
        out.put("holders", jdbc.query("""
                SELECT h.holder_no, c.id::text, c.customer_no, c.display_name FROM deposits.account_holder h
                  JOIN customer.customer c ON c.id = h.customer_id WHERE h.account_id = ? ORDER BY h.holder_no
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("holderNo", rs.getInt(1));
                    m.put("customerId", rs.getString(2));
                    m.put("customerNo", rs.getString(3));
                    m.put("name", rs.getString(4));
                    return m;
                }, id));
        out.put("nominees", jdbc.query("""
                SELECT nominee_no, name, relationship, share_percent::text, is_minor, guardian_name FROM deposits.nominee
                 WHERE account_id = ? AND status = 'ACTIVE' ORDER BY nominee_no
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nomineeNo", rs.getInt(1));
                    m.put("name", rs.getString(2));
                    m.put("relationship", rs.getString(3));
                    m.put("sharePercent", DepositRuleSets.plain(rs.getString(4)));
                    m.put("minor", rs.getBoolean(5));
                    m.put("guardianName", rs.getString(6));
                    return m;
                }, id));
        out.putAll(money.balanceView(id));
        if (DepositProductService.CASA.equals(family)) {
            out.put("nextInterestDate", jdbc.queryForObject("SELECT next_interest_date::text FROM deposits.casa_account WHERE account_id = ?", String.class, id));
        } else {
            out.put("termDeposit", termDeposit(id));
        }
        return out;
    }

    /** The terms frozen at booking. Until the deposit is funded its dates and figures are those of funding on the business date. */
    private Map<String, Object> termDeposit(UUID id) {
        BusinessDays.BusinessDay day = days.current();
        return jdbc.queryForObject("""
                SELECT t.principal, t.tenure_months, t.tenure_days, t.booked_on, t.start_date, t.maturity_date, t.rate_percent, t.card_rate_percent,
                       t.card_from_amount, t.card_from_months, t.card_from_days,
                       (SELECT coalesce(jsonb_agg(jsonb_build_object('code', x->>'code', 'percent', x->>'percent') ORDER BY x->>'code'), '[]'::jsonb)
                          FROM jsonb_array_elements(t.add_ons) x)::text,
                       t.interest_period_months, t.cumulative, t.day_count, t.rounding, t.tds_applicable, t.premature_allowed,
                       t.premature_penalty_percent, t.premature_min_days, t.payout_to, t.payout_account_id::text, pay.account_no,
                       t.maturity_instruction, t.funding_source, fa.account_no, t.funding_reference, t.payout_bank_account_last4, t.payout_bank_ifsc,
                       (SELECT p.product_type FROM deposits.account x JOIN deposits.product p ON p.code = x.product_code WHERE x.id = t.account_id)
                  FROM deposits.term_deposit t LEFT JOIN deposits.account pay ON pay.id = t.payout_account_id
                  LEFT JOIN deposits.account fa ON fa.id = t.funding_account_id
                 WHERE t.account_id = ?
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    BigDecimal principal = rs.getBigDecimal(1);
                    Tenure tenure = new Tenure(rs.getInt(2), rs.getInt(3));
                    LocalDate start = rs.getObject(5, LocalDate.class);
                    BigDecimal rate = rs.getBigDecimal(7);
                    m.put("principal", DepositRuleSets.plain(principal));
                    m.put("tenureMonths", tenure.months());
                    m.put("tenureDays", tenure.days());
                    m.put("bookedOn", rs.getObject(4, LocalDate.class));
                    m.put("startDate", start);
                    m.put("maturityDate", rs.getObject(6, LocalDate.class));
                    m.put("ratePercent", DepositRuleSets.plain(rate));
                    m.put("cardRatePercent", DepositRuleSets.plain(rs.getBigDecimal(8)));
                    m.put("cardFromAmount", DepositRuleSets.plain(rs.getBigDecimal(9)));
                    m.put("cardFromMonths", rs.getInt(10));
                    m.put("cardFromDays", rs.getInt(11));
                    m.put("addOns", List.of(json.read(rs.getString(12), DepositProductService.AddOn[].class)));
                    m.put("interestPeriodMonths", rs.getInt(13));
                    m.put("cumulative", rs.getBoolean(14));
                    m.put("dayCount", rs.getString(15));
                    m.put("rounding", rs.getString(16));
                    m.put("tdsApplicable", rs.getBoolean(17));
                    m.put("prematureAllowed", rs.getBoolean(18));
                    m.put("prematurePenaltyPercent", DepositRuleSets.plain(rs.getBigDecimal(19)));
                    m.put("prematureMinDays", DepositProductService.whole(rs, "premature_min_days"));
                    m.put("payoutTo", rs.getString(21));
                    m.put("payoutAccountId", rs.getString(22));
                    m.put("payoutAccountNo", rs.getString(23));
                    m.put("maturityInstruction", rs.getString(24));
                    m.put("fundingSource", rs.getString(25));
                    m.put("fundingAccountNo", rs.getString(26));
                    m.put("fundingReference", rs.getString(27));
                    m.put("payoutBankAccountMasked", rs.getString(28) == null ? null : "XXXXXXXX" + rs.getString(28));
                    m.put("payoutBankIfsc", rs.getString(29));
                    // figures from the engine, on the frozen terms
                    LocalDate from = start != null ? start : day == null ? rs.getObject(4, LocalDate.class) : day.businessDate();
                    TermDeposit.Terms terms = new TermDeposit.Terms(principal, rate, from, tenure.addTo(from), rs.getInt(13), rs.getBoolean(14),
                            DayCount.valueOf(rs.getString(15)), Rounding.valueOf(rs.getString(16)));
                    m.put("funded", start != null);
                    m.put("figuresFrom", from);
                    m.put("figuresMaturityDate", terms.maturity());
                    boolean rd = "RD".equals(rs.getString(30));
                    m.put("recurring", rd);
                    // a recurring deposit (P4-5): the principal is the monthly instalment; the figures are for every instalment paid on time
                    m.put("totalInterest", amount(rd ? RecurringDeposit.totalInterest(principal, rate, tenure.months(), rs.getInt(13), Rounding.valueOf(rs.getString(16)))
                            : TermDeposit.totalInterest(terms)));
                    m.put("maturityValue", amount(rd ? RecurringDeposit.maturityValue(principal, rate, tenure.months(), rs.getInt(13), Rounding.valueOf(rs.getString(16)))
                            : TermDeposit.maturityValue(terms)));
                    return m;
                }, id);
    }

    private Map<String, Object> quoteView(Product product, DepositRules rules, TermDepositBooking.Request request, TermDepositBooking.Quote q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", product.code());
        m.put("productVersion", product.version());
        m.put("amount", request.amount().toPlainString());
        m.put("tenureMonths", request.tenure().months());
        m.put("tenureDays", request.tenure().days());
        m.put("startDate", request.start());
        m.put("maturityDate", q.maturity());
        m.put("ratePercent", DepositRuleSets.plain(q.rate().ratePercent()));
        m.put("cardRatePercent", DepositRuleSets.plain(q.rate().cardRatePercent()));
        m.put("cardFromAmount", DepositRuleSets.plain(q.rate().fromAmount()));
        m.put("cardFromMonths", q.rate().fromTenure().months());
        m.put("cardFromDays", q.rate().fromTenure().days());
        List<Map<String, Object>> addOns = new ArrayList<>();
        for (DepositRateTable.AddOn a : q.rate().addOns()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", a.code());
            x.put("percent", DepositRuleSets.plain(a.percent()));
            addOns.add(x);
        }
        m.put("addOns", addOns);
        m.put("interestPeriodMonths", product.interestPeriodMonths());
        m.put("cumulative", "FD_CUMULATIVE".equals(product.productType()) || "RD".equals(product.productType()));
        m.put("recurring", "RD".equals(product.productType()));
        m.put("totalInterest", amount(q.totalInterest()));
        m.put("maturityValue", amount(q.maturityValue()));
        m.put("annualisedYieldPercent", q.annualisedYield().toPlainString());
        List<Map<String, Object>> periods = new ArrayList<>();
        for (TermDeposit.Period p : q.periods()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("number", p.number());
            x.put("from", p.from());
            x.put("to", p.to());
            x.put("balance", amount(p.balance()));
            x.put("interest", amount(p.interest()));
            x.put("whole", p.whole());
            periods.add(x);
        }
        m.put("periods", periods);
        // a product that deducts no tax shows none: no line, no total
        m.put("tdsApplicable", Boolean.TRUE.equals(product.tdsApplicable()));
        List<Map<String, Object>> tax = new ArrayList<>();
        for (TermDepositBooking.TaxLine t : q.tax()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("period", t.period());
            x.put("on", t.on());
            x.put("taxYearStarting", t.taxYearStarting());
            x.put("interest", amount(t.interest()));
            x.put("tax", amount(t.tax()));
            tax.add(x);
        }
        m.put("tax", tax);
        m.put("totalTax", Boolean.TRUE.equals(product.tdsApplicable()) ? amount(q.totalTax()) : null);
        m.put("taxNote", Boolean.TRUE.equals(product.tdsApplicable())
                ? "Tax is worked out on the interest of this deposit alone. The depositor's other deposits and a declaration on file change what is deducted."
                : null);
        m.put("insured", rules.number(DepositRules.INSURED).map(v -> v.signum() != 0).orElse(null));
        return m;
    }
}
