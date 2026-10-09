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

## P2-4 documents and reports

Built 02-Oct-2026. Stories: US-048 (KFS as PDF), US-106 (invoices and reports), US-113, US-114, US-115. Migration `V16`.

### What was built

**PDF writer** (`SimplePdf`, `Inr` in `backend/kernel`)
- A small PDF 1.4 writer with no dependency: A4 pages, Helvetica and Helvetica-Bold (not embedded), headings, wrapped paragraphs, label-value blocks, tables.
  - A table's header row repeats after a page break, a row never splits across pages, and numbers are right-aligned.
  - Every page has a header line, a footer line and "Page x of y". Page content is Flate-compressed.
  - The same input gives the same bytes.
- Amounts print as "Rs. 1,23,45,678.90" (Indian grouping) because the standard fonts have no rupee sign. `Inr.words` gives the amount in words with lakh and crore.
- **Limit:** text must be in Windows-1252. Accents outside it are dropped, the rupee sign becomes "Rs." and other scripts (for example Devanagari) print as "?". Documents in Indian languages need an embedded font, which is not built.

**Borrower documents** (`LoanDocuments` in `backend/lending-core`, `LoanDocumentService` in the app)
- **Key Facts Statement:** laid out as in RBI's circular "Key Facts Statement (KFS) for Loans & Advances" (15-Apr-2024).
  - Part 1 (interest rate and fees/charges), Part 2 (other qualitative information), the illustration of the APR computation and the repayment schedule.
  - Figures come from the KFS stored at booking. The APR is the one the product computed; it is not recomputed.
  - Contingent charges come from the product's fee rules and the penal charge rate.
- **Statement of account:** opening and closing balance, every transaction with its principal, interest and charges split, running outstanding, and the overdue summary with days past due.
  - It is built from the ledger entries on the loan account (`lending.loan_statement`, `lending.loan_balance`), so it always agrees with the books.
  - Day-end interest accruals between two transactions are shown as one line per month.
  - Interest includes interest accrued but not yet due. Money held as an advance is shown apart.
  - A statement whose lines do not add up to the closing balance is refused, not printed.
- **Repayment schedule:** instalments fallen due with what was paid, and instalments to come.
- **No-objection letter:** only for a loan whose status is CLOSED (the API returns 409 otherwise).
- **GST tax invoice** for a fee: supplier and recipient state, SAC 9971, CGST + SGST or IGST, total in words.
- Name and address are printed in full; PAN and mobile only masked.
- **Tenant settings used** (system properties; a missing one prints as "[not configured]"):
  - `lender.registered-address`, `lender.grievance.officer-name`, `lender.grievance.officer-phone`, `lender.grievance.officer-email`;
  - `lender.kfs.recovery-agent-clause`, `lender.kfs.grievance-clause`, `lender.kfs.transferable`, `lender.kfs.collaborative-lending`, `lender.kfs.lsp-recovery-agent`, `lender.kfs.validity-days` (default 3);
  - for floating-rate loans: `lender.kfs.benchmark`, `lender.kfs.reset-periodicity`, `lender.kfs.reset-impact`.
- **API** (permission `loan:view`, branch scope, `Cache-Control: no-store`, download): `/api/v1/loans/{id}/documents/kfs.pdf`, `statement.pdf?from&to`, `schedule.pdf`, `noc.pdf`, and `/api/v1/loans/{id}/charges/{chargeId}/invoice.pdf`.
  - Every generation is written to the audit log (`DOCUMENT_GENERATED`: who, which loan, which document).

**GST fee invoices** (`lending.fee_invoice`, `lending.issue_fee_invoices`)
- One invoice per fee charged, built from the fee's ledger entries, so an invoice always equals what was posted.
- Numbers come from a new number series `GST_INVOICE` (14 characters, same check digit as the other series), issued in SQL.
- Invoices are issued at day-end (a new step "GST fee invoices"), and on demand by the invoice PDF and the GST reports.
- An invoice is never edited or deleted. When the fee's transaction is reversed, the invoice is marked CANCELLED and keeps its number.
- `chargeId` is the charge id on the loan (C1, C2 …), or D1, D2 … for fees deducted from the disbursement.

**Report catalogue** (`reporting.report_definition`, `reporting.report_run`, `ReportService`)
- A report is a SQL function `reporting.<name>(user, parameters)` returning rows. Every function filters by `platform.visible_branches(user)`.
- A run is synchronous. The rows are written as CSV with the kernel `Csv` writer (formula cells neutralised) and stored in the document store under `tenants/<code>/reports/…`.
- A finished run cannot be changed or deleted: it is the record of who exported what.
- **Reports** (permission `report:run`):
  - `LOAN_BOOK`: portfolio outstanding by branch and product;
  - `DPD_AGEING`: buckets 0, 1-30, 31-60, 61-90, 91-180, 181-365, above 365;
  - `COLLECTIONS_VS_DEMAND`: instalments due in a period against what was collected;
  - `DISBURSEMENT_REGISTER`;
  - `NPA_REGISTER`: with provision required, held and shortfall;
  - `GST_OUTPUT_REGISTER` and `GST_OUTPUT_SUMMARY`: fee invoices, and tax by state and rate (this closes the reports part of US-106);
  - `INTEREST_ACCRUAL_SUSPENSE`: opening balance, movement and closing balance of interest income, suspense and receivable.
- `LOAN_BOOK` and `DPD_AGEING` accept an earlier date and then use the day-end history (`lending.dpd_history`).
- **API:** `GET /api/v1/reports`, `POST /api/v1/reports/{code}/runs`, `GET /api/v1/reports/runs` (own runs; all runs with `report:admin`), `GET /api/v1/reports/runs/{id}/download`.
  - These calls need the tenant's `REPORTS` module, which every plan includes.

**Credit bureau file** (report `BUREAU_CONSUMER`, `UcrfConsumerFile` in `backend/kernel`)
- A monthly consumer file in a UCRF-style layout: pipe-delimited text with a header record, one record per loan and a trailer record.
- Fields, in order: record type, member code, account number, account type, ownership, date opened, date of last payment, date closed, date reported, sanctioned amount, current balance, amount overdue, days past due, asset classification (STD, SMA, SUB, DBT, LSS), written-off or settled status, EMI, tenure, rate, name, date of birth, gender, PAN, mobile, address, state code, pincode.
- Reported: live and written-off loans, and loans closed in the reporting month.
- An account that fails a check (for example no PAN and no mobile) is left out and listed with the reason in a second file. That file has no personal data.
- It needs the permission `bureau:export` and all-branch access. PAN, mobile and address are decrypted only to write the file. Every run and every download is audited (`BUREAU_EXPORT`, `BUREAU_DOWNLOAD`).
- **Tenant settings:** `bureau.member-code` (required), `bureau.member-name`, and the account type code per product (`bureau.account-type.<product code in lower case>`, or `bureau.account-type.default`).

**Dashboard** (`GET /api/v1/dashboard`, permission `dashboard:view`)
- Portfolio outstanding, active loans, overdue, gross NPA and NPA % (gross NPA / gross advances).
- Disbursed and collected today and month to date; collection efficiency for instalments due this month.
- DPD bucket distribution (count and amount), pending approvals, last end-of-day status.
- One SQL function per group (`reporting.dashboard_*`), each limited to the caller's branches.

**Document store** (`DocumentStore`, `FileDocumentStore`; `DocumentKey` in the kernel)
- Put, get and delete by key on a directory (`corebanking.documents.dir`, by default under the temporary directory).
- Keys are always `tenants/<code>/…` and are checked in one place; a key of another tenant is refused.
- These two classes are the same files as in increment P2-5, so the two increments share one store.

### Bureau format caveat
- **The bureau file is not certified by any bureau.** TransUnion CIBIL, Equifax, Experian and CRIF High Mark accept the Uniform Credit Reporting Format, but each gives its exact field positions, lengths and code lists to members only.
- Before the first submission, check the layout field by field against each bureau's current specification and run a file through that bureau's validation utility. This needs the tenant's membership documents.
- Codes that must be confirmed: account type, ownership, gender, written-off or settled status, and state codes (the file uses GST state codes).

### Permissions to add
- `report:run`, `report:admin`, `bureau:export`, `dashboard:view`.
- They are not yet in the Keycloak realm or in the console's permission list.

### Tests
- **PDF writer and file formats (kernel):** 24 new tests: file structure (xref offsets, stream lengths, page count), wrapping, right alignment, page breaks with repeated headers, compression, character replacement, amounts in figures and words, the bureau file layout and its rejections, document keys.
- **Documents (lending-core):** 13 tests with golden text checks for the five documents, including a statement that does not balance and an invoice with the wrong tax for its place of supply.
- **Database:** 66 checks in `documents_reports_test.sql` on a fixture of two branches and six loans: balance and statement lines, invoice issue and cancellation, every report with branch scope, the bureau extract and the dashboard.
- **Real readers:** the generated PDFs were checked outside Java with `qpdf --check` (no errors or warnings), `pdftotext` and `pypdf`.
- **App:** 3 tests for report parameters. The Spring code could not be compiled here (no access to Maven Central); it was type-checked against stand-in classes for the Spring API with the CI lint options.

### Not yet built in P2-4
- **S3 document store:** pending. It needs the AWS SDK dependency, to be added when a build with Maven Central access is available. Until then the store is a directory, which must be an encrypted volume: report files and the bureau file hold personal data.
- **Scheduler and e-mail delivery of reports:** pending. `schedule` and `email_to` are recorded on the report definition and not acted on. They wait for the notification provider decision (OI-06).
- **Background runs:** a run is synchronous and limited to 500,000 rows.
- **Credit notes:** a fee waived after its invoice was issued needs a GST credit note. Only reversal (cancellation) is handled.
- **Console screens** for documents, reports and the dashboard, and the regenerated API types for the console.
- **Bureau:** commercial borrowers, guarantors and joint holders, payment history by month, and settled status.
- **Earlier reporting dates:** the bureau file for a past date uses that day's DPD and class but today's loan status.
- **KFS:** benchmark details for floating-rate loans are text settings; third-party fees are always shown as nil.

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
- **P2-2:** integrations — built with a simulator; see the P2-2 section below.
  - Real partners still need decision D-09 and sandbox credentials.
- **P2-3:** amendments and restructure — built, see the P2-3 section above.
- **P2-4:** documents and reports — built, see the P2-4 section above.
- **Other stories:**
  - usage metering and support access (US-004, US-007);
  - custom fields (US-014) and sessions (US-027);
  - role amount limits (US-021), KYC documents (US-032), associates (US-034) and consent (US-036) — built, see the P2-5 section below;
  - 24x7 posting (US-111) and the job catalogue (US-112);
  - the developer portal (US-119).

## P2-5 customer, consent, KYC documents and limits

Built 02-Oct-2026. Stories: US-021, US-032, US-034, US-036 and the rest of US-047 (loan parties), plus the approval-payload part of SEC-03. Migration `V17`.

### What was built

**Role amount limits (US-021)**
- **Table:** `platform.amount_limit` holds, per role and transaction type, a maximum per transaction and an optional cumulative maximum per business day, with effective dates.
  - Transaction types: `LOAN_DISBURSEMENT`, `LOAN_REPAYMENT`, `LOAN_WAIVER`, `VOUCHER`, `LOAN_PRECLOSURE`, `FEE_WAIVER`.
  - Limits change only through maker-checker (entity `AMOUNT_LIMIT`). A new limit takes over from its effective date and the earlier one ends the day before. Periods cannot overlap, and a limit row is never edited or deleted.
- **Rule:** the pure class `AmountLimits` (kernel).
  - A user with several roles gets the most permissive one. Each role is judged as a whole: one role's day limit is not combined with another role's transaction limit.
  - No limit row for any of the user's roles means no limit, unless the tenant property `limits.default-deny` is `true`.
- **Where it is enforced:**
  - Makers: disbursement request (also for straight-through clients), repayment and part-prepayment (`LOAN_REPAYMENT`), pre-closure and cooling-off cancellation (`LOAN_PRECLOSURE`), waiver proposal, voucher proposal, voucher upload and voucher reversal. Above the limit the API returns 403 with the limit in the message.
  - Checkers: `ApprovalService.approve` checks the checker's limit for disbursements, waivers and vouchers before the decision is recorded, including the first of two approvals. Above the limit it returns 403, so a checker with a higher limit must approve.
  - A waiver of a fee counts as `FEE_WAIVER`; a waiver of a penal charge counts as `LOAN_WAIVER`.
- **Daily totals:** `platform.amount_limit_usage` is append-only. `platform.limit_used` takes a lock per user, type, stage and day, so two concurrent transactions of one user cannot both pass.
  - Makers and checkers have separate totals.
  - Usage is counted when the request is proposed, and is not given back if the request is later rejected.
  - Usage is recorded only while a limit applies to the user. A limit introduced during the day counts from then on.
- **Roles in the token:** `CurrentUser` now exposes `roles`, read from a `roles` claim and from Keycloak's `realm_access.roles`. See "Needs action" for the realm mapper.
- **API:** `GET` and `POST /api/v1/amount-limits`.

**Associates and customer limits (US-034)**
- **Customer-level relationships** (`customer.relationship`): co-applicant, guarantor, nominee and authorised signatory.
  - The related party must be an ACTIVE customer and cannot be the customer themselves.
  - An authorised signatory is an individual acting for a non-individual customer.
  - Nominees carry a share. The shares of the active nominees of one account (or of the customer, when no account is named) must total 100; this is checked at commit so a set can be replaced in one transaction. A new set replaces the old one, which stays as history.
  - Relationships are ended, never edited or deleted.
- **Loan parties** (`lending.loan_party`): BORROWER, CO_APPLICANT, GUARANTOR.
  - The database writes the borrower row when a loan is booked; existing loans were backfilled.
  - Loan creation and preview accept `parties` (customer id and role).
  - A customer holds one role per loan, so a borrower cannot also be guarantor or co-applicant. Guarantors and co-applicants must be ACTIVE customers.
  - Parties cannot be changed or removed (release of a guarantor is not built).
- **Exposure view** (`customer.exposure`): totals as borrower, as co-applicant and as guarantor, with loan counts.
  - A loan counts at its sanctioned amount until disbursed and at its principal outstanding afterwards. Closed, cancelled and written-off loans count as zero.
  - Borrower-level NPA is unchanged: it looks only at the borrower's own accounts and does not reach guarantors or co-applicants.
- **Exposure limit** (`customer.exposure_limit`): set, changed or removed through maker-checker (entity `CUSTOMER_EXPOSURE_LIMIT`).
  - A database trigger checks it when a loan is booked and again when it is disbursed, with the customer row locked, and refuses with 409.
  - It covers exposure as borrower only.
- **API:** `GET` and `POST /api/v1/customers/{id}/relationships`, `GET /api/v1/customers/{id}/exposure`, `POST /api/v1/customers/{id}/exposure-limit`, `GET /api/v1/loans/{id}/parties`.

**Consent and purpose records (US-036)**
- **Record** (`customer.consent`, completed from the V4 stub): purpose, lawful basis (`CONSENT` or `LEGITIMATE_USE`), notice version shown, channel, evidence reference, granted and expiry times, withdrawal time, reason and user.
  - Purposes are the enumeration `consent-purpose`: `LOAN_PROCESSING`, `KYC_VERIFICATION`, `CREDIT_BUREAU_REPORTING`, `ACCOUNT_AGGREGATOR`, `MARKETING`.
  - One record can be in force per customer and purpose.
- **Immutability:** a trigger allows only one later change to a record, its withdrawal. Records cannot be edited, deleted or truncated. Every grant and withdrawal is also written to the append-only `customer.consent_event`.
- **Functions:**
  - `customer.has_consent(customer, purpose, at)`: true when a record is granted, not withdrawn and not expired at that moment. History can be queried for any past moment.
  - `customer.bureau_reportable(customer)`: the hook for the reporting module.
  - `customer.has_retention_obligation(customer, at)`: true while the customer is party to a loan that is sanctioned, live or written off, or that closed within the retention period.
- **API:** `GET` and `POST /api/v1/customers/{id}/consents`, `POST /api/v1/customers/{id}/consents/{consentId}/withdraw`. Both writes are audited.

**KYC documents (US-032)**
- **Metadata** (`customer.kyc_document`): document type (enumeration `kyc-document-type`), last four characters and keyed hash of the number, issue and expiry dates, status, file key, content type, size, SHA-256, uploader and time.
- **Files:** stored through `DocumentStore` (`put`, `get`, `delete`, `exists`) under `tenants/<code>/kyc/<customer>/<uuid>`.
  - `FileDocumentStore` writes under `corebanking.documents.dir` with owner-only permissions and refuses keys with `..`, a leading `/` or a backslash.
  - **The S3 implementation is not built.** It needs the AWS SDK dependency. Until then the directory must be an encrypted volume.
- **Upload:** `POST /api/v1/customers/{id}/kyc-documents` with the file as the request body.
  - PDF, JPEG or PNG, at most 5 MB; the leading bytes must match the declared type.
  - Metadata is in the query string. The document number is in the `X-Document-Number` header so it does not reach access logs.
- **Verification:** `POST …/{docId}/verify` and `…/reject`, by someone other than the uploader (also a database constraint). A decided document cannot be decided again.
- **Download:** `GET …/{docId}/content` needs `kyc:view-document`, is audited, checks the stored SHA-256, and is served as an attachment with `Cache-Control: no-store`.
- **KYC status:** `customer.kyc_complete` is true when every required document is verified and unexpired. The required set is the tenant property `kyc.required-documents` (default `pan,address-proof,photo`).
  - A trigger moves the customer to VERIFIED when the last required document is verified, and refuses any other attempt to set VERIFIED.
  - `customer.expire_kyc(date)` moves customers whose documents have run out to EXPIRED. It is not yet wired into end of day.
- **Branch scope:** every endpoint above reports a customer outside the caller's branch scope as not found.

**Approval payloads (SEC-03, part)**
- Date of birth, city and pincode are now sealed inside customer-create requests. The checker sees an age band, the state and a masked pincode.
- The applier still reads requests raised before the change.
- Name storage is unchanged. Options and a recommendation are in `docs/security/sec-03-display-name.md`.

### Regulatory interpretations encoded
These are the product's reading and need confirmation by the lender's compliance or counsel.

- **DPDP Act 2023, consent and withdrawal:**
  - Each record holds the notice version shown (s.5) and an evidence reference, because the lender must be able to prove that consent was given (s.6(10)). The evidence reference is mandatory for `CONSENT`.
  - `LEGITIMATE_USE` (s.7) is recorded as a basis without consent. Such a record cannot be withdrawn; it ends through its expiry.
  - A withdrawal (s.6(4)) takes effect immediately and never removes the grant. Processing before the withdrawal stays lawful (s.6(5)), which is why `has_consent` can be asked for a past moment.
  - **Marketing** and account-aggregator consent simply end on withdrawal.
  - **Servicing purposes** (`LOAN_PROCESSING`, `KYC_VERIFICATION`, `CREDIT_BUREAU_REPORTING`; tenant property `consent.servicing-purposes`): when the customer is party to a loan, the withdrawal is recorded and flagged `retained_for_legal_obligation`. The consent is no longer in force, but the data is kept and used as far as law requires (s.6(6) and s.8(7)): RBI and PMLA record-keeping and servicing the existing contract. The database sets the flag; the caller cannot.
  - The retention period after closure is the tenant property `consent.retention-years`, default 5.
- **Credit-bureau reporting:** `bureau_reportable` is true with a current consent or legitimate-use record. It is also true after a withdrawal flagged `retained_for_legal_obligation`, for as long as the retention obligation lasts, on the reading that the duty to furnish credit information on an existing loan (Credit Information Companies (Regulation) Act 2005) continues. **This second case is the least certain interpretation here** and is listed as a decision below.
- **Aadhaar (UIDAI masked Aadhaar; RBI Master Direction on KYC):**
  - A full Aadhaar number is never accepted, hashed or stored. A value shaped like one is refused for every document type.
  - Only the last four digits are kept, and no hash (a database constraint).
  - The verifier must confirm that the uploaded copy is masked before an Aadhaar document can be VERIFIED (a database constraint). The system does not inspect the image itself.
- **Other document numbers:** only the last four characters and a keyed hash are stored, never the number.
- **Guarantors and NPA:** a borrower's NPA does not classify the guarantor's own accounts. Guarantor exposure is shown, not limited.

### Tests
- **Kernel:** 22 new tests in `CustomerRulesTest` (42 in the module): amount limits, file signatures, document numbers and Aadhaar refusal, age band and pincode masking.
- **Database rules:** 110 SQL checks in `customer_p25_test.sql`: limits (14), loan parties (9), relationships (15), exposure (12), consent (33), KYC documents (27). The other five SQL suites still pass.
- **App unit tests:** `FileDocumentStoreTest` (4, including role-claim parsing) and one new case in `CustomerValidationTest` for both payload shapes.
- **OpenAPI:** lint clean.
- **Not verified here:** the Spring module is compiled only in CI. In the build sandbox the changed files were type-checked against hand-written API stubs, and every SQL statement they send was checked with `PREPARE` on a migrated database. No endpoint has been called end to end.

### Needs action
- **Console API types:** `frontend/console/src/api/schema.d.ts` must be regenerated (`npm run gen:api`), otherwise the console CI job fails.
- **New permissions** to add to the realm template and the console list: `limit:view`, `limit:propose`, `consent:view`, `consent:record`, `kyc:upload`, `kyc:verify`, `kyc:view-document`.
- **Keycloak mapper for roles:** the `console` client has `fullScopeAllowed: false`, so realm roles are probably not in the access token today. Add a "User Realm Role" mapper (claim `roles`, multivalued, in the access token) to the `console` client and to API clients, and include the realm roles in the client's scope. Without it every user has no roles: limits never apply, or with `limits.default-deny` everything limited is refused.
- **Documents directory:** set `COREBANKING_DOCUMENTS_DIR` to an encrypted, backed-up volume. The default is a temporary directory.

### Not yet built in P2-5
- S3 document store.
- Console screens for limits, relationships, exposure, consent and KYC documents.
- Ending a relationship and releasing a guarantor through the API.
- Daily KYC expiry step in end of day.
- Erasure of documents and consent records under a DPDP erasure request.
- Virus scanning of uploads.
- Role amount limits on loan reversals and restructures.

## Console for P2-4 and P2-5

- **Dashboard** on the home page (portfolio, disbursed and collected today / month to date, collection efficiency, NPA %, DPD buckets, pending approvals, last EOD).
- **Reports:** catalogue, run form built from each report's parameters, run list and CSV download; the bureau file needs `bureau:export` and an acknowledgement of the personal-data and layout warning.
- **Loan detail:** Documents tab (KFS, statement, schedule, NOC, GST invoices) and Parties tab; new loans accept co-applicants and guarantors.
- **Customer detail:** KYC documents (upload, verify/reject, open), consents (grant, withdraw), relationships and exposure with limit.
- **Masters:** role amount limits.
- **Tests:** 162 console tests in mock mode. The pages have not been checked by eye in a browser yet.

### API gaps noted while building the console (to settle in the next increment)
- Reports: no mine/all filter on the run list; bureau `rowCount` counts accounts.
- Limits: refusals are a plain 403 (no distinct problem type); no endpoint listing valid role names; `LOAN_WAIVER` vs `FEE_WAIVER` for the charge-waiver endpoint is not stated in the contract.
- KYC: error statuses for too-large / wrong-type uploads and for uploader-verifies-own-upload are not declared; the document number header is optional for every type.
- Consent: no "can be withdrawn" flag; `expiresAt` is a date-time though staff enter a date.
- Relationships and loan parties cannot be ended or changed after creation.
- Dashboard: `lastEod` has no run id; `pendingApprovals` scope is not stated.


## P2-6 lending completion

Stories: US-038 (templates), US-039 / US-054 (repayment methods, rate bases, schedules), US-044 (product preview), US-050 (tranches), US-059 (remaining amendments), US-060 (simulations). Migration `V18__product_completion.sql`.

### What was built
- **Engine (`lending-core`, `calc`):**
  - Repayment methods `EQUATED`, `STEP_EQUATED`, `FIXED_PRINCIPAL` (optionally with principal every n-th instalment), `BULLET_TOTAL_INTEREST`, `BULLET_PERIODIC_INTEREST`, `STRUCTURED`.
  - Every method at seven frequencies: daily, weekly, fortnightly, monthly, quarterly, half-yearly, yearly. The tenor counts periods of the frequency.
  - Interest bases: daily-reducing (actual days by day count), periodic-reducing (rate / periods per year) and flat. Nine day-count conventions.
  - Step-up and step-down EMI: the instalment changes by a percentage every n instalments; a step so steep that an instalment would not cover its interest is refused.
  - Broken-period interest (BPI): absorbed by the first instalment, added to it, demanded on its own, or deducted from the payout. Deducted BPI is held as an advance and settles its demand on the due date, so the income is still earned day by day.
  - APR: periodic IRR × periods per year for evenly spaced instalments; XIRR on dated flows for bullet, structured and separately collected BPI. A flat-rate loan needs nothing special: its instalments are the flows.
- **Products:** frequency, interest basis, BPI mode, step settings, principal interval, tranche / pre-EMI / top-up flags and a benchmark link. The engine and the database enforce the same combinations.
- **Templates (US-038):** eight seeded templates, `GET /api/v1/loan-product-templates`.
- **Product preview (US-044):** `POST /api/v1/loan-products/preview` runs a sample loan on a draft product (no code or approval needed) through the booking engine and returns the KFS figures.
- **KFS:** now also shows frequency, interest basis, the effective (reducing) rate, BPI and the APR basis. A flat-rate product discloses its true APR.
- **Tranches (US-050):** `POST /loans/{id}/disbursement` takes an `amount`.
  - Interest accrues only on the amount drawn.
  - Product option `preEmi`: interest-only instalments until the final tranche, then EMIs for the full tenor. Without it: EMIs on the amount drawn, recomputed on each tranche over the instalments left.
  - `DISBURSEMENT` fees once on the sanctioned amount; `EVERY_DISBURSEMENT` fees on each tranche. Deducted BPI comes off the first tranche only (later tranches start accruing on their own date, so they have no broken period).
  - No tranche while the account has unpaid dues or is NPA.
  - Cancelling the undrawn amount is a sanction change to the amount disbursed; it starts the EMIs of a pre-EMI loan.
  - `GET /loans/{id}/tranches` lists them; `lending.loan_tranche` is the queryable copy.
- **Amendments (US-059):**
  - `MATURITY_CHANGE` through the existing amendment endpoints (a tenure change expressed as a date).
  - Sanctioned amount: `POST /loans/{id}/sanction-change` (and `/preview`). Reduction only of the undrawn part. Top-up on the same account needs a product that allows it, a standard account without dues, and room in the product maximum and the customer's exposure limit; the extra amount is then disbursed as a tranche. One checker.
  - Manual NPA mark: `POST /loans/{id}/npa-override` downgrades the account to an NPA class or holds it there until a date. It never upgrades.
  - Un-mark: `POST /loans/{id}/npa-override/release`. Refused while dues are unpaid, at proposal and again at approval. Nothing is posted; the next day-end upgrades the account by the normal rule.
  - Mark and un-mark each need two checkers. Neither they, nor a tranche, nor a sanction change can be reversed, and no transaction before one of them can be reversed.
- **Simulations (US-060):** `POST /loans/{id}/simulations/disbursement` and `/simulations/transaction` (receipt, part-prepayment, pre-closure, today or up to 366 days ahead). They run the posting code on a copy; nothing is stored.
- **Also in V18 (from the first pass of this increment):** GST credit notes for fees waived or reversed after their invoice (CGST Act s.34), benchmark and benchmark-rate tables, and the `rate_reset_due` view.

### Repayment methods of the reference system (18)
| # | Reference method | Status | How |
|---|---|---|---|
| 1 | Bullet Total Interest | Built | `BULLET_TOTAL_INTEREST` |
| 2 | Equated | Built | `EQUATED` (moratorium, balloon, step variant `STEP_EQUATED`) |
| 3 | Overdraft | Not built | Revolving limit, not a term loan. Needs the line-of-credit design (BR-LPR-02). |
| 4 | Bullet Periodic Interest | Built | `BULLET_PERIODIC_INTEREST` |
| 5 | Periodic Fixed Principal And Accrued Interest | Built | `FIXED_PRINCIPAL` |
| 6 | Periodic Fixed Principal And No Interest | Built, as read | `FIXED_PRINCIPAL` at a 0% rate. Open question 1. |
| 7 | Periodic Fixed Principal … With Differing Interval | Built | `FIXED_PRINCIPAL` with `principalEvery` > 1 |
| 8 | Periodic Assigned Principal And Accrued Interest | Built | `STRUCTURED` |
| 9 | Periodic Full Principal And Accrued Interest | Not built | Meaning unclear. Open question 2. |
| 10 | Periodic Full Principal And No Interest | Not built | Open question 2. |
| 11 | Periodic Full Principal … With Differing Interval | Not built | Open question 2. |
| 12 | Tranche Bullet Total Interest | Built | `BULLET_TOTAL_INTEREST` with `multipleDisbursements` |
| 13 | Tranche Principal And No Interest | Built, as read | `FIXED_PRINCIPAL` at 0% with `multipleDisbursements`. Open question 1. |
| 14 | Tranche Principal And Periodic Interest | Built, as read | `FIXED_PRINCIPAL` with `multipleDisbursements`. Open question 3. |
| 15 | Dropline Overdraft With Differing Interval | Not built | Revolving limit that reduces on a schedule. With 3. |
| 16 | Tranche Bullet Periodic Interest | Built | `BULLET_PERIODIC_INTEREST` with `multipleDisbursements` |
| 17 | Tranche Equated Loan | Built | `EQUATED` with `multipleDisbursements`, with or without `preEmi` |
| 18 | Tranche Bullet Tranche Repayment | Not built | Each tranche repaid as its own bullet. Open question 4. |

11 built (three of them on our reading of the name), 7 not built.

### Rate bases of the reference system (10)
| # | Reference rate basis | Status | How |
|---|---|---|---|
| 1 | Configured Rate Fixed | Built | Rate on the application, within the product band |
| 2 | Simple Interest Rate Annual | Built | `interestBasis` `DAILY_REDUCING` (or `PERIODIC_REDUCING`) |
| 3 | Simple Interest Rate Flat | Built | `interestBasis` `FLAT`; accrues at the equivalent reducing rate |
| 4 | Tenure Amount and Installment | Built | Application `instalment`; the rate follows from it |
| 5 | Simple Interest Rate Annual From Future Value | Built | Application `maturityAmount` on a bullet loan |
| 6 | Customer Limit | Not built | Meaning unclear. Open question 5. |
| 7 | Configured Rate Floating | Built | Booking rate = benchmark + spread; reset automatically at day-end on each reset date (V24, docs/runbooks/rate-reset.md) |
| 8 | Fixed Interest Rate Slab | Built | Product interest table (`lending.resolve_rate`, Phase 1) |
| 9 | Floating Interest Rate Slab | Not built | Slab table on top of a benchmark. With the floating reset work. |
| 10 | Elapsed-Tenure Interest Rate Slab | Not built | Rate that changes with the age of the loan. Open question 6. |

6 built, 1 partly, 3 not built.

### Open questions
1. **"No Interest" methods (6, 10, 13):** read as principal-only instalments at a 0% rate. If the reference means interest collected upfront or by another account, this is wrong.
2. **"Periodic Full Principal" (9, 10, 11):** the full principal falling due every period is not a term loan as we understand it (a rolling bullet? a renewable loan?). Needs a look at a live reference account.
3. **"Tranche Principal And Periodic Interest" (14):** read as equal principal plus interest with tranches. It may instead mean each tranche's principal repaid separately.
4. **"Tranche Bullet Tranche Repayment" (18):** needs a maturity per tranche; today the loan has one schedule.
5. **"Customer Limit" rate basis:** rate taken from the customer's limit record? There is no such record yet.
6. **"Elapsed-Tenure" slab:** does the rate step by loan age automatically, and is that a fixed or a floating rate for the RBI reset circular?
7. **Mark needs two checkers too:** the brief asks two checkers for the un-mark; the mark is also set to two. Confirm or lower it in `platform.approval_rule`.
8. **Un-mark timing:** the release posts nothing and the upgrade happens at the next day-end. Confirm that a same-day upgrade is not required.
9. **Top-up and evergreening:** a top-up is refused for any account that is not STANDARD (so also SMA). Confirm.
10. **KFS of a tranche loan:** figures assume the whole amount is disbursed on day one. Confirm that this is the disclosure wanted for pre-EMI loans.

### Tests
- **Engine:** 174 tests pass in the pure modules (calc 22, lending-core 78, kernel 66, ledger-core 8). New in this increment: `RepaymentMethodsTest` 14, `TrancheAndServicingTest` 11, `RateBasisTest` 3 (28 tests).
  - Golden values: weekly micro-loan, step-up and step-down, each BPI mode, flat rate, periodic-reducing, given instalment, future value, differing interval, structured, no-interest.
  - Whole-life GL reconciliation for each method and frequency, with tranches, pre-EMI, cancellation of the undrawn amount, top-up, maturity change, override and release.
  - APR checked as the IRR (the net present value of the flows at the APR is zero) for step, broken-period, flat-rate and structured schedules.
- **Database rules:** 98 SQL checks in `product_completion_test.sql`. The other seven suites still pass (amendments 23, customer 110, documents and reports 66, ledger 19, lending rules 21, phase 1 gaps 22, phase 1 rules 39).
- **App unit test:** `LoanStateJsonTest` has 4 cases, one new for tranche and override state, and the pre-P2-6 state case now removes the new fields.
- **OpenAPI:** lint clean.
- **Not verified here:** the Spring module is compiled only in CI. In the build sandbox the lending services and controller were type-checked with `-Xlint:all -Werror` against hand-written API stubs, every SQL statement they send was checked with `PREPARE` on a migrated database, and `LoanStateJsonTest` was run on Jackson 2.16 through an adapter. No endpoint has been called end to end.

### Needs action
- **Console API types:** regenerate `frontend/console/src/api/schema.d.ts` (`npm run gen:api`).
- **New permission:** `loan:classify` (NPA mark and un-mark). Sanction changes use `loan:amend`, tranches `loan:disburse`, simulations and tranche list `loan:view`, templates and product preview `product:view`.

### Not yet built in P2-6
- Reference methods 3, 9, 10, 11, 15, 18 and rate bases 6, 9, 10 (tables above).
- Benchmark rates: recorded through `GET/POST /api/v1/benchmarks` and `POST /api/v1/benchmarks/{code}/rates` (maker-checker, append-only history; console: Masters → Benchmark rates).
- **Floating-rate reset (V24, RBI 18-Aug-2023 "Reset of floating interest rate on EMI based personal loans"):** on a loan's reset date the day-end that closes that calendar day sets the rate to the benchmark rate in force on that date + the loan's spread, through the amendment code, with the loan's option (borrower's choice, else the product's `resetOption`, default KEEP_TENURE_CHANGE_EMI). Keeping the EMI falls back to a higher EMI when the longer tenure would pass the product maximum, amortise negatively or extend a borrower in arrears. The next reset date always moves on (also when the rate is unchanged). Applied outside the product band and flagged (D-14). Recorded as loan transaction and amendment history kind RATE_RESET (no approval: it follows the contract); the RATE_RESET message (SMS template seeded for the demo tenant) tells the borrower the new rate, EMI and instalments left. A frozen account's reset is held until it is unfrozen; closed and written-off loans are not reset. `GET /api/v1/rate-resets/upcoming?days=n` lists the loans due with the projected rate and estimated EMI; `GET /api/v1/rate-resets` the resets applied; `POST /api/v1/loans/{id}/rate-reset-preference` records the borrower's EMI/tenure choice through maker-checker. A combination (CHANGE_BOTH) is a RATE_CHANGE amendment; a switch to a fixed rate exists in the engine as amendment SWITCH_TO_FIXED but the board-policy terms of the switch (charges, which fixed rate) are not built: out of scope for now.
- Amendments, restructures and `REDUCE_TENURE` prepayment apply to monthly equated loans on the daily-reducing basis only. Other methods take part-prepayment with `REDUCE_EMI`; structured and differing-interval loans take none.
- Tranches and top-up: equated, fixed-principal and bullet products on the daily-reducing basis only; not step or structured loans.
- Sanction change of a loan that is not yet disbursed.
- Simulation download (CSV / PDF); the endpoints return JSON.
- Credit-note PDF and the GST summary report reading `lending.gst_output_document`.
- Console screens for everything in this increment.
- A differing-interval loan at 0% has instalments of zero in the interest-only periods; its schedule is tested, its whole life is not.

## P2-2 integrations

**Read this first.** No partner has been chosen (decision D-09) and there are no sandbox credentials. What is
built is the provider-independent machinery and a built-in **SIMULATOR** that makes every flow run end to end
without a partner. The Easebuzz and generic SMS adapters are skeletons marked **UNVERIFIED-AGAINST-PROVIDER**:
written from recollection of public documentation, never run against the provider. Nothing here is certified by,
or has been exchanged with, any gateway, bank, NPCI, SMS aggregator or DLT platform.

The Spring code of this increment was type-checked against hand-written API stubs and has **not been started or
run**: there is no application test of these flows yet. What was executed: the pure module's tests and the SQL
checks. `tools/integration/los_contract_test.py` is the first thing to run on a local stack.

### Story by story
| Story | State | Built | Needed from the partner (or still open) |
|-------|-------|-------|------------------------------------------|
| US-051 Payout via gateway | Simulator-only | Beneficiary account (encrypted, masked, validation hook); payout instruction from the outbox; send, poll and callbacks through one lifecycle; idempotent on our reference; failed or returned payout reverses the disbursement (straight-through), proposes the reversal (staff) or parks it (`payout.failure-action`); reconciliation query; operations retry and refresh | Payout provider: credentials, sandbox, the status-enquiry and callback specifications and callback signature scheme, penny-drop API, IMPS/NEFT limits, return handling |
| US-070 Mandate registration status | Simulator-only | Mandate register (UMRN, lifecycle, limit, frequency, validity, masked account, sponsor bank and utility codes); status by callback, poll, operator or CSV upload; mandate status on the loan | e-Mandate provider or sponsor bank: registration API or file specification, callback specification, utility code, sponsor bank code |
| US-071 Presentation file | Built for the GENERIC layout only | End-of-day step; T-n working days by the holiday calendar; mandate limit and validity respected; one file per sponsor bank and utility code; file kept in the document store | **The sponsor bank's file layout** (GENERIC is ours and no bank accepts it), file naming, encryption or signing, delivery channel, cut-off times |
| US-072 Response file | Built for the GENERIC layout only | Whole-file refusal on bad control totals; file, control-total and row idempotency; success posts the repayment; bounce records the reason, charges the product's bounce fee through the fee engine, posts no receipt (DPD runs on) and schedules a re-presentation within the tenant's limits | The sponsor bank's response layout; **NPCI return reason list to verify** (27 codes shipped as indicative, all `verified = false`); which reasons may be re-presented |
| US-073 Gateway collections | Simulator-only | Payment order with methods passed through; verified callback, idempotent on the provider payment id; repayment through the loan engine with the value-date rule; unmatched receipts queue; settlement CSV upload; reconciliation view in both directions | Gateway: credentials, sandbox, callback specification, settlement report format, refund API |
| US-120 OAuth2 clients | Built, not run against Keycloak | Maker-checker for create, scope change, secret rotation, disable, enable; two checkers for money-moving scopes; scope allow-list; secret collected once by the proposer and never stored; staff profile with branch scope | An admin service account in Keycloak with the right to manage clients and role mappings in tenant realms; a test against a live Keycloak |
| US-121 Signed webhooks | Built | Endpoints through maker-checker; SSRF guard at registration and before each delivery; HMAC-SHA256 over `timestamp.body` with key id; rotation with overlap; backoff with jitter; dead letter; delivery log; replay; payload allow-list and redaction | A pilot subscriber to verify against (the sample verifier is `tools/integration/verify_webhook.py`) |
| US-122 Gateway adapters | Skeleton, unverified | Easebuzz collections (initiate, status, callback hash) and payout (transfer request) isolated in `EasebuzzSpec` and two adapters, with contract tests of what they send; off unless the deployment enables it. Razorpay and Cashfree: not started | Easebuzz: current documentation to confirm every constant in `EasebuzzSpec`, sandbox credentials, payout status and callback specifications |
| US-123 SMS and e-mail | Simulator-only | Templates per tenant with DLT ids through maker-checker; strict rendering; consent and opt-out check; queue with retries and a per-tenant rate limit; delivery log with masked recipient; events: disbursement, due reminder, payment received, bounce, NOC issued, rate reset | SMS aggregator and e-mail provider (credentials, API, delivery reports); the tenant's **DLT registration**: principal entity id, headers, content template ids |
| US-124 Pilot LOS integration | Guide and script written, not run | `docs/integration/los-integration-guide.md`; `tools/integration/los_contract_test.py`; customer create-or-get by `externalRef`; beneficiary, mandate, payout status and collection calls | A pilot LOS; a run of the script on a local stack |

### How it fits together
- **Outbox (ADR-008):** lending writes `loan.disbursed`, `payment.received`, `loan.closed`, `loan.npa`,
  `loan.noc_issued`, `loan.rate_reset` and `loan.disbursement_reversed` in the transaction of the change. The
  integration module adds `payout.status`, `mandate.status` and `payment.bounced`. A relay takes events in order,
  one transaction each, and fans them out to payout instructions, webhook deliveries and queued messages. SQS is
  not in between yet: the relay runs in process, on a timer (5 seconds).
- **No provider call inside a posting transaction.** Senders claim rows with a lease, call the provider, then
  record the outcome.
- **Unknown outcomes stay unknown.** A payout call without an answer is retried with the same reference and,
  when retries run out, flagged for operations; it is never marked FAILED by us.
- **New pure module** `backend/integration-core` (113 tests): signature, SSRF guard, retry schedule, templates,
  lifecycles, value-date rule, simulator, Easebuzz skeletons, NACH GENERIC layout, Keycloak admin requests.

### Interpretations to confirm
- **Failed payout:** the disbursement is reversed as a whole (principal, fees deducted, GST, day-end accruals) and
  the loan returns to SANCTIONED; nothing is kept from a borrower who never received the money. The existing
  cooling-off cancellation was not used because it keeps fees and interest. Refused once the loan has another
  transaction.
- **Value date of a gateway or NACH receipt:** the payment (settlement) date when it is the business date or up to
  `collections.max-back-value-days` (3) before it; otherwise the business date, flagged for review when older.
- **Messages and consent:** transactional messages about the customer's own loan are sent under legitimate use
  (DPDP s.7) even after a channel opt-out; service messages respect the opt-out; promotional ones also need a
  MARKETING consent. For the lender's counsel to confirm.
- **Second payment on one order** is not posted automatically; it goes to the unmatched receipts queue.
- **Beneficiary account** is recorded without maker-checker (it is validated by the gateway and audited; the
  disbursement itself is approved). `payout:beneficiary` is a two-checker scope for API clients.

### Security decisions
- **Secrets at rest:** provider secrets, webhook signing secrets, account numbers, message recipients and texts
  and the raw body of provider callbacks are AES-256-GCM ciphertext under the tenant's data key, with the column
  as associated data. Provider secrets are also sealed inside the approval payload. No API returns a secret:
  only its name, "set" and the last four characters. API client secrets are never stored.
- **SSRF:** https only, public DNS names, no IP literals, ports 443 and 8443, no redirects; the host is resolved
  and every address checked before each request. Not closed: a DNS answer that changes between the check and the
  connection (see the runbook for the DNS cache setting and the egress policy).
- **Outbound signature:** `X-CoreBanking-Signature: t=…,v1=<key id>:<hex>`; receiver tolerance 300 seconds.
- **Inbound:** verified over the raw body by the provider's adapter, only for the tenant's active provider; one
  row per provider event id (replay protection); 2xx only after the row is committed; processing afterwards.
- **Deployment gate:** no provider can be configured unless `corebanking.integration.providers-enabled` lists it.
  The default is empty; the simulator must never be listed in production.

### Tests
- **integration-core:** 113 tests (signature vectors computed independently, SSRF address ranges, retry
  schedule, template strictness, payload redaction, lifecycles, simulator outcomes, Easebuzz request and hash
  contract tests, NACH round trips and control totals, Keycloak requests, scope allow-list).
- **Database rules:** 104 SQL checks in `integrations_test.sql`.
- **Not tested:** the Spring services, controllers and the worker (never started); the LOS contract script.

### What is needed before this is used
- **Console API types:** `frontend/console/src/api/schema.d.ts` must be regenerated (`npm run gen:api`), otherwise
  the console CI job fails.
- **New permissions** to add to the realm template and the console list: `integration:view`, `integration:admin`,
  `integration:simulate` (never in production), `payout:view`, `payout:beneficiary`, `payout:admin`,
  `collection:view`, `collection:create`, `collection:admin`, `mandate:view`, `mandate:register`, `mandate:admin`,
  `nach:admin`, `nach:file`, `webhook:view`, `webhook:admin`, `apiclient:view`, `apiclient:admin`, `message:view`,
  `message:admin`.
- **Ingress:** route `POST /hooks/v1/**` to the app.
- **Migrations:** this increment is V20; V18 and V19 come from other increments. Flyway is not configured for
  out-of-order migrations, so a database that already has V20 will refuse V18 or V19 arriving later: merge the
  three before any shared database is migrated.

### Not yet built in P2-2
- Console screens for any of this.
- Razorpay and Cashfree adapters; a real mandate provider; a real e-mail provider; SMS delivery reports.
- SQS between the outbox and the consumers.
- Automatic pick-up of bank files (the document store has no listing) and automatic submission of presentations.
- Refunds through the gateway (a receipt is only marked REFUND_DUE).
- Rate limits on the callback endpoint and per API client; mTLS and IP allow-lists for API clients.
- Straight-through customer creation for an LOS.
- Messages in languages other than English; mandate suspension after repeated bounces.
- Application tests of the integration services.


## P2-7 platform completion

Built 03-Oct-2026. Stories: US-014, US-027, US-111, US-112, US-113 (scheduling and retention), US-004, US-007, US-119. Migrations: tenant `V19`, control `V3`.

### What was built

**Custom fields (US-014)**
- Definitions in `platform.custom_field` for customers, loan accounts and loan products: key, label, type (TEXT, NUMBER, DATE, BOOLEAN, ENUM with an enumeration type), widget, required, pattern, minimum and maximum, personal-data flag, active.
- Definitions change through maker-checker (entity `CUSTOM_FIELD`). A field is deactivated, never deleted; its type and personal-data flag cannot change.
- Values are a JSON object `custom` on the record. They are checked twice: by the pure `CustomFields` (kernel), which reports every problem at once, and by the database (`platform.validate_custom`, a deferred trigger on the three tables).
- Unknown key, wrong type and missing required field are errors. On an update, values that did not change are not judged again.
- **Personal-data fields are encrypted.** A field flagged `pii` must be TEXT. Its value is sealed with the tenant PII cipher, stored as ciphertext plus mask, and returned masked. The database refuses a clear value for such a field.
- **API:** `GET` and `POST /api/v1/custom-fields`. Customer create and read, and loan create and read, accept and return `custom`. Loan product values have their own calls (`GET` and `PUT /api/v1/loan-products/{code}/custom`, maker-checker) so the product factory is unchanged.

**Session management (US-027)**
- `GET /api/v1/sessions` lists the caller's sessions; with `?user=` and `session:admin`, another user's. `DELETE /api/v1/sessions/{id}` ends one. Every termination is audited.
- Sessions live in Keycloak. The port is `SessionAdmin`; `KeycloakSessionAdmin` uses the JDK HTTP client and a service-account client in each tenant realm.
- Requests and reply parsing are pure code in the kernel (`KeycloakAdmin`, `MiniJson`) and tested there.

**Posting during end of day (US-111, ADR-015)**
- Cut-off is the start of end of day. After it, a repayment from a client with `loan:stp` is accepted (202) as a deferred receipt and booked and valued on the next business date once that date opens. Staff postings stay blocked.
- Receipts are booked right after end of day and by the job `DEFERRED_RECEIPTS`. A receipt that cannot be booked becomes FAILED and waits for a person (`loan:admin`: retry or cancel with a note).
- The database now refuses a ledger lot for a closed date or for a date not yet open.

**Job catalogue and report scheduling (US-112, US-113)**
- `platform.job_definition` and `platform.job_run`. A scheduler polls every tenant; a job runs under a PostgreSQL advisory lock and a fire time is unique per job, so two instances never run it twice.
- Jobs: booking of deferred receipts, dashboard metrics refresh, KYC expiry, consent expiry, removal of report files past retention, usage snapshot, and one job per report.
- A scheduled report runs with the branch scope of the user who scheduled it and belongs to that user. Its dates come from a period relative to the business date.
- **E-mail delivery is not built.** The file is stored and the run says `delivery: PENDING_PROVIDER` (OI-06).
- Schedules (six-field cron in IST), the on/off switch and parameters change through maker-checker (entity `JOB_SCHEDULE`).
- **API:** `GET /api/v1/jobs`, `POST /api/v1/jobs/{code}/run`, `PUT /api/v1/jobs/{code}`, `GET /api/v1/jobs/runs`, `GET /api/v1/dashboard/trend`.

**Usage metering (US-004)**
- `control.usage_daily`: per tenant and day, active loans, active customers, staff users, API calls, document bytes and database bytes.
- API calls are counted in memory in `TenantFilter` and written to the control plane every minute and at shutdown. No request waits for a database write.
- **API:** `GET /platform/v1/usage`, `GET /platform/v1/usage/monthly`, `GET /platform/v1/usage/monthly.csv`, `POST /platform/v1/usage/snapshot`.

**Support access (US-007, ADR-016)**
- An engineer with `platform:support` asks for read-only access of 15 minutes to 8 hours. The tenant admin approves, rejects or revokes in the tenant API.
- While a grant is in force, the engineer may call listed GET endpoints of that tenant with the headers `X-Support-Tenant` and `X-Support-Grant`. Expiry is checked on every request.
- Responses have personal values masked, files are withheld, and every call is audited under `support:<username>` with the grant id.
- The decision is one pure function, `SupportAccess` (kernel).

**Developer portal (US-119)**
- `/developer` serves a static page, the guide and `/developer/openapi.yaml`, public on the API host, with `Cache-Control` and a Content-Security-Policy that allows this origin only.
- The page and the guide are in `docs/api/portal`. The build copies them and the contract into the application's resources (`processResources` in `backend/app/build.gradle.kts`), so the repository holds one copy of the contract.

### Tests
- **Kernel:** 46 new tests (112 in the module): custom fields (10), cron schedules (7), support access decisions and masking (15), posting cut-off, JSON reader, Keycloak calls, usage meter and report periods (14).
- **Tenant database:** 151 checks in `platform_p27_test.sql`. The other seven tenant suites still pass.
- **Control database:** 18 checks in `control/control_p27_test.sql`. CI and `tools/db/run-sql-tests.sh` now run control tests too.
- **App:** one new case in `FileDocumentStoreTest`.
- **OpenAPI:** lint clean, 130 operations.

### Not verified here
- **Spring code:** compiled only in CI. Here it was type-checked against hand-written API stubs with the CI lint options. `SecurityConfig` and the files that need Boot, Flyway or Hikari were not type-checked.
- **Keycloak admin calls:** never run against a live Keycloak.
- **Gradle change:** tried on a small stand-in project with the same structure, not on the real build.
- **No endpoint has been called end to end.**

### Needs action
- **Console API types:** `frontend/console/src/api/schema.d.ts` must be regenerated (`npm run gen:api`), otherwise the console CI job fails.
- **Permissions to add to the tenant realm and the console list:** `custom-field:view`, `custom-field:propose`, `session:admin`, `job:view`, `job:run`, `job:schedule`, `support-access:approve`.
- **Platform realm:** add `platform:support`.
- **Keycloak:** a client `corebanking-admin` in each tenant realm with service accounts on and the realm-management roles `view-users` and `manage-users`.

### Not yet built in P2-7
- E-mail delivery of scheduled reports and of support access requests (OI-06).
- Editing the custom values of an existing customer or loan (there is no update API for them yet).
- Support access scopes other than READ_ONLY.
- Value-dating a deferred receipt on the date that was being closed (D-15).
- Rate limits; the portal says "to be published".

## Integration of P2-6, P2-2 and P2-7

The three increments were built in parallel and merged on 03-Oct-2026. Migrations: tenant `V18` (P2-6), `V19` (P2-7), `V20` (P2-2), `V21` (this merge); control `V3` (P2-7).

### What the merge changed
- **Loan endpoints:** loan creation, the loan detail and repayments are served by `LoanEntryController` only. It calls the `LoanService` methods of P2-6 and P2-2, so parties, `externalRef`, tranches, events, custom fields and deferred receipts all apply to the same request.
- **Customer creation:** the request carries both `externalRef` and `custom`.
- **Payout per tranche:** every disbursement raises `loan.disbursed` with `trancheNo`, and each tranche gets its own payout. `V21` makes the one-live-payout rule per disbursement. A new attempt after a failed payout keeps the disbursement it pays.
- **Disbursement reversal and tranches:** a reversed disbursement leaves its tranche row as history (`reversed_by`, `V21`) and restores the undrawn amount, so the loan can be disbursed again. A loan with more than one tranche drawn is not reversed as a whole; the failed payout is parked for operations.
- **Module entitlement:** `/api/v1/deferred-receipts` and `/api/v1/loan-product-templates` need the LENDING module.
- **Docker image:** the build context now includes `docs/api`, which the developer portal is packaged from.
- **Loan creation:** a value of the wrong type in the body is answered with 422, not 500.

### Checks
- `tools/api/check-handlers.py`: 199 handlers, no duplicate method and path, every OpenAPI operation (193) has a handler and the reverse. The `/developer` static files are not in the contract, on purpose.
- Every constant SQL statement in the application (491) was prepared against databases migrated to `V21` and control `V3`: none fails. Two provider-callback lookups compared an untyped parameter with NULL, which PostgreSQL refuses when the value is null; they now cast it (`?::text`). 48 statements built at run time were not covered.
- No module depends on `integration`; the module graph has no cycle.
- SQL: 12 suites pass, among them `lending_integration_alignment_test.sql` (13 checks) for `V21`.

### Left open
- **A later tranche whose payout fails** cannot be taken back in the books on its own: the engine has no reversal of a single tranche. The payout is parked; operations retry it.
- **Gateway and NACH receipts during end of day** wait in the integration queue and are posted when the day opens, valued on the payment date within the back-value limit. They do not go through deferred receipts, which value on the next business date. One rule should be chosen.
- **Two Keycloak admin configurations:** `corebanking.integration.keycloak-admin.*` (API clients) and `corebanking.keycloak.admin.*` (sessions).
- **Integration endpoints** (payouts, mandates, NACH, collections, webhooks) are not tied to a licensable module.
- **Provider callbacks** (`/hooks/v1/**`) are not counted as API calls in usage metering.
- **Custom fields** cannot be searched by an LOS; `externalRef` is the lookup key.
- **Support access** is closed to every P2-2 and P2-6 endpoint added in this phase (allow-list in `SupportAccess`).
- **Console:** API types and the permission list must be brought up to date.

## Providers: documents on S3, e-mail over SMTP (10-Oct-2026)
- **Documents:** `corebanking.documents.store=s3` selects `S3DocumentStore` (AWS SDK for Java v2): one bucket per environment, keys under `tenants/<code>/…`, every object SSE-KMS with the tenant's own key (`control.tenant.kms_key_arn`, required by the tenant IAM policy), else the configured default key; content type set, SHA-256 checksum on upload; callers keep their own SHA-256 as before. `directory` remains the default for dev and standalone installs. Local stack: MinIO with its built-in KMS key.
- **E-mail:** Amazon SES over SMTP (`spring-boot-starter-mail`, `spring.mail.*`), so any SMTP relay can replace it; Mailpit locally. EOD failure alerts go to the schedule's recipients; scheduled report files only to addresses on the tenant property `mail.internal-domains` (a report can hold personal data; a credit-bureau file is never e-mailed); message templates can use the `SMTP` e-mail provider (text only, the customer's own loan). No customer statement e-mail exists, so nothing with personal data is attached to a customer. Logs carry counts, never addresses.
- **Infra (unapplied):** Terraform module `ses` (domain identity with DKIM, configuration set with bounce/complaint suppression, a sending-only IAM user; SMTP credentials created out of band), wired in `platform` when `mail_domain` is set; Helm values for the S3 store and SMTP relay. The S3 bucket module already existed.
