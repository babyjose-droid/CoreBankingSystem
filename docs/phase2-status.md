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
- **P2-2:** payout gateway, NACH presentation and responses, collection webhooks, SMS and email, signed webhooks, OAuth clients, LOS integration.
  - These need decision D-09 and sandbox credentials from the partners.
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
