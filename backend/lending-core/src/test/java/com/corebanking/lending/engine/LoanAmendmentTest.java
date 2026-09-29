package com.corebanking.lending.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.lending.engine.Delinquency.AssetClass;
import com.corebanking.lending.engine.LoanLifecycleTest.Gl;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** P2-3: amendments (rate, tenure, EMI, due day) and restructuring, checked over the whole life against a GL. */
class LoanAmendmentTest {

    static BigDecimal bd(String s) { return new BigDecimal(s); }
    static void eq(String e, BigDecimal a, String what) { assertEquals(0, bd(e).compareTo(a), what + ": expected " + e + " but was " + a); }
    static void eq(BigDecimal e, BigDecimal a, String what) { assertEquals(0, e.compareTo(a), what + ": expected " + e + " but was " + a); }

    static final LocalDate OPEN = LocalDate.of(2026, 6, 30);
    static final LocalDate OPEN_15 = LocalDate.of(2026, 6, 15);

    /** No penal charges, so interest income can be compared with the demands exactly. */
    static LoanAccount.Params params() {
        return new LoanAccount.Params("10010000000017", "HO", "32", "32", bd("18"), null, null, null, null, null, 3,
                BigDecimal.ZERO, null, List.of());
    }

    static LoanAccount book(LocalDate open, Gl gl) {
        var b = LoanAccount.disburse(params(), LoanTerms.equated(bd("100000"), bd("18"), 12, open), open);
        gl.post(b.result().lots());
        return b.account();
    }

    static void eod(LoanAccount a, Gl gl, LocalDate from, LocalDate to) {
        LoanLifecycleTest.runEod(a, gl, from, to);
    }

    /** Pays the next {@code count} instalments on their due dates (as an advance applied at that day's EOD). */
    static LocalDate payOnTime(LoanAccount a, Gl gl, LocalDate from, int count) {
        LocalDate d = from;
        for (int i = 0; i < count && a.status() == LoanAccount.Status.ACTIVE; i++) {
            Instalment next = a.futureSchedule().get(0);
            eod(a, gl, d, next.dueDate().minusDays(1));
            gl.post(a.pay(next.instalment(), next.dueDate(), next.dueDate(), "EMI").lots());
            eod(a, gl, next.dueDate(), next.dueDate());
            assertEquals(0, a.dpd(), "paid on time: no DPD on " + next.dueDate());
            d = next.dueDate().plusDays(1);
        }
        return d;
    }

    static void payToClosure(LoanAccount a, Gl gl, LocalDate from) {
        payOnTime(a, gl, from, 500);
        assertEquals(LoanAccount.Status.CLOSED, a.status(), "loan closed");
    }

    static BigDecimal demandedInterest(LoanAccount a) {
        return a.demands().stream().map(LoanAccount.DemandRow::interestDue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Principal demanded net of principal rescheduled on restructuring (which is demanded again later). */
    static BigDecimal demandedPrincipal(LoanAccount a) {
        return a.demands().stream().map(r -> r.principalDue().subtract(r.principalRescheduled())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Whole-life invariants after closure. */
    static void reconciles(LoanAccount a, Gl gl, BigDecimal capitalised) {
        eq("0", gl.dr("1101"), "principal GL");
        eq("0", gl.dr("1102"), "interest receivable GL");
        eq("0", gl.dr("2305"), "interest suspense GL");
        eq("0", gl.dr("1109").add(gl.dr("5102")), "provision released");
        eq(demandedInterest(a), gl.cr("4101"), "interest income = interest in the demands raised");
        eq(bd("100000").add(capitalised), demandedPrincipal(a), "principal demanded = disbursed + capitalised");
        eq("0", a.suspense(), "suspense");
    }

    static BigDecimal accrualFor(BigDecimal balance, String rate, LocalDate from, LocalDate to) {
        return balance.multiply(bd(rate)).multiply(BigDecimal.valueOf(to.toEpochDay() - from.toEpochDay()))
                .divide(bd("36500"), MathContext.DECIMAL128);
    }

    // ---- rate reset (RBI 18-Aug-2023) -----------------------------------------------------------
    @Test
    void rate_reset_keeping_tenure_changes_the_emi_and_the_next_demand_blends_old_and_new_rate() {
        Gl gl = new Gl();
        LoanAccount a = book(OPEN, gl);
        LocalDate d = payOnTime(a, gl, OPEN, 3);
        LocalDate day = LocalDate.of(2026, 10, 15);
        eod(a, gl, d, day.minusDays(1));
        var before = a.snapshot();
        var amendment = Amendment.rate(bd("21"), Amendment.RateResetOption.KEEP_TENURE_CHANGE_EMI, 24);
        var e = a.previewAmendment(amendment, day);
        assertEquals(before, a.snapshot(), "preview does not change the loan");
        assertEquals(9, e.remainingBefore());
        assertEquals(9, e.remainingAfter());
        eq("9168", e.emiBefore(), "EMI before");
        assertTrue(e.emiAfter().compareTo(bd("9168")) > 0, "higher EMI at the higher rate: " + e.emiAfter());
        assertEquals(e.maturityBefore(), e.maturityAfter());
        assertTrue(e.interestAfter().compareTo(e.interestBefore()) > 0, "more interest");
        BigDecimal balance = e.principal();
        BigDecimal accrued = a.accruedNotDemanded();

        var r = a.amend(amendment, day);
        assertTrue(r.lots().isEmpty(), "an amendment moves no money");
        eq("21", a.ratePercent(), "rate on the account");
        assertEquals(e.scheduleAfter(), a.futureSchedule(), "applied = previewed");
        eq(balance, a.futureSchedule().stream().map(Instalment::principal).reduce(BigDecimal.ZERO, BigDecimal::add), "principal rescheduled");
        eq(a.futureSchedule().get(1).instalment(), e.emiAfter(), "regular instalment is the new EMI");

        // next demand: interest accrued at 18% up to the amendment + 21% from it to the due date, rounded once
        LocalDate due = LocalDate.of(2026, 10, 31);
        eod(a, gl, day, due);
        BigDecimal expected = accrued.add(accrualFor(balance, "21", day, due)).setScale(0, RoundingMode.HALF_UP);
        eq(expected, a.demands().get(3).interestDue(), "blended interest in the next demand");
        eq(e.emiAfter(), a.demands().get(3).principalDue().add(a.demands().get(3).interestDue()), "next demand = new EMI");
        gl.post(a.pay(a.overdueAmount(due.plusDays(1)), due.plusDays(1), due.plusDays(1), "EMI").lots());
        payToClosure(a, gl, due.plusDays(1));
        reconciles(a, gl, BigDecimal.ZERO);
        assertEquals(12, a.demands().size());
    }

    @Test
    void rate_reset_keeping_emi_extends_tenure_only_within_the_product_limit() {
        Gl gl = new Gl();
        LoanAccount a = book(OPEN, gl);
        LocalDate d = payOnTime(a, gl, OPEN, 2);
        LocalDate day = LocalDate.of(2026, 9, 10);
        eod(a, gl, d, day.minusDays(1));
        var tooLong = assertThrows(IllegalArgumentException.class,
                () -> a.previewAmendment(Amendment.rate(bd("30"), Amendment.RateResetOption.KEEP_EMI_CHANGE_TENURE, 12), day));
        assertTrue(tooLong.getMessage().contains("maximum tenure of 12"), tooLong.getMessage());

        var e = a.previewAmendment(Amendment.rate(bd("30"), Amendment.RateResetOption.KEEP_EMI_CHANGE_TENURE, 36), day);
        eq("9168", e.emiAfter(), "EMI kept");
        assertTrue(e.remainingAfter() > 10, "tenure extended to " + e.remainingAfter());
        assertTrue(e.maturityAfter().isAfter(e.maturityBefore()));
        a.amend(Amendment.rate(bd("30"), Amendment.RateResetOption.KEEP_EMI_CHANGE_TENURE, 36), day);
        payToClosure(a, gl, day);
        reconciles(a, gl, BigDecimal.ZERO);
        assertEquals(2 + e.remainingAfter(), a.demands().size(), "instalments as previewed");

        // a rate cut keeping the EMI shortens the tenure
        Gl gl2 = new Gl();
        LoanAccount b = book(OPEN, gl2);
        LocalDate d2 = payOnTime(b, gl2, OPEN, 2);
        eod(b, gl2, d2, day.minusDays(1));
        var cut = b.previewAmendment(Amendment.rate(bd("12"), Amendment.RateResetOption.KEEP_EMI_CHANGE_TENURE, 12), day);
        assertTrue(cut.remainingAfter() <= 10 && !cut.maturityAfter().isAfter(cut.maturityBefore()), "no longer");
        eq("9168", cut.emiAfter(), "EMI kept");
        assertTrue(cut.scheduleAfter().get(cut.remainingAfter() - 1).instalment().compareTo(
                cut.scheduleBefore().get(cut.remainingBefore() - 1).instalment()) < 0, "smaller last instalment");
        assertTrue(cut.interestAfter().compareTo(cut.interestBefore()) < 0, "less interest");
    }

    @Test
    void rate_reset_changing_both_emi_and_tenure() {
        Gl gl = new Gl();
        LoanAccount a = book(OPEN, gl);
        LocalDate d = payOnTime(a, gl, OPEN, 1);
        LocalDate day = LocalDate.of(2026, 8, 20);
        eod(a, gl, d, day.minusDays(1));
        var both = new Amendment(Amendment.Kind.RATE_CHANGE, bd("20"), Amendment.RateResetOption.CHANGE_BOTH, 15, null, null, 24, "reset");
        var e = a.previewAmendment(both, day);
        assertEquals(15, e.remainingAfter());
        assertTrue(e.emiAfter().compareTo(bd("9168")) < 0, "longer tenure, lower EMI: " + e.emiAfter());
        a.amend(both, day);
        payToClosure(a, gl, day);
        reconciles(a, gl, BigDecimal.ZERO);
        assertEquals(16, a.demands().size());
        assertThrows(IllegalArgumentException.class, () -> new Amendment(Amendment.Kind.RATE_CHANGE, bd("20"),
                Amendment.RateResetOption.CHANGE_BOTH, null, null, null, 24, null), "CHANGE_BOTH needs a tenure or an EMI");
    }

    // ---- tenure and EMI ---------------------------------------------------------------------------
    @Test
    void tenure_change_recomputes_the_emi_and_emi_change_recomputes_the_tenure() {
        Gl gl = new Gl();
        LoanAccount a = book(OPEN, gl);
        LocalDate d = payOnTime(a, gl, OPEN, 2);
        LocalDate day = LocalDate.of(2026, 9, 12);
        eod(a, gl, d, day.minusDays(1));
        var t = a.previewAmendment(Amendment.tenure(16, 24), day);
        assertEquals(16, t.remainingAfter());
        assertTrue(t.emiAfter().compareTo(bd("9168")) < 0, "lower EMI");
        assertThrows(IllegalArgumentException.class, () -> a.previewAmendment(Amendment.tenure(30, 24), day), "beyond product maximum");
        assertThrows(IllegalArgumentException.class, () -> a.previewAmendment(Amendment.tenure(10, 24), day), "unchanged tenure");

        var neg = assertThrows(IllegalArgumentException.class, () -> a.previewAmendment(Amendment.emi(bd("1200"), 480), day));
        assertTrue(neg.getMessage().contains("negatively amortise"), neg.getMessage());

        var e = a.previewAmendment(Amendment.emi(bd("15000"), 24), day);
        eq("15000", e.emiAfter(), "EMI as asked");
        assertTrue(e.remainingAfter() < 10, "fewer instalments: " + e.remainingAfter());
        assertTrue(e.interestAfter().compareTo(e.interestBefore()) < 0, "less interest");
        a.amend(Amendment.emi(bd("15000"), 24), day);
        payToClosure(a, gl, day);
        reconciles(a, gl, BigDecimal.ZERO);
    }

    @Test
    void emi_just_above_the_monthly_interest_is_accepted_only_within_the_limit() {
        Gl gl = new Gl();
        LoanAccount a = book(OPEN, gl);
        LocalDate day = LocalDate.of(2026, 7, 10);
        eod(a, gl, OPEN, day.minusDays(1));
        // 1,500.00 monthly interest on 1,00,000 at 18%: 1,500.01 is the floor
        var neg = assertThrows(IllegalArgumentException.class, () -> a.previewAmendment(Amendment.emi(bd("1500"), 480), day));
        assertTrue(neg.getMessage().contains("1500.00"), neg.getMessage());
        // above the 30-day floor but below a 31-day month's interest (1,528.77): refused on the row that would grow
        var month31 = assertThrows(IllegalArgumentException.class, () -> a.previewAmendment(Amendment.emi(bd("1520"), 480), day));
        assertTrue(month31.getMessage().contains("does not cover"), month31.getMessage());
        var slow = a.previewAmendment(Amendment.emi(bd("1600"), 480), day);
        assertTrue(slow.remainingAfter() > 150, "slow but amortising: " + slow.remainingAfter());
        assertThrows(IllegalArgumentException.class, () -> a.previewAmendment(Amendment.emi(bd("1600"), 60), day), "beyond the product maximum");
    }

    // ---- due day ----------------------------------------------------------------------------------
    @Test
    void due_day_change_adds_the_broken_period_interest_to_the_next_instalment() {
        Gl gl = new Gl();
        LoanAccount a = book(OPEN_15, gl);
        LocalDate d = payOnTime(a, gl, OPEN_15, 2);                   // 15-Jul, 15-Aug paid
        LocalDate day = LocalDate.of(2026, 9, 5);
        eod(a, gl, d, day.minusDays(1));
        var e = a.previewAmendment(Amendment.dueDay(25, 24), day);
        assertEquals(LocalDate.of(2026, 9, 15), e.nextDueBefore());
        assertEquals(LocalDate.of(2026, 9, 25), e.nextDueAfter());
        assertEquals(10, e.remainingAfter());
        BigDecimal balance = e.principal();
        BigDecimal tenDays = accrualFor(balance, "18", LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 25));
        assertTrue(e.brokenPeriodInterest().subtract(tenDays).abs().compareTo(BigDecimal.ONE) <= 0,
                "broken period ≈ 10 days' interest " + tenDays + " vs " + e.brokenPeriodInterest());
        eq(e.emiBefore().add(e.brokenPeriodInterest()), e.scheduleAfter().get(0).instalment(), "next instalment = EMI + broken period");
        assertEquals(LocalDate.of(2026, 10, 25), e.scheduleAfter().get(1).dueDate());
        assertEquals(e.maturityBefore().plusDays(10), e.maturityAfter());
        a.amend(Amendment.dueDay(25, 24), day);
        payToClosure(a, gl, day);
        reconciles(a, gl, BigDecimal.ZERO);
        assertTrue(a.demands().stream().skip(2).allMatch(r -> r.dueDate().getDayOfMonth() == 25), "all later dues on the 25th");

        // to an earlier day: the next date moves to that day of the next month
        Gl gl2 = new Gl();
        LoanAccount b = book(OPEN_15, gl2);
        LocalDate d2 = payOnTime(b, gl2, OPEN_15, 2);
        eod(b, gl2, d2, day.minusDays(1));
        var early = b.previewAmendment(Amendment.dueDay(5, 24), day);
        assertEquals(LocalDate.of(2026, 10, 5), early.nextDueAfter());
        var monthEnd = b.previewAmendment(Amendment.dueDay(31, 24), day);
        assertEquals(LocalDate.of(2026, 9, 30), monthEnd.nextDueAfter());
        assertEquals(LocalDate.of(2026, 10, 31), monthEnd.scheduleAfter().get(1).dueDate());
        assertEquals(LocalDate.of(2027, 2, 28), monthEnd.scheduleAfter().get(5).dueDate());
        assertThrows(IllegalArgumentException.class, () -> b.previewAmendment(Amendment.dueDay(15, 24), day), "same day");
    }

    // ---- guards -----------------------------------------------------------------------------------
    @Test
    void borrower_in_arrears_cannot_get_a_longer_tenure_by_amendment() {
        Gl gl = new Gl();
        LoanAccount a = book(OPEN, gl);
        LocalDate day = LocalDate.of(2026, 8, 10);
        eod(a, gl, OPEN, day.minusDays(1));                             // 31-Jul instalment unpaid
        assertTrue(a.dpd() > 0);
        var ex = assertThrows(IllegalStateException.class, () -> a.previewAmendment(Amendment.tenure(20, 24), day));
        assertTrue(ex.getMessage().contains("restructure"), ex.getMessage());
        assertThrows(IllegalStateException.class, () -> a.previewAmendment(Amendment.emi(bd("5000"), 24), day));
        // a market rate reset keeping the tenure is still allowed (the lower EMI follows the lower rate)
        var cut = a.previewAmendment(Amendment.rate(bd("15"), Amendment.RateResetOption.KEEP_TENURE_CHANGE_EMI, 24), day);
        assertTrue(cut.emiAfter().compareTo(cut.emiBefore()) < 0);
        assertThrows(IllegalStateException.class, () -> a.previewAmendment(
                Amendment.rate(bd("24"), Amendment.RateResetOption.KEEP_EMI_CHANGE_TENURE, 24), day), "tenure extension in arrears");
        // demands already raised are untouched by an amendment
        var demandsBefore = a.demands();
        a.amend(Amendment.rate(bd("15"), Amendment.RateResetOption.KEEP_TENURE_CHANGE_EMI, 24), day);
        assertEquals(demandsBefore, a.demands());
    }

    @Test
    void amendment_on_a_due_date_before_day_end_is_refused_and_restore_undoes_it() {
        Gl gl = new Gl();
        LoanAccount a = book(OPEN, gl);
        eod(a, gl, OPEN, LocalDate.of(2026, 7, 30));
        assertThrows(IllegalStateException.class, () -> a.previewAmendment(Amendment.tenure(18, 24), LocalDate.of(2026, 7, 31)));
        eod(a, gl, LocalDate.of(2026, 7, 31), LocalDate.of(2026, 7, 31));
        gl.post(a.pay(bd("9168"), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), "EMI").lots());
        var before = a.snapshot();
        a.amend(Amendment.tenure(18, 24), LocalDate.of(2026, 8, 1));
        assertFalse(before.equals(a.snapshot()));
        a.restore(before);                                               // reversal restores the state before the amendment
        assertEquals(before, a.snapshot());
        eq("18", a.ratePercent(), "rate");
    }

    @Test
    void material_difference_between_proposal_and_approval_is_detected() {
        Gl gl = new Gl();
        LoanAccount a = book(OPEN, gl);
        eod(a, gl, OPEN, LocalDate.of(2026, 7, 9));
        var am = Amendment.tenure(18, 24);
        var proposed = a.previewAmendment(am, LocalDate.of(2026, 7, 10));
        assertFalse(proposed.differsMateriallyFrom(a.previewAmendment(am, LocalDate.of(2026, 7, 10))), "same state, same figures");
        gl.post(a.prepay(bd("20000"), LoanAccount.PrepaymentMode.REDUCE_EMI, LocalDate.of(2026, 7, 10)).lots());
        var atApproval = a.previewAmendment(am, LocalDate.of(2026, 7, 10));
        assertTrue(atApproval.differsMateriallyFrom(proposed), "a prepayment since the proposal changes the EMI");
        assertTrue(atApproval.emiAfter().compareTo(proposed.emiAfter()) < 0);
    }

    // ---- prepayment carry (regression) ----------------------------------------------------------------
    @Test
    void two_prepayments_in_one_period_carry_accrued_interest_once() {
        Gl gl = new Gl();
        LoanAccount a = book(OPEN, gl);
        LocalDate d = payOnTime(a, gl, OPEN, 1);
        eod(a, gl, d, LocalDate.of(2026, 8, 9));
        gl.post(a.prepay(bd("10000"), LoanAccount.PrepaymentMode.REDUCE_EMI, LocalDate.of(2026, 8, 10)).lots());
        eod(a, gl, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 19));
        gl.post(a.prepay(bd("10000"), LoanAccount.PrepaymentMode.REDUCE_EMI, LocalDate.of(2026, 8, 20)).lots());
        eod(a, gl, LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 30));
        BigDecimal accrued = a.accruedNotDemanded();                     // 1-Aug .. 30-Aug nights, across both prepayments
        eod(a, gl, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31));
        BigDecimal demanded = a.demands().get(1).interestDue();
        assertTrue(demanded.subtract(accrued).abs().compareTo(BigDecimal.ONE) < 0, "demand " + demanded + " ≈ accrued " + accrued);
        gl.post(a.pay(a.overdueAmount(LocalDate.of(2026, 9, 1)), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), "EMI").lots());
        payToClosure(a, gl, LocalDate.of(2026, 9, 1));
        eq(demandedInterest(a), gl.cr("4101"), "income = demanded interest");
    }

    // ---- restructure (RBI prudential framework, 7-Jun-2019) ----------------------------------------------
    static List<RestructureTerms> options() {
        return List.of(
                new RestructureTerms(null, 18, 3, RestructureTerms.OverdueInterest.CAPITALISE, 36, "moratorium"),
                new RestructureTerms(bd("16"), 12, 0, RestructureTerms.OverdueInterest.KEEP_AS_ARREARS, 36, "rate cut"),
                new RestructureTerms(null, 24, 0, RestructureTerms.OverdueInterest.CAPITALISE, 36, "longer"));
    }

    /** Two EMIs paid, the third (30-Sep) missed; restructured on 20-Oct at DPD 21 (SMA-0). */
    static LoanAccount stressed(Gl gl, LocalDate day) {
        LoanAccount a = book(OPEN, gl);
        LocalDate d = payOnTime(a, gl, OPEN, 2);
        eod(a, gl, d, day.minusDays(1));
        assertEquals(AssetClass.SMA0, a.assetClass());
        return a;
    }

    @Test
    void simulation_compares_up_to_three_options_without_changing_the_loan() {
        Gl gl = new Gl();
        LocalDate day = LocalDate.of(2026, 10, 20);
        LoanAccount a = stressed(gl, day);
        var before = a.snapshot();
        var sims = a.simulateRestructure(options(), day);
        assertEquals(before, a.snapshot(), "simulation does not change the loan");
        assertEquals(3, sims.size());
        var mor = sims.get(0);
        assertEquals(AssetClass.SMA0, mor.classBefore());
        assertEquals(AssetClass.SUBSTANDARD, mor.classAfter(), "standard account downgraded on restructuring");
        assertTrue(mor.interestCapitalised().signum() > 0);
        eq(mor.principalBefore().add(mor.interestCapitalised()), mor.principalAfter(), "capitalised into principal");
        assertEquals(18, mor.remainingAfter());
        for (int i = 0; i < 3; i++) eq("0", mor.schedule().get(i).principal(), "moratorium row " + i);
        assertEquals(mor.schedule().get(3).dueDate().plusYears(1), mor.specifiedPeriodMinEnd(), "one year from the first principal payment");
        var cut = sims.get(1);
        eq(cut.overdueInterest(), cut.arrearsKept(), "interest kept as arrears");
        eq("0", cut.interestCapitalised(), "nothing capitalised");
        assertTrue(cut.npvLoss().signum() > 0, "a rate cut is a sacrifice at the contract rate: " + cut.npvLoss());
        assertTrue(sims.get(2).emiAfter().compareTo(cut.emiAfter()) < 0, "24 instalments: lower EMI");
        assertThrows(IllegalArgumentException.class, () -> a.simulateRestructure(List.of(options().get(0), options().get(1),
                options().get(2), options().get(0)), day), "at most three options");
        assertThrows(IllegalArgumentException.class, () -> a.simulateRestructure(
                new RestructureTerms(null, 40, 0, RestructureTerms.OverdueInterest.CAPITALISE, 36, null), day), "beyond maximum tenure");
        assertEquals(before, a.snapshot(), "failed simulation leaves the loan unchanged");
    }

    @Test
    void restructured_standard_account_is_npa_until_the_specified_period_ends_then_upgrades_and_reconciles() {
        Gl gl = new Gl();
        LocalDate day = LocalDate.of(2026, 10, 20);
        LoanAccount a = stressed(gl, day);
        var sim = a.simulateRestructure(options().get(0), day);
        var r = a.restructure(options().get(0), day);
        gl.post(r.lots());
        assertEquals(AssetClass.SUBSTANDARD, a.assetClass());
        assertEquals(day, a.npaSince());
        assertEquals(0, a.dpd(), "arrears rescheduled or capitalised");
        assertEquals(sim.schedule(), a.futureSchedule(), "applied = simulated");
        RestructureStatus rs = a.restructureStatus();
        assertNotNull(rs);
        assertEquals(day, rs.restructuredOn());
        assertEquals(AssetClass.SMA0, rs.classBefore());
        assertEquals(LocalDate.of(2028, 1, 31), rs.specifiedPeriodMinEnd());
        var third = a.demands().get(2);
        eq("0", third.principalUnpaid(), "overdue principal rescheduled");
        eq("0", third.interestUnpaid(), "overdue interest capitalised");
        eq(sim.interestCapitalised(), third.interestCapitalised(), "capitalised on the demand");
        eq(a.principalOutstanding(), gl.dr("1101"), "principal GL includes capitalised interest");
        eq(a.accruedNotDemanded(), gl.dr("1102"), "receivable GL = accrued not demanded");
        eq(a.suspense(), gl.cr("2305"), "suspense GL");
        eq(sim.interestCapitalised(), a.capitalisedSuspense(), "capitalised interest held in suspense");

        // performs on time; ages to doubtful at 12 months (IRACP ageing continues) and stays NPA until 31-Jan-2028
        LocalDate d = payOnTime(a, gl, day, 15);                        // up to 31-Dec-2027
        assertEquals(LocalDate.of(2028, 1, 1), d);
        assertEquals(AssetClass.DOUBTFUL1, a.assetClass(), "still NPA inside the specified period");
        assertFalse(a.restructureStatus().defaulted());
        assertTrue(a.capitalisedSuspense().compareTo(sim.interestCapitalised()) < 0, "capitalised interest realised as principal is repaid");
        payOnTime(a, gl, d, 1);                                          // 31-Jan-2028
        assertEquals(AssetClass.STANDARD, a.assetClass(), "upgraded after the specified period");
        assertEquals(LocalDate.of(2028, 1, 31), a.restructureStatus().upgradedOn());
        eq(a.capitalisedSuspense(), a.suspense(), "only unrealised capitalised interest remains in suspense");
        payToClosure(a, gl, LocalDate.of(2028, 2, 1));
        reconciles(a, gl, sim.interestCapitalised());
    }

    @Test
    void restructured_account_that_misses_an_instalment_is_not_upgraded() {
        Gl gl = new Gl();
        LocalDate day = LocalDate.of(2026, 10, 20);
        LoanAccount a = stressed(gl, day);
        BigDecimal capitalised = a.simulateRestructure(options().get(2), day).interestCapitalised();
        gl.post(a.restructure(options().get(2), day).lots());           // 24 instalments, no moratorium
        LocalDate d = payOnTime(a, gl, day, 2);
        Instalment next = a.futureSchedule().get(0);
        eod(a, gl, d, next.dueDate().plusDays(3));                       // three days late
        assertTrue(a.restructureStatus().defaulted(), "default during the specified period");
        gl.post(a.pay(a.overdueAmount(next.dueDate().plusDays(4)), next.dueDate().plusDays(4), next.dueDate().plusDays(4), "late").lots());
        payOnTime(a, gl, next.dueDate().plusDays(4), 16);                // well past one year after the first payment
        assertTrue(a.assetClass().isNpa(), "no upgrade after a default in the specified period: " + a.assetClass());
        payToClosure(a, gl, a.lastAccrualDate().plusDays(1));
        reconciles(a, gl, capitalised);
    }

    @Test
    void npa_keeps_its_class_and_interest_kept_as_arrears_blocks_upgrade() {
        Gl gl = new Gl();
        LoanAccount a = book(OPEN, gl);
        LocalDate day = LocalDate.of(2026, 11, 15);                      // 31-Jul unpaid: NPA from 29-Oct (DPD 91)
        eod(a, gl, OPEN, day.minusDays(1));
        assertEquals(AssetClass.SUBSTANDARD, a.assetClass());
        LocalDate npaSince = a.npaSince();
        BigDecimal suspenseBefore = a.suspense();
        var terms = new RestructureTerms(bd("16"), 12, 0, RestructureTerms.OverdueInterest.KEEP_AS_ARREARS, 36, "NPA");
        var r = a.restructure(terms, day);
        gl.post(r.lots());
        assertEquals(AssetClass.SUBSTANDARD, a.assetClass(), "an NPA keeps its class");
        assertEquals(npaSince, a.npaSince(), "and its NPA date");
        eq(suspenseBefore, a.suspense(), "no second income reversal");
        assertEquals(AssetClass.SUBSTANDARD, a.restructureStatus().classBefore());
        assertTrue(a.dpd() > 0, "interest arrears kept: still overdue");
        eq("16", a.ratePercent(), "new rate");
        BigDecimal arrears = a.overdueAmount(day);
        gl.post(a.pay(arrears, day, day, "arrears").lots());
        assertEquals(AssetClass.SUBSTANDARD, a.assetClass(), "zero arrears, but the specified period has not run");
        eq(a.suspense(), gl.cr("2305"), "suspense GL");
        eq(a.principalOutstanding(), gl.dr("1101"), "principal GL");
        payToClosure(a, gl, day.plusDays(1));
        reconciles(a, gl, BigDecimal.ZERO);
    }

    @Test
    void classifier_and_npv_units() {
        LocalDate npaSince = LocalDate.of(2026, 1, 10);
        assertEquals(AssetClass.SUBSTANDARD, Delinquency.classify(LocalDate.of(2026, 3, 1), 0, AssetClass.SUBSTANDARD, npaSince, false, true).assetClass());
        assertEquals(AssetClass.STANDARD, Delinquency.classify(LocalDate.of(2026, 3, 1), 0, AssetClass.SUBSTANDARD, npaSince, false, false).assetClass());
        eq("108.91", RestructureSimulation.npv(BigDecimal.ZERO, List.of(bd("110")), bd("12")), "one month at 1%");
        eq("210.00", RestructureSimulation.npv(bd("100"), List.of(bd("110")), BigDecimal.ZERO), "zero rate");
        var rs = new RestructureStatus(LocalDate.of(2026, 1, 1), 1, AssetClass.STANDARD, bd("100000"), LocalDate.of(2026, 2, 1),
                LocalDate.of(2027, 2, 1), false, null);
        assertFalse(rs.upgradeAllowed(LocalDate.of(2027, 1, 31), bd("50000")), "before one year");
        assertFalse(rs.upgradeAllowed(LocalDate.of(2027, 2, 1), bd("90000.01")), "less than 10% repaid");
        assertTrue(rs.upgradeAllowed(LocalDate.of(2027, 2, 1), bd("90000")), "both conditions met");
        assertFalse(rs.withDefaulted().upgradeAllowed(LocalDate.of(2030, 1, 1), bd("1")), "defaulted");
        assertTrue(rs.withUpgradedOn(LocalDate.of(2027, 2, 1)).upgradeAllowed(LocalDate.of(2027, 3, 1), bd("90000")));
        assertNull(rs.upgradedOn());
    }
}
