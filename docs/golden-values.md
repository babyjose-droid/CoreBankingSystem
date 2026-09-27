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
