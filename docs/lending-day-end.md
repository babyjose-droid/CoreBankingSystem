# Lending day-end: days past due, non-working days and reversals

This note fixes one convention for days past due (DPD) and asset classification, and records what the code did
before it (found in the second end-to-end run of the local stack, October 2026).

## The convention

1. **Day-end closes every calendar day.** The day-end of business date *D* closes *D* and every following calendar
   day before the next business date (`platform.next_working_day(D) - 1`): Saturday's day-end also closes Sunday,
   the day-end before a holiday also closes the holiday. Each day gets its own accrual, penal charge, demands,
   classification and `dpd_history` row, and its entries are posted in *D*'s books with the day as value date. A
   day missed earlier (a loan whose last processed day is older) is caught up by the next day-end the same way.
   Interest and penal charges therefore run per calendar day, as the engine's simulation, dry-run and reversal
   replay always assumed.
2. **DPD and class are those of the last completed day-end.** For a loan that is the engine's `lastAccrualDate`;
   on an open business day it is normally the calendar day before. RBI's 12-Nov-2021 clarification makes
   classification a day-end process and counts the due date as day 1 (`Delinquency.dpd`).
3. **A transaction during the open day can only lower DPD.** A receipt (and a restructure) recomputes DPD and
   class as of the last completed day-end: clearing the oldest demand lowers DPD, a full arrears payment upgrades,
   nothing raises DPD or makes an account NPA between two day-ends.
4. **A reversal restores, then replays only what day-end really ran.** The state before the reversed transaction
   is restored and the day-ends after it are replayed up to the last day day-end had processed for the loan before
   the reversal (the loan's `lastAccrualDate` at that moment — an intraday transaction never moves it), into
   today's books. A reversal of a transaction of the open day replays nothing; a day day-end has not closed is
   never processed early.
5. **Closure.** A loan repaid in full, pre-closed or cancelled has nothing outstanding: DPD 0. An SMA class
   becomes standard; an NPA class (and its NPA date) is the class at closure and is kept. The closing day gets a
   `dpd_history` row. A receipt that pays all arrears of an NPA upgrades it first (RBI), so a loan closed by
   receipts is standard. Written-off loans keep their class.

## What the code did before (V22)

- **(a) As-of dates.** Day-end computed DPD as of the business date it closed. A receipt (`LoanAccount.pay`) and a
  restructure recomputed it as of the *open* business date, whose day-end had not run: one day more than the last
  day-end, so a part payment could raise DPD (live: 82 → 83). Pre-closure, cancellation, prepayment and waiver did
  not reclassify at all, so a pre-closed loan kept its last DPD and class (live: DPD 15, SMA-0).
- **(b) Non-working days.** Day-end processed only the business date itself (`endOfDay(businessDate)` once): a
  Sunday or holiday was never processed. Its interest was not lost (the demand-date true-up brings each period's
  interest to the schedule) but was recognised later, and its penal charge was never charged; DPD stayed right
  because it counts calendar days.
- **(c) Reversal replay.** The replay processed every day from the restored state up to the day before the open
  business date, including a Sunday the regular day-end had skipped — posting that Sunday's penal (live: +12.95)
  and moving DPD forward one day. It could not double-post (`endOfDay` refuses a day already processed), but it
  changed totals relative to a loan that never had the reversed transaction, because the regular path never
  charged that day.

Non-working days skipped before this change stay unprocessed (their penal charges are not charged after the
event). Only the days since a loan's last processed day are caught up, by the first day-end after the change.
