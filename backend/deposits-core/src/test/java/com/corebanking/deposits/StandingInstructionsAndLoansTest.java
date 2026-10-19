package com.corebanking.deposits;

import static com.corebanking.deposits.Fixtures.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Standing instruction dates and loans against deposits (P4-5b). */
class StandingInstructionsAndLoansTest {

    static final LocalDate D = LocalDate.of(2026, 7, 31);

    @Test
    void aMonthlyTransferPaysOnItsDateAndIsTriedForTheRetryDays() {
        assertEquals(List.of(D), StandingInstructionDates.transferDates(D, 1, null, D, 3));
        assertEquals(List.of(), StandingInstructionDates.transferDates(D, 1, null, D.minusDays(1), 3));
        // 31 Jul, then 31 Aug, 30 Sep (the month has no 31st)
        assertEquals(List.of(LocalDate.of(2026, 9, 30)), StandingInstructionDates.transferDates(D, 1, null, LocalDate.of(2026, 10, 2), 3));
        assertEquals(List.of(), StandingInstructionDates.transferDates(D, 1, null, LocalDate.of(2026, 10, 4), 3));
        assertEquals(List.of(LocalDate.of(2026, 10, 31)), StandingInstructionDates.transferDates(D, 3, null, LocalDate.of(2026, 11, 1), 3));
        assertEquals(List.of(), StandingInstructionDates.transferDates(D, 1, LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 31), 3));
        assertTrue(StandingInstructionDates.over(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 19), 3));
        assertFalse(StandingInstructionDates.over(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 18), 3));
        assertEquals(4, StandingInstructionDates.window(D.minusDays(10), D, 3).size());
        assertEquals(1, StandingInstructionDates.window(D, D, 3).size());
    }

    static final DepositLoans.Pledged FD = new DepositLoans.Pledged("30010000000011", bd("100000"), bd("7.1"), LocalDate.of(2026, 3, 1),
            LocalDate.of(2027, 3, 1));

    @Test
    void anNbfcLendsUpToThreeQuartersTwoPointsAboveAfterThreeMonths() {
        DepositLoans.Terms t = DepositLoans.terms(Fixtures.nbfc(), bd("90"), bd("1"));
        assertEquals(0, bd("75").compareTo(t.maxPercent()));
        assertEquals(3, t.lockInMonths());
        assertEquals(0, bd("75000").compareTo(DepositLoans.maxLoan(t, List.of(FD))));
        assertEquals(0, bd("9.1").compareTo(DepositLoans.minRate(t, List.of(FD))));
        DepositLoans.Loan ok = new DepositLoans.Loan("L1", bd("75000"), bd("9.1"), LocalDate.of(2027, 1, 1));
        assertTrue(DepositLoans.problems(t, ok, List.of(FD), LocalDate.of(2026, 6, 1)).isEmpty());
        List<String> early = DepositLoans.problems(t, ok, List.of(FD), LocalDate.of(2026, 5, 31));
        assertEquals(1, early.size());
        assertTrue(early.get(0).contains("within 3 months"));
        DepositLoans.Loan bad = new DepositLoans.Loan("L2", bd("75001"), bd("9"), LocalDate.of(2027, 4, 1));
        List<String> p = DepositLoans.problems(t, bad, List.of(FD), LocalDate.of(2026, 6, 1));
        assertEquals(3, p.size(), p.toString());
    }

    @Test
    void aBankLendsOnItsOwnPolicyAndSeveralDepositsAddUp() {
        DepositLoans.Terms t = DepositLoans.terms(Fixtures.bank(), bd("90"), bd("1"));
        assertEquals(0, t.lockInMonths());
        DepositLoans.Pledged rd = new DepositLoans.Pledged("30010000000045", bd("12000.50"), bd("7"), LocalDate.of(2026, 6, 1), LocalDate.of(2027, 6, 1));
        assertEquals(0, bd("100800").compareTo(DepositLoans.maxLoan(t, List.of(FD, rd))));
        assertEquals(0, new BigDecimal("8.1").compareTo(DepositLoans.minRate(t, List.of(FD, rd))));
        assertTrue(DepositLoans.problems(t, new DepositLoans.Loan("L3", bd("100800"), bd("8.1"), null), List.of(FD, rd), FD.start()).isEmpty());
        assertEquals(List.of("no deposit is pledged"), DepositLoans.problems(t, new DepositLoans.Loan("L4", bd("1"), bd("9"), null), List.of(), FD.start()));
    }
}
