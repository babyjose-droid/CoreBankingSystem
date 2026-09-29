package com.corebanking.lending.engine;

import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.lending.engine.Delinquency.AssetClass;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Figures of one restructuring option, computed by the same code that applies it (ADR-006).
 *
 * <p>NPV: cash flows the borrower owes are discounted at the contract rate (the rate before restructuring) in
 * monthly periods: arrears at period 0, the k-th future instalment at period k. {@code npvLoss} = NPV before − NPV
 * after, the lender's sacrifice (diminution in fair value) when positive.
 */
public record RestructureSimulation(RestructureTerms terms, AssetClass classBefore, AssetClass classAfter,
                                    BigDecimal principalBefore, BigDecimal overduePrincipalRescheduled,
                                    BigDecimal overdueInterest, BigDecimal interestCapitalised, BigDecimal arrearsKept,
                                    BigDecimal principalAfter, BigDecimal rateBefore, BigDecimal rateAfter,
                                    BigDecimal emiBefore, BigDecimal emiAfter, int remainingBefore, int remainingAfter,
                                    LocalDate maturityBefore, LocalDate maturityAfter, BigDecimal interestBefore,
                                    BigDecimal interestAfter, BigDecimal npvBefore, BigDecimal npvAfter, BigDecimal npvLoss,
                                    LocalDate specifiedPeriodMinEnd, List<Instalment> schedule) {

    /** NPV of {@code arrears} now plus {@code rows} at months 1..n, at {@code annualRatePercent} compounded monthly. */
    public static BigDecimal npv(BigDecimal arrears, List<BigDecimal> instalments, BigDecimal annualRatePercent) {
        MathContext mc = MathContext.DECIMAL128;
        BigDecimal factor = BigDecimal.ONE.add(annualRatePercent.divide(BigDecimal.valueOf(1200), mc));
        BigDecimal total = arrears;
        BigDecimal discount = BigDecimal.ONE;
        for (BigDecimal amount : instalments) {
            discount = discount.multiply(factor, mc);
            total = total.add(amount.divide(discount, mc));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
