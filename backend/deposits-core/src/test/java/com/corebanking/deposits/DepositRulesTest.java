package com.corebanking.deposits;

import static com.corebanking.deposits.Fixtures.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import com.corebanking.deposits.DepositRateTable.AddOn;
import com.corebanking.deposits.DepositRateTable.Cell;
import com.corebanking.deposits.DepositRules.Kind;
import com.corebanking.deposits.DepositRules.Violation;
import com.corebanking.deposits.PrematureWithdrawal.Quote;
import com.corebanking.deposits.PrematureWithdrawal.Reason;
import com.corebanking.deposits.PrematureWithdrawal.Request;
import com.corebanking.deposits.TdsOnInterest.Payee;
import com.corebanking.deposits.TdsOnInterest.YearToDate;
import com.corebanking.deposits.TermDeposit.Terms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Rule sets by institution type, the rate card, premature withdrawal and tax deducted at source. */
class DepositRulesTest {

    static final DepositRules BANK = Fixtures.bank();
    static final DepositRules NBFC = Fixtures.nbfc();
    static final LocalDate APR1 = LocalDate.of(2026, 4, 1);

    static void eq(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    static List<String> rulesOf(List<Violation> v) {
        return v.stream().map(Violation::rule).toList();
    }

    // ---- rule sets -------------------------------------------------------------------------------------------

    @Test void rule_set_by_entity_type() {
        assertEquals(Optional.of(Kind.BANK), Kind.forEntityType("BANK"));
        assertEquals(Optional.of(Kind.BANK), Kind.forEntityType("SFB"));
        assertEquals(Optional.of(Kind.BANK), Kind.forEntityType("COOP_BANK"));
        assertEquals(Optional.of(Kind.NBFC_DEPOSIT), Kind.forEntityType("NBFC"));
        assertEquals(Optional.empty(), Kind.forEntityType("HFC"));
        assertEquals(Optional.empty(), Kind.forEntityType("MFI"));
        assertEquals(Optional.empty(), Kind.forEntityType(null));
    }

    @Test void an_nbfc_cannot_take_demand_deposits() {
        assertTrue(BANK.checkDemandDeposit().isEmpty());
        assertEquals(List.of(DepositRules.DEMAND_DEPOSITS_ALLOWED), rulesOf(NBFC.checkDemandDeposit()));
        assertFalse(new DepositRules(Kind.BANK, Map.of()).demandDepositsAllowed(), "absent means not allowed");
    }

    @Test void nbfc_term_deposit_limits() {
        assertTrue(NBFC.checkTermDeposit(Tenure.ofMonths(12), bd("12.5"), 1, 1).isEmpty());
        assertTrue(NBFC.checkTermDeposit(Tenure.ofMonths(60), bd("9"), 0, 0).isEmpty());
        assertEquals(List.of(DepositRules.TD_MIN_TENURE_MONTHS), rulesOf(NBFC.checkTermDeposit(Tenure.ofMonths(6), bd("9"), 3, 1)));
        assertEquals(List.of(DepositRules.TD_MIN_TENURE_MONTHS), rulesOf(NBFC.checkTermDeposit(new Tenure(11, 29), bd("9"), 3, 1)));
        assertEquals(List.of(DepositRules.TD_MAX_TENURE_MONTHS), rulesOf(NBFC.checkTermDeposit(new Tenure(60, 1), bd("9"), 3, 1)));
        assertEquals(List.of(DepositRules.TD_MAX_RATE), rulesOf(NBFC.checkTermDeposit(Tenure.ofMonths(24), bd("12.51"), 3, 1)));
        assertEquals(List.of(DepositRules.NOMINEES_MAX), rulesOf(NBFC.checkTermDeposit(Tenure.ofMonths(24), bd("9"), 3, 2)));
        assertEquals(3, NBFC.checkTermDeposit(Tenure.ofDays(180), bd("13"), 3, 3).size(), "tenure, rate and nominees are all reported at once");
    }

    @Test void a_bank_term_deposit_is_limited_by_its_product_not_by_these_rules() {
        assertTrue(BANK.checkTermDeposit(Tenure.ofDays(7), bd("3"), 0, 4).isEmpty());
        assertTrue(BANK.checkTermDeposit(Tenure.ofMonths(120), bd("14"), 3, 4).isEmpty());
        assertEquals(List.of(DepositRules.NOMINEES_MAX), rulesOf(BANK.checkTermDeposit(Tenure.ofMonths(12), bd("7"), 3, 5)));
    }

    @Test void a_missing_rule_the_engine_needs_is_an_error_that_names_it() {
        var e = assertThrows(IllegalStateException.class, () -> new DepositRules(Kind.BANK, Map.of()).required(DepositRules.TDS_RATE));
        assertTrue(e.getMessage().contains(DepositRules.TDS_RATE));
    }

    // ---- rate card -------------------------------------------------------------------------------------------

    static DepositRateTable card() {
        return new DepositRateTable(List.of(
                new Cell(bd("0"), Tenure.ofMonths(12), bd("7.00")), new Cell(bd("0"), Tenure.ofMonths(24), bd("7.50")),
                new Cell(bd("0"), Tenure.ofMonths(36), bd("7.75")),
                new Cell(bd("500000"), Tenure.ofMonths(12), bd("7.25")), new Cell(bd("500000"), Tenure.ofMonths(24), bd("7.75")),
                new Cell(bd("500000"), Tenure.ofMonths(36), bd("8.00"))),
                List.of(new AddOn("SENIOR_CITIZEN", bd("0.50")), new AddOn("STAFF", bd("1.00"))));
    }

    @Test void the_rate_is_the_cell_plus_named_add_ons() {
        var r = card().resolve(bd("100000"), APR1, APR1.plusMonths(24), Set.of());
        eq("7.50", r.ratePercent());
        assertEquals(Tenure.ofMonths(24), r.fromTenure());
        eq("7.50", card().resolve(bd("499999.99"), APR1, APR1.plusMonths(35), Set.of()).ratePercent());   // still the 24-month step
        eq("7.75", card().resolve(bd("500000"), APR1, APR1.plusMonths(35), Set.of()).ratePercent());
        eq("8.00", card().resolve(bd("9000000"), APR1, APR1.plusMonths(60), Set.of()).ratePercent());      // last step runs on
        var senior = card().resolve(bd("100000"), APR1, APR1.plusMonths(12), Set.of("SENIOR_CITIZEN", "STAFF"));
        eq("7.00", senior.cardRatePercent());
        eq("8.50", senior.ratePercent());
        assertEquals(2, senior.addOns().size());
        eq("7.00", card().minimumRate());
    }

    @Test void twelve_months_is_a_year_in_a_leap_year_too() {
        LocalDate start = LocalDate.of(2027, 3, 1);                       // 366 days to 1-Mar-2028
        eq("7.00", card().resolve(bd("1000"), start, start.plusMonths(12), Set.of()).ratePercent());
        assertThrows(IllegalArgumentException.class, () -> card().resolve(bd("1000"), start, start.plusMonths(12).minusDays(1), Set.of()));
    }

    @Test void a_card_must_cover_everything_its_product_accepts() {
        assertTrue(card().coverageProblems(bd("1000"), Tenure.ofMonths(12)).isEmpty());
        assertEquals(1, card().coverageProblems(bd("1000"), Tenure.ofMonths(6)).size(), "6-month deposits have no rate");
        var holed = new DepositRateTable(List.of(
                new Cell(bd("10000"), Tenure.ofMonths(12), bd("7")), new Cell(bd("10000"), Tenure.ofMonths(24), bd("7.5")),
                new Cell(bd("500000"), Tenure.ofMonths(12), bd("7.25"))), List.of());
        List<String> problems = holed.coverageProblems(bd("1000"), Tenure.ofMonths(12));
        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("below 10000"));
        assertTrue(problems.get(1).contains("500000") && problems.get(1).contains("24 months"));
        assertEquals(1, new DepositRateTable(List.of(), List.of()).coverageProblems(bd("1"), Tenure.ofDays(7)).size());
    }

    @Test void a_card_is_validated() {
        assertThrows(IllegalArgumentException.class, () -> new DepositRateTable(List.of(
                new Cell(bd("0"), Tenure.ofMonths(12), bd("7")), new Cell(bd("0.00"), Tenure.ofMonths(12), bd("8"))), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DepositRateTable(List.of(
                new Cell(bd("0"), Tenure.ofMonths(1), bd("7")), new Cell(bd("0"), Tenure.ofDays(31), bd("8"))), List.of()));
        assertThrows(IllegalArgumentException.class, () -> card().resolve(bd("1000"), APR1, APR1.plusMonths(12), Set.of("PLATINUM")));
        assertThrows(IllegalArgumentException.class, () -> new Cell(bd("0"), Tenure.ofMonths(1), bd("-0.1")));
    }

    // ---- premature withdrawal: deposit-taking NBFC -----------------------------------------------------------

    static Terms nbfcDeposit() {      // Rs 1,00,000 at 9%, 36 months, interest compounded yearly
        return new Terms(bd("100000"), bd("9"), APR1, APR1.plusMonths(36), 12, true, DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP);
    }

    static Request nbfc(LocalDate on, Reason reason, boolean individual, String allDeposits, String periodRate) {
        return new Request(nbfcDeposit(), on, reason, individual, bd(allDeposits), periodRate == null ? null : bd(periodRate),
                bd("7.5"), BigDecimal.ZERO, 0, false, BigDecimal.ZERO);
    }

    @Test void nbfc_nothing_in_the_first_three_months_on_request() {
        Quote q = PrematureWithdrawal.quote(NBFC, nbfc(LocalDate.of(2026, 6, 30), Reason.DEPOSITOR_REQUEST, true, "100000", null));
        assertFalse(q.allowed());
        assertTrue(q.refusal().contains("3 months"));
        assertNull(q.netPayable());
    }

    @Test void nbfc_death_within_three_months_repays_the_principal() {
        Quote q = PrematureWithdrawal.quote(NBFC, nbfc(LocalDate.of(2026, 5, 10), Reason.DEATH, true, "100000", null));
        assertTrue(q.allowed());
        eq("100000", q.principalPayable());
        eq("0", q.interestDue());
        eq("100000", q.netPayable());
    }

    @Test void nbfc_emergency_within_three_months() {
        Quote half = PrematureWithdrawal.quote(NBFC, nbfc(LocalDate.of(2026, 5, 10), Reason.EMERGENCY_EXPENSE, true, "100000", null));
        eq("50000", half.principalPayable());
        eq("50000", half.principalRemaining());
        eq("0", half.interestDue());
        Quote tiny = PrematureWithdrawal.quote(NBFC, new Request(
                new Terms(bd("8000"), bd("9"), APR1, APR1.plusMonths(12), 12, true, DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP),
                LocalDate.of(2026, 5, 10), Reason.EMERGENCY_EXPENSE, true, bd("10000"), null, bd("7.5"), BigDecimal.ZERO, 0, false, BigDecimal.ZERO));
        eq("8000", tiny.principalPayable());
        eq("0", tiny.principalRemaining());
        Quote capped = PrematureWithdrawal.quote(NBFC, new Request(
                new Terms(bd("2000000"), bd("9"), APR1, APR1.plusMonths(12), 12, true, DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP),
                LocalDate.of(2026, 5, 10), Reason.EMERGENCY_EXPENSE, true, bd("2000000"), null, bd("7.5"), BigDecimal.ZERO, 0, false, BigDecimal.ZERO));
        eq("500000", capped.principalPayable());                       // 50% is 10 lakh; the cap is 5 lakh
        eq("1500000", capped.principalRemaining());
        Quote ill = PrematureWithdrawal.quote(NBFC, nbfc(LocalDate.of(2026, 5, 10), Reason.CRITICAL_ILLNESS, true, "100000", null));
        eq("100000", ill.principalPayable());
        Quote company = PrematureWithdrawal.quote(NBFC, nbfc(LocalDate.of(2026, 5, 10), Reason.EMERGENCY_EXPENSE, false, "100000", null));
        assertFalse(company.allowed());
    }

    @Test void nbfc_three_to_six_months_no_interest() {
        Quote first = PrematureWithdrawal.quote(NBFC, nbfc(LocalDate.of(2026, 7, 1), Reason.DEPOSITOR_REQUEST, true, "100000", "8"));
        assertTrue(first.allowed());
        eq("0", first.ratePercent());
        eq("100000", first.netPayable());
        Quote last = PrematureWithdrawal.quote(NBFC, nbfc(LocalDate.of(2026, 9, 30), Reason.DEPOSITOR_REQUEST, true, "100000", "8"));
        eq("0", last.interestDue());
    }

    @Test void nbfc_after_six_months_two_points_below_the_rate_for_the_period_run() {
        Quote q = PrematureWithdrawal.quote(NBFC, nbfc(LocalDate.of(2027, 2, 1), Reason.DEPOSITOR_REQUEST, true, "100000", "8"));
        eq("6", q.ratePercent());
        eq("5030.14", q.interestDue());                // 1,00,000 x 6% x 306 / 365
        eq("105030.14", q.netPayable());
        Quote onTheDay = PrematureWithdrawal.quote(NBFC, nbfc(LocalDate.of(2026, 10, 1), Reason.DEPOSITOR_REQUEST, true, "100000", "8"));
        eq("6", onTheDay.ratePercent());
    }

    @Test void nbfc_no_rate_for_the_period_run_three_points_below_the_lowest_rate() {
        Quote q = PrematureWithdrawal.quote(NBFC, nbfc(LocalDate.of(2027, 2, 1), Reason.DEPOSITOR_REQUEST, true, "100000", null));
        eq("4.5", q.ratePercent());
        eq("3772.60", q.interestDue());                // 1,00,000 x 4.5% x 306 / 365
        Quote death = PrematureWithdrawal.quote(NBFC, nbfc(LocalDate.of(2027, 2, 1), Reason.DEATH, true, "100000", null));
        eq("4.5", death.ratePercent());                // para 40 applies on death too
    }

    // ---- premature withdrawal: bank --------------------------------------------------------------------------

    static Request bank(LocalDate on, Reason reason, String periodRate, int minimumDays, String paidOut) {
        Terms t = new Terms(bd("200000"), bd("7.25"), APR1, APR1.plusMonths(24), 3, false, DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP);
        return new Request(t, on, reason, true, bd("200000"), periodRate == null ? null : bd(periodRate), bd("3"),
                bd("1"), minimumDays, true, bd(paidOut));
    }

    @Test void bank_rate_for_the_period_run_less_penalty_and_excess_interest_recovered() {
        // Two quarters of 3625.00 were paid at 7.25%. For 263 days the card rate was 6.5%; less 1 point = 5.5%.
        Quote q = PrematureWithdrawal.quote(BANK, bank(LocalDate.of(2026, 12, 20), Reason.DEPOSITOR_REQUEST, "6.5", 7, "7250.00"));
        eq("5.5", q.ratePercent());
        eq("7910.96", q.interestDue());                // 2750.00 + 2750.00 + 2,00,000 x 5.5% x 80 / 365
        eq("0", q.interestRecoverable());
        eq("200660.96", q.netPayable());               // 2,00,000 + 7910.96 - 7250.00
    }

    @Test void bank_recovers_interest_paid_above_what_is_due() {
        Quote q = PrematureWithdrawal.quote(BANK, bank(LocalDate.of(2026, 10, 5), Reason.DEPOSITOR_REQUEST, "4", 7, "7250.00"));
        eq("3", q.ratePercent());
        // 1500.00 + 1500.00 + 2,00,000 x 3% x 4 / 365 = 3065.75 due; 7250.00 was paid.
        eq("3065.75", q.interestDue());
        eq("4184.25", q.interestRecoverable());
        eq("195815.75", q.netPayable());
    }

    @Test void bank_contract_rate_caps_the_period_rate_and_death_waives_the_penalty() {
        Quote capped = PrematureWithdrawal.quote(BANK, bank(LocalDate.of(2026, 12, 20), Reason.DEPOSITOR_REQUEST, "9", 7, "0"));
        eq("6.25", capped.ratePercent());              // not 9 - 1: never above the contracted 7.25
        Quote death = PrematureWithdrawal.quote(BANK, bank(LocalDate.of(2026, 12, 20), Reason.DEATH, "6.5", 7, "0"));
        eq("6.5", death.ratePercent());
        Quote noCard = PrematureWithdrawal.quote(BANK, bank(LocalDate.of(2026, 12, 20), Reason.DEPOSITOR_REQUEST, null, 7, "0"));
        eq("6.25", noCard.ratePercent());
    }

    @Test void bank_no_interest_below_the_minimum_period_and_no_lock_in() {
        Quote q = PrematureWithdrawal.quote(BANK, bank(LocalDate.of(2026, 4, 6), Reason.DEPOSITOR_REQUEST, "3", 7, "0"));
        assertTrue(q.allowed());
        eq("0", q.interestDue());
        eq("200000", q.netPayable());
        Quote day7 = PrematureWithdrawal.quote(BANK, bank(LocalDate.of(2026, 4, 8), Reason.DEPOSITOR_REQUEST, "3", 7, "0"));
        eq("2", day7.ratePercent());
    }

    @Test void a_deposit_cannot_be_closed_early_on_or_after_maturity() {
        assertFalse(PrematureWithdrawal.quote(BANK, bank(APR1.plusMonths(24), Reason.DEPOSITOR_REQUEST, "6", 7, "0")).allowed());
        assertFalse(PrematureWithdrawal.quote(BANK, bank(APR1, Reason.DEPOSITOR_REQUEST, "6", 7, "0")).allowed());
    }

    // ---- tax deducted at source ------------------------------------------------------------------------------

    static final Payee RESIDENT = new Payee(false, true, false, false);
    static final Payee SENIOR = new Payee(true, true, false, false);

    static BigDecimal tds(DepositRules rules, Payee p, String interestBefore, String taxBefore, String now) {
        return TdsOnInterest.deduct(rules, p, new YearToDate(bd(interestBefore), bd(taxBefore)), bd(now), Rounding.RUPEE_HALF_UP);
    }

    @Test void no_tax_up_to_the_threshold() {
        eq("0", tds(BANK, RESIDENT, "45000", "0", "4000"));
        eq("0", tds(BANK, RESIDENT, "45000", "0", "5000"));            // exactly 50,000: not above it
        eq("0", tds(BANK, SENIOR, "90000", "0", "10000"));
        eq("0", tds(NBFC, RESIDENT, "6000", "0", "4000"));
    }

    @Test void crossing_the_threshold_taxes_the_whole_year() {
        eq("5100", tds(BANK, RESIDENT, "45000", "0", "6000"));         // 10% of 51,000
        eq("600", tds(BANK, RESIDENT, "51000", "5100", "6000"));       // 10% of 57,000 less 5,100 already deducted
        eq("10001", tds(BANK, SENIOR, "90000", "0", "10010"));         // 10% of 1,00,010
        eq("1001", tds(NBFC, RESIDENT, "6000", "0", "4010"));          // other payers: threshold 10,000
        eq("1001", tds(NBFC, SENIOR, "6000", "0", "4010"));            // no higher threshold for seniors there
    }

    @Test void tax_never_exceeds_the_interest_and_is_caught_up_later() {
        eq("3000", tds(BANK, RESIDENT, "48000", "0", "3000"));         // 5,100 due, only 3,000 to take it from
        eq("2000", tds(BANK, RESIDENT, "51000", "3000", "2000"));      // 5,300 due, 3,000 taken, 2,000 available
        eq("900", tds(BANK, RESIDENT, "53000", "5000", "6000"));       // 5,900 due, 5,000 taken
    }

    @Test void declaration_exemption_and_missing_pan() {
        eq("0", tds(BANK, new Payee(false, true, true, false), "45000", "0", "60000"));     // Form 121 on file
        eq("0", tds(BANK, new Payee(false, true, false, true), "45000", "0", "60000"));     // exempt payee
        eq("12000", tds(BANK, new Payee(false, false, false, false), "0", "0", "60000"));   // no PAN: 20%
        eq("12000", tds(BANK, new Payee(false, false, true, false), "0", "0", "60000"));    // a declaration without PAN is ignored
        eq("0", tds(BANK, new Payee(false, false, false, false), "0", "0", "50000"));       // the threshold still applies
        eq("0", tds(BANK, RESIDENT, "60000", "6000", "0"));
    }

    @Test void tax_year_and_senior_citizen() {
        assertEquals(2026, TdsOnInterest.taxYearStarting(LocalDate.of(2027, 3, 31)));
        assertEquals(2027, TdsOnInterest.taxYearStarting(LocalDate.of(2027, 4, 1)));
        assertTrue(TdsOnInterest.seniorInTaxYear(LocalDate.of(1967, 3, 31), LocalDate.of(2026, 4, 1), 60), "turns 60 on the last day of the tax year");
        assertFalse(TdsOnInterest.seniorInTaxYear(LocalDate.of(1967, 4, 1), LocalDate.of(2026, 4, 1), 60));
        assertTrue(TdsOnInterest.seniorInTaxYear(LocalDate.of(1967, 4, 1), LocalDate.of(2027, 4, 1), 60));
    }
}
