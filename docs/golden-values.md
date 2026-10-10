# Golden values

Verified against the reference sandbox walkthrough (Sep 2026) and independent calculation.
Pinned in `backend/calc/src/test/.../GoldenCalcTest.java`. Changing one requires a PR note and reviewer sign-off.

| # | Case | Inputs | Expected | Status |
|---|------|--------|----------|--------|
| G-01 | EMI | ₹1,00,000 · 18% · 12 m | ₹9,168 | Matches reference |
| G-02 | EMI after prepayment | ₹90,000 · 18% · 12 m | ₹8,251 | Matches reference |
| G-03 | Due dates, month-end open | open 30-Jun-2026 | 31-Jul … 28-Feb … 30-Jun-2027 | Matches reference |
| G-04 | Schedule rows 1–7 | as G-01, Actual/365, rupee half-up | interest 1529, 1412, 1252, 1172, 1016, 926, 800 | Matches reference |
| G-05 | Schedule rows 8 and 10 | as G-01 | 607 / 396 (reference shows 606 / 395) | **Open item OI-01** |
| G-06 | Bullet, extra first day | ₹5,000 · 638.75% · 30 days + 1 | ₹2,713 | Matches reference |
| G-07 | Fee + GST | ₹750 + 18% ; ₹300 + 18% | ₹885 ; ₹354 | Matches reference |
| G-08 | APR, IRR basic | net ₹99,700 (fee ex-GST), 12 × ₹9,168 | 18.58% | Matches reference (was open) |
| G-09 | FD cumulative, quarterly | ₹1,00,000 · 7.5% · 24 m | ₹1,16,022.17 | Independent calc |
| G-10 | FD card rate | base 7% + slab 7.5% | 14.5% | Matches reference behaviour |
| G-11 | Foreclosure quote | ₹43,931 · 18% · 10 days · 2% charge + GST | ₹45,185 | New (reference errored) |

## Deposits (Phase 4)

Pinned in `backend/deposits-core/src/test/.../DepositInterestGoldenTest.java` and `DepositRulesTest.java`. Each figure was worked out with Python decimal arithmetic before the engine was run. There is no reference-system figure for these: the sandbox walkthrough did not complete a deposit.

| # | Case | Inputs | Expected | Status |
|---|------|--------|----------|--------|
| G-12 | FD cumulative, each quarter's credit rounded | ₹1,00,000 · 7.5% · 1-Apr-2026 to 1-Apr-2028 · quarterly | credits 1875.00, 1910.16 … 2135.38; maturity ₹1,16,022.18; yield 8.00% | Independent calc. One paisa above G-09, which does not round the credits |
| G-13 | FD monthly payout | ₹2,00,000 · 8% · 12 months | 12 × ₹1,333.33; principal repaid ₹2,00,000 | Independent calc |
| G-14 | Last period not whole | ₹50,000 · 7% · 10-Jan-2027 + 400 days · quarterly cumulative | four quarters, then 35 days: ₹359.73; interest ₹3,952.68 | Independent calc |
| G-15 | Interest once at maturity | ₹75,000 · 6.25% · 91 days · Actual/365 | ₹1,168.66 | Independent calc |
| G-16 | Accrual inside a period | G-12 deposit, earned before 16-May-2026 and before 15-Aug-2026 | ₹927.20; ₹2,809.32 | Independent calc |
| G-17 | Savings, one day | ₹2,50,000 at 3%; and 2.75% to ₹1,00,000 then 3.5% (incremental) | ₹20.54794521; ₹21.91780822 | Independent calc |
| G-18 | Savings, 30 days | ₹40,000 for 10 days, ₹2,50,000 for 20 days, bands as G-17 | ₹468.49 | Independent calc |
| G-19 | Recurring deposit | ₹1,000 × 12 at 7%; ₹5,000 × 24 at 7.5%; monthly product, quarterly compounding | ₹12,462.40; ₹1,29,779.56 | Independent calc. A bank's closed formula gives a few paise less |
| G-20 | NBFC premature, after 6 months | ₹1,00,000 · 9% · closed after 306 days; rate for the period run 8% | rate 6%; interest ₹5,030.14 | Independent calc; rule: RBI NBFC Directions para 40 |
| G-21 | NBFC premature, no rate for the period run | as G-20; lowest card rate 7.5% | rate 4.5%; interest ₹3,772.60 | Independent calc; para 40 |
| G-22 | Bank premature, payout deposit | ₹2,00,000 · 7.25% quarterly payout · closed after 263 days; period rate 6.5%, penalty 1 point; ₹7,250 already paid | rate 5.5%; interest ₹7,910.96; net ₹2,00,660.96 | Independent calc; penalty is a product setting |
| G-23 | Bank premature, interest recovered | as G-22, closed 5-Oct-2026, period rate 4% | rate 3%; due ₹3,065.75; recovered ₹4,184.25; net ₹1,95,815.75 | Independent calc |
| G-24 | TDS crossing the threshold | bank payer; ₹45,000 earlier in the year, ₹6,000 now | ₹5,100 (10% of ₹51,000) | Independent calc; thresholds unconfirmed (P4-02) |
| G-25 | TDS capped by the credit | ₹48,000 earlier, ₹3,000 now; then ₹2,000 | ₹3,000; then ₹2,000 | Independent calc |
