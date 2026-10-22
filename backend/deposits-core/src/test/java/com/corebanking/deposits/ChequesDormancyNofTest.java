package com.corebanking.deposits;

import static com.corebanking.deposits.Fixtures.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.deposits.Cheques.Leaf;
import com.corebanking.deposits.Cheques.ReturnReason;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Cheques presented, dormancy and the net owned fund cap (P4-6). */
class ChequesDormancyNofTest {

    static final LocalDate T = LocalDate.of(2026, 7, 1);

    static Optional<ReturnReason> present(boolean active, Leaf leaf, LocalDate date, String amount, boolean frozen, boolean inop, String bal, String avail) {
        return Cheques.decide(new Cheques.Presentment(active, leaf, date, bd(amount), T, 3, frozen, inop, bd(bal), bd(avail)));
    }

    @Test
    void aChequeIsPaidOrReturnedForTheFirstReasonFound() {
        assertTrue(present(true, Leaf.UNUSED, T, "500", false, false, "1000", "1000").isEmpty());
        assertEquals(Optional.of(ReturnReason.ACCOUNT_CLOSED), present(false, Leaf.UNUSED, T, "500", false, false, "1000", "1000"));
        assertEquals(Optional.of(ReturnReason.NOT_ISSUED), present(true, null, T, "500", false, false, "1000", "1000"));
        assertEquals(Optional.of(ReturnReason.ALREADY_PAID), present(true, Leaf.PAID, T, "500", false, false, "1000", "1000"));
        assertEquals(Optional.of(ReturnReason.PAYMENT_STOPPED), present(true, Leaf.STOPPED, T, "5000", true, true, "1", "1"));
        assertEquals(Optional.of(ReturnReason.POST_DATED), present(true, Leaf.UNUSED, T.plusDays(1), "500", false, false, "1000", "1000"));
        // dated 1-Apr is good through 1-Jul; dated 31-Mar is stale on 1-Jul
        assertTrue(present(true, Leaf.UNUSED, LocalDate.of(2026, 4, 1), "500", false, false, "1000", "1000").isEmpty());
        assertEquals(Optional.of(ReturnReason.STALE), present(true, Leaf.UNUSED, LocalDate.of(2026, 3, 31), "500", false, false, "1000", "1000"));
        assertEquals(Optional.of(ReturnReason.ACCOUNT_FROZEN), present(true, Leaf.UNUSED, T, "500", true, false, "1000", "1000"));
        assertEquals(Optional.of(ReturnReason.ACCOUNT_INOPERATIVE), present(true, Leaf.UNUSED, T, "500", false, true, "1000", "1000"));
        assertEquals(Optional.of(ReturnReason.AMOUNT_HELD), present(true, Leaf.UNUSED, T, "500", false, false, "1000", "400"));
        assertEquals(Optional.of(ReturnReason.INSUFFICIENT_FUNDS), present(true, Leaf.UNUSED, T, "1500", false, false, "1000", "1000"));
    }

    @Test
    void anAccountUnusedForTwoYearsIsInoperativeAndForTenUnclaimed() {
        Dormancy.Rules r = new Dormancy.Rules(12, 24, 10);
        LocalDate last = LocalDate.of(2024, 7, 2);
        assertEquals(Dormancy.Stage.REVIEW, Dormancy.stage(r, last, T));
        assertEquals(Dormancy.Stage.INOPERATIVE, Dormancy.stage(r, LocalDate.of(2024, 7, 1), T));
        assertEquals(Dormancy.Stage.OPERATIVE, Dormancy.stage(r, LocalDate.of(2025, 7, 2), T));
        assertEquals(Dormancy.Stage.UNCLAIMED, Dormancy.stage(r, LocalDate.of(2016, 7, 1), T));
        assertEquals(LocalDate.of(2026, 7, 2), Dormancy.inoperativeOn(r, last));
        assertEquals(LocalDate.of(2034, 7, 2), Dormancy.unclaimedOn(r, last));
    }

    @Test
    void anNbfcHoldsPublicDepositsToOneAndAHalfTimesItsNetOwnedFund() {
        assertTrue(NetOwnedFund.problems(null, null, bd("1"), bd("1")).isEmpty());
        assertTrue(NetOwnedFund.problems(bd("1.5"), null, bd("0"), bd("1")).get(0).contains("not recorded"));
        assertTrue(NetOwnedFund.problems(bd("1.5"), bd("1000000"), bd("1400000"), bd("100000")).isEmpty());
        String p = NetOwnedFund.problems(bd("1.5"), bd("1000000"), bd("1400000"), bd("100001")).get(0);
        assertTrue(p.contains("1500000") && p.contains("100000 may still be accepted"), p);
        assertEquals(0, bd("0").compareTo(NetOwnedFund.headroom(bd("1.5"), bd("1000000"), bd("1600000"))));
    }
}
