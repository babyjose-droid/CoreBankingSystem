package com.corebanking.lending.engine;

import com.corebanking.calc.EmiCalculator;
import com.corebanking.calc.ScheduleGenerator;
import com.corebanking.calc.ScheduleGenerator.Instalment;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds repayment schedules for every {@link RepaymentMethod} (US-054). Interest uses actual days per period. */
public final class ScheduleBuilder {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ScheduleBuilder() {}

    public static List<Instalment> build(LoanTerms t) {
        return switch (t.method()) {
            case EQUATED -> equated(t);
            case FIXED_PRINCIPAL -> fixedPrincipal(t);
            case BULLET_TOTAL_INTEREST -> bulletTotal(t);
            case BULLET_PERIODIC_INTEREST -> bulletPeriodic(t);
        };
    }

    /** Due date of instalment n (1-based), month-end anchored when the anchor is a month end. */
    public static LocalDate dueDate(LoanTerms t, int n) {
        if (t.firstDueDate() == null) return ScheduleGenerator.dueDate(t.disbursalDate(), n);
        if (n == 1) return t.firstDueDate();
        LocalDate d = t.firstDueDate().plusMonths(n - 1L);
        boolean monthEnd = t.firstDueDate().getDayOfMonth() == t.firstDueDate().lengthOfMonth();
        return monthEnd ? d.withDayOfMonth(d.lengthOfMonth()) : d;
    }

    /** EMI for the equated part (after any moratorium), allowing for a balloon. */
    public static BigDecimal emi(LoanTerms t) {
        int n = t.tenorMonths() - t.moratoriumMonths();
        if (t.balloon().signum() == 0) return EmiCalculator.pmt(t.principal(), t.ratePercent(), n, t.rounding());
        if (t.ratePercent().signum() == 0) {
            return t.rounding().apply(t.principal().subtract(t.balloon()).divide(BigDecimal.valueOf(n), MC));
        }
        BigDecimal r = t.ratePercent().divide(BigDecimal.valueOf(1200), MC);
        BigDecimal growth = BigDecimal.ONE.add(r).pow(n, MC);
        BigDecimal pv = t.principal().subtract(t.balloon().divide(growth, MC));
        BigDecimal emi = pv.multiply(r, MC).multiply(growth, MC).divide(growth.subtract(BigDecimal.ONE), MC);
        return t.rounding().apply(emi);
    }

    private static BigDecimal interest(LoanTerms t, BigDecimal balance, LocalDate from, LocalDate to) {
        return t.rounding().apply(balance.multiply(t.ratePercent().divide(HUNDRED, MC), MC)
                .multiply(t.dayCount().yearFraction(from, to), MC));
    }

    private static List<Instalment> equated(LoanTerms t) {
        BigDecimal emi = emi(t);
        List<Instalment> rows = new ArrayList<>();
        BigDecimal bal = t.principal();
        LocalDate prev = t.disbursalDate();
        for (int n = 1; n <= t.tenorMonths(); n++) {
            LocalDate due = dueDate(t, n);
            BigDecimal interest = interest(t, bal, prev, due);
            BigDecimal principal;
            if (n <= t.moratoriumMonths()) {
                principal = BigDecimal.ZERO;
            } else if (n == t.tenorMonths()) {
                principal = bal;
            } else {
                principal = emi.subtract(interest).max(BigDecimal.ZERO).min(bal);
            }
            BigDecimal closing = bal.subtract(principal);
            rows.add(new Instalment(n, due, t.dayCount().days(prev, due), bal, interest, principal, principal.add(interest), closing));
            bal = closing;
            prev = due;
        }
        return Collections.unmodifiableList(rows);
    }

    private static List<Instalment> fixedPrincipal(LoanTerms t) {
        BigDecimal part = t.rounding().apply(t.principal().divide(BigDecimal.valueOf(t.tenorMonths()), MC));
        List<Instalment> rows = new ArrayList<>();
        BigDecimal bal = t.principal();
        LocalDate prev = t.disbursalDate();
        for (int n = 1; n <= t.tenorMonths(); n++) {
            LocalDate due = dueDate(t, n);
            BigDecimal interest = interest(t, bal, prev, due);
            BigDecimal principal = n == t.tenorMonths() ? bal : part.min(bal);
            BigDecimal closing = bal.subtract(principal);
            rows.add(new Instalment(n, due, t.dayCount().days(prev, due), bal, interest, principal, principal.add(interest), closing));
            bal = closing;
            prev = due;
        }
        return Collections.unmodifiableList(rows);
    }

    private static List<Instalment> bulletTotal(LoanTerms t) {
        LocalDate maturity = dueDate(t, t.tenorMonths());
        BigDecimal interest = ScheduleGenerator.bulletTotalInterest(t.principal(), t.ratePercent(), t.disbursalDate(),
                maturity, t.dayCount(), t.extraDayOnFirst(), t.rounding());
        return List.of(new Instalment(1, maturity, t.dayCount().days(t.disbursalDate(), maturity), t.principal(), interest,
                t.principal(), t.principal().add(interest), BigDecimal.ZERO));
    }

    private static List<Instalment> bulletPeriodic(LoanTerms t) {
        List<Instalment> rows = new ArrayList<>();
        LocalDate prev = t.disbursalDate();
        for (int n = 1; n <= t.tenorMonths(); n++) {
            LocalDate due = dueDate(t, n);
            BigDecimal interest = interest(t, t.principal(), prev, due);
            BigDecimal principal = n == t.tenorMonths() ? t.principal() : BigDecimal.ZERO;
            rows.add(new Instalment(n, due, t.dayCount().days(prev, due), t.principal(), interest, principal,
                    principal.add(interest), t.principal().subtract(principal)));
            prev = due;
        }
        return Collections.unmodifiableList(rows);
    }
}
