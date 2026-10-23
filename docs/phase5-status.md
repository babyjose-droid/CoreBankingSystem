# Phase 5 (co-lending, business accounting, securitisation and BI) – status

Updated 23-Oct-2026. Phase 5 starts with co-lending; business accounting, securitisation and BI follow, each after its own round of decisions.

| Increment | State |
|-----------|-------|
| P5-1 co-lending arrangements and allocation | Built, pushed (`04421b7`), live-tested on the local stack on 23-Oct-2026 (28 of 28) |
| P5-2 daily split and settlement | Planned |
| P5-3 classification sync, default loss guarantee, bureau and provisioning on the own share | Planned |
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
