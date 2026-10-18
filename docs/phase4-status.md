# Phase 4 (CASA and term deposits) – status

Updated 18-Oct-2026. Plan: the Phase 4 design and increment plan (eight increments, P4-0 to P4-7).

| Increment | State |
|-----------|-------|
| P4-0 deposit core | Built, pushed (`9cb5f8e`, `3851a93`), live-tested on the local stack on 10-Oct-2026 |
| P4-1 products and opening | Built, pushed (`586b8e8`), live-tested on the local stack on 11-Oct-2026 |
| P4-2 money movement | Built and pushed (`cdbe397`, fix `fa9482d`); live-tested on the local stack on 14-Oct-2026 (32 of 32 after the fix) |
| P4-2b gateway for deposits | Built, pushed (`a004bdf`), live-tested on the local stack on 15-Oct-2026 (40 of 40) |
| P4-3 day-end interest | Built, pushed (`4cb6da2`), live-tested on the local stack on 15-Oct-2026 (17 of 17) |
| P4-3b tax deducted at source | Built, pushed (`6c52240`), live-tested on the local stack on 16-Oct-2026 (21 of 21) |
| P4-4 lifecycle | Built on 18-Oct-2026 (charges with GST, minimum balance, closures, premature withdrawal, maturity, renewal, notices); not yet pushed or live-tested |
| P4-5 to P4-7 | Not started |

## Decisions taken on 10-Oct-2026
| # | Decision | Answer |
|---|----------|--------|
| 1 | First tenant type | Both: bank and deposit-taking NBFC |
| 2 | Build order | CASA and term deposits in parallel, on a shared deposit core |
| 3 | Cheque clearing | Simulator only |
| 4 | Overdraft on current accounts | Later, with the line-of-credit design |
| 5 | Savings interest and TDS | Posting frequency is a product setting (default quarterly); TDS follows the Income-tax rule |
| D-16 | Deposit rate model | Option A: the rate card holds the full rate per amount and tenure step, plus named add-ons. Not base + slab |
| D-17 | TDS on savings interest | Not deducted by default |
| D-18 | Bank premature withdrawal | Penalty 1 point, no interest under 7 days; both editable per product. Placeholders, not regulatory values |
| D-19 | Recurring deposit late charge | A fixed amount per Rs 100 per month of delay, per product. A placeholder formula |
| D-20 | Joint accounts and minors | Joint accounts yes; accounts for minors later |

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

**Banks.** All 14 rows (15 with the row added by V29: no interest on current accounts) are `verified = false`: the RBI (Commercial Banks – Interest Rate on Deposits) Directions, 2025 could not be opened (open item P4-01). Seeded from summaries: one savings rate up to ₹1 lakh; premature withdrawal mandatory for individuals' term deposits up to ₹1 crore; four nominees (Banking Laws (Amendment) Act, 2025); review after one year, inoperative after two, unclaimed after ten. **Not seeded:** minimum tenor, periodicity of savings interest, interest on overdue deposits, the DICGC cover amount. The BANK rule set is also applied to small finance banks and co-operative banks, whose own Directions were not read.

**Tax deducted at source.** Section 393 of the Income-tax Act, 2025: 10%; threshold ₹50,000 (₹1,00,000 for senior citizens) for banks and ₹10,000 for other payers; Form 121 in place of Forms 15G and 15H. From practitioner sites, `verified = false` (open item P4-02). The 20% rate without PAN is carried over from the 1961 Act. A declaration without a PAN is ignored, also carried over.

### Tests
- **Engine:** 38 tests in `deposits-core` (`DepositInterestGoldenTest` 13, `DepositRulesTest` 25). Golden values G-12 to G-25 in `docs/golden-values.md`.
- **Tenant database:** 58 checks in `deposits_foundation_test.sql` and 7 in `deposits_bank_starter_kit_test.sql`. The 15 earlier tenant suites still pass.
- **Control database:** 26 checks in `control_deposit_modules_test.sql` and 4 in `control_deposit_modules_upgrade_test.sql`. `control_p27_test.sql` still passes.

### Not verified here
- **Gradle:** the build sandbox cannot reach Maven Central or the Gradle plugin portal. The new module was compiled with `javac -Xlint:all -Werror` and its tests were run on a stand-in for the JUnit API. CI runs the real build and `:backend:deposits-core:test`.
- **Spring:** `TenantProvisioner` changed in two statements. It was not compiled here; the changed SQL was prepared against a migrated control database.
### Verified after the push (10-Oct-2026)
- **CI.** `9cb5f8e`: the Gradle build with the new module passed; `sql-invariants` failed because CI's database sorts text in the en_US locale (CO_LENDING before COLLECTIONS) and the build sandbox in C (cause inferred from the result; the job log was not readable). `3851a93` sorts module codes with `COLLATE "C"` in the SQL tests: `sql-invariants`, console, API contract, secrets scan and JavaScript CodeQL passed. The Gradle build and Java CodeQL of `3851a93` were still running when last looked at.
- **Live stack** (signed in as dev-admin): backend health UP after the rebuild; dashboard, loans, customers, loan products, approvals and reports load; both loans open and their schedule PDFs generate; the trial balance for 30-Jun-2026 balances (debit 6,20,000 = credit 6,20,000). `demo-nbfc` is refused `/api/v1/casa/...` and `/api/v1/deposits/...` (module not enabled) and its chart has no deposit heads, as intended. Migration versions were not read directly: the backend starting and serving the tenant is the evidence that V28 and control V4 applied.

## Increment P4-1: products and opening

No money moves in this increment: a savings or current account opens with no balance, and a term deposit is BOOKED and waits for its funds (P4-2).

### What was built
**Deposit engine** (`backend/deposits-core`, four new classes)
- `TermDepositProduct` and `DemandDepositProduct`: whether a product may be approved under a rule set, every reason at once. For a term deposit product: a rate for every amount and tenure it accepts, no rate above the ceiling with every add-on, tenures and interest rests inside the rule set, premature-withdrawal settings that fit the institution. For savings and current: the uniform savings rate up to the rule set's limit, no interest on a current account, and no demand deposits at all for an NBFC.
- `TermDepositBooking`: whether one deposit may be booked, and its figures: the rate with its parts (card rate, amount step, tenure step, add-ons), maturity date, every interest credit, maturity value, yield, and the tax expected on each credit. The preview, the proposal and the approval all call it. The senior citizen add-on is given only to an individual of the rule set's senior-citizen age on the start date.
- `AccountHolders` and `Nomination`: holders against the operating mode (single; either, former or anyone or survivor; jointly; at most four holders, a product choice); nominees taking together with shares that total 100, or one after another, up to the rule set's limit (four for a bank, one for an NBFC).
- `DepositRules.checkTermDeposit` on real dates, and `SavingsInterest.periodEnd` (the last day of the interest period in progress, counted from the first month of the financial year).

**Tenant database** (`V29__deposit_products_and_opening.sql`)
- One more rule row: a current account bears no interest (BANK, `verified = false`, open item P4-01).
- `deposits.product` with `deposits.product_history` (every version is kept; history rows cannot be changed). An ACTIVE term deposit product is refused when its rate card has a gap (`deposits.rate_card_problems`, the same list as the engine's) or breaks the rule set.
- `deposits.account`, `account_holder`, `nominee`, `casa_account` and `term_deposit`. The account number is issued by the database from the series of its family (CASA 2001…, term deposits 3001…), so the two can never share a number. An account is opened only for ACTIVE customers with verified KYC, at an active branch, on an ACTIVE product. The whole account (holders against the mode, nominees against their mode and the rule set, the row of its family) is checked at commit.
- A term deposit's terms are frozen at booking: the rate must be the product's card rate for the amount and the real dates plus add-ons the product has, and the interest period, day count, rounding, tax and premature-withdrawal settings must be the product's. After booking only the funding dates, the payout and the maturity instruction can change.

**Application** (new Spring module `com.corebanking.deposits`; paths under `/api/v1/casa` need the CASA module, under `/api/v1/deposits` the TD module)
- Rule set: `GET /api/v1/casa/rules` and `/api/v1/deposits/rules` (the same answer): every rule in force with its source and whether it is verified.
- Products, per family: list, read, history, `POST` to propose a new product or the replacement of an existing one, `POST …/check` to list what stops a product without proposing it, `POST …/{code}/withdraw`. A refusal is 422 with `problems`, every reason at once. The check runs again when the request is approved.
- Savings and current accounts: list, read, `POST /api/v1/casa/accounts` to propose an opening.
- Term deposits: list, read, `POST /api/v1/deposits/preview` (the figures, nothing stored), `POST /api/v1/deposits/accounts` to propose a booking. The request keeps the rate the maker saw; the approval is refused (409) when the product no longer gives that rate.
- Everything goes through maker-checker: entity types `DEPOSIT_PRODUCT` (CREATE, UPDATE, WITHDRAW), `CASA_ACCOUNT` (OPEN) and `TERM_DEPOSIT` (BOOK, with the amount). Lists are filtered by branch scope; an account outside the scope is not found.
- Permissions: products use `product:view` and `product:propose`; new `casa:view`, `casa:open`, `deposit:view`, `deposit:book` (realm roles: MAKER and TENANT_ADMIN get all four; CHECKER, AUDITOR and OPERATIONS the two view permissions).

**Control plane** (open item P4-04, closed)
- `PUT /platform/v1/tenants/{code}/deposit-registration` records or withdraws an NBFC's registration to accept public deposits: the control plane first (it validates and writes the operator log), then the tenant's database, which puts the NBFC rule set in force and adds the term deposit heads. The TD module is enabled afterwards with the existing modules call. No control migration was needed; V5 is still the next number.
- `GET /platform/v1/tenants` shows `depositTaking` and `depositRegistrationRef`.

**Local stack**
- Two more demo tenants, provisioned on start like `demo-nbfc`: `demo-bank` (a bank on the ENTERPRISE edition: CASA and TD) and `demo-nbfc-d` (an NBFC recorded as deposit-taking, with TD). Each has its own database, its own Keycloak realm with the same demo users, and demo deposit products (bank: SB01, CA01, FDC01, FDP01; NBFC: FD01, FD02). The rates and the registration reference are demo data.
- A one-shot compose service creates the two databases when they are missing, so the postgres volume needs no reset. Keycloak has to be re-created once (new realms, new permissions).
- The console's development sign-in has a Tenant choice (`VITE_DEV_TENANTS`).

**Console**
- Deposits menu, shown by permission and by the tenant's modules: Savings and current, Term deposits, Products, Rule set.
- Rule set page; product list, detail (rate card as a grid, versions, withdraw) and form (rate card edited as a grid, "Check product" before submitting); account lists and details; opening a savings or current account; booking a term deposit with a preview of the rate, maturity, every interest credit and tax. A refusal lists every reason. A deposit-taking NBFC's screens say that deposits are not insured.
- Mock mode has the same three demo tenants (chosen on the sign-in page) and follows the engine's checks and figures.

### Choices made in this increment (to confirm)
- **Renewal on a deposit-taking NBFC.** The rule set needs the depositor's consent for a renewal (para 17). Recording that consent comes with maturity processing (P4-4), so until then such a deposit can only be booked to be paid out. A bank deposit can be booked with a renewal instruction when its product allows renewal.
- **Payout to a bank account.** A deposit can be booked with proceeds going to the depositor's bank account, but the account itself is not captured yet: it is recorded with funding (P4-2). Payout to a savings or current account of the depositor is checked now.
- **Minors.** A customer under 18 is refused as a holder (D-20); a minor can be a nominee, with a guardian named.
- **Product permissions.** Deposit products reuse the loan product permissions rather than new ones.
- **One Spring module.** CASA and term deposits share `com.corebanking.deposits` (products and accounts share tables); the plan had two.
- **Savings day count.** Savings interest needs a fixed basis: actual/365 or actual/360.

### Tests
- **Engine:** 60 tests in `deposits-core` (22 new in `DepositProductsAndBookingTest`).
- **Tenant database:** 110 checks in `deposits_products_accounts_test.sql`; the 17 earlier tenant suites and the 3 control suites still pass (`deposits_foundation_test.sql` and `deposits_bank_starter_kit_test.sql` now expect 15 bank rules).
- **Application services:** 46 checks run in the build sandbox against migrated bank and deposit-taking NBFC databases (products proposed, approved, updated and withdrawn; accounts opened; deposits previewed and booked; every refusal). They are not in the repository: they ran on stand-ins for Spring (see below).
- **Console:** 16 tests in `deposits.test.tsx` and 3 more in `devLogin.test.tsx`. The mock's interest and tax figures are pinned to the engine's for three sample deposits.
- **API contract:** `docs/api/openapi.yaml` lints clean; every handler has an operation and every operation a handler.

### Not verified here
- **Gradle and Spring.** The build sandbox still cannot reach Maven Central. The new module was type-checked against stand-ins that have the method signatures of `JdbcTemplate`, the Spring annotations and the platform classes it uses, and its SQL was run through `psql` with parameters typed as the JDBC driver types them. That catches wrong SQL, wrong parameter counts and wrong types; it does not prove the Spring wiring, Jackson's handling of the records, or Modulith's module check. CI runs the real build.
- **Not type-checked at all:** the changes to `TenantProvisioner`, `TenantAdminController` and `DemoTenantBootstrap` (they need Hikari and Spring Boot classes). Their two SQL statements were run by hand.
- **Live stack.** Nothing in this increment has been run on the local stack: not the three-tenant bootstrap, the new realms, the compose service or the sign-in with a tenant choice.

### Not yet built
- Money: deposits, withdrawals, funding a term deposit, interest, tax, charges (P4-2 onwards). No posting, no day-end step.
- Changing a nomination, a payout or a maturity instruction after opening; closing or cancelling an account.
- Changing a rule through the API (ADR-017 expected it from P4-1; it is not built).
- Recurring deposits as a product type (P4-5).
- Receipts and other documents (P4-7).
- The 1.5 × net owned fund cap on an NBFC's deposits (P4-6).
- Support access (read-only platform engineers) to the deposit endpoints.
- Sessions and API clients on the two new demo tenants: their realms have no local Keycloak admin client.

### Verified after the push (11-Oct-2026)
- **Local stack.** The Docker build compiled the backend with Gradle and it started, so the Spring wiring and Jackson handling the sandbox could not prove are proven. V29 applied to `demo-nbfc`; `demo-bank` and `demo-nbfc-d` were created, migrated to V29 and seeded with 4 and 2 products.
- **Through the API, as the development users** (maker proposes, checker approves): tenant separation (`demo-nbfc` 403 on every deposit path; `demo-nbfc-d` 403 on `/casa`); the deposit heads of each chart; a product checked for a rate-card gap, proposed and approved (the maker could not approve it); a joint savings account refused with two reasons at once, then opened; a bank term deposit previewed (7.1% + 0.5% senior, maturity 1,07,819.36), a senior add-on refused for a younger depositor, booked and approved; an NBFC deposit previewed (8.75%, not insured), refused with four reasons, then booked; an NBFC product at 13% refused on the ceiling and both tenure limits.
- **Console pages read:** rule set, product list and detail with the rate-card grid, both account lists and details, the booking form on both tenants.
- **Not covered:** CI for `586b8e8` was not read; the forms were not driven by clicks; the deposit-registration route was not called over HTTP (the bootstrap calls the same method).
- **Found and fixed in P4-2:** a customer with an unknown gender code was accepted and failed only at approval; product timestamps were not ISO-8601; the booked-deposit banner showed a raw date; the README named a log line written only on later starts.

## Increment P4-2: money movement

Money now moves on deposit accounts. Bank transfers are recorded by staff with the bank's reference (UTR); sending and receiving them through the payment gateway is P4-2b (decided 14-Oct-2026: the payout and collection pipeline is wired to loans and is extended to deposits separately). No interest, tax or charges yet (P4-3, P4-4).

### What was built
**Deposit engine** (`backend/deposits-core`, now also on `ledger-core`)
- `DepositPostings`: one balanced lot per movement. Cash deposit Dr cash in hand / Cr the account; withdrawal the reverse; bank transfer in Dr the collection bank; bank transfer out Cr the disbursement bank; transfer Dr the paying account / Cr the receiving account, with inter-branch legs on the tenant's inter-branch head when the branches differ; funding Dr the paying account, cash or the collection bank / Cr the term deposit. A reversal is the mirror lot.
- `AccountHolds`: freezes (debit, credit, total) and liens, and whether money may move. No movement takes the balance below zero; a debit never goes below the liens; a lien is placed only within the available balance; a freeze of a kind already in force is refused. A reversal (a correction) is not stopped by a freeze but still by the balance and the liens. Every reason at once.

**Tenant database** (`V30__deposit_money_movement.sql`)
- Settings: the cash and bank heads deposits settle against (`deposits.gl.cash` 1201, `deposits.gl.bank-in` 1203, `deposits.gl.bank-out` 1202) and the funding window (`deposits.funding-within-days`, 30).
- `deposits.movement`: one row per account per movement, tied to its ledger lot, with the balance after it. Value date is the business date. Never changed or deleted, except to record the movement that reverses it. Checked at COMMIT: the movements of an account in a lot equal what the lot posts to the account on its product's liability head.
- `deposits.account_balance`: kept by the movements only; never below zero. `deposits.balance_problems()` lists every account whose balance is not what the ledger says (empty when the books agree).
- `deposits.account_hold`: freezes and liens, released not deleted; one freeze of a kind at a time; a term deposit takes liens only, once funded.
- Funding: how a deposit was funded, from which account, the funding movement and reference, and the payout bank account (sealed, last four digits and IFSC kept; open item P4-07 closed). A deposit is funded on the business date, with its principal, from an account its depositor holds, within the funding window; it is ACTIVE exactly while funded and then holds its principal. The reversal of the funding takes it back to BOOKED.
- Approval rules: money in and out and a funding apply at once below 2,00,000 and need a checker from it upward; a reversal, a hold and a funding reversal always need one. The threshold is a starting value for the tenant, not a regulatory figure. Amount-limit types `DEPOSIT_CREDIT` and `DEPOSIT_DEBIT`.

**Application** (`com.corebanking.deposits`)
- `DepositMoneyService` and `DepositMoneyController`: transactions (`POST /api/v1/casa/accounts/{id}/transactions`), transfers (`POST /api/v1/casa/transfers`), reversals (`POST /api/v1/casa/movements/{movementId}/reverse`), statement (`GET …/movements`), holds (`GET`/`POST …/holds`, `POST …/holds/{holdId}/release`) on savings and current accounts; funding (`POST /api/v1/deposits/accounts/{id}/fund`), its reversal (`…/funding/reverse`), movements and liens on term deposits.
- Every request through maker-checker (entity types `DEPOSIT_TXN`, `DEPOSIT_HOLD`, and `TERM_DEPOSIT` with actions `FUND` and `REVERSE_FUNDING`). The maker's role amount limit applies when the request is raised, the checker's when it is approved. Each check runs when the request is raised and again when it is applied.
- Account detail and list show the balance, the liens, the available balance and the freezes in force; a term deposit shows how it was funded and the payout bank account masked.
- Permissions: new `casa:transact`, `casa:hold`, `deposit:fund`, `deposit:hold` (MAKER and TENANT_ADMIN). The three demo realms carry them.

**Console**
- Account page: balance card, statement with the balance after each movement and a Reverse action, freezes and liens with Release, and buttons for money in, money out, transfer, freeze or lien; a term deposit page has Fund the deposit, Lien and Reverse the funding. A request that posts at once says "done"; one that waits says it was sent for approval.
- The account list shows each savings or current account's balance and whether a hold is in force.
- Mock mode follows the same checks, posts at once below the threshold, and keeps balances from the movements.

### Choices made in this increment (to confirm)
- **The threshold** for a checker on money in and out and on funding: 2,00,000, the same for cash and transfers. A tenant changes it in its approval rules.
- **Value date** is always the business date: no back-valued movement.
- **Funding window:** a booking lapses if not funded within 30 days (its rate is held that long). Book again after that.
- **A lien** may not exceed the available balance when it is placed; a reversal that would break a lien is refused until the lien is released.
- **A term deposit** takes liens only (no freezes) and money only by its funding until maturity processing (P4-4).
- **Cash** is posted against the account's own branch; there is no teller or cash-drawer control yet.
- **The funding reversal** is refused once anything else has moved on the deposit.

### Tests
- **Engine:** 72 tests in `deposits-core` (12 new in `DepositMovementsTest`).
- **Tenant database:** 68 checks in the new `deposits_money_movement_test.sql`; all 18 other tenant suites and the 3 control suites pass (`deposits_products_accounts_test.sql` no longer sets funding dates by hand).
- **Application services:** 67 checks run in the build sandbox against migrated bank and deposit-taking NBFC databases, on stand-ins for Spring (as for P4-1, not in the repository); the 46 checks of P4-1 still pass.
- **Console:** 9 tests in `depositMoney.test.tsx`; the whole suite, typecheck and build pass.
- **API contract:** 13 new operations; `docs/api/openapi.yaml` lints clean and every handler has an operation.

### Not verified here
- **Gradle and Spring:** as before, the build sandbox cannot reach Maven Central. The module was type-checked against stand-ins and its SQL run through `psql` with JDBC-typed parameters. CI runs the real build.
- **No automated service test in the repository** (open item P4-08): a Testcontainers test that boots the services was not written, because it could not be run here and would first be run by CI.
- **Live stack:** nothing in P4-2 has run there yet.

### Verified after the push (14-Oct-2026)
- **Local stack.** The Docker build compiled `cdbe397` with Gradle; V30 applied to `demo-bank`, `demo-nbfc` and `demo-nbfc-d` and the backend registered all three.
- **First live test** (a script run by the product owner on the Mac, through the console's proxy): 20 checks passed, 10 failed. Passed: a request above the threshold waits for a checker and posts when approved; refusals with every reason (balance, missing UTR and payee); debit freeze stops a withdrawal and is released; a lien shows on the account; funding refused without the payout bank account; both trial balances balance; `demo-nbfc` still refused deposits, its loans load. **Failed, one cause:** every request that a rule applies at once (no checker: money below 2,00,000, funding) was refused with 409 `maker_is_not_checker`. `ApprovalService` recorded the maker as the checker of an auto-applied request, which the database forbids; no approval rule had needed zero checkers before, so the path had never run. The stand-in for `ApprovalService` in the build sandbox always leaves a request pending, so the service checks did not reach it.
- **Fix:** an auto-applied request is recorded with the rule (`rule:auto`) as its checker (`ApprovalService.AUTO_CHECKER`). Two checks added to `deposits_money_movement_test.sql` (S4, S5).
- **Second live test** (after `fa9482d`): 32 of 32 passed. Money below the threshold posts at once with `rule:auto` as checker; a bank and an NBFC term deposit were funded (the bank's from a savings account, the NBFC's in cash with the payout bank account `XXXXXXXX4433`, ICIC0004321); both trial balances balance (bank 7,63,000 on each side, NBFC 50,000).

### Not yet built
- Interest, tax, charges, minimum balance, closure (P4-3, P4-4).
- Cheques, standing instructions, dormancy (P4-5, P4-6).
- Cash drawer and teller limits beyond the role amount limits.

## Increment P4-2b: the payment gateway for deposits

The payout and collection pipeline built for loans in P2-2 now also serves deposit accounts (open item P4-10 closed). A bank transfer out of a savings or current account can be sent through the payout gateway instead of being recorded with a UTR, and a payment link can be made for a savings or current account (the payment credits it) or for a booked term deposit (the payment of its principal funds it).

### What was built
**Deposit engine** (`backend/deposits-core`)
- `DepositPostings`: a transfer through the gateway posts Dr the account / Cr payout in transit (2408, a new setting head); when the bank pays, Dr payout in transit / Cr the disbursement bank (no account leg); when it fails or comes back, the reversal of what was posted (the payment first). Funding source `GATEWAY` (Dr the collection bank).

**Tenant database** (`V31__deposit_gateway.sql`)
- Setting `deposits.gl.in-transit` (2408).
- `deposits.gateway_transfer`: one row per transfer sent through the gateway, SENT → PAID (with the payment lot and the UTR) → RETURNED, or SENT → RETURNED. Only a bank transfer out to a named account; returned only once its movement is reversed; never deleted; a returned one never changes.
- Funding source `GATEWAY` on term deposits.
- The integration tables take a deposit account instead of a loan, exactly one of the two: `integration.beneficiary`, `integration.payout_instruction` (with the transfer's movement, one payout per transfer, and when the in-transit head was cleared), `integration.collection_order` (purpose `REPAYMENT`, `CREDIT` or `FUNDING`; one live link per term deposit), `integration.collection_payment` (posted as a loan transaction or a deposit movement, never both). `integration.collection_reconciliation` shows the deposit account (new columns at the end).

**Application**
- Port `com.corebanking.deposits.DepositOperations` (implemented by `DepositMoneyService`), called by the integration module as lending's `LoanOperations` is: read an account, read a transfer's payee in clear, settle a transfer, return a transfer, receive money from the gateway, record a term deposit's payout bank account. Topic `deposit.transfer_out` (`DepositEvents`), with no personal data.
- `POST /api/v1/casa/accounts/{id}/transactions` takes `viaGateway: true` on a bank transfer out: no UTR is needed; the request goes through maker-checker and the amount limits as any debit; when applied, the money leaves the account and waits in transit, and the transfer is written to the outbox. A staff reversal of such a transfer is refused (its money comes back only through the gateway's outcome).
- `PayoutService`: the relay makes one INITIATED payout per transfer, with the payee sealed again under the integration key (reference `PO<account no>T<n>`); the worker sends and polls it as a loan payout; SUCCESS is settled (in-transit cleared) by the failure task while the books are open; FAILED or RETURNED puts the money back in the account at once (it is the customer's money: nothing is proposed). A return that cannot be made (the account no longer active) is parked; "Return to the account" on the Payouts page tries again. Search by `accountId`.
- `CollectionService`: `POST`/`GET /api/v1/casa/accounts/{id}/collection-orders` (permissions `casa:transact` / `casa:view`) and `/api/v1/deposits/accounts/{id}/collection-orders` (`deposit:fund` / `deposit:view`). A term deposit's link is for its principal (the amount may be left out); one paid out to a bank account records that account when the link is made, unless it is recorded already. A payment is posted on the open business date (a deposit movement takes value that day); a payment the account cannot take (a freeze, a deposit funded another way) goes to the unmatched receipts queue, which can now assign it to a deposit account (`accountId`).
- No new permissions; the realms are unchanged.

**Console**
- Money out: "Send it through the payment gateway" on a bank transfer out; then no UTR is asked for.
- Statement: a gateway transfer shows "With the gateway", "Paid by the bank" (with the UTR) or "Came back"; it has no Reverse action.
- "Payment link" on an active savings or current account and on a booked term deposit (asking for the payout bank account when needed), and a Payment links card.
- Payouts page and reconciliation show the deposit account where there is no loan.
- Mock mode: the simulator acts when the statement or the links are next read (a transfer or link whose amount ends in .01 fails).

### Choices made in this increment (to confirm)
- **The money leaves the account when the transfer is approved**, not when the bank pays; it waits on payout in transit (2408). If the payout fails or comes back, it returns at once, without a checker.
- **The payee of a gateway transfer is not validated by penny drop** (the beneficiary is recorded as `PENDING`): the maker entered the account and a checker approved it above the threshold. See open item P4-14.
- **Money received through a payment link** is credited without maker-checker, on the business date it is posted (not the payment date).
- **A term deposit funded through a payment link** starts on the business date the payment is posted; a second payment for it goes to the unmatched queue.
- **The reversal of a funding through a payment link** (by maker-checker) puts the principal back on the collection bank head; refunding the payer is outside the system for now.

### Tests
- **Engine:** 73 tests in `deposits-core` (1 new).
- **Tenant database:** 36 checks in the new `deposits_gateway_test.sql`; all 23 tenant and control suites pass, including the P2-2 integration suites.
- **Application services:** in the build sandbox (stand-ins for Spring, not in the repository): 32 checks of the deposits side (transfer, settle, return after payment, failure, freezes, payment-link credit and funding, funding reversal); and 30 checks end to end with the real `PayoutService` and `CollectionService`, the simulator as provider and the real deposits service on a migrated bank database (relay, send, poll, failure, return after payment, settlement, links for an account and a deposit, unmatched receipt assigned to another account, reconciliation); the P4-1 (46) and P4-2 (67) checks still pass.
- **Console:** 4 new tests in `depositMoney.test.tsx` (13 in all); the whole suite (303), typecheck and build pass.
- **API contract:** 4 new operations; `docs/api/openapi.yaml` lints clean and every handler has an operation.

### Not verified here
- **Gradle and Spring:** as before; the integration module was compiled against the real sources with stand-ins for Spring.
- **The outbox relay and the scheduled worker** were played by the test, not run.

### Verified after the push (15-Oct-2026)
- **Local stack.** `a004bdf` built; V31 applied to the three tenants.
- **Live test** (`p4-2b-smoke.py`): 40 of 40. Transfers of 1000.00 (paid and settled), 1000.01 (failed, returned at once), 1000.02 (paid on the poll two minutes later) and 1000.03 (paid, then returned on refresh and reversed, payment first); the statement showed the UTR and the returns; a staff reversal was refused. A payment link credited 2,500 to the savings account; new deposits 30010000000029 on demo-bank and demo-nbfc-d were funded through their links with the payout bank account recorded; a part-principal link and a second link were refused. Trial balances balanced (bank 8,00,500.18; NBFC 70,000); reconciliation listed the deposit payment.

## Increment P4-3: day-end interest

Interest on deposits now accrues and is credited at end of day. P4-3 is delivered in two parts: this one (interest) and P4-3b (tax deducted at source: the ledger per depositor and tax year, thresholds, Form 121), so interest is credited gross for now.

### What was built
**Deposit engine** (`backend/deposits-core`)
- `InterestDayEnd`: what a day-end does with an account's interest, for the calendar days since the last one accrued. Savings: each day's closing balance earns a day's interest (`SavingsInterest`, 8 places); on the last day of the interest period (quarterly by default, counted from the financial year) the period's interest is credited, rounded as the product says, and earns from the next day. Term deposits: interest accrues every day from the start date to the day before maturity (`TermDeposit.earnedBefore`); each period is credited on its credit date, the last on the maturity date. Accrual is booked as the change in interest earned so far, rounded to the paisa, so rounding never builds up; a credit rounded to the rupee is trued up first. A re-run of a day produces nothing.
- `DepositPostings`: accrual (Dr the product's interest expense head / Cr interest payable 2404), credit (Dr interest payable / Cr the account), payout of a deposit's interest to a savings or current account (with inter-branch legs).

**Tenant database** (`V32__deposit_interest.sql`)
- Setting `deposits.gl.interest-payable` (2404).
- Movements `INTEREST_CREDIT` (on a savings or current account or a funded deposit) and `INTEREST_PAYOUT` (a deposit's interest to the account named at booking); a deposit also sends its interest to a bank account as a gateway transfer (P4-2b). A freeze does not stop the bank crediting interest. A funded deposit holds at least its principal (a cumulative deposit grows).
- `deposits.interest_state` (accrued through which day, accrued and not credited, credited, periods credited) and `deposits.interest_event` (every accrual, credit, payout, payout kept on the deposit, accrual taken back; never changed). `deposits.interest_problems()` lists branches whose interest-payable head differs from what their accounts have accrued and not been credited.

**Application**
- `DepositEod` (day-end steps after lending, order 200): "Savings interest (accrual and credit)" and "Term deposit interest (accrual, credit, payout)". Per account in its own transaction; a failing account is an exception, above 5% the step fails. The days up to the next working day are closed with the business date. A payout deposit pays each credit at once: to the savings or current account named at booking, or through the payment gateway to the bank account recorded; when the account cannot take it (no longer active, frozen for credits) or no bank account is recorded, the interest stays on the deposit, to be paid with the proceeds (P4-4).
- The reversal of a funding takes back interest accrued and not credited (to the expense head); once interest has been credited the funding cannot be reversed. A staff reversal of interest is refused.
- `GET /api/v1/casa/accounts/{id}/interest` and `GET /api/v1/deposits/accounts/{id}/interest` (permissions `casa:view`, `deposit:view`): accrued, accrued through, credited, next credit date, the day-end's events and a deposit's interest periods.

**Console**
- An Interest card on an interest-bearing savings account and on a funded deposit: accrued, credited, next credit, the deposit's periods (credited or due) and the latest credits and payouts. The statement names interest credits and payouts, without a Reverse action.
- Mock mode has no deposit day-end: it shows the periods and nothing accrued.

### Choices made in this increment (to confirm)
- **Savings rates** are the product's bands on the day the day-end runs; days caught up after a missed day-end use the same bands. A rate change takes effect from the next day-end.
- **Savings interest is credited on the last day of the period** (30 June, 30 September …), for the period including that day; a deposit's interest is credited on its credit date (the monthly or quarterly anniversary of the start date), for the days before it.
- **A freeze does not stop interest being credited** to the frozen account; a payout to an account frozen for credits is kept on the deposit instead.
- **Interest is credited gross** until P4-3b deducts tax.
- **No accrual on a savings account before money is first paid in**; a zero balance earns nothing.

### Tests
- **Engine:** 81 tests in `deposits-core` (8 new in `InterestDayEndTest`, with figures worked out independently).
- **Tenant database:** 19 checks in the new `deposits_interest_test.sql`; all 24 suites pass.
- **Application services:** 20 checks in the build sandbox playing the day-end on a migrated bank database from 30 June to 31 July (stand-ins for Spring): the 30 June quarter-end credit, the first day's accrual of a cumulative deposit, a re-run posting nothing, a funding reversal taking back accrued interest, monthly payouts to a savings account, to a frozen account (kept) and through the gateway, the deposit's booked interest equal to what it has earned, the interest view, and every invariant after each stage. The P4-1, P4-2 and P4-2b checks still pass.
- **Console:** 2 new tests; the whole suite, typecheck and build pass.
- **API contract:** 2 new operations; lint clean; every handler has an operation.

### Not verified here
- **Gradle and Spring**, the real EOD engine and its scheduling: the steps were called by the test as the engine calls them.

### Verified after the push (15-Oct-2026)
- **Local stack.** `4cb6da2` built; V32 applied to the three tenants.
- **Live test** (`p4-3-smoke.py`): 17 of 17. One real end of day on demo-bank and demo-nbfc-d (both COMPLETED; business date now 1-Jul-2026). Savings 20010000000013: 30 June ended the quarter, one day's interest 47.38 credited as worked out from the product (5,47,499.98 → 5,47,547.36), shown in the statement. First day accrued exactly as worked out from each deposit's periods: 4.82 and 20.65 (demo-bank), 4.66 and 11.99 (demo-nbfc-d); next credit 30-Sep-2026. No exceptions; trial balances balanced (bank 8,00,620.41; NBFC 70,016.65).
- **Not covered live:** payout deposits (none on the local stack; their first credit needs about 30 day-ends) and the console Interest card by eye.

## Increment P4-3b: tax deducted at source

Tax is now deducted at source from deposit interest when the day-end credits it (section 393 of the Income-tax Act, 2025; rules of the rule set, all still unverified: open item P4-02).

### What was built
**Deposit engine** (`backend/deposits-core`)
- `TdsOnInterest` (from P4-0) decides the tax; new `basis` says why (exempt, Form 121, within the threshold, at the rate, at the higher rate without a PAN). `DepositPostings.taxDeducted`: Dr the account / Cr TDS payable.

**Tenant database** (`V33__deposit_tax_at_source.sql`)
- Settings `deposits.gl.tds-payable` (2205, added to a deposit tenant that lacked it) and `deposits.tds.rounding` (to the rupee).
- Movement `TAX_DEDUCTED` on a savings or current account or a funded deposit (a freeze does not stop it).
- `deposits.tds_ledger`: every interest credit of a product that deducts tax, with the tax (zero or not), the basis, the rate, the payee's senior and PAN status and the declaration in force; never changed. `deposits.tds_problems()` checks the TDS payable head against it, per branch.
- `deposits.tds_declaration`: Form 121 per depositor and tax year, one in force at a time, only for an individual with a PAN, revoked once (with a reason) and never deleted. Approval rule: a checker for recording and for revoking.

**Application**
- `TaxAtSource`, called by the day-end after each interest credit of a product that deducts tax (term deposits with `tds_applicable`; savings only when the rule `tds.on-savings-interest` is 1, which it is not): the payee is the first holder; their interest for the tax year is added up across all their deposits here; the tax is deducted from the account and recorded; a payout deposit pays what is left.
- `DepositTaxService` and `DepositTaxController`: `GET`/`POST /api/v1/deposits/tax/declarations`, `POST /api/v1/deposits/tax/declarations/{id}/revoke`, `GET /api/v1/deposits/tax/summary` (the depositor's year: threshold and rate that apply, interest and tax by quarter, by account and per credit with its basis). New permission `deposit:tax` (MAKER and TENANT_ADMIN) in the three demo realms; the realms must be reloaded.
- The interest view shows the tax deducted; a staff reversal of a deduction is refused.

**Console**
- A "Tax at source" page (Deposits menu, and a link on an account page): pick a depositor; their tax year (interest, tax, threshold, rate, Form 121), by quarter and per credit; Form 121 declarations with Record and Revoke (maker-checker).
- The Interest card shows the tax deducted; the statement names it.

### Choices made in this increment (to confirm)
- **Tax is deducted when interest is credited**, not on interest accrued and not yet credited. A deposit that credits its interest only at maturity (period 0) is taxed only then: see open item P4-17.
- **The payee is the first holder** of a joint account.
- **A deduction is rounded to the rupee.**
- **Form 121** is recorded for the current tax year or the next, with an acknowledgement number; the document itself is not stored yet.
- **Revoking Form 121** catches the year's tax up from the next credit (never more than that credit).
- **Paying the tax over to the government** (challans, returns, certificates in Form 16A's successor) is not built: the payable head only accumulates.

### Tests
- **Engine:** 82 tests in `deposits-core` (1 new for the basis, one more posting check).
- **Tenant database:** 23 checks in the new `deposits_tax_test.sql`; all 25 suites pass.
- **Application services:** 28 checks in the build sandbox, playing the day-end from 30 June to 31 August on a bank database with deposits of 1 crore paying monthly (66,666.67 a month): a resident crosses 50,000 on the first credit (6,667 deducted, 59,999.67 paid out) and pays 6,666 on the second; a senior crosses 1,00,000 on the second (13,333 on the whole year); Form 121 deducts nothing until revoked, then the year is caught up (13,333); no PAN: 20% (13,333, and the gateway payout sends what is left); Form 121 refusals; the depositor's tax record by quarter; every invariant, including TDS payable against the ledger. The P4-1, P4-2, P4-2b and P4-3 checks still pass.
- **Console:** 2 new tests; the whole suite, typecheck and build pass.
- **API contract:** 4 new operations; lint clean; every handler has an operation.

### Not verified here
- **Gradle and Spring**, the real EOD run, and the realm import with the new permission.
- **Live stack:** the local deposits earn far less than the 50,000 threshold in a year, so the live test cannot see a deduction from interest alone in a few day-ends; `p4-3b-smoke.py` records and revokes Form 121 through maker-checker, reads the depositor's tax record, and runs one day-end to check that nothing fails and the books (and TDS payable) agree.

### Verified after the push (16-Oct-2026)
- **Local stack.** `6c52240` built; V33 applied; the realms reloaded with `deposit:tax`.
- **Live test** (`p4-3b-smoke.py`): 21 of 21. Form 121 recorded and revoked through maker-checker; the depositor's tax year read (threshold 1,00,000 for the senior depositor on demo-bank, 10,000 on demo-nbfc-d); one end of day on both tenants with no exception, and the books, the interest payable and the TDS payable heads agreeing.

## Increment P4-4: the life of an account after opening

Charges, the minimum balance, closing accounts, closing a term deposit before maturity under either rule set, maturity, renewal, matured deposits left unpaid and maturity notices.

### What was built
**Deposit engine** (`backend/deposits-core`)
- `DepositCharges`: a savings or current product's schedule of charges (minimum balance as a percentage of the month's shortfall with a cap, a closure charge with a free period and a limit, named service charges), GST on top (CGST and SGST within the state, IGST otherwise), the month's average balance, and a charge cut to what the account can pay.
- `Maturity`: a deposit maturing on a holiday is paid on the next working day with interest at the contracted rate for the days between; a matured deposit left unpaid earns, at a bank, the lower of the contracted rate and the tenant's overdue rate for the days it waited, and at a deposit-taking NBFC nothing (rule `td.overdue-interest`).
- `PrematureWithdrawal` (from P4-0) gives the figures of an early closure under both rule sets; `InterestDayEnd.savingsToClose` the savings interest to the day before a closure; `DepositPostings`: charge, closure, interest recovery and renewal lots.

**Tenant database** (`V34__deposit_lifecycle.sql`)
- Settings: the heads charges post to (4107 and the GST heads 2201 to 2203), the overdue rate (2.75%, the demo savings rate) and the notice period when the rule set has none (14 days). Rule `td.overdue-interest`: bank 1, NBFC 0 (unverified, P4-19).
- `deposits.product.charges` (savings and current only); `deposits.charge`: every charge with its GST and tax invoice number, cancelled with a credit note when its movement is reversed; the GST output register and summary include deposit charges.
- `deposits.min_balance_month`: each month-end's average balance against the minimum, whether the depositor was told, and the charge.
- Statuses: a savings or current account is CLOSED with nothing on it and nothing held; a term deposit is MATURED while its proceeds wait. `deposits.closure` records how each account was closed: the rate, the interest due, credited and recovered, interest after maturity, charges, tax, what was paid and where, or the deposit it was renewed into.
- Movements CHARGE, CLOSURE, INTEREST_RECOVERY and RENEWAL; a deposit funded by renewing another (funding source RENEWAL).
- `deposits.renewal_consent`: a deposit-taking NBFC renews only with the depositor's consent, recorded after booking (P4-06). `deposits.maturity_notice`: one per deposit and maturity date; the message it sends carries the deposit (`integration.message.deposit_account_id`).
- Approval rules: a checker for a charge, a closure, a maturity instruction and a renewal.

**Application**
- `DepositClosing`: the same code for the day-end and for an approved request (`DepositBooks`). It works out every reason an operation cannot be done before it writes anything.
- Day-end steps after interest: "Term deposit maturity (pay out or renew)", "Term deposit maturity notices" and "Minimum balance (month-end)". At maturity a deposit is paid to the account named at booking or through the gateway to the bank account recorded, or renewed for the same tenure at the card rate of the day (principal, or principal with interest); when it cannot be (the account closed or frozen, no bank account, no consent on an NBFC, the product no longer offered), it waits as MATURED with the reason. A gateway payout that comes back puts the deposit back to MATURED.
- `DepositLifecycleService` and `DepositLifecycleController`: `GET`/`POST /casa/accounts/{id}/charges`, `GET /casa/accounts/{id}/minimum-balance`, `GET /casa/accounts/{id}/closure-quote`, `POST /casa/accounts/{id}/close`, `GET /deposits/accounts/{id}/closure-quote?reason=`, `POST /deposits/accounts/{id}/close`, `POST /deposits/accounts/{id}/maturity-instruction`, `POST /deposits/accounts/{id}/renew`, `GET /deposits/maturities`. The account view shows how it was closed. No new permission.
- Messaging: template code DEPOSIT_MATURITY (name, account_no, amount, maturity_date, instruction); the demo tenants get an SMS template for it.
- A staff reversal of a charge cancels its invoice with a credit note; a closure, a renewal or an interest recovery is not reversed.

**Console**
- Account page: Close the account, Close the deposit (or Pay out when matured), Maturity instruction, Renew; each dialog shows the backend's quote and every reason it cannot go ahead. A Charges card on a savings or current account (schedule, invoices, months below the minimum, Levy a charge) and a card saying how a closed account was closed.
- A Maturities page (Deposits menu): deposits maturing in a window and matured deposits waiting, with the instruction, consent and notice.
- The product form and page take a savings or current product's schedule of charges.
- Mock mode: charges, closures, instructions and the maturities list; no deposit day-end, so nothing matures or is noticed by itself and no minimum balance month is evaluated.

### Choices made in this increment (to confirm: open item P4-20)
- **Minimum balance**: the month's average of the daily closing balances; the charge is a percentage of the shortfall with a monthly cap, and only for a month whose shortfall follows a short month the depositor was told of.
- **Closure charge**: none within the free period after opening (14 days on the demo schedule) nor after the limit; cut to what the account holds.
- **GST** on deposit charges at the product's rate, place of supply the depositor's state (the branch's when unknown).
- **Overdue interest** at a bank: the lower of the contracted rate and the tenant's overdue rate (a setting, 2.75%); none at a deposit-taking NBFC (P4-19).
- **Renewal** is for the same tenure at the card rate of the renewal day, keeping the add-ons it had that the product still offers; on a deposit-taking NBFC only with a consent recorded after booking (P4-06).
- **An emergency withdrawal within an NBFC's lock-in** repays the allowed share; the rest stays on deposit on its terms.
- **Tax at source** is deducted from interest credited at closure, as from any credit.

### Tests
- **Engine:** 94 tests in `deposits-core` (12 new in `DepositLifecycleTest`).
- **Tenant database:** 58 checks in the new `deposits_lifecycle_test.sql`; all 26 suites pass (three rule counts in older suites updated for the new rule).
- **Application services:** in the build sandbox, on stand-ins for Spring with the real approval flow and real day-end runs: 68 checks on a bank (charges and invoices, credit note on reversal, the minimum balance month, closing a savings account, premature closure, maturity paid, renewed and on a holiday, a matured deposit waiting and paid with overdue interest, notices once) and 23 on a deposit-taking NBFC (lock-in, death, emergency share, no interest before six months, the rate cut, renewal refused without consent and done with it).
- **Console:** 8 new tests; 315 pass, typecheck and build clean.
- **API contract:** 9 new operations; lint clean; every handler has an operation.

### Not verified here
- **Gradle and Spring** and the real EOD scheduling; the live stack (`p4-4-smoke.py`).

### Next: P4-5
Recurring deposits with late charge, standing instructions (including a sweep to a loan instalment), loan against a deposit. Next tenant migration: V35; next control migration: V5.
