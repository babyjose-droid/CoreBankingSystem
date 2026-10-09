# Runbook: floating-rate resets

RBI, 18-Aug-2023, "Reset of floating interest rate on EMI based personal loans"; product owner decisions D-14 and
10-Oct-2026. Built in V24.

## What happens
- A floating-rate loan (product with `benchmarkCode`, `spread`, `resetFrequencyMonths`) is reset every
  `resetFrequencyMonths` months from its disbursal date.
- The day-end that closes the reset date (a Sunday or holiday is closed by the day-end of the working day before it,
  docs/lending-day-end.md) sets the rate to **the benchmark rate in force on the reset date + the loan's spread**.
  The rate applies even outside the product's band; the loan is flagged (`rateOutsideBand`) and the reset appears in
  report RATE_RESETS_APPLIED with `outsideBand`.
- What changes: the borrower's standing choice if one was approved, otherwise the product's `resetOption` (default
  KEEP_TENURE_CHANGE_EMI: the EMI changes, the tenure is kept). Keeping the EMI lengthens the tenure; when that would
  pass the product's maximum tenure, amortise negatively or extend the loan of a borrower in arrears, the EMI is
  raised instead and the history says why.
- No change when benchmark + spread equals the current rate; the next reset date still moves on.
- The borrower is told (RATE_RESET message: new rate, EMI and instalments left).
- Frozen account: the reset is held (the reset date stays in the past, `held` in the upcoming list) and is made by the
  first day-end after the account is unfrozen, at the benchmark rate of the latest reset date passed. Closed,
  cancelled and written-off loans are not reset.
- A missing benchmark rate for the reset date makes that loan a day-end exception: record the rate
  (Masters → Benchmark rates), then restart the day-end.

## Before a reset date
1. Record the benchmark rate (maker-checker) from its effective date.
2. Lending → Rate resets (`GET /api/v1/rate-resets/upcoming?days=30`): loans due, projected rate, estimated EMI,
   outside-band warnings and held resets.
3. A borrower who wants the other option: loan page → Floating rate → "Reset choice" (maker-checker,
   `POST /api/v1/loans/{id}/rate-reset-preference`). A combination of EMI and tenure for one reset is a RATE_CHANGE
   amendment with CHANGE_BOTH. Prepayment remains available as usual.

## After the day-end
- Lending → Rate resets → Applied (report RATE_RESETS_APPLIED): rate, EMI and tenure before/after, option applied,
  fallback reason, outside-band flag.
- A reversal of an earlier transaction replays the reset (it is recorded again; the borrower is not messaged again).
  A reset itself cannot be reversed on its own.

## Not built
- Switching to a fixed rate at the reset under the lender's board policy (charges, which fixed rate): the engine has
  the amendment kind SWITCH_TO_FIXED, the policy terms are not configured.
