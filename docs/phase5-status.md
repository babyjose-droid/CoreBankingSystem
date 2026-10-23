# Phase 5 (co-lending, business accounting, securitisation and BI) – status

Updated 23-Oct-2026. Phase 5 starts with co-lending; business accounting, securitisation and BI follow, each after its own round of decisions.

| Increment | State |
|-----------|-------|
| P5-1 co-lending arrangements and allocation | Built, pushed (`04421b7`), live-tested on the local stack on 23-Oct-2026 (28 of 28) |
| P5-2 daily split and settlement | Built, pushed (`e0a6ddd`, fix `1156e2e`), live-tested on the local stack on 23-Oct-2026 (split 15 of 17, then after the fix 10 of 10) |
| P5-3 classification sync, default loss guarantee, bureau and provisioning on the own share | Built, in review (patch `0006-P5-3`) |
| P5-4 the partner side (this lender takes shares originated by others) | Planned |
| P5-5 reports and disclosures | Planned |

## Decisions taken on 23-Oct-2026
| # | Decision | Answer |
|---|----------|--------|
| 1 | First area of Phase 5 | Co-lending |
| 2 | Role of the tenant | Both originator and partner; the originator side first, the partner side in P5-4 |
| 3 | Default loss guarantee | In the first build (limit and record in P5-1, invocation in P5-3) |
| 4 | Exchange with the partner | Files (offer file out, decision file in) and a simulator; no live API to a partner yet |

## The rules (RBI (Co-Lending Arrangements) Directions, 2025, read from the RBI notification)
In force from 1-Jan-2026, for commercial banks (not small finance banks, local area banks or RRBs), all-India financial institutions and NBFCs including HFCs. Multiple banking, consortium and syndication are outside it.
- Each lender keeps at least 10% of every loan (para 10).
- The borrower pays the blended rate, the share-weighted average of the lenders' rates (para 17); fees go into the APR and the KFS (para 19) and are not a credit enhancement (para 20).
- The partner takes its share into its books within 15 calendar days of disbursement; otherwise the loan stays with the originator (paras 22 and 24).
- All flows go through an escrow account (para 26). Each lender reports its own share to the credit bureaus (para 31).
- A default loss guarantee is at most 5% of the outstanding (para 32).
- Asset classification is at borrower level across both lenders, shared by the end of the next working day (para 33).
- Partners are disclosed on the website (para 35) and in aggregate in the Notes to Accounts (para 36).

## Increment P5-1: arrangements and allocation

### What was built
**Engine** (`lending-core`, `CoLending`): the Directions' limits (`problems`), the blended rate (2 decimals, half up), the partner's share and the split of principal, interest (in the ratio share × rate, so each lender earns its own rate on its own share) and charges, the 15-day deadline, and the transfer lot (`CO_LENDING_TRANSFER`: Dr escrow, Cr partner's share).

**Database** (tenant `V39`)
- Heads: 1105 *Co-lent loans – partner's share* (a contra to the loan book, so the balance sheet shows only this lender's share), 1208 escrow, 4110 servicing fee income; 2303 partner payable is reused. Loaded by `ledger.load_co_lending_heads()`; named by the settings `lending.co-lending.*`.
- `lending.co_lending_arrangement`: partner, agreement, shares, both rates, servicing fee, how fees and penal charges are shared, the guarantee, products, the borrower's single point of contact. The checks of the Directions are in the database too. Never deleted; the partner never changes; a change is a new version; a closed arrangement stays closed.
- `lending.co_lent_loan`: one row per loan, with the terms frozen at sanction. Its states: SANCTIONED → OFFERED (at the first disbursement; deadline = disbursement + 15 days) → ACCEPTED (by the deadline, with its transfer lot), DECLINED or LAPSED; OFFERED → SANCTIONED when the disbursement is reversed; WITHDRAWN before acceptance. The database refuses a loan whose rate is not the blended rate, a product outside the arrangement, a loan disbursed in tranches, an acceptance after the 15th day or without its transfer.
- `lending.co_lending_problems()`: the contra head agrees, per branch, with the partner's principal of the loans accepted.

**Application**
- `CoLendingService`: arrangements and their status through maker-checker (`CO_LENDING_ARRANGEMENT`); a loan application naming an arrangement is priced at the blended rate and its KFS gets a co-lending section (lenders, shares, rates, single point of contact); the offer at disbursement; a disbursement reversal is refused once the partner has accepted; the offers and the offer file; the partner's decisions through maker-checker (`CO_LENDING_DECISION`), from its file or the simulator. A decision that reaches the checker after the 15th day, or for a loan no longer offered, is skipped and reported.
- Day-end step *Co-lending offers lapsed*.
- API `/api/v1/co-lending/...` (eight operations; see `docs/api/openapi.yaml`); `LoanApplication.coLendingArrangement`, `Loan.coLending`, `LoanKfs.coLending`.

**Console**: *Lending → Co-lending* (arrangements, new and change with the blended rate shown as it is typed, suspend, reactivate, close); an arrangement's page with the offers, the offer file, the partner's decisions one by one or from its file, and *Simulate partner*; a co-lending choice on the new-loan form; a co-lending card on the loan; the co-lending section of the KFS. Mock API and tests. The mock does not post the transfer to the ledger.

### Tests
- `CoLendingTest` (lending-core): limits, blended rate, splits, transfer lot.
- `co_lending_test.sql`: 35 checks on V39 (heads, the arrangement's limits and immutability, the loan's terms and rate, the offer and its 15 days, the contra head check).
- Service scenario on a deposit-taking NBFC: 33 checks (arrangement through a checker, KFS and rate, offer and file, acceptance and its accounting, reversal refused, a decline, acceptance on the 15th day, the lapse at day-end, a late approval, suspend and close, the invariants). The earlier lending scenarios and all SQL suites pass unchanged.
- Console: 8 tests (mock API and pages).

### Live test (23-Oct-2026, demo-nbfc, `p5-1-smoke.py`)
28 of 28: a CLAUDE-TEST borrower created and its KYC verified (the tenant had been reset); a 9% share and a 6% guarantee refused; arrangement CLTEST on PL01 (20% at 30%, 80% at 12%, blended 15.6%) through a checker; a loan at another rate refused; the KFS names both lenders; two loans booked at 15.6%, disbursed and offered (decide by 15-Jul); the offer file; a decline without a reason refused, one declined; the simulated partner accepted the other, 80,000 booked to 1105; the trial balance balances.

### Open items
P5-01 (tranche and floating-rate loans not co-lent) and P5-02 (P5-1 choices to confirm) in `open-items.md`.

## Increment P5-2: daily split and settlement

### What was built
**Engine** (`CoLending`): where the partner should stand on a loan (`target`): principal repaid to it is what it funded less its share of the principal outstanding; interest received divides in the ratio share × rate; fees received (without their GST) and penal charges are shared by share or kept by the originator, as the arrangement says. The servicing fee accrues daily on the partner's principal outstanding (% a year, 365 days). `splitLot` posts the change since the last split: Dr contra 1105 / Cr due to the partner 2303 for principal, Dr the income head / Cr 2303 for interest, fees and penal, Dr 2303 / Cr 4110 for the servicing fee; a negative change (a receipt reversed) posts the other way. `settlementLot` pays the partner: Dr 2303 per loan / Cr escrow 1208, per branch.

**Database** (tenant `V40`)
- `lending.co_lent_position`: per loan, what the borrower had paid when the partner took its share (the baseline, fixed) and where the partner stands (cumulative). Loans accepted before V40 count from the day of the migration.
- `lending.co_lending_split`: each day's change as posted, with its lot; settled once and never changed.
- `lending.co_lending_settlement`: a payment to the partner with its lot; its figures must be those of the splits it settles (checked at commit).
- `lending.co_lending_problems()` now also checks that the partner payable on the co-lent loans equals the splits not yet settled, and that the contra head equals the partner's principal funded less repaid to it.

**Application**
- Day-end step *Co-lending split*, per loan, after the loan's own day-end; the calendar days closed with a business day are split with it. A loan written off is left for P5-3. Once a loan is closed and fully divided its split is final.
- At acceptance the baseline is recorded.
- Settlement with the partner through a checker (`CO_LENDING_SETTLEMENT`): settles every split not yet settled; one at a time per arrangement; refused when nothing is due. A statement CSV per settlement.
- A rate amendment or a switch to fixed is refused on a co-lent loan (its rate is the blended rate), and a restructure once the partner holds a share (P5-03).
- API: what is due by loan, the settlements, propose a settlement, one settlement, its statement (five operations); `Loan.coLending` shows what has been split to the partner and what is not yet settled.

**Console**: on the arrangement page, *Due to the partner* (by loan, with the total and *Settle with partner…*) and *Settlements* (with each statement); on the loan's co-lending card, what has been split to the partner, the servicing fee taken and what is not yet settled. Mock API and tests.

### Tests
- `CoLendingTest`: the day's split, a reversal taken back, fees kept or shared, the servicing fee, the settlement lot (111 engine tests in all).
- `co_lending_split_test.sql`: 19 checks on V40 (the position, splits, settlements and the books).
- Service scenario P52: 38 checks (servicing fee from acceptance, an instalment split, a fee shared without GST and its receipt reversed, a settlement through a checker with its statement, one at a time, pre-closure repaying the partner's principal in full, the final split, the books). All SQL suites and the earlier scenarios pass.
- Console: 11 co-lending tests (353 in all).

### Found in the live run (23-Oct-2026)
The first end of day on demo-nbfc since Phase 4 failed at *Term deposit maturity notices*: on a tenant that takes no deposits the step
read the deposit rule set, which refuses ("not recorded as registered by RBI to accept public deposits"). The co-lending split itself
ran clean (2 loans; 8,000 of a 10,000 part-prepayment to the partner). Fix (patch 0004): the step has nothing to do on a tenant without
deposits, as the inoperative and unclaimed steps already did. A service scenario now runs the whole day-end on a lending-only NBFC.
After the fix the failed run was restarted from that step and completed (next business date 1-Jul-2026); the partner was paid the
8,000 through a checker from escrow (settlement CLS00000001, with its statement), nothing was left to settle, and the trial balance
balances. The first attempt's exception stays on the run as a record (unresolved) until someone marks it resolved.

### Open items
P5-03 in `open-items.md`.

## Increment P5-3: classification, guarantee, bureau and provisioning

### The rules (read from the source)
- Co-Lending Directions para 33: classification is at borrower level across both lenders, shared by the end of the next working day; para 31: each lender reports its own share to the credit bureaus; para 32: a default loss guarantee follows the Digital Lending Directions.
- Default loss guarantee (Digital Lending Directions, 2025, which repealed and carried over the 2023 guidelines):
  - its forms are cash, a fixed deposit with a scheduled commercial bank under lien, or a bank guarantee;
  - all covers are at most 5% of the portfolio;
  - it is invoked within 120 days overdue unless the borrower makes good;
  - it lasts at least as long as the longest loan it covers;
  - the lender still classifies and provisions each loan regardless of the cover;
  - the amount invoked is not set off against the loan, and recoveries may be shared back under the contract.

### What was built
- **Provisioning on the own share:** the daily split writes back the partner's share of the provision the loan engine holds on the whole loan (Dr provision 1109 / Cr provision expense 5102, lot `CO_LENDING_PROVISION`); `co_lent_position.provision_partner`.
- **Bureau:** the consumer file reports the originator's share of a co-lent loan's sanction, balance, overdue amount and EMI once the partner holds its share (tenant V41 replaces `reporting.bureau_consumer_rows`).
- **Classification with the partner:**
  - This lender's file for the partner: days past due, class, NPA date, principal and the partner's principal, without personal data.
  - The partner's file comes back through a checker (`CO_LENDING_CLASSIFICATION`). An NPA at the partner holds the loan at that class here by an asset-class override for a year, so the day-end does not classify it back from its own days past due; the borrower's other loans follow at day-end (borrower-level NPA). An SMA or standard report is recorded.
  - The loan shows the partner's latest classification and what was done with it.
- **Default loss guarantee** (`CO_LENDING_DLG`, each step through a checker):
  - Covers: cash (Dr DLG deposits 1304 / Cr bank 1202), or a fixed deposit under lien or a bank guarantee, which are off the books. All covers together are capped at the arrangement's percentage of the partner's share funded, and each must last to the last due date of the loans it covers.
  - The partner's invocation for a loan in default: at most what the cover has left and the partner's principal on the loan. Dr DLG paid 5109 / Cr the deposit or the bank; flagged when after 120 days; not set off against the loan.
  - Recoveries shared back, at most what was invoked (Dr bank / Cr 4111). Release of a cover, with cash not invoked coming back to the bank.
  - The arrangement page lists the loans in default with the last day to invoke.
- Tenant V41: `co_lending_partner_class`, `co_lending_dlg_cover`, `co_lending_dlg_invocation`, `co_lending_dlg_recovery` with their guards, the heads 1304, 5109 and 4111 (loaded with the first arrangement), and `co_lending_problems()` now also checks the DLG deposit head.
- API: seven operations (the classification file, the partner's classification, the guarantee, a cover, its release, an invocation, a recovery). Console: *Default loss guarantee* and *Classification with the partner* on the arrangement page; the partner's classification on the loan's co-lending card. Mock and tests.

### Not in P5-3
Write-off is not built for any loan yet (the engine has the status but no operation), so the sharing of a written-off co-lent loan waits for it. Restructuring a loan the partner shares stays refused. Both are in open item P5-04.

### Tests
- `CoLendingTest`: provisioning on the own share, the guarantee's cap and postings (113 engine tests).
- `co_lending_risk_test.sql`: 21 checks on V41.
- Service scenario P53: 32 checks:
  - the bureau and classification files;
  - the cap, the term and the instrument number;
  - a cash cover and a bank guarantee;
  - a partner NPA held here, with the borrower's other loan following;
  - provisioning on the own share (10,400 on the whole loans, the partner's 8,320 written back);
  - an invocation on an overdue loan, not set off; a recovery; the release;
  - the books.
- All SQL suites and the earlier scenarios pass. Console: 14 co-lending tests (356 in all).

### Open items
P5-04 in `open-items.md`.
