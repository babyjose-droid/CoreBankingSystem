package com.corebanking.deposits;

import static com.corebanking.deposits.Fixtures.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import com.corebanking.deposits.AccountHolders.OperatingMode;
import com.corebanking.deposits.DepositRateTable.AddOn;
import com.corebanking.deposits.DepositRateTable.Cell;
import com.corebanking.deposits.Nomination.Mode;
import com.corebanking.deposits.Nomination.Nominee;
import com.corebanking.deposits.SavingsInterest.Band;
import com.corebanking.deposits.SavingsInterest.BandMode;
import com.corebanking.deposits.TermDepositBooking.Quote;
import com.corebanking.deposits.TermDepositBooking.Request;
import com.corebanking.deposits.TermDepositProduct.Settings;
import com.corebanking.deposits.TermDepositProduct.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Deposit products (what may be approved), bookings with their figures, account holders and nominations (P4-1). */
class DepositProductsAndBookingTest {

    static final DepositRules BANK = Fixtures.bank();
    static final DepositRules NBFC = Fixtures.nbfc();
    static final LocalDate JUL1 = LocalDate.of(2026, 7, 1);
    static final LocalDate BORN_1990 = LocalDate.of(1990, 5, 20);
    static final LocalDate BORN_1960 = LocalDate.of(1960, 5, 20);

    static void eq(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    static void has(List<String> problems, String part) {
        assertTrue(problems.stream().anyMatch(p -> p.contains(part)), "no problem containing '" + part + "' in " + problems);
    }

    static Cell cell(String amount, int months, int days, String rate) {
        return new Cell(bd(amount), new Tenure(months, days), bd(rate));
    }

    /** A bank card: two amount steps, three tenure steps, a senior citizen add-on. */
    static List<Cell> bankCard() {
        return List.of(cell("0", 0, 7, "4.50"), cell("0", 12, 0, "7.10"), cell("0", 24, 0, "7.25"),
                cell("500000", 0, 7, "4.75"), cell("500000", 12, 0, "7.35"), cell("500000", 24, 0, "7.50"));
    }

    static Settings bankFd(Type type, int rest, List<Cell> cells) {
        return new Settings(type, bd("10000"), null, Tenure.ofDays(7), Tenure.ofMonths(120), rest, DayCount.ACTUAL_365,
                Rounding.PAISE_HALF_UP, cells, List.of(new AddOn("SENIOR_CITIZEN", bd("0.50"))), true, bd("1"), 7, true);
    }

    static Settings nbfcFd(int rest, String topRate, List<AddOn> addOns) {
        return new Settings(Type.FD_PAYOUT, bd("5000"), bd("10000000"), Tenure.ofMonths(12), Tenure.ofMonths(60), rest,
                DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP,
                List.of(cell("0", 12, 0, "8.50"), cell("0", 36, 0, topRate)), addOns, true, null, null, true);
    }

    static Request request(String amount, Tenure tenure, LocalDate born, int nominees, String... addOns) {
        return new Request(bd(amount), tenure, JUL1, Set.of(addOns), nominees, true, born, true);
    }

    // ---- term deposit products -------------------------------------------------------------------------------

    @Test void a_complete_bank_product_can_be_approved() {
        assertEquals(List.of(), TermDepositProduct.problems(BANK, bankFd(Type.FD_CUMULATIVE, 3, bankCard())));
        assertEquals(List.of(), TermDepositProduct.problems(BANK, bankFd(Type.FD_PAYOUT, 0, bankCard())));
    }

    @Test void every_amount_and_tenure_the_product_accepts_needs_a_rate() {
        List<Cell> gap = List.of(cell("0", 0, 7, "4.50"), cell("0", 12, 0, "7.10"), cell("500000", 0, 7, "4.75"));
        has(TermDepositProduct.problems(BANK, bankFd(Type.FD_CUMULATIVE, 3, gap)), "no rate for amount from 500000 and tenure from 12 months");
        List<Cell> late = List.of(cell("25000", 0, 30, "4.50"));
        List<String> p = TermDepositProduct.problems(BANK, bankFd(Type.FD_CUMULATIVE, 3, late));
        has(p, "no rate for amounts below 25000");
        has(p, "no rate for tenures shorter than 30 days");
        has(TermDepositProduct.problems(BANK, bankFd(Type.FD_CUMULATIVE, 3, List.of())), "the rate table has no rates");
    }

    @Test void a_rate_step_the_product_can_never_reach_is_reported() {
        Settings s = new Settings(Type.FD_CUMULATIVE, bd("10000"), bd("400000"), Tenure.ofDays(7), Tenure.ofMonths(18), 3,
                DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP, bankCard(), List.of(), true, bd("1"), 7, true);
        List<String> p = TermDepositProduct.problems(BANK, s);
        has(p, "the rate from 24 months can never apply");
        has(p, "the rate from amount 500000 can never apply");
    }

    @Test void product_settings_are_checked_together() {
        Settings s = new Settings(Type.FD_CUMULATIVE, bd("0"), null, Tenure.ofMonths(24), Tenure.ofMonths(12), 0,
                DayCount.THIRTY_360, Rounding.PAISE_HALF_UP, bankCard(), List.of(new AddOn("staff", bd("0"))), true, null, null, true);
        List<String> p = TermDepositProduct.problems(BANK, s);
        has(p, "the smallest deposit must be above zero");
        has(p, "the shortest tenure 24 months is longer than the longest 12 months");
        has(p, "interest is compounded every 1, 3, 6 or 12 months");
        has(p, "the day count must be");
        has(p, "an add-on code is");
        has(p, "the percentage is required");
        has(p, "the premature-withdrawal penalty is required");
        has(p, "the minimum period before which no interest is paid is required");
        assertTrue(p.size() >= 8, p.toString());
    }

    @Test void a_bank_product_below_one_crore_must_allow_premature_withdrawal() {
        Settings locked = new Settings(Type.FD_CUMULATIVE, bd("10000"), null, Tenure.ofDays(7), Tenure.ofMonths(120), 3,
                DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP, bankCard(), List.of(), false, null, null, true);
        has(TermDepositProduct.problems(BANK, locked), "must allow premature withdrawal");
        Settings large = new Settings(Type.FD_CUMULATIVE, bd("10000000.01"), null, Tenure.ofDays(7), Tenure.ofMonths(120), 3,
                DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP, bankCard(), List.of(), false, null, null, true);
        assertEquals(List.of(), TermDepositProduct.problems(BANK, large));
        Settings withPenalty = new Settings(Type.FD_CUMULATIVE, bd("10000000.01"), null, Tenure.ofDays(7), Tenure.ofMonths(120), 3,
                DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP, bankCard(), List.of(), false, bd("1"), null, true);
        has(TermDepositProduct.problems(BANK, withPenalty), "does not allow premature withdrawal");
    }

    @Test void an_nbfc_product_stays_inside_the_directions() {
        assertEquals(List.of(), TermDepositProduct.problems(NBFC, nbfcFd(1, "9.25", List.of(new AddOn("SENIOR_CITIZEN", bd("0.50"))))));
        // para 16: 12 to 60 months
        Settings shortTenure = new Settings(Type.FD_PAYOUT, bd("5000"), null, Tenure.ofMonths(6), Tenure.ofMonths(72), 1,
                DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP, List.of(cell("0", 6, 0, "8")), List.of(), true, null, null, true);
        List<String> p = TermDepositProduct.problems(NBFC, shortTenure);
        has(p, "the tenure 6 months is shorter than 12 months");
        has(p, "the tenure 72 months is longer than 60 months");
        // para 19: 12.5% a year, with every add-on a depositor could be given
        has(TermDepositProduct.problems(NBFC, nbfcFd(1, "12.75", List.of())), "the highest rate 12.75% is above the ceiling of 12.5%");
        has(TermDepositProduct.problems(NBFC, nbfcFd(1, "12.25", List.of(new AddOn("SENIOR_CITIZEN", bd("0.50"))))),
                "the highest rate 12.25% with every add-on (0.5%) is 12.75%, above the ceiling of 12.5%");
        assertEquals(List.of(), TermDepositProduct.problems(NBFC, nbfcFd(1, "12", List.of(new AddOn("SENIOR_CITIZEN", bd("0.50"))))));
        // para 40 fixes the interest on premature repayment: the product has no penalty of its own
        Settings ownPenalty = new Settings(Type.FD_PAYOUT, bd("5000"), null, Tenure.ofMonths(12), Tenure.ofMonths(60), 1,
                DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP, List.of(cell("0", 12, 0, "8.50")), List.of(), true, bd("1"), 7, true);
        has(TermDepositProduct.problems(NBFC, ownPenalty), "fixed by the rule set");
        // para 36(1): whether premature repayment is offered at all is the NBFC's choice
        Settings noPremature = new Settings(Type.FD_PAYOUT, bd("5000"), null, Tenure.ofMonths(12), Tenure.ofMonths(60), 1,
                DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP, List.of(cell("0", 12, 0, "8.50")), List.of(), false, null, null, true);
        assertEquals(List.of(), TermDepositProduct.problems(NBFC, noPremature));
    }

    @Test void a_reduction_cannot_take_the_rate_below_zero() {
        Settings s = new Settings(Type.FD_PAYOUT, bd("5000"), null, Tenure.ofDays(7), Tenure.ofMonths(12), 0, DayCount.ACTUAL_365,
                Rounding.PAISE_HALF_UP, List.of(cell("0", 0, 7, "0.25")), List.of(new AddOn("BULK_CUT", bd("-0.50"))), true, bd("0"), 0, true);
        has(TermDepositProduct.problems(BANK, s), "is below zero");
    }

    // ---- savings and current products ------------------------------------------------------------------------

    static DemandDepositProduct.Settings savings(List<Band> bands, BandMode mode, int posting) {
        return new DemandDepositProduct.Settings(DemandDepositProduct.Type.SAVINGS, bd("1000"), bands, mode, DayCount.ACTUAL_365,
                posting, Rounding.PAISE_HALF_UP);
    }

    @Test void savings_and_current_products() {
        List<Band> bands = List.of(new Band(bd("0"), bd("2.75")), new Band(bd("100000"), bd("3.5")));
        assertEquals(List.of(), DemandDepositProduct.problems(BANK, savings(bands, BandMode.INCREMENTAL, 3)));
        has(DemandDepositProduct.problems(BANK, savings(bands, BandMode.WHOLE_BALANCE, 3)), "one rate must apply to balances up to 100000");
        has(DemandDepositProduct.problems(BANK, savings(bands, BandMode.INCREMENTAL, 2)), "interest is credited every 1, 3, 6 or 12 months");
        has(DemandDepositProduct.problems(BANK, savings(List.of(), null, 0)), "a savings product needs at least one interest band");
        has(DemandDepositProduct.problems(BANK, savings(List.of(new Band(bd("500"), bd("3"))), BandMode.INCREMENTAL, 3)), "the first band must start at 0");

        var current = new DemandDepositProduct.Settings(DemandDepositProduct.Type.CURRENT, bd("10000"), List.of(), null, null, 0,
                Rounding.PAISE_HALF_UP);
        assertEquals(List.of(), DemandDepositProduct.problems(BANK, current));
        assertFalse(current.bearsInterest());
        var paying = new DemandDepositProduct.Settings(DemandDepositProduct.Type.CURRENT, bd("10000"), List.of(new Band(bd("0"), bd("2"))),
                BandMode.WHOLE_BALANCE, DayCount.ACTUAL_365, 3, Rounding.PAISE_HALF_UP);
        has(DemandDepositProduct.problems(BANK, paying), "a current account bears no interest");
        var posting = new DemandDepositProduct.Settings(DemandDepositProduct.Type.CURRENT, bd("-1"), List.of(), null, null, 3,
                Rounding.PAISE_HALF_UP);
        List<String> p = DemandDepositProduct.problems(BANK, posting);
        has(p, "the minimum balance is required");
        has(p, "has no interest posting frequency");
    }

    @Test void savings_interest_periods_follow_the_financial_year() {
        assertEquals(LocalDate.of(2026, 9, 30), SavingsInterest.periodEnd(JUL1, 3, 4));
        assertEquals(LocalDate.of(2026, 6, 30), SavingsInterest.periodEnd(LocalDate.of(2026, 6, 30), 3, 4));
        assertEquals(LocalDate.of(2027, 3, 31), SavingsInterest.periodEnd(LocalDate.of(2027, 1, 15), 3, 4));
        assertEquals(LocalDate.of(2028, 2, 29), SavingsInterest.periodEnd(LocalDate.of(2028, 2, 1), 1, 4));
        assertEquals(LocalDate.of(2026, 9, 30), SavingsInterest.periodEnd(JUL1, 6, 4));
        assertEquals(LocalDate.of(2027, 3, 31), SavingsInterest.periodEnd(LocalDate.of(2026, 10, 1), 6, 4));
        assertEquals(LocalDate.of(2027, 3, 31), SavingsInterest.periodEnd(JUL1, 12, 4));
        assertEquals(LocalDate.of(2026, 12, 31), SavingsInterest.periodEnd(JUL1, 12, 1));      // a calendar-year entity
        assertThrows(IllegalArgumentException.class, () -> SavingsInterest.periodEnd(JUL1, 2, 4));
        assertThrows(IllegalArgumentException.class, () -> SavingsInterest.periodEnd(JUL1, 3, 13));
    }

    @Test void an_nbfc_has_no_savings_or_current_product() {
        List<Band> bands = List.of(new Band(bd("0"), bd("3")));
        has(DemandDepositProduct.problems(NBFC, savings(bands, BandMode.INCREMENTAL, 3)), "cannot accept deposits repayable on demand");
    }

    // ---- bookings --------------------------------------------------------------------------------------------

    @Test void g26_bank_cumulative_deposit_with_the_senior_citizen_add_on() {
        Quote q = TermDepositBooking.quote(BANK, bankFd(Type.FD_CUMULATIVE, 3, bankCard()),
                request("250000", Tenure.ofMonths(15), BORN_1960, 2, "SENIOR_CITIZEN"));
        eq("7.10", q.rate().cardRatePercent());
        eq("7.60", q.rate().ratePercent());
        assertEquals(Tenure.ofMonths(12), q.rate().fromTenure());
        eq("0", q.rate().fromAmount());
        assertEquals(LocalDate.of(2027, 10, 1), q.maturity());
        assertEquals(5, q.periods().size());
        eq("4750.00", q.periods().get(0).interest());
        eq("4840.25", q.periods().get(1).interest());
        eq("5121.42", q.periods().get(4).interest());
        eq("24669.81", q.totalInterest());
        eq("274669.81", q.maturityValue());
        eq("7.88", q.annualisedYield());
        eq("0", q.totalTax());                           // 9,590.25 and 15,079.56 in the two tax years: under 1,00,000
        assertEquals(5, q.tax().size());
    }

    @Test void g27_tax_is_projected_by_tax_year_on_the_deposit_alone() {
        Settings product = bankFd(Type.FD_CUMULATIVE, 3, bankCard());
        Quote q = TermDepositBooking.quote(BANK, product, request("2000000", Tenure.ofMonths(24), BORN_1990, 0));
        eq("7.50", q.rate().ratePercent());
        eq("500000", q.rate().fromAmount());
        eq("320443.35", q.totalInterest());
        eq("0", q.tax().get(0).tax());                   // 1-Oct-2026: 37,500 so far, under 50,000
        eq("7570.31", q.tax().get(1).tax());             // 1-Jan-2027: 10% of 75,703.13
        assertEquals(2026, q.tax().get(1).taxYearStarting());
        eq("0", q.tax().get(2).tax());                   // 1-Apr-2027 opens tax year 2027-28
        eq("7856.86", q.tax().get(3).tax());
        eq("4039.26", q.tax().get(4).tax());
        eq("4115.00", q.tax().get(5).tax());
        eq("0", q.tax().get(6).tax());
        eq("8462.91", q.tax().get(7).tax());
        eq("32044.34", q.totalTax());
        // a senior citizen: 8.00% and the higher threshold
        Quote senior = TermDepositBooking.quote(BANK, product, request("2000000", Tenure.ofMonths(24), BORN_1960, 0, "SENIOR_CITIZEN"));
        eq("8.00", senior.rate().ratePercent());
        eq("12736.16", senior.tax().get(4).tax());
        eq("17152.48", senior.totalTax());
    }

    @Test void a_product_that_deducts_no_tax_shows_none() {
        Settings noTax = new Settings(Type.FD_CUMULATIVE, bd("10000"), null, Tenure.ofDays(7), Tenure.ofMonths(120), 3,
                DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP, bankCard(), List.of(), true, bd("1"), 7, false);
        Quote q = TermDepositBooking.quote(BANK, noTax, request("2000000", Tenure.ofMonths(24), BORN_1990, 0));
        assertTrue(q.tax().isEmpty());
        eq("0", q.totalTax());
    }

    @Test void g28_nbfc_monthly_payout_and_interest_once_at_maturity() {
        Quote q = TermDepositBooking.quote(NBFC, nbfcFd(1, "9.25", List.of()), request("100000", Tenure.ofMonths(36), BORN_1990, 1));
        eq("9.25", q.rate().ratePercent());
        assertEquals(36, q.periods().size());
        assertTrue(q.periods().stream().allMatch(p -> p.interest().compareTo(bd("770.83")) == 0));
        eq("27749.88", q.totalInterest());
        eq("100000", q.maturityValue());                 // a payout deposit repays the principal
        Settings atMaturity = new Settings(Type.FD_PAYOUT, bd("10000"), null, Tenure.ofDays(7), Tenure.ofMonths(120), 0,
                DayCount.ACTUAL_365, Rounding.PAISE_HALF_UP, List.of(cell("0", 0, 7, "4.50"), cell("0", 12, 0, "6.75")), List.of(),
                true, bd("1"), 7, true);
        Quote once = TermDepositBooking.quote(BANK, atMaturity, request("60000", Tenure.ofDays(400), BORN_1990, 0));
        eq("6.75", once.rate().ratePercent());            // 400 days from 1-Jul-2026 is past 1-Jul-2027: the 12-month step
        assertEquals(1, once.periods().size());
        eq("4438.36", once.totalInterest());
    }

    @Test void an_nbfc_booking_is_refused_with_every_reason() {
        Settings product = nbfcFd(1, "9.25", List.of());
        DepositRefusal e = assertThrows(DepositRefusal.class,
                () -> TermDepositBooking.quote(NBFC, product, request("1000", Tenure.ofMonths(6), BORN_1990, 0, "STAFF")));
        has(e.problems(), "below the product's smallest deposit of 5000");
        has(e.problems(), "the tenure 6 months is shorter than the product's shortest tenure of 12 months");
        has(e.problems(), "the product has no add-on STAFF");
        // para 50: one nominee
        has(TermDepositBooking.problems(NBFC, product, request("100000", Tenure.ofMonths(12), BORN_1990, 2)), "2 nominees named; at most 1 allowed");
        assertEquals(List.of(), TermDepositBooking.problems(NBFC, product, request("100000", Tenure.ofMonths(60), BORN_1990, 1)));
        has(TermDepositBooking.problems(NBFC, product, request("100000", new Tenure(60, 1), BORN_1990, 1)), "longer than the product's longest tenure");
    }

    @Test void the_rule_set_is_applied_to_real_dates() {
        // 365 days from 1-Jul-2026 end on 1-Jul-2027: twelve months. From 1-Jul-2027 they end on 30-Jun-2028, a day short.
        assertTrue(NBFC.checkTermDeposit(JUL1, JUL1.plusDays(365), bd("9"), 1, 1).isEmpty());
        LocalDate leap = LocalDate.of(2027, 7, 1);
        assertEquals(List.of(DepositRules.TD_MIN_TENURE_MONTHS),
                NBFC.checkTermDeposit(leap, leap.plusDays(365), bd("9"), 1, 1).stream().map(DepositRules.Violation::rule).toList());
        assertEquals(List.of(DepositRules.TD_MAX_TENURE_MONTHS, DepositRules.TD_MAX_RATE, DepositRules.NOMINEES_MAX),
                NBFC.checkTermDeposit(JUL1, JUL1.plusMonths(60).plusDays(1), bd("13"), 1, 2).stream().map(DepositRules.Violation::rule).toList());
        assertTrue(BANK.checkTermDeposit(JUL1, JUL1.plusDays(7), bd("13"), 3, 4).isEmpty());
    }

    @Test void the_senior_citizen_add_on_needs_the_age_on_the_start_date() {
        Settings product = bankFd(Type.FD_CUMULATIVE, 3, bankCard());
        has(TermDepositBooking.problems(BANK, product, request("50000", Tenure.ofMonths(12), BORN_1990, 0, "SENIOR_CITIZEN")),
                "for an individual aged 60 or more on the start date");
        LocalDate sixtyTomorrow = JUL1.plusDays(1).minusYears(60);
        assertFalse(TermDepositBooking.problems(BANK, product, request("50000", Tenure.ofMonths(12), sixtyTomorrow, 0, "SENIOR_CITIZEN")).isEmpty());
        assertEquals(List.of(), TermDepositBooking.problems(BANK, product,
                request("50000", Tenure.ofMonths(12), JUL1.minusYears(60), 0, "SENIOR_CITIZEN")));
        Request company = new Request(bd("50000"), Tenure.ofMonths(12), JUL1, Set.of("SENIOR_CITIZEN"), 0, false, null, true);
        assertFalse(TermDepositBooking.problems(BANK, product, company).isEmpty());
    }

    @Test void a_booking_needs_an_amount_a_start_date_and_a_tenure() {
        Settings product = bankFd(Type.FD_CUMULATIVE, 3, bankCard());
        List<String> p = TermDepositBooking.problems(BANK, product, new Request(null, null, null, null, 0, true, null, false));
        has(p, "the deposit amount must be above zero");
        has(p, "the start date is required");
        has(p, "the tenure is required");
        has(TermDepositBooking.problems(BANK, product, request("50000.005", Tenure.ofMonths(12), BORN_1990, 0)), "at most two decimal places");
        has(TermDepositBooking.problems(BANK, product, request("50000", Tenure.ofDays(6), BORN_1990, 0)), "shorter than the product's shortest tenure of 7 days");
    }

    // ---- holders and nominees --------------------------------------------------------------------------------

    @Test void operating_modes_and_holders() {
        assertTrue(AccountHolders.check(OperatingMode.SINGLE, 1).isEmpty());
        assertFalse(AccountHolders.check(OperatingMode.SINGLE, 2).isEmpty());
        assertTrue(AccountHolders.check(OperatingMode.EITHER_OR_SURVIVOR, 2).isEmpty());
        assertFalse(AccountHolders.check(OperatingMode.EITHER_OR_SURVIVOR, 3).isEmpty());
        assertFalse(AccountHolders.check(OperatingMode.FORMER_OR_SURVIVOR, 1).isEmpty());
        assertTrue(AccountHolders.check(OperatingMode.ANYONE_OR_SURVIVOR, 3).isEmpty());
        assertTrue(AccountHolders.check(OperatingMode.JOINTLY, AccountHolders.MAX_HOLDERS).isEmpty());
        assertFalse(AccountHolders.check(OperatingMode.JOINTLY, AccountHolders.MAX_HOLDERS + 1).isEmpty());
        assertFalse(AccountHolders.check(null, 1).isEmpty());
        assertFalse(AccountHolders.check(OperatingMode.SINGLE, 0).isEmpty());
    }

    static Nominee nominee(String name, String share) {
        return new Nominee(name, "SPOUSE", share == null ? null : bd(share), false, null);
    }

    static List<String> messages(List<DepositRules.Violation> v) {
        return v.stream().map(DepositRules.Violation::message).toList();
    }

    @Test void a_bank_account_names_up_to_four_nominees_together_or_one_after_another() {
        assertTrue(Nomination.check(BANK, Mode.NONE, List.of()).isEmpty());
        assertTrue(Nomination.check(BANK, Mode.SIMULTANEOUS, List.of(nominee("CLAUDE-TEST A", "100"))).isEmpty());
        assertTrue(Nomination.check(BANK, Mode.SIMULTANEOUS, List.of(nominee("CLAUDE-TEST A", "33.33"), nominee("CLAUDE-TEST B", "33.33"),
                nominee("CLAUDE-TEST C", "33.34"))).isEmpty());
        has(messages(Nomination.check(BANK, Mode.SIMULTANEOUS, List.of(nominee("CLAUDE-TEST A", "60"), nominee("CLAUDE-TEST B", "30")))),
                "nominee shares must total 100 percent; they total 90");
        assertTrue(Nomination.check(BANK, Mode.SUCCESSIVE, List.of(nominee("CLAUDE-TEST A", null), nominee("CLAUDE-TEST B", null))).isEmpty());
        has(messages(Nomination.check(BANK, Mode.SUCCESSIVE, List.of(nominee("CLAUDE-TEST A", "50"), nominee("CLAUDE-TEST B", "50")))),
                "successive nominees have no shares");
        has(messages(Nomination.check(BANK, Mode.SUCCESSIVE, List.of(nominee("CLAUDE-TEST A", null)))), "successive nomination needs two nominees");
        List<Nominee> five = List.of(nominee("A", "20"), nominee("B", "20"), nominee("C", "20"), nominee("D", "20"), nominee("E", "20"));
        assertEquals(DepositRules.NOMINEES_MAX, Nomination.check(BANK, Mode.SIMULTANEOUS, five).get(0).rule());
        has(messages(Nomination.check(BANK, Mode.NONE, List.of(nominee("CLAUDE-TEST A", "100")))), "the nomination mode is NONE");
        has(messages(Nomination.check(BANK, Mode.SIMULTANEOUS, List.of())), "needs at least one nominee");
    }

    @Test void a_nominee_needs_a_name_a_relationship_and_for_a_minor_a_guardian() {
        List<String> m = messages(Nomination.check(BANK, Mode.SIMULTANEOUS, List.of(new Nominee(" ", null, bd("100"), true, null))));
        has(m, "the name is required");
        has(m, "the relationship to the depositor is required");
        has(m, "is a minor");
        assertTrue(Nomination.check(BANK, Mode.SIMULTANEOUS,
                List.of(new Nominee("CLAUDE-TEST Child", "CHILD", bd("100"), true, "CLAUDE-TEST Guardian"))).isEmpty());
        has(messages(Nomination.check(BANK, Mode.SIMULTANEOUS, List.of(new Nominee("CLAUDE-TEST A", "SPOUSE", bd("100"), false, "CLAUDE-TEST G")))),
                "a guardian is named only for a minor");
        has(messages(Nomination.check(BANK, Mode.SIMULTANEOUS, List.of(nominee("CLAUDE-TEST A", "100.001")))), "at most 100");
        has(messages(Nomination.check(BANK, Mode.SIMULTANEOUS, List.of(nominee("CLAUDE-TEST A", "50.005"), nominee("CLAUDE-TEST B", "49.995")))),
                "at most two decimal places");
    }

    @Test void a_deposit_with_an_nbfc_names_one_nominee() {
        assertTrue(Nomination.check(NBFC, Mode.SIMULTANEOUS, List.of(nominee("CLAUDE-TEST A", "100"))).isEmpty());
        assertEquals(DepositRules.NOMINEES_MAX,
                Nomination.check(NBFC, Mode.SIMULTANEOUS, List.of(nominee("CLAUDE-TEST A", "50"), nominee("CLAUDE-TEST B", "50"))).get(0).rule());
    }
}
