package com.corebanking.deposits;

import static com.corebanking.deposits.Fixtures.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.deposits.AccountHolds.Direction;
import com.corebanking.deposits.AccountHolds.Hold;
import com.corebanking.deposits.AccountHolds.Kind;
import com.corebanking.deposits.DepositPostings.FundingSource;
import com.corebanking.deposits.DepositPostings.Leg;
import com.corebanking.ledger.PostingLine;
import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Money movements on deposit accounts (P4-2): the posting rules, freezes and liens. */
class DepositMovementsTest {

    static final LocalDate DAY = LocalDate.of(2026, 7, 1);
    static final DepositPostings POST = new DepositPostings(DepositPostings.Heads.starter(), DAY);
    static final Leg SB = new Leg("HO", "2401", "20010000000013");
    static final Leg CA_MUM = new Leg("MUM", "2402", "20010000000021");
    static final Leg FD = new Leg("HO", "2403", "30010000000011");
    static final Hold LIEN_400 = new Hold(Kind.LIEN, bd("400"), "security for loan");

    static void has(List<String> problems, String part) {
        assertTrue(problems.stream().anyMatch(p -> p.contains(part)), "no problem containing '" + part + "' in " + problems);
    }

    static PostingLine line(TransactionLot lot, String account) {
        return lot.lines().stream().filter(l -> l.account().equals(account)).findFirst().orElseThrow();
    }

    static void leg(TransactionLot lot, String account, String gl, PostingLine.Side side, String amount) {
        PostingLine l = line(lot, account);
        assertEquals(gl, l.glCode(), account + " head");
        assertEquals(side, l.side(), account + " side");
        assertEquals(0, bd(amount).compareTo(l.amount()), account + " amount");
    }

    // ---- posting rules ---------------------------------------------------------------------------------------------

    @Test
    void cashMovementsPostAgainstCashInHand() {
        TransactionLot in = POST.cashDeposit(SB, bd("5000"), "T1", null);
        assertEquals(DepositPostings.CASH_DEPOSIT, in.type());
        assertEquals(2, in.lines().size());
        leg(in, "1201", "1201", PostingLine.Side.DR, "5000");
        leg(in, SB.accountNo(), "2401", PostingLine.Side.CR, "5000");
        assertEquals(DAY, in.valueDate());

        TransactionLot out = POST.cashWithdrawal(SB, bd("1200.50"), "T2", "ATM counter");
        leg(out, SB.accountNo(), "2401", PostingLine.Side.DR, "1200.50");
        leg(out, "1201", "1201", PostingLine.Side.CR, "1200.50");
        assertEquals("ATM counter", line(out, SB.accountNo()).narration());
    }

    @Test
    void bankTransfersUseTheCollectionAndDisbursementBanks() {
        TransactionLot in = POST.bankTransferIn(SB, bd("25000"), "UTR123456", null);
        leg(in, "1203", "1203", PostingLine.Side.DR, "25000");
        leg(in, SB.accountNo(), "2401", PostingLine.Side.CR, "25000");
        assertEquals("UTR123456", in.reference());

        TransactionLot out = POST.bankTransferOut(SB, bd("10000"), "UTR999", null);
        leg(out, SB.accountNo(), "2401", PostingLine.Side.DR, "10000");
        leg(out, "1202", "1202", PostingLine.Side.CR, "10000");
    }

    @Test
    void aTransferBetweenBranchesGetsInterBranchLegs() {
        TransactionLot same = POST.transfer(SB, new Leg("HO", "2401", "20010000000039"), bd("700"), "T3", null);
        assertEquals(2, same.lines().size());

        TransactionLot cross = POST.transfer(SB, CA_MUM, bd("700"), "T4", null);
        assertEquals(4, cross.lines().size(), "two customer legs and two inter-branch legs");
        leg(cross, SB.accountNo(), "2401", PostingLine.Side.DR, "700");
        leg(cross, CA_MUM.accountNo(), "2402", PostingLine.Side.CR, "700");
        assertEquals(0, cross.totalDebits().compareTo(bd("1400")));
        assertTrue(cross.lines().stream().filter(l -> l.glCode().equals("1900")).count() == 2, "the inter-branch legs use the inter-branch head");
        assertThrows(IllegalArgumentException.class, () -> POST.transfer(SB, SB, bd("1"), "T5", null));
    }

    @Test
    void fundingATermDepositFromEachSource() {
        TransactionLot fromAccount = POST.funding(FD, bd("100000"), FundingSource.ACCOUNT, SB, "F1");
        assertEquals(DepositPostings.FUNDING, fromAccount.type());
        leg(fromAccount, SB.accountNo(), "2401", PostingLine.Side.DR, "100000");
        leg(fromAccount, FD.accountNo(), "2403", PostingLine.Side.CR, "100000");

        leg(POST.funding(FD, bd("100000"), FundingSource.CASH, null, "F2"), "1201", "1201", PostingLine.Side.DR, "100000");
        leg(POST.funding(FD, bd("100000"), FundingSource.BANK_TRANSFER, null, "F3"), "1203", "1203", PostingLine.Side.DR, "100000");
        assertThrows(IllegalArgumentException.class, () -> POST.funding(FD, bd("1"), FundingSource.ACCOUNT, null, "F4"));
    }

    @Test
    void theReversalOfAMovementIsItsMirror() {
        TransactionLot in = POST.cashDeposit(SB, bd("5000"), "T1", null);
        TransactionLot back = TransactionLot.reversal(in, DAY.plusDays(1), "wrong account");
        assertEquals(in.id(), back.reverses());
        leg(back, SB.accountNo(), "2401", PostingLine.Side.DR, "5000");
        leg(back, "1201", "1201", PostingLine.Side.CR, "5000");
    }

    @Test
    void anAmountMustBeAboveZero() {
        assertThrows(IllegalArgumentException.class, () -> POST.cashDeposit(SB, BigDecimal.ZERO, "T", null));
        assertThrows(IllegalArgumentException.class, () -> POST.cashWithdrawal(SB, bd("-1"), "T", null));
    }

    // ---- freezes, liens and the balance ------------------------------------------------------------------------------

    @Test
    void aDebitMayNotTakeTheBalanceBelowZeroOrBelowTheLiens() {
        assertEquals(List.of(), AccountHolds.checkMovement(bd("1000"), List.of(), Direction.DEBIT, bd("1000"), false));
        has(AccountHolds.checkMovement(bd("1000"), List.of(), Direction.DEBIT, bd("1000.01"), false), "below zero");
        assertEquals(List.of(), AccountHolds.checkMovement(bd("1000"), List.of(LIEN_400), Direction.DEBIT, bd("600"), false));
        has(AccountHolds.checkMovement(bd("1000"), List.of(LIEN_400), Direction.DEBIT, bd("601"), false), "only 600 is available: 400 is held under lien");
        // a lien never stops money coming in
        assertEquals(List.of(), AccountHolds.checkMovement(bd("0"), List.of(LIEN_400), Direction.CREDIT, bd("50"), false));
        eqAvailable("600", bd("1000"), List.of(LIEN_400));
    }

    static void eqAvailable(String expected, BigDecimal balance, List<Hold> holds) {
        assertEquals(0, bd(expected).compareTo(AccountHolds.available(balance, holds)));
    }

    @Test
    void eachFreezeStopsItsDirection() {
        Hold debit = new Hold(Kind.DEBIT_FREEZE, null, "court order 12/2026");
        Hold credit = new Hold(Kind.CREDIT_FREEZE, null, null);
        Hold total = new Hold(Kind.TOTAL_FREEZE, null, null);
        has(AccountHolds.checkMovement(bd("1000"), List.of(debit), Direction.DEBIT, bd("1"), false), "debit freeze (court order 12/2026)");
        assertEquals(List.of(), AccountHolds.checkMovement(bd("1000"), List.of(debit), Direction.CREDIT, bd("1"), false));
        has(AccountHolds.checkMovement(bd("1000"), List.of(credit), Direction.CREDIT, bd("1"), false), "credit freeze");
        assertEquals(List.of(), AccountHolds.checkMovement(bd("1000"), List.of(credit), Direction.DEBIT, bd("1"), false));
        has(AccountHolds.checkMovement(bd("1000"), List.of(total), Direction.DEBIT, bd("1"), false), "total freeze");
        has(AccountHolds.checkMovement(bd("1000"), List.of(total), Direction.CREDIT, bd("1"), false), "total freeze");
    }

    @Test
    void aCorrectionIsNotStoppedByAFreezeButStillByTheBalanceAndTheLiens() {
        Hold total = new Hold(Kind.TOTAL_FREEZE, null, null);
        assertEquals(List.of(), AccountHolds.checkMovement(bd("1000"), List.of(total), Direction.DEBIT, bd("1000"), true));
        has(AccountHolds.checkMovement(bd("1000"), List.of(total, LIEN_400), Direction.DEBIT, bd("700"), true), "held under lien");
        has(AccountHolds.checkMovement(bd("100"), List.of(), Direction.DEBIT, bd("700"), true), "below zero");
    }

    @Test
    void everyReasonIsReportedAtOnce() {
        List<String> p = AccountHolds.checkMovement(bd("100"), List.of(new Hold(Kind.DEBIT_FREEZE, null, null)), Direction.DEBIT, bd("100.555"), false);
        assertEquals(3, p.size(), p.toString());
        has(p, "two decimals");
        has(p, "debit freeze");
        has(p, "below zero");
        has(AccountHolds.checkMovement(bd("100"), List.of(), Direction.CREDIT, BigDecimal.ZERO, false), "above zero");
    }

    @Test
    void aLienIsPlacedOnlyWithinTheAvailableBalance() {
        assertEquals(List.of(), AccountHolds.checkNewHold(bd("1000"), List.of(LIEN_400), Kind.LIEN, bd("600"), "overdraft security"));
        has(AccountHolds.checkNewHold(bd("1000"), List.of(LIEN_400), Kind.LIEN, bd("600.01"), "x"), "more than the available balance of 600");
        has(AccountHolds.checkNewHold(bd("1000"), List.of(), Kind.LIEN, null, "x"), "needs an amount");
        has(AccountHolds.checkNewHold(bd("1000"), List.of(), Kind.LIEN, bd("10"), " "), "a reason is required");
    }

    @Test
    void aFreezeHasNoAmountAndIsNotPlacedTwice() {
        Hold debit = new Hold(Kind.DEBIT_FREEZE, null, "first order");
        assertEquals(List.of(), AccountHolds.checkNewHold(bd("0"), List.of(), Kind.DEBIT_FREEZE, null, "court order"));
        has(AccountHolds.checkNewHold(bd("0"), List.of(), Kind.TOTAL_FREEZE, bd("5"), "x"), "a freeze has no amount");
        has(AccountHolds.checkNewHold(bd("0"), List.of(debit), Kind.DEBIT_FREEZE, null, "second order"), "already under a debit freeze");
        assertEquals(List.of(), AccountHolds.checkNewHold(bd("0"), List.of(debit), Kind.TOTAL_FREEZE, null, "escalated"));
        assertThrows(IllegalArgumentException.class, () -> new Hold(Kind.LIEN, null, "x"));
        assertThrows(IllegalArgumentException.class, () -> new Hold(Kind.CREDIT_FREEZE, bd("1"), "x"));
    }
}
