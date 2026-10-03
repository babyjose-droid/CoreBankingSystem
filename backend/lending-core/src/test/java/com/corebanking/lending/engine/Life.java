package com.corebanking.lending.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.ledger.PostingLine;
import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Shared helpers for whole-life scenarios: a mini general ledger built from the lots, and a borrower who pays on time. */
final class Life {

    private Life() {}

    static BigDecimal bd(String s) { return new BigDecimal(s); }

    static void eq(String expected, BigDecimal actual, String what) {
        assertEquals(0, bd(expected).compareTo(actual), what + ": expected " + expected + " but was " + actual);
    }

    static LoanAccount.Params params(String rate, List<FeeRule> fees) {
        return new LoanAccount.Params("10010000000017", "HO", "32", "32", bd(rate), bd("24"), null, null, null, null, 3,
                BigDecimal.ZERO, null, fees);
    }

    /** Net debit balance per GL code from the lots; every lot must balance. */
    static final class Gl {
        final Map<String, BigDecimal> net = new TreeMap<>();

        void post(List<TransactionLot> lots) {
            for (TransactionLot lot : lots) {
                BigDecimal total = BigDecimal.ZERO;
                for (PostingLine l : lot.lines()) {
                    net.merge(l.glCode(), l.signed(), BigDecimal::add);
                    total = total.add(l.signed());
                }
                assertEquals(0, total.signum(), "lot " + lot.type() + " unbalanced");
            }
        }

        BigDecimal dr(String gl) { return net.getOrDefault(gl, BigDecimal.ZERO); }
        BigDecimal cr(String gl) { return dr(gl).negate(); }
    }

    static void runEod(LoanAccount a, Gl gl, LocalDate from, LocalDate to) {
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) gl.post(a.endOfDay(d, Provisioning.starter()).lots());
    }

    /**
     * From {@code from}, each day: pay whatever fell due at the last day-end, then run the day-end; until the loan
     * closes. Returns the day after the last one run.
     */
    static LocalDate payOnTimeUntilClosed(LoanAccount a, Gl gl, LocalDate from) {
        LocalDate d = from;
        for (int guard = 0; a.status() != LoanAccount.Status.CLOSED; guard++, d = d.plusDays(1)) {
            assertTrue(guard < 20000, "the loan never closed");
            d = payDay(a, gl, d);
        }
        return d;
    }

    /** One day of an on-time borrower: pay the dues, then the day-end. Returns the same day. */
    static LocalDate payDay(LoanAccount a, Gl gl, LocalDate d) {
        BigDecimal due = a.overdueAmount(d);
        if (due.signum() > 0) gl.post(a.pay(due, d, d, "Instalment").lots());
        if (a.status() != LoanAccount.Status.CLOSED) gl.post(a.endOfDay(d, Provisioning.starter()).lots());
        return d;
    }

    static void runPaying(LoanAccount a, Gl gl, LocalDate from, LocalDate to) {
        for (LocalDate d = from; !d.isAfter(to) && a.status() != LoanAccount.Status.CLOSED; d = d.plusDays(1)) payDay(a, gl, d);
    }

    /** A closed loan leaves nothing on the loan-side GLs, and the interest income is the interest demanded. */
    static void assertClosedAndReconciled(LoanAccount a, Gl gl, String what) {
        assertEquals(LoanAccount.Status.CLOSED, a.status(), what + ": closed");
        eq("0", gl.dr("1101"), what + ": principal GL");
        eq("0", gl.dr("1102"), what + ": interest receivable GL");
        eq("0", gl.dr("1103"), what + ": fee receivable GL");
        eq("0", gl.dr("1104"), what + ": penal receivable GL");
        eq("0", gl.dr("2302"), what + ": advances GL");
        eq("0", gl.dr("2305"), what + ": suspense GL");
        eq("0", gl.dr("1109").add(gl.dr("5102")), what + ": provision released");
        BigDecimal demanded = a.demands().stream().map(LoanAccount.DemandRow::interestDue).reduce(BigDecimal.ZERO, BigDecimal::add);
        eq(demanded.toPlainString(), gl.cr("4101"), what + ": interest income = interest demanded");
        eq("0", a.principalOutstanding(), what + ": principal outstanding");
    }

    static BigDecimal sum(List<Instalment> rows, java.util.function.Function<Instalment, BigDecimal> f) {
        return rows.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Sum of principal = loan amount, balances chain, and the last row clears the balance. */
    static void assertAmortises(List<Instalment> rows, BigDecimal principal, String what) {
        eq(principal.toPlainString(), sum(rows, Instalment::principal), what + ": principal adds up");
        BigDecimal bal = principal;
        for (Instalment i : rows) {
            eq(bal.toPlainString(), i.openingBalance(), what + ": opening balance of " + i.number());
            eq(i.principal().add(i.interest()).toPlainString(), i.instalment(), what + ": instalment " + i.number());
            assertTrue(i.principal().signum() >= 0 && i.interest().signum() >= 0, what + ": no negative component in " + i.number());
            bal = bal.subtract(i.principal());
            eq(bal.toPlainString(), i.closingBalance(), what + ": closing balance of " + i.number());
        }
        eq("0", bal, what + ": the last instalment clears the balance");
    }

    /** Net present value of dated flows at an effective annual rate (Actual/365): about zero at the XIRR. */
    static double xnpv(LocalDate start, double netOut, List<Instalment> rows, int fromRow, double annualPercent) {
        double v = -netOut;
        for (int i = fromRow; i < rows.size(); i++) {
            double years = ChronoUnit.DAYS.between(start, rows.get(i).dueDate()) / 365.0;
            v += rows.get(i).instalment().doubleValue() / Math.pow(1 + annualPercent / 100, years);
        }
        return v;
    }

    /** Net present value of evenly spaced flows at a nominal annual rate. */
    static double npv(double netOut, List<Instalment> rows, double annualPercent, int periodsPerYear) {
        double v = -netOut;
        double r = annualPercent / 100 / periodsPerYear;
        for (int i = 0; i < rows.size(); i++) v += rows.get(i).instalment().doubleValue() / Math.pow(1 + r, i + 1);
        return v;
    }
}
