package com.corebanking.lending.engine;

import com.corebanking.lending.engine.Delinquency.AssetClass;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The restructured flag carried on a loan's state, and the upgrade test for the "specified period".
 *
 * <p>Interpretation encoded here (RBI Prudential Framework for Resolution of Stressed Assets, 7-Jun-2019, Annex-1,
 * with the IRACP norms), deliberately conservative:
 * <ul>
 *   <li>On restructuring a standard account is downgraded to sub-standard (NPA); an NPA keeps its class. NPA ageing
 *       (sub-standard → doubtful) continues from the NPA date while the account is under monitoring.</li>
 *   <li>The specified period runs from the restructuring until at least 10% of the principal as per the plan
 *       (capitalised interest included) has been repaid, and cannot end before one year from the commencement of
 *       the first payment of interest or principal, whichever is later, under the new schedule (with a principal
 *       moratorium, that is the first principal instalment).</li>
 *   <li>Satisfactory performance: no instalment under the new schedule is unpaid at the day-end of its due date at
 *       any time during the specified period. One default ends eligibility for upgrade (RBI requires a fresh
 *       resolution; the account then stays NPA and ages normally until it is closed or restructured again).</li>
 *   <li>Upgrade happens only after the specified period, with performance satisfactory and every arrear of interest
 *       and principal paid. Interest capitalised on restructuring stays in suspense (the "interest capitalisation"
 *       liability) and becomes income only as the principal is repaid.</li>
 * </ul>
 *
 * @param count                  how many times the loan has been restructured
 * @param classBefore            asset class just before this restructuring
 * @param principalAsPerPlan     principal outstanding after restructuring (incl. capitalised interest)
 * @param firstPaymentDue        later of the first interest and first principal due date in the new schedule
 * @param specifiedPeriodMinEnd  {@code firstPaymentDue} plus one year: the earliest the specified period can end
 * @param defaulted              an instalment of the new schedule was unpaid at the day-end of its due date
 * @param upgradedOn             date the account was upgraded after the specified period (monitoring over)
 */
public record RestructureStatus(LocalDate restructuredOn, int count, AssetClass classBefore, BigDecimal principalAsPerPlan,
                                LocalDate firstPaymentDue, LocalDate specifiedPeriodMinEnd, boolean defaulted,
                                LocalDate upgradedOn) {

    /** Share of the plan principal that must be repaid before the specified period can end. */
    public static final BigDecimal MIN_REPAID_SHARE = new BigDecimal("0.10");

    public RestructureStatus {
        Objects.requireNonNull(restructuredOn);
        Objects.requireNonNull(principalAsPerPlan);
        Objects.requireNonNull(specifiedPeriodMinEnd);
    }

    public boolean underMonitoring() {
        return upgradedOn == null;
    }

    /** True once the specified period is over (time and 10% repayment), regardless of performance. */
    public boolean specifiedPeriodOver(LocalDate day, BigDecimal principalOutstanding) {
        BigDecimal repaid = principalAsPerPlan.subtract(principalOutstanding);
        return !day.isBefore(specifiedPeriodMinEnd)
                && repaid.compareTo(principalAsPerPlan.multiply(MIN_REPAID_SHARE, MathContext.DECIMAL128)) >= 0;
    }

    /** May the account be upgraded today (arrears are checked separately by the classifier)? */
    public boolean upgradeAllowed(LocalDate day, BigDecimal principalOutstanding) {
        return !underMonitoring() || (!defaulted && specifiedPeriodOver(day, principalOutstanding));
    }

    RestructureStatus withDefaulted() {
        return new RestructureStatus(restructuredOn, count, classBefore, principalAsPerPlan, firstPaymentDue, specifiedPeriodMinEnd,
                true, upgradedOn);
    }

    RestructureStatus withUpgradedOn(LocalDate day) {
        return new RestructureStatus(restructuredOn, count, classBefore, principalAsPerPlan, firstPaymentDue, specifiedPeriodMinEnd,
                defaulted, day);
    }
}
