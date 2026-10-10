# Phase 4 (CASA and term deposits) – status

Updated 10-Oct-2026. Plan: the Phase 4 design and increment plan (eight increments, P4-0 to P4-7).

## Decisions taken on 10-Oct-2026
| # | Decision | Answer |
|---|----------|--------|
| 1 | First tenant type | Both: bank and deposit-taking NBFC |
| 2 | Build order | CASA and term deposits in parallel, on a shared deposit core |
| 3 | Cheque clearing | Simulator only |
| 4 | Overdraft on current accounts | Later, with the line-of-credit design |
| 5 | Savings interest and TDS | Posting frequency is a product setting (default quarterly); TDS follows the Income-tax rule |

Module codes are the ones the control plane already had: `CASA` and `TD` (the plan called the second one `DEPOSITS_TD`).

## Increment P4-0: deposit core

### What was built
**Deposit engine** (`backend/deposits-core`, pure Java, depends on `calc` only)
- `DepositRules`: the rule set in force for a tenant, read from `deposits.rule`. Checks a term deposit (tenure, rate, compounding rests, nominees) and a demand-deposit product, and reports every problem at once.
- `DepositRateTable`: the rate card. A full rate per amount step and tenure step, plus named add-ons. `coverageProblems` lists every amount or tenure a product accepts that has no rate; a product is to be approved only when the list is empty.
- `TermDeposit`: interest periods, total interest, maturity value, annualised yield and the interest earned up to a date. The day-end books the difference between "earned up to tomorrow" and what it has booked, so the accrued total on a credit date equals the credit.
- `SavingsInterest`: one day's interest on an end-of-day balance, with rate bands applied to the whole balance or incrementally.
- `PrematureWithdrawal`: what a term deposit pays when closed early, for both rule sets, with one sentence saying which rule gave the figures.
- `TdsOnInterest`: tax to deduct from a credit, from the depositor's interest and tax so far in the tax year.
- `RecurringDeposit`: instalment schedule, maturity value and the late charge.

**Tenant database** (`V28__deposit_foundation.sql`, schema `deposits`)
- `platform.legal_entity` records whether an NBFC is registered to accept public deposits, with the registration reference.
- `deposits.rule` with 24 NBFC rules and 14 bank rules; `deposits.rule_kind`, `rule_value`, `rule_required`, `rules_in_force`, `assert_can_offer`, `set_deposit_taking`.
- `ledger.load_deposit_heads`: liabilities 2400–2409 and 2205, expense 5107–5108, income 4107–4108, outward clearing 1207. A bank gets all of them; a deposit-taking NBFC gets the term deposit heads only; any other tenant gets none. Heads a tenant already has are left as they are.

**Control plane** (`V4__deposit_module_rules.sql`)
- `control.module_allowed`: CASA for BANK, SFB and COOP_BANK; TD for those and for an NBFC whose registration is recorded.
- A module that is not allowed cannot be enabled, and the entity type or registration cannot change under an enabled module.
- `control.set_deposit_taking` records or withdraws the registration and writes the operator log.
- Entitlements recorded earlier that break the rule (CASA and TD given to an NBFC with the ENTERPRISE edition) are switched off by the migration, each with a line in the operator log.

**Application** (`TenantProvisioner`)
- A new tenant gets the modules of its edition that its institution type may have.
- A new bank tenant's chart gets the deposit heads.

### Conventions the engine encodes
These are the product's choices. They are settings or one place in the code, and can be changed before a product is live.

- **Interest periods** run in whole months from the start date. A whole period earns principal × rate × months / 12. A last period that is not whole earns for its actual days on the product's day count.
- **Each credit is rounded** when it is made, because that is the amount posted. A cumulative deposit's maturity value is the sum of rounded credits (G-12 is one paisa above G-09 for that reason).
- **Rate card steps** are decided on real dates: "12 months" is a year in a leap year too.
- **Savings interest** is kept to eight decimal places per day and rounded when credited.
- **Recurring deposits** use the monthly product with quarterly compounding, not a closed formula.
- **TDS** is worked out on the whole year's interest once the threshold is passed, less what was already deducted, and is never more than the credit it is taken from. A shortfall is deducted from the next credit.

### Regulatory rules encoded
**Deposit-taking NBFC.** RBI (Non-Banking Financial Companies – Acceptance of Public Deposits) Directions, 2025, dated 28-Nov-2025. The full text was read on 10-Oct-2026 in a reproduction of the notification; the RBI PDF was not opened. These 19 rows are `verified = true`.

| Para | Rule |
|------|------|
| 15 | No deposit repayable on demand |
| 16 | Tenure 12 to 60 months |
| 17 | Deposits at most 1.5 × net owned fund; renewal only with the depositor's consent |
| 19 | Rate at most 12.5% a year; interest paid or compounded at rests not shorter than monthly |
| 22 | Maturity notice at least 14 days before |
| 31(9) | Deposits are not insured |
| 35 | No premature repayment or loan in the first three months, except on death |
| 36(2) | Loan against the deposit up to 75%, at the deposit rate plus 2 points |
| 37 | Emergency within three months, individuals, no interest: tiny deposits (all deposits up to ₹10,000) in full; others 50% of principal up to ₹5 lakh; critical illness 100% |
| 40 | Premature repayment: no interest from 3 to 6 months; afterwards 2 points below the rate for the period run, or 3 points below the lowest rate when there is none |
| 50 | One nominee |

**Interpretations to confirm (NBFC):**
- Death in the first three months: the principal is repaid without interest. Para 35 allows the repayment; para 40 sets a rate only after three months.
- "Rate for the period run" is taken from the rate card as it stood when the deposit was booked, with the deposit's add-ons. The caller supplies it; the engine does not look it up.
- The 1.5 × NOF cap and renewal consent are rules in the table; nothing enforces them until bookings exist (P4-1, P4-6).

**Banks.** All 14 rows are `verified = false`: the RBI (Commercial Banks – Interest Rate on Deposits) Directions, 2025 could not be opened (open item P4-01). Seeded from summaries: one savings rate up to ₹1 lakh; premature withdrawal mandatory for individuals' term deposits up to ₹1 crore; four nominees (Banking Laws (Amendment) Act, 2025); review after one year, inoperative after two, unclaimed after ten. **Not seeded:** minimum tenor, periodicity of savings interest, interest on overdue deposits, the DICGC cover amount. The BANK rule set is also applied to small finance banks and co-operative banks, whose own Directions were not read.

**Tax deducted at source.** Section 393 of the Income-tax Act, 2025: 10%; threshold ₹50,000 (₹1,00,000 for senior citizens) for banks and ₹10,000 for other payers; Form 121 in place of Forms 15G and 15H. From practitioner sites, `verified = false` (open item P4-02). The 20% rate without PAN is carried over from the 1961 Act. A declaration without a PAN is ignored, also carried over.

### Tests
- **Engine:** 38 tests in `deposits-core` (`DepositInterestGoldenTest` 13, `DepositRulesTest` 25). Golden values G-12 to G-25 in `docs/golden-values.md`.
- **Tenant database:** 58 checks in `deposits_foundation_test.sql` and 7 in `deposits_bank_starter_kit_test.sql`. The 15 earlier tenant suites still pass.
- **Control database:** 26 checks in `control_deposit_modules_test.sql` and 4 in `control_deposit_modules_upgrade_test.sql`. `control_p27_test.sql` still passes.

### Not verified here
- **Gradle:** the build sandbox cannot reach Maven Central or the Gradle plugin portal. The new module was compiled with `javac -Xlint:all -Werror` and its tests were run on a stand-in for the JUnit API. CI runs the real build and `:backend:deposits-core:test`.
- **Spring:** `TenantProvisioner` changed in two statements. It was not compiled here; the changed SQL was prepared against a migrated control database.
- **Live stack:** nothing in this increment has been run on the local stack. After `git am` and a rebuild, the check is that V28 and control V4 apply and lending still works.

### Not yet built
- No deposit product, account, posting, day-end step, API, console screen or permission. They start with P4-1.
- The application does not load `deposits.rule` yet, and rules cannot be changed through the API.
- Recording an NBFC's registration through the platform API (open item P4-04). The local demo tenant `demo-nbfc` is therefore not deposit-taking, and there is no demo bank tenant.
- Monthly payout at a discounted rate, floating-rate deposits, non-resident deposits, deposit-taking HFCs.
- Recurring deposit maturity value with late or missed instalments (P4-5).

### Next: P4-1 products and opening
Savings, current and term deposit products with the rate-coverage check at approval; account opening and booking with nominees and preview; the rule set shown in the console; a demo bank tenant and a deposit-taking demo NBFC on the local stack.
