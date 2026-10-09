package com.corebanking.lending.engine;

import static com.corebanking.lending.engine.LoanLifecycleTest.bd;
import static com.corebanking.lending.engine.LoanLifecycleTest.eq;
import static com.corebanking.lending.engine.LoanLifecycleTest.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.lending.engine.Delinquency.AssetClass;
import com.corebanking.lending.engine.LoanLifecycleTest.Gl;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One convention for days past due (docs/lending-day-end.md): day-end closes every calendar day before the next
 * business date, the days past due and the class shown are those of the last day closed, a receipt during the open
 * day can only lower them, and a reversal brings back exactly the state before the reversed transaction plus the
 * day-ends that really ran since — never a day day-end has not closed.
 */
class DayEndConventionTest {

    static final LocalDate DISBURSED = LocalDate.of(2026, 7, 2);
    static final LocalDate SATURDAY = LocalDate.of(2026, 11, 21);
    static final LocalDate SUNDAY = LocalDate.of(2026, 11, 22);
    static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);

    static LoanAccount loan() {
        var book = LoanAccount.disburse(params(List.of()), LoanTerms.equated(bd("100000"), bd("18"), 12, DISBURSED), DISBURSED);
        assertEquals(LocalDate.of(2026, 8, 2), book.schedule().get(0).dueDate(), "first instalment due 2 Aug");
        return book.account();
    }

    /** Day-end of a business date as the service runs it: that day and the non-working days (Sundays) after it. */
    static void dayEnd(LoanAccount a, Gl gl, LocalDate businessDate) {
        LocalDate next = businessDate.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SUNDAY) next = next.plusDays(1);
        for (LocalDate d = a.lastAccrualDate().plusDays(1); d.isBefore(next); d = d.plusDays(1)) {
            gl.post(a.endOfDay(d, Provisioning.starter(), businessDate).lots());
        }
    }

    static void dayEndsThrough(LoanAccount a, Gl gl, LocalDate lastBusinessDate) {
        for (LocalDate d = DISBURSED; !d.isAfter(lastBusinessDate); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY) dayEnd(a, gl, d);
        }
    }

    static BigDecimal penal(LoanAccount a) {
        return a.charges().stream().filter(c -> c.kind() == Appropriation.Component.PENAL).map(LoanAccount.ChargeRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void saturdays_day_end_also_closes_sunday_and_a_receipt_on_monday_never_raises_the_days_past_due() {
        Gl gl = new Gl();
        LoanAccount a = loan();
        dayEndsThrough(a, gl, SATURDAY);
        assertEquals(SUNDAY, a.lastAccrualDate(), "Sunday has no day-end run of its own: it is closed with Saturday");
        assertEquals(113, a.dpd(), "2 Aug to 22 Nov, both days counted");
        assertEquals(AssetClass.SUBSTANDARD, a.assetClass());
        BigDecimal penalBefore = penal(a);
        BigDecimal overdueBefore = a.overdueAmount(MONDAY);

        // Monday is open; its day-end has not run. A part payment leaves the oldest instalment unpaid.
        gl.post(a.pay(bd("5000"), MONDAY, MONDAY, "CASH").lots());
        assertEquals(113, a.dpd(), "still as of the last day-end: the open day is not counted before it ends");
        eq(overdueBefore.subtract(bd("5000")).toPlainString(), a.overdueAmount(MONDAY), "overdue less the receipt");
        LoanAccount.Snapshot beforeFullPayment = a.snapshot();
        Gl glBefore = new Gl();
        glBefore.net.putAll(gl.net);

        // all arrears paid: nothing past due, the NPA is upgraded
        var full = a.pay(a.overdueAmount(MONDAY), MONDAY, MONDAY, "CASH");
        gl.post(full.lots());
        assertEquals(0, a.dpd());
        assertEquals(AssetClass.STANDARD, a.assetClass());
        eq("0", a.overdueAmount(MONDAY), "nothing overdue");

        // that payment is reversed (as the service does it): its lots mirrored, the state before it restored, and the
        // day-ends since then replayed - none, because the payment was made today
        gl.post(full.lots().stream().map(l -> com.corebanking.ledger.TransactionLot.reversal(l, MONDAY, "CLAUDE-TEST reversal")).toList());
        LocalDate lastDayEnd = a.lastAccrualDate();
        a.restore(beforeFullPayment);
        List<com.corebanking.ledger.TransactionLot> replayed = a.replayDayEnds(lastDayEnd, MONDAY, Provisioning.starter());
        assertTrue(replayed.isEmpty(), "no day is processed early");
        assertEquals(beforeFullPayment, a.snapshot(), "days past due, overdue amount, penal charges: all as before the payment");
        assertEquals(113, a.dpd());
        eq(penalBefore.toPlainString(), penal(a), "no penal charge added by the payment or its reversal");
        assertEquals(glBefore.net.keySet(), gl.net.keySet());
        for (String head : glBefore.net.keySet()) eq(glBefore.net.get(head).toPlainString(), gl.net.get(head), "GL " + head);

        // Monday's own day-end then counts Monday, once
        dayEnd(a, gl, MONDAY);
        assertEquals(MONDAY, a.lastAccrualDate());
        assertEquals(114, a.dpd());
        assertTrue(penal(a).compareTo(penalBefore) > 0, "Monday's penal charge");
    }

    @Test
    void a_receipt_that_clears_the_oldest_instalment_lowers_the_days_past_due_to_the_next_one() {
        Gl gl = new Gl();
        LoanAccount a = loan();
        dayEndsThrough(a, gl, SATURDAY);
        LoanAccount.DemandRow oldest = a.demands().get(0);
        BigDecimal charges = a.charges().stream().map(LoanAccount.ChargeRow::unpaid).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(charges.signum() > 0);
        // BY_DEMAND, interest then principal: exactly the first instalment
        gl.post(a.pay(oldest.principalDue().add(oldest.interestDue()), MONDAY, MONDAY, "CASH").lots());
        assertEquals(82, a.dpd(), "2 Sep to 22 Nov: the next unpaid instalment, as of the last day-end");
        assertEquals(AssetClass.SUBSTANDARD, a.assetClass(), "an NPA is upgraded only when all arrears are paid");
    }

    @Test
    void a_payment_reversed_after_a_weekend_leaves_the_loan_as_if_it_had_never_been_made() {
        // paid on Saturday 21 Nov; Saturday's day-end (closing Saturday and Sunday) runs; reversed on Monday 23 Nov
        Gl gl = new Gl();
        LoanAccount a = loan();
        dayEndsThrough(a, gl, LocalDate.of(2026, 11, 20));
        LoanAccount twin = LoanAccount.restore(params(List.of()), a.snapshot());
        Gl twinGl = new Gl();
        twinGl.net.putAll(gl.net);

        LoanAccount.Snapshot beforePayment = a.snapshot();
        var paid = a.pay(a.overdueAmount(SATURDAY), SATURDAY, SATURDAY, "CASH");
        gl.post(paid.lots());
        assertEquals(0, a.dpd(), "all arrears paid");
        dayEnd(a, gl, SATURDAY);
        dayEnd(twin, twinGl, SATURDAY);
        assertEquals(SUNDAY, a.lastAccrualDate());
        assertTrue(penal(twin).compareTo(penal(a)) > 0, "the payment saved Saturday's and Sunday's penal charges");

        // reversal on Monday, as the service does it: every lot since the payment mirrored, the state before the
        // payment restored, and the day-ends that ran since (Saturday, Sunday) replayed into Monday's books
        List<com.corebanking.ledger.TransactionLot> since = new java.util.ArrayList<>(paid.lots());
        LocalDate lastDayEnd = a.lastAccrualDate();
        // the day-end lots after the payment are part of what is reversed: rebuild them from a copy of the account
        LoanAccount copy = LoanAccount.restore(params(List.of()), beforePayment);
        copy.pay(paidAmount(paid), SATURDAY, SATURDAY, "CASH");
        since.addAll(copy.endOfDay(SATURDAY, Provisioning.starter(), SATURDAY).lots());
        since.addAll(copy.endOfDay(SUNDAY, Provisioning.starter(), SATURDAY).lots());
        gl.post(since.stream().map(l -> com.corebanking.ledger.TransactionLot.reversal(l, MONDAY, "CLAUDE-TEST reversal")).toList());
        a.restore(beforePayment);
        gl.post(a.replayDayEnds(lastDayEnd, MONDAY, Provisioning.starter()));
        assertEquals(twin.snapshot(), a.snapshot(), "days past due, class, penal, accrual, overdue: as if never paid");
        for (String head : twinGl.net.keySet()) eq(twinGl.net.get(head).toPlainString(), gl.net.get(head), "GL " + head);
        // and Monday's day-end continues from Sunday, once
        dayEnd(a, gl, MONDAY);
        dayEnd(twin, twinGl, MONDAY);
        assertEquals(twin.snapshot(), a.snapshot());
    }

    private static BigDecimal paidAmount(LoanAccount.Result paid) {
        return paid.lots().get(0).lines().stream().filter(l -> l.side() == com.corebanking.ledger.PostingLine.Side.DR)
                .map(com.corebanking.ledger.PostingLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void a_reversal_never_processes_a_day_that_day_end_has_not_closed() {
        // A loan whose last day-end closed Saturday only (before day-end closed the following non-working days):
        Gl gl = new Gl();
        LoanAccount a = loan();
        for (LocalDate d = DISBURSED; !d.isAfter(SATURDAY); d = d.plusDays(1)) gl.post(a.endOfDay(d, Provisioning.starter()).lots());
        assertEquals(SATURDAY, a.lastAccrualDate());
        assertEquals(112, a.dpd());
        LoanAccount twin = LoanAccount.restore(params(List.of()), a.snapshot());
        Gl twinGl = new Gl();
        twinGl.net.putAll(gl.net);

        // a receipt on Monday does not move the days past due forward
        LoanAccount.Snapshot before = a.snapshot();
        var paid = a.pay(bd("5000"), MONDAY, MONDAY, "CASH");
        gl.post(paid.lots());
        assertEquals(112, a.dpd());
        // its reversal replays nothing: Sunday is left to the next regular day-end, with no early penal charge
        LocalDate lastDayEnd = a.lastAccrualDate();
        gl.post(paid.lots().stream().map(l -> com.corebanking.ledger.TransactionLot.reversal(l, MONDAY, "CLAUDE-TEST reversal")).toList());
        a.restore(before);
        assertTrue(a.replayDayEnds(lastDayEnd, MONDAY, Provisioning.starter()).isEmpty());
        assertEquals(before, a.snapshot());
        // Monday's day-end catches Sunday up and closes Monday, as it does for the twin that was never paid
        dayEnd(a, gl, MONDAY);
        dayEnd(twin, twinGl, MONDAY);
        assertEquals(twin.snapshot(), a.snapshot(), "same state: nothing posted twice, nothing skipped");
        for (String head : twinGl.net.keySet()) eq(twinGl.net.get(head).toPlainString(), gl.net.get(head), "GL " + head);
        assertEquals(MONDAY, a.lastAccrualDate());
        assertEquals(114, a.dpd());
    }

    @Test
    void a_loan_closed_with_nothing_outstanding_is_not_past_due_and_is_standard() {
        // pre-closed while 22 days past due (SMA-0)
        Gl gl = new Gl();
        LoanAccount a = loan();
        dayEndsThrough(a, gl, LocalDate.of(2026, 8, 22));
        assertEquals(22, a.dpd(), "2 Aug to 23 Aug (Sunday, closed with Saturday 22 Aug)");
        assertEquals(AssetClass.SMA0, a.assetClass());
        LocalDate day = LocalDate.of(2026, 8, 24);
        gl.post(a.preclose(a.preclosureQuote(day).total(), day).lots());
        assertEquals(LoanAccount.Status.CLOSED, a.status());
        assertEquals(0, a.dpd());
        assertEquals(AssetClass.STANDARD, a.assetClass(), "an SMA class only describes days past due");
        assertNull(a.npaSince());

        // pre-closed while NPA: nothing past due, but the class at closure is kept with its NPA date
        Gl gl3 = new Gl();
        LoanAccount n = loan();
        dayEndsThrough(n, gl3, SATURDAY);
        assertEquals(AssetClass.SUBSTANDARD, n.assetClass());
        LocalDate since = n.npaSince();
        gl3.post(n.preclose(n.preclosureQuote(MONDAY).total(), MONDAY).lots());
        assertEquals(LoanAccount.Status.CLOSED, n.status());
        assertEquals(0, n.dpd());
        assertEquals(AssetClass.SUBSTANDARD, n.assetClass());
        assertEquals(since, n.npaSince());

        // an NPA whose receipt pays all arrears is upgraded first (RBI: on payment of the entire arrears), then closed
        Gl gl2 = new Gl();
        LoanAccount npa = loan();
        dayEndsThrough(npa, gl2, LocalDate.of(2027, 7, 3));           // past the last instalment, nothing ever paid
        assertTrue(npa.assetClass().isNpa());
        LocalDate payDay = LocalDate.of(2027, 7, 5);
        gl2.post(npa.pay(npa.preclosureQuote(payDay).total(), payDay, payDay, "CASH").lots());
        assertEquals(LoanAccount.Status.CLOSED, npa.status());
        assertEquals(0, npa.dpd());
        assertEquals(AssetClass.STANDARD, npa.assetClass());
        assertNull(npa.npaSince());
        eq("-100000", gl2.dr("1101"), "all principal repaid (the disbursement itself is not posted to this test ledger)");
        eq("0", gl2.dr("2305"), "no interest left in suspense");

        // cancelled in the cooling-off period
        LoanAccount c = loan();
        c.endOfDay(DISBURSED, Provisioning.starter());
        c.cancel(c.cancellationAmount(), DISBURSED.plusDays(1));
        assertEquals(LoanAccount.Status.CANCELLED, c.status());
        assertEquals(0, c.dpd());
        assertEquals(AssetClass.STANDARD, c.assetClass());
    }
}
