# Phase 1 (Foundation) – story status

Updated 28-Sep-2026. The source of each story is the product backlog v1.0 (29 Phase 1 stories).
"Done" means built and tested; "Partial" means the core is built and the remaining gap is named.

| Story | What | Status | Where / gap |
|-------|------|--------|-------------|
| US-001 | Provision a tenant in one action | Partial | `POST /platform/v1/tenants`: database, migrations, starter kit, head office, modules, provisioning log. The KMS key, secret and S3 prefix come from Terraform `modules/tenant` and the realm from `new-tenant-realm.py`; running these as one pipeline is still to do. |
| US-002 | Editions and modules per tenant | Done | `PUT /platform/v1/tenants/{code}/modules`. The API returns 403 for disabled modules (`TenantFilter`), `/me` lists the modules, and the console hides them. |
| US-005 | Migrate all tenants with a dry run | Done | `POST /platform/v1/migrations?dryRun=true` reports status per tenant, and one failure does not stop the others. The rollback approach (forward-fix) is in the runbooks. |
| US-010 | Legal entity, branches, branch sets | Partial | Branches work end to end, with maker-checker and a close guard that blocks closing a branch with live loans. The legal entity is seeded. Branch sets exist as tables but have no API yet. |
| US-011 | Holiday calendar | Partial | Holidays, weekly offs (including 2nd and 4th Saturday), `next_working_day` and the holiday modes are done. File upload is not built. |
| US-012 | Territory masters | Partial | The schema covers country, state (with GST code), district, city and pincode. The upload and lookup API is not built. |
| US-013 | Enumerations and system properties | Partial | Tables and the read API are done. The maker-checker edit API is not built. |
| US-015 | Number series with check digit | Done | Luhn check digit, prefixes that cannot overlap (checked by the database) and gap-safe sequences. |
| US-016 | Business date only through EOD | Done | A database trigger rejects direct changes and backwards moves; the date moves only through `advance_business_date()`. |
| US-017 | Tax rates by effective date | Done | Overlapping periods are rejected by an exclusion constraint. The rate is resolved by value date, and a new rate closes the previous open-ended period. |
| US-018 | Login with password and TOTP | Done | Keycloak realm: TOTP required, lockout after 5 failures, 30-minute idle timeout (ADR-007). |
| US-020 | Roles, permissions and branch scope | Partial | Permissions are enforced on every endpoint (`@PreAuthorize`) and the console hides actions the user lacks. Branch scope has tables but is not yet applied as a data filter. |
| US-022 | Maker-checker framework | Done | Rules by entity, action and amount (vouchers of ₹10 lakh or more need two checkers). The maker can never approve their own request, the checker sees current vs proposed, and approval replays the change through the module's applier. |
| US-023 | Approval queue | Done | Filters, SLA ageing, bulk approve (each item in its own transaction) and a pending badge in the console. |
| US-025 | Tamper-evident audit | Done | Hash-chained, immutable `audit.event`, plus `GET /audit/events` and `/audit/verify`. |
| US-026 | Password policy | Done | Keycloak: minimum length 12, complexity rules, history of 5, 90-day expiry. |
| US-028 | Two-step customer creation | Partial | Basics, personal, identification and contact are done. The rest of FSD §5 (demography, bank accounts, KYC documents) is Phase 2. |
| US-029 | Customer API with idempotency | Done | The `Idempotency-Key` header is honoured, and a create with a PAN already on file returns the existing customer (200). |
| US-030 | Dedupe | Done | Keyed-hash matching on PAN (exact), Aadhaar reference (exact), mobile (strong) and name + date of birth (possible). Overriding a match needs a reason and approval. |
| US-100 | Chart of accounts starter kit | Done | NBFC chart of 51 heads; the BANK kit adds deposits and CRR/SLR. Parent and category rules are enforced. |
| US-101 | Posting engine | Done | Balanced lots, append-only entries and reversal lots, checked twice: in Java and by database triggers. |
| US-102 | Automatic inter-branch entries | Done | Inter-branch (IBR) legs are added by `TransactionLot.Builder`. Branch trial balances net to zero, and EOD checks this. |
| US-103 | Manual vouchers | Partial | Contra, receipt, payment and journal vouchers with multiple lines, maker-checker and reversal. Upload is not built. |
| US-104 | Trial balance and statements | Done | Trial balance by branch and date, drill-down to entries, P&L, balance sheet and daily GL snapshot. The console downloads each as CSV. |
| US-108 | EOD orchestrator | Done | Step graph, partitions on virtual threads, checkpointed idempotent restart (ADR-014). |
| US-109 | Per-account isolation and exception report | Done | The exception report is built. Alerts are logged with their recipients; email delivery is OI-06. |
| US-110 | Scheduler | Done | Manual or cron (IST), set up through maker-checker, one run at a time. The console has schedule and run screens. |
| US-132 | OWASP ASVS L2 and scanning in CI | Partial | CodeQL, dependency review, Dependabot, gitleaks and checkov are in CI (Trivy removed after its March 2026 supply-chain compromise). Third-party actions still need pinning to commit SHAs, and the formal ASVS L2 review is still to do. |
| US-133 | Encryption and masking of personal data | Partial | Customer personal data is encrypted with AES-GCM, searched through keyed hashes and masked in the API and UI; personal data in approval payloads is sealed. A review of logs and exports is still to do. |

**Totals:** 19 Done, 10 Partial, 0 not started.

The partial items are included in the Phase 1 exit plan (weeks 7–8), together with the first sandbox deployment on AWS.
