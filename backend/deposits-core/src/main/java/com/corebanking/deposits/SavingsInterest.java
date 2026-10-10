package com.corebanking.deposits;

import com.corebanking.calc.DayCount;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Savings interest on the daily product: each day's end-of-day balance earns one day's interest. The figures
 * returned are not rounded; the account keeps the running total and rounds when interest is credited.
 */
public final class SavingsInterest {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Places kept on the running accrual. */
    public static final int ACCRUAL_SCALE = 8;

    /** A balance band starting at {@code fromBalance} and running to the next band. */
    public record Band(BigDecimal fromBalance, BigDecimal ratePercent) {}

    public enum BandMode {
        /** The whole balance earns the rate of the band it falls in. */
        WHOLE_BALANCE,
        /** Each part of the balance earns the rate of its own band. */
        INCREMENTAL
    }

    /** A run of days with the same end-of-day balance: {@code from} inclusive, {@code to} exclusive. */
    public record Balance(LocalDate from, LocalDate to, BigDecimal amount) {}

    private final List<Band> bands;
    private final BandMode mode;
    private final DayCount dayCount;

    public SavingsInterest(List<Band> bands, BandMode mode, DayCount dayCount) {
        if (bands.isEmpty()) throw new IllegalArgumentException("at least one rate band is required");
        List<Band> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparing(Band::fromBalance));
        if (sorted.get(0).fromBalance().signum() != 0) throw new IllegalArgumentException("the first band must start at 0");
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).fromBalance().compareTo(sorted.get(i - 1).fromBalance()) == 0) {
                throw new IllegalArgumentException("two bands start at " + sorted.get(i).fromBalance().toPlainString());
            }
        }
        if (dayCount.yearBasis() == 0 || dayCount == DayCount.THIRTY_360 || dayCount == DayCount.THIRTY_E_360) {
            throw new IllegalArgumentException("savings interest needs a fixed actual-days basis, not " + dayCount);
        }
        this.bands = List.copyOf(sorted);
        this.mode = mode;
        this.dayCount = dayCount;
    }

    /** Why the bands break a rule set; empty when they do not. */
    public List<DepositRules.Violation> check(DepositRules rules) {
        List<DepositRules.Violation> out = new ArrayList<>(rules.checkDemandDeposit());
        rules.number(DepositRules.SAVINGS_UNIFORM_RATE_UP_TO).ifPresent(limit -> {
            // INCREMENTAL: a second band from exactly the limit leaves balances up to the limit on one rate.
            // WHOLE_BALANCE: it would move a balance of exactly the limit to the second rate.
            int cmp = bands.size() > 1 ? bands.get(1).fromBalance().compareTo(limit) : 1;
            if (cmp < 0 || (cmp == 0 && mode == BandMode.WHOLE_BALANCE)) {
                out.add(new DepositRules.Violation(DepositRules.SAVINGS_UNIFORM_RATE_UP_TO,
                        "one rate must apply to balances up to " + limit.stripTrailingZeros().toPlainString()
                                + "; the second band starts at " + bands.get(1).fromBalance().stripTrailingZeros().toPlainString()));
            }
        });
        return out;
    }

    /** One day's interest on an end-of-day balance. A balance of zero or below earns nothing. */
    public BigDecimal forOneDay(BigDecimal balance) {
        if (balance.signum() <= 0) return BigDecimal.ZERO.setScale(ACCRUAL_SCALE);
        BigDecimal yearly = BigDecimal.ZERO;
        if (mode == BandMode.WHOLE_BALANCE) {
            Band hit = bands.get(0);
            for (Band b : bands) if (balance.compareTo(b.fromBalance()) >= 0) hit = b;
            yearly = balance.multiply(hit.ratePercent(), MC);
        } else {
            for (int i = 0; i < bands.size(); i++) {
                BigDecimal lower = bands.get(i).fromBalance();
                if (balance.compareTo(lower) <= 0) break;
                BigDecimal upper = i + 1 < bands.size() ? bands.get(i + 1).fromBalance().min(balance) : balance;
                yearly = yearly.add(upper.subtract(lower).multiply(bands.get(i).ratePercent(), MC));
            }
        }
        return yearly.divide(HUNDRED, MC).divide(BigDecimal.valueOf(dayCount.yearBasis()), MC)
                .setScale(ACCRUAL_SCALE, RoundingMode.HALF_UP);
    }

    /** Interest for runs of days; each day is worked out on its own so the result equals the day-end's total. */
    public BigDecimal forPeriod(List<Balance> balances) {
        BigDecimal total = BigDecimal.ZERO.setScale(ACCRUAL_SCALE);
        for (Balance b : balances) {
            if (b.to().isBefore(b.from())) throw new IllegalArgumentException("a balance run ends before it starts");
            long days = DayCount.ACTUAL_365.days(b.from(), b.to());
            total = total.add(forOneDay(b.amount()).multiply(BigDecimal.valueOf(days)));
        }
        return total;
    }
}
