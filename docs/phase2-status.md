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

## P2-1d console screens

- **Lending:** loan products (list, detail, propose), loans list with search and filters, new loan with preview (EMI, APR, fees and GST, schedule, KFS) and create, loan detail with Schedule, Transactions, KFS and Amendments tabs.
- **Servicing actions** (each only with its permission and in the right loan state): disburse, repayment, part-prepayment, pre-closure (quote then confirm), cooling-off cancellation, charge and waive fees, reverse, freeze/unfreeze, amend (preview then propose) and restructure (up to 3 simulated options side by side, two checkers).
- **Masters:** staff and branch scope, branch sets, territory (states, pincode lookup, upload), system properties, enumerations; CSV uploads with templates for holidays, territory and vouchers.
- **Approvals:** shows multi-checker progress ("1 of 2 approvals") and what an approval produced.
- **Tests:** 138 console tests in mock mode.
- **Contract fixes made with it:** enumeration types are kebab-case (V15); enumeration values return `sortOrder`; validation problems carry `errors[{field, message}]`; the Approval schema documents `checkersRequired`, `approvalsSoFar`, `appliedRef`.
- **Open:** the queue cannot yet tell a checker that they already approved a two-checker request (the second click gets 409); a floating-rate reset above the product's rate band is refused — decide whether resets may exceed the band (D-14).

## P2-3 amendments and restructure

### What was built
**Amendments** (`LoanAccount.amend`, `Amendment`): the engine rebuilds the future schedule from the principal not yet demanded. Demands already raised are not changed.
- **Rate change:** the borrower picks one of three options:
  - keep the EMI and change the tenure;
  - keep the tenure and change the EMI;
  - change both, by giving a new tenure or a new EMI.
- **Tenure change:** the EMI is recomputed.
- **EMI change:** the tenure is recomputed.
- **Due-day change:**
  - The next due date moves forward to the new day.
  - Interest for the extra days (the broken period) is added to the next instalment on top of the EMI.
  - Day 31 means month end.
- **Interest continuity:**
  - Interest accrued since the last due date, at the old terms, goes into the next instalment's interest.
  - Accrual continues at the new rate.
  - As a result, the next demand equals what was accrued: no interest is lost or counted twice.
- **Limits:**
  - A tenure extension may not take the loan past the product's maximum tenure.
  - The EMI must be at least the monthly interest plus 1 paisa of principal.
  - An EMI that would not cover a 31-day month's interest is refused.
  - A borrower in arrears cannot get a longer tenure or a lower EMI through an amendment; that is a restructure.
  - A market rate reset that only lowers the EMI is still allowed for a borrower in arrears.
- **Scope:** only equated (EMI) loans without a balloon can be amended.
- **Preview:** `previewAmendment` uses the same code that applies the change.
- **Maker-checker:** entity `LOAN_AMENDMENT`, one checker.
  - The approval runs the engine again on the loan as it is at approval time.
  - If the figures differ from the proposal, the current figures are applied and both sets are kept (`differs_from_proposal`).
- **Reversal:** amendments move no money and are reversed through the normal reversal flow, which restores the state. The history row is marked reversed.

**Restructure** (`LoanAccount.simulateRestructure`, `restructure`, `RestructureTerms`, `RestructureStatus`):
- **Simulation:** compares up to three options without changing the loan. For each option it shows:
  - the new schedule, EMI and tenure;
  - interest before and after;
  - interest capitalised or kept as arrears;
  - NPV before and after at the contract rate (monthly periods) and the difference, which is the lender's sacrifice.
- **Applying a restructure:**
  - Overdue principal is rescheduled.
  - Overdue interest is either capitalised into principal (Dr principal, Cr interest receivable) or kept as arrears.
  - A principal moratorium can be set, along with a new rate and tenure.
  - A separate funded-interest term loan (FITL) is **not** built; capitalisation covers the same economics on one schedule.
- **Maker-checker:** entity `LOAN_RESTRUCTURE`, **two checkers** (approval rule in V14).
- **Reversal:** a restructure cannot be reversed, and neither can any transaction before it. The API returns 409 with the reason, because reversing would undo the downgrade.

### RBI basis and the interpretation encoded
- **Rate reset:** follows RBI's circular on reset of floating interest rates on EMI-based personal loans (18-Aug-2023). The borrower chooses between a higher EMI, a longer tenure, or both, within the product's maximum tenure and without negative amortisation.
- **Asset class on restructuring:** follows RBI's Prudential Framework for Resolution of Stressed Assets (7-Jun-2019) and the IRACP norms. A STANDARD account (including SMA) is downgraded to SUBSTANDARD on the restructuring date. An NPA keeps its class and NPA date.
- **Specified period:** runs until both of these are true:
  - at least 10% of the principal under the plan (capitalised interest included) has been repaid;
  - at least one year has passed since the later of the first interest payment and the first principal payment under the new schedule.
- **Satisfactory performance:** no instalment of the new schedule is unpaid at the day-end of its due date during the specified period.
  - One default ends eligibility for upgrade. The account stays NPA until it closes or is restructured again.
  - An instalment paid on the due date (received as an advance) counts as on time.
- **Upgrade:** only after the specified period, with satisfactory performance and zero arrears.
  - The flag and dates are kept in the loan state (`RestructureStatus`) and copied to `loan_account`: `restructured_on`, `restructure_count`, `upgrade_not_before` and `restructure_defaulted`.
  - The view `lending.restructured_loan` lists these accounts.
- **Conservative choices:**
  - NPA ageing (sub-standard to doubtful) continues during the specified period.
  - Capitalised interest stays in interest suspense and becomes income only as principal is repaid, in proportion. At closure it is all income.
  - The NPV loss is reported but no diminution-in-fair-value provision is posted yet.

### Fixes found on the way
- **Second prepayment in a period:** the interest accrued before the first prepayment was counted twice in the next demand.
- **Last instalment paid in advance:** the loan never closed. The day-end now closes it.
- **Reduce-tenure prepayment:** now keeps the regular EMI rather than the next row's amount.

### Tests
- **Engine:** 40 tests (25 before this increment), including 15 new in `LoanAmendmentTest`.
  - Whole-life scenarios for every amendment kind and for restructuring (standard to NPA to upgrade, default during the specified period, NPA kept as arrears), each checked against a general ledger.
  - At closure, principal, interest receivable, suspense and provision are zero, and interest income equals the interest in the demands raised.
- **Database rules:** 23 SQL checks in `amendments_test.sql`, covering the approval rules, history constraints, immutability, the fact that a restructure is never marked reversed, the restructured flag and the view.
- **JSON:** the stored loan state round-trips with the new fields, and state stored before this increment still loads at the booked rate (`LoanStateJsonTest`).

### Not yet built in P2-3
- FITL as a separate facility.
- Diminution-in-fair-value provisioning from the NPV loss.
- Borrower communication of a rate reset (letter or SMS, P2-2/P2-4).

### Not yet built in P2-1
- **P2-1d:** console screens — built, see the P2-1d section below.
- **P2-2:** payout gateway, NACH presentation and responses, collection webhooks, SMS and email, signed webhooks, OAuth clients, LOS integration.
  - These need decision D-09 and sandbox credentials from the partners.
- **P2-3:** amendments and restructure — built, see the P2-3 section above.
- **P2-4:** documents and reports: KFS, statement of account and NOC as PDFs; bureau files; GST invoices; report catalogue; dashboard.
- **Other stories:**
  - usage metering and support access (US-004, US-007);
  - custom fields (US-014) and role amount limits (US-021);
  - sessions (US-027) and KYC documents (US-032);
  - associates (US-034) and consent (US-036);
  - 24x7 posting (US-111) and the job catalogue (US-112);
  - the developer portal (US-119).
