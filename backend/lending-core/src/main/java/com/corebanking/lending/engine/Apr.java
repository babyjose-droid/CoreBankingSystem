package com.corebanking.lending.engine;

import com.corebanking.calc.AprCalculator;
import com.corebanking.calc.ScheduleGenerator.Instalment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Annual percentage rate for the Key Fact Statement: the internal rate of return of the loan's actual cash flows -
 * the amount the borrower receives (principal less fees excluding GST, less any interest deducted upfront), then
 * every instalment.
 * <ul>
 *   <li>Evenly spaced instalments (any frequency): periodic IRR × periods per year, a nominal annual rate. This is
 *       the basis that reproduces the reference system's 18.58% on the golden loan.</li>
 *   <li>Flows that are not evenly spaced - a bullet, a structured schedule, or broken-period interest collected on
 *       its own date or upfront - : XIRR, the effective annual rate on the dated flows (Actual/365).</li>
 * </ul>
 * A flat-rate loan needs nothing special: its instalments are the flows, so the APR shows the true reducing rate.
 */
public final class Apr {

    private Apr() {}

    /** True when the APR of these terms is the nominal periodic IRR; false when it is the XIRR. */
    public static boolean evenlySpaced(LoanTerms t, ScheduleBuilder.Plan plan) {
        return t.method() != RepaymentMethod.BULLET_TOTAL_INTEREST && t.method() != RepaymentMethod.STRUCTURED
                && plan.brokenPeriodInterest().signum() == 0;
    }

    /** @param feesExGst fees charged at disbursement, excluding GST */
    public static BigDecimal of(LoanTerms t, ScheduleBuilder.Plan plan, BigDecimal feesExGst) {
        BigDecimal net = t.principal().subtract(feesExGst);
        List<Instalment> rows = plan.schedule();
        if (evenlySpaced(t, plan)) {
            List<BigDecimal> flows = new ArrayList<>();
            flows.add(net.negate());
            rows.forEach(i -> flows.add(i.instalment()));
            return AprCalculator.nominalAnnualIrr(flows, t.frequency().periodsPerYear());
        }
        List<AprCalculator.DatedFlow> flows = new ArrayList<>();
        int from = 0;
        if (plan.bpiDeducted()) {              // paid out of the disbursement: less received, and no flow on its date
            net = net.subtract(plan.brokenPeriodInterest());
            from = 1;
        }
        flows.add(new AprCalculator.DatedFlow(t.disbursalDate(), net.negate()));
        for (int i = from; i < rows.size(); i++) flows.add(new AprCalculator.DatedFlow(rows.get(i).dueDate(), rows.get(i).instalment()));
        return AprCalculator.xirr(flows);
    }
}
