# ADR-015: Posting during end of day: cut-off and next-date booking

Status: Accepted  
Date: 2026-10-03

## Context
- Until now every posting was refused while end of day ran (`business_day.status` not OPEN). Payment gateways, collection partners and the LOS send repayments at any hour, so a refusal means a lost or retried payment (US-111: no downtime window).
- The ledger must stay closed-date safe: once a business date is closed, nothing more is posted to it (ADR-005, ADR-014).
- During end of day each loan gets its demands, accrual, penal charges and classification for the date being closed. A repayment that changed a loan in the middle of that would make the day's figures depend on timing.

## Decision
- **Cut-off is the start of end of day.** It is the moment `business_day.status` leaves OPEN. A posting already in flight holds a share lock on the business day row, so the cut-off waits for it; it never lands half before and half after.
- **After the cut-off, a receipt from a customer-facing channel is accepted, not posted.** "Customer-facing" means the repayment API called by a client with `loan:stp`. The receipt is written to `lending.deferred_receipt` and the API answers 202 with the receipt.
- **Staff back-office postings stay blocked** until the day is open again (409, as before). So do all other kinds of transaction, including straight-through disbursements.
- **Nothing is posted to the ledger while the day is being closed**, except end of day's own entries for that date. A deferred receipt is booked as an ordinary repayment once the next business date has opened: right after end of day, and by the job `DEFERRED_RECEIPTS` every five minutes, which also covers an application that stopped in between.
- **Value date equals posting date.** A deferred receipt is booked and valued on the next business date. The time the money was reported is kept (`received_at`) as evidence. A request that asks for another value date after the cut-off is refused with 422.
- **The database enforces the ledger rule.** A trigger on `ledger.transaction_lot` refuses a lot dated before the current business date once any end of day has completed, and a lot dated after the current business date. Before a tenant's first end of day, earlier dates stay open so opening balances can be loaded.
- **The database guards the receipt.** It sets the cut-off date and the expected posting date itself, accepts a receipt only while the day is not OPEN and the loan is ACTIVE, and lets a receipt become APPLIED only when it points at a repayment of the same amount booked and valued on the open date after its cut-off.
- **A receipt is never dropped.** One that cannot be booked (the loan was frozen or closed meanwhile) becomes FAILED with the reason. A user with `loan:admin` queues it again or sets it aside with a note.

## Consequences
- The repayment API has no downtime window for integrations. The borrower's balance shows the payment when the next date opens, usually minutes later.
- If end of day fails, receipts keep being accepted and wait until it is restarted and completes. A long failure delays their booking.
- A payment made after the cut-off does not reduce the days past due of the date being closed. Lenders that want the old date as value date need back-valued replay, which is not built (decision D-15).
- The role amount limit of the receiving client is checked when the receipt is accepted, not again when it is booked.

## Alternatives considered
- **Post to the ledger at once with the next date.** Rejected: the ledger would hold entries for a date that is not open, and the loan would change while its day-end is running.
- **Value the receipt on the date being closed.** Rejected: it needs that date's demands, penal charges and classification to be redone after they were closed.
