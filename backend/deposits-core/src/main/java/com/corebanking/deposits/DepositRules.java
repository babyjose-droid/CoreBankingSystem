package com.corebanking.deposits;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The regulatory rule set in force for a tenant on a date. Values are data (deposits.rule in the tenant database),
 * keyed by the codes below, so a change by the regulator is a new dated row and not a code change. A rule that is
 * absent does not apply to that kind of institution: the product's own settings decide.
 *
 * <p>Sources read on 10-Oct-2026: RBI (Non-Banking Financial Companies - Acceptance of Public Deposits)
 * Directions, 2025 (paragraph numbers in the comments); for banks, summaries of the RBI (Commercial Banks -
 * Interest Rate on Deposits) Directions, 2025 and of the inoperative-accounts instructions; section 393 of the
 * Income-tax Act, 2025 for tax deducted at source. See docs/phase4-status.md for what is still unconfirmed.
 */
public final class DepositRules {

    /** Which rule set a legal entity falls under. */
    public enum Kind {
        /** Banks: savings, current and term deposits. */
        BANK,
        /** Deposit-taking NBFC (NBFC-D): term deposits only. */
        NBFC_DEPOSIT;

        /** The rule set for a legal entity type, or empty when that type cannot take deposits here. */
        public static Optional<Kind> forEntityType(String entityType) {
            return switch (entityType == null ? "" : entityType) {
                case "BANK", "SFB", "COOP_BANK" -> Optional.of(BANK);
                case "NBFC" -> Optional.of(NBFC_DEPOSIT);
                // HFC: paragraphs 10, 11, 30, 40, 50-52 of the NBFC Directions do not apply to a deposit-taking
                // HFC (para 3); its rules are in the HFC Directions, which are not encoded. MFI: no deposits.
                default -> Optional.empty();
            };
        }
    }

    public static final String DEMAND_DEPOSITS_ALLOWED = "demand-deposits-allowed";          // NBFC para 15: 0
    public static final String INSURED = "insured";                                          // NBFC para 31(9): 0
    public static final String NOMINEES_MAX = "nominees.max";                                // bank 4; NBFC para 50: 1
    public static final String TD_MIN_TENURE_MONTHS = "td.min-tenure-months";                // NBFC para 16: 12
    public static final String TD_MAX_TENURE_MONTHS = "td.max-tenure-months";                // NBFC para 16: 60
    public static final String TD_MAX_RATE = "td.max-rate-percent";                          // NBFC para 19: 12.5
    public static final String TD_MIN_REST_MONTHS = "td.min-compounding-rest-months";        // NBFC para 19: 1
    public static final String TD_LOCK_IN_MONTHS = "td.lock-in-months";                      // NBFC para 35: 3
    public static final String TD_NO_INTEREST_BEFORE_MONTHS = "td.premature.no-interest-before-months"; // para 40: 6
    public static final String TD_PREMATURE_RATE_CUT = "td.premature.rate-cut-percent";      // para 40: 2
    public static final String TD_PREMATURE_CUT_FROM_MINIMUM = "td.premature.rate-cut-from-minimum-percent"; // para 40: 3
    public static final String TD_PREMATURE_MANDATORY_UP_TO = "td.premature.mandatory-up-to"; // bank: 1 crore
    public static final String TD_TINY_DEPOSIT_MAX = "td.emergency.tiny-deposit-max";        // NBFC para 7(19): 10,000
    public static final String TD_EMERGENCY_MAX_PERCENT = "td.emergency.max-percent";        // NBFC para 37(2): 50
    public static final String TD_EMERGENCY_MAX_AMOUNT = "td.emergency.max-amount";          // NBFC para 37(2): 5 lakh
    public static final String TD_MATURITY_NOTICE_DAYS = "td.maturity-notice-days";          // NBFC para 22: 14
    public static final String TD_LOAN_MAX_PERCENT = "td.loan-max-percent";                  // NBFC para 36(2): 75
    public static final String TD_LOAN_SPREAD = "td.loan-spread-percent";                    // NBFC para 36(2): 2
    public static final String TD_DEPOSITS_TO_NOF = "td.deposits-to-nof-multiple";           // NBFC para 17: 1.5
    public static final String TD_RENEWAL_NEEDS_CONSENT = "td.renewal-needs-consent";        // NBFC para 17: 1
    public static final String SAVINGS_UNIFORM_RATE_UP_TO = "savings.uniform-rate-up-to";    // bank: 1 lakh
    public static final String CASA_REVIEW_AFTER_MONTHS = "casa.review-after-months";        // bank: 12
    public static final String CASA_INOPERATIVE_AFTER_MONTHS = "casa.inoperative-after-months"; // bank: 24
    public static final String UNCLAIMED_AFTER_YEARS = "unclaimed-after-years";              // bank: 10
    public static final String TDS_RATE = "tds.rate-percent";                                // 10
    public static final String TDS_NO_PAN_RATE = "tds.no-pan-rate-percent";                  // to be confirmed
    public static final String TDS_THRESHOLD = "tds.threshold";                              // bank 50,000; others 10,000
    public static final String TDS_THRESHOLD_SENIOR = "tds.threshold-senior";                // bank 1,00,000; others 10,000
    public static final String TDS_ON_SAVINGS = "tds.on-savings-interest";                   // 0 (to be confirmed)
    public static final String SENIOR_CITIZEN_AGE = "senior-citizen-age";                    // 60

    private final Kind kind;
    private final Map<String, BigDecimal> values;

    public DepositRules(Kind kind, Map<String, BigDecimal> values) {
        if (kind == null) throw new IllegalArgumentException("rule set kind is required");
        this.kind = kind;
        this.values = Map.copyOf(new TreeMap<>(values));
    }

    public Kind kind() { return kind; }

    public Optional<BigDecimal> number(String code) {
        return Optional.ofNullable(values.get(code));
    }

    public Optional<Integer> whole(String code) {
        return number(code).map(BigDecimal::intValueExact);
    }

    /** A yes/no rule: stored as 1 or 0; {@code whenAbsent} when the rule set says nothing. */
    public boolean flag(String code, boolean whenAbsent) {
        return number(code).map(v -> v.signum() != 0).orElse(whenAbsent);
    }

    /** A rule the engine cannot work without (the tax rate, for one). */
    public BigDecimal required(String code) {
        return number(code).orElseThrow(() ->
                new IllegalStateException("rule " + code + " is not set for " + kind + "; add it to deposits.rule"));
    }

    public boolean demandDepositsAllowed() { return flag(DEMAND_DEPOSITS_ALLOWED, false); }

    /** One finding of a check: the rule that is broken and a sentence a member of staff can act on. */
    public record Violation(String rule, String message) {}

    /** Why a savings or current product may not be offered; empty when it may. */
    public List<Violation> checkDemandDeposit() {
        List<Violation> out = new ArrayList<>();
        if (!demandDepositsAllowed()) {
            out.add(new Violation(DEMAND_DEPOSITS_ALLOWED,
                    "a " + label() + " cannot accept deposits repayable on demand (savings or current accounts)"));
        }
        return out;
    }

    /**
     * Checks one term deposit, or the limits of a term deposit product, against the rule set.
     *
     * @param tenure                tenure of the deposit (for a product: call once with its shortest and once with
     *                              its longest tenure)
     * @param ratePercent           the rate paid, add-ons included
     * @param compoundingRestMonths months between two interest compoundings or payouts; 0 when interest is paid
     *                              once, at maturity
     * @param nominees              number of nominees named
     */
    public List<Violation> checkTermDeposit(Tenure tenure, BigDecimal ratePercent, int compoundingRestMonths, int nominees) {
        List<Violation> out = new ArrayList<>();
        whole(TD_MIN_TENURE_MONTHS).ifPresent(min -> {
            if (tenure.compareTo(Tenure.ofMonths(min)) < 0) {
                out.add(new Violation(TD_MIN_TENURE_MONTHS, "the tenure " + tenure + " is shorter than " + min + " months"));
            }
        });
        whole(TD_MAX_TENURE_MONTHS).ifPresent(max -> {
            if (tenure.compareTo(Tenure.ofMonths(max)) > 0) {
                out.add(new Violation(TD_MAX_TENURE_MONTHS, "the tenure " + tenure + " is longer than " + max + " months"));
            }
        });
        number(TD_MAX_RATE).ifPresent(max -> {
            if (ratePercent.compareTo(max) > 0) {
                out.add(new Violation(TD_MAX_RATE, "the rate " + ratePercent.stripTrailingZeros().toPlainString()
                        + "% is above the ceiling of " + max.stripTrailingZeros().toPlainString() + "%"));
            }
        });
        whole(TD_MIN_REST_MONTHS).ifPresent(min -> {
            if (compoundingRestMonths != 0 && compoundingRestMonths < min) {
                out.add(new Violation(TD_MIN_REST_MONTHS, "interest may not be paid or compounded more often than every "
                        + min + " month(s)"));
            }
        });
        whole(NOMINEES_MAX).ifPresent(max -> {
            if (nominees > max) {
                out.add(new Violation(NOMINEES_MAX, nominees + " nominees named; at most " + max + " allowed"));
            }
        });
        return out;
    }

    private String label() {
        return kind == Kind.NBFC_DEPOSIT ? "deposit-taking NBFC" : "bank";
    }
}
