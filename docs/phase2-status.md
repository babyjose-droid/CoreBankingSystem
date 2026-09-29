# Phase 2 (Lending) – status

Updated 29-Sep-2026. Source: product backlog v1.0, 51 Phase 2 stories. Built in increments.

## Increment P2-1: lending core (this commit)

### Built and tested
| Area | Stories | Where |
|------|---------|-------|
| Loan product factory | US-038, US-040, US-041, US-042, US-043 | Details below the table |
| Schedules for every repayment method | US-039 (partial), US-054 | Details below the table |
| Loan booking, KFS and APR | US-047, US-048 (data; PDF in P2-4), US-049 | Details below the table |
| Disbursement | US-050 | Details below the table |
| Repayment | US-055 | Details below the table |
| Part-prepayment | US-055 | Details below the table |
| Pre-closure | US-056 | Details below the table |
| Cooling-off cancellation | US-053 | Details below the table |
| Fee charge and waiver | US-057 | Details below the table |
| Reversal with back-dated replay | US-058 | Details below the table |
| Freeze and close | US-061 | Details below the table |
| DPD, SMA and NPA | US-076, US-077, US-037 | Details below the table |
| Income recognition | US-078 | Details below the table |
| Penal charges | US-081 | Details below the table |
| GST place of supply | US-106 (computation) | Details below the table |
| Lending EOD | US-108 (lending steps) | Details below the table |

**Loan product factory**
- Products are versioned and change only through maker-checker; each change keeps a history row.
- Fee rules: fixed, percentage or slab, with minimum and maximum amounts.
- GST can be inclusive or exclusive.
- Interest tables are additive (base + slab) or absolute, and the rate lookup explains itself.
- A provisioning table sets rates by asset class and by secured or unsecured exposure.
- Accounts keep their booked product version.

**Schedules**
- Four methods: equated, fixed principal, bullet (total interest), and bullet with periodic interest.
- Options: moratorium, balloon payment and a first due date different from the standard one.
- The pure engine is `backend/lending-core`.

**Loan booking, KFS and APR**
- Preview comes from the same engine that posts.
- The rate comes from the interest table unless set on the account.
- APR is the IRR of the loan's cash flows with fees excluding GST; it gives 18.58% on the golden case.
- A KFS snapshot is stored, and the borrower must accept it before disbursement.

**Disbursement**
- Principal is booked gross, and fees and GST are deducted from the payout.
- Staff disbursements go through maker-checker.
- LOS clients with `loan:stp` are processed straight through.

**Repayment**
- Receipts follow the product's appropriation sequence, either demand by demand or component by component.
- Any excess is kept as an advance and applied automatically at the next demand.

**Part-prepayment**
- The borrower can choose to reduce the EMI or reduce the tenure.
- Interest already accrued is carried into the next instalment.

**Pre-closure**
- The quote and the posting use identical figures, including the foreclosure fee and its GST.
- Provision and suspense are released on closure.

**Cooling-off cancellation**
- The borrower repays principal plus interest for the days used.
- Fees that were disclosed are retained.

**Fee charge and waiver**
- Waivers go through maker-checker.
- For NPA loans, a penal waiver reduces suspense.

**Reversal with back-dated replay**
- Reversing a transaction also reverses every later one.
- The loan's state is restored to before the transaction, and the days since then are replayed into today's books.
- Reversals go through maker-checker.

**Freeze and close**
- A frozen account blocks transactions but interest still accrues.
- A loan closes automatically once it is fully repaid.

**DPD, SMA and NPA**
- These follow RBI's 12-Nov-2021 clarification: overdue from the day-end of the due date.
- Classes: SMA-0/1/2, NPA above 90 days, doubtful D1/D2/D3.
- An NPA is upgraded only when all arrears of interest and principal are paid.
- Classification applies at borrower level.

**Income recognition**
- When a loan becomes NPA, unrealised interest and penal income are moved to suspense (GL 2305).
- While NPA, interest accrues to suspense.
- Amounts move from suspense to income when received or on upgrade.

**Penal charges**
- Charged on the overdue principal and interest only.
- Never added to the interest rate and never compounded.
- No GST (to be confirmed, D-08).

**GST place of supply**
- CGST + SGST when the branch state equals the borrower's state, IGST otherwise.

**Lending EOD**
- Each loan's day-end runs in its own transaction: demands, accrual true-up, advance adjustment, penal charges, classification and provisioning.
- One failing loan is recorded as an EOD exception; above 5% failures the step fails.
- Borrower-level NPA runs as a separate step.

### Tests
- **Engine:** 25 tests, including whole-life scenarios checked against a general ledger:
  - interest income equals the scheduled interest exactly;
  - the NPA flow and upgrade;
  - prepayment in both modes;
  - pre-closure and cancellation;
  - penal charges and waivers;
  - snapshot and reversal.
- **Database rules:** 21 SQL checks (`lending_rules_test.sql`).
- **JSON:** a round-trip test for the stored loan state runs in CI.

### Not yet built in P2-1
- **P2-1d:** the console's loan screens. The partial work is parked and continues next.
- **P2-2:** payout gateway, NACH presentation and responses, collection webhooks, SMS and email, signed webhooks, OAuth clients, LOS integration.
  - These need decision D-09 and sandbox credentials from the partners.
- **P2-3:** amendments (rate, tenure, EMI, dates) and restructure simulations.
- **P2-4:** documents and reports: KFS, statement of account and NOC as PDFs; bureau files; GST invoices; report catalogue; dashboard.
- **Other stories:**
  - usage metering and support access (US-004, US-007);
  - custom fields (US-014) and role amount limits (US-021);
  - sessions (US-027) and KYC documents (US-032);
  - associates (US-034) and consent (US-036);
  - 24x7 posting (US-111) and the job catalogue (US-112);
  - the developer portal (US-119).
