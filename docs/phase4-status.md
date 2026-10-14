# Phase 4 (CASA and term deposits) – status

Updated 14-Oct-2026. Plan: the Phase 4 design and increment plan (eight increments, P4-0 to P4-7).

| Increment | State |
|-----------|-------|
| P4-0 deposit core | Built, pushed (`9cb5f8e`, `3851a93`), live-tested on the local stack on 10-Oct-2026 |
| P4-1 products and opening | Built, pushed (`586b8e8`), live-tested on the local stack on 11-Oct-2026 |
| P4-2 money movement | Built and pushed (`cdbe397`) on 14-Oct-2026; first live test found one defect (auto-applied requests), fixed in the next commit |
| P4-2b gateway for deposits | Not started: bank transfers and gateway funding through the payout and collection pipeline |
| P4-3 to P4-7 | Not started |

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
- **Fix:** an auto-applied request is recorded with the rule (`rule:auto`) as its checker (`ApprovalService.AUTO_CHECKER`). Two checks added to `deposits_money_movement_test.sql` (S4, S5). The live test is to be run again.

### Not yet built
- Bank transfers and gateway funding through the payout and collection pipeline (P4-2b).
- Interest, tax, charges, minimum balance, closure (P4-3, P4-4).
- Cheques, standing instructions, dormancy (P4-5, P4-6).
- Cash drawer and teller limits beyond the role amount limits.

### Next: P4-2b or P4-3
P4-2b extends the payout and collection pipeline to deposit accounts (bank transfers out sent through the gateway, money in and term deposit funding through collection orders). P4-3 is the day-end interest: savings accrual and credit, term deposit accrual, payout and compounding, the TDS ledger. Next tenant migration: V31; next control migration: V5.
