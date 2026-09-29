# Phase 1 (Foundation) – story status

Updated 29-Sep-2026. The source of each story is the product backlog v1.0 (29 Phase 1 stories).
"Done" means built and tested; "Partial" means the core is built and the remaining gap is named.

| Story | What | Status | Where / gap |
|-------|------|--------|-------------|
| US-001 | Provision a tenant in one action | Done | Merging `infra/tenants/<env>/<code>.json` runs the `provision-tenant` workflow: `tools/tenant/provision_tenant.py` does Terraform (key, secret, IAM), the Aurora database, the Keycloak realm (rotated client secret, first admins with forced password change + TOTP), External Secrets + restart, and `POST /platform/v1/tenants`. Every step is idempotent; 21 offline self-tests. |
| US-002 | Editions and modules per tenant | Done | `PUT /platform/v1/tenants/{code}/modules`. The API returns 403 for disabled modules (`TenantFilter`), `/me` lists the modules, and the console hides them. |
| US-005 | Migrate all tenants with a dry run | Done | `POST /platform/v1/migrations?dryRun=true` reports status per tenant, and one failure does not stop the others. The rollback approach (forward-fix) is in the runbooks. |
| US-010 | Legal entity, branches, branch sets | Done | Branches with maker-checker and the close guard; branch sets API (`/api/v1/branch-sets`, maker-checker), used in staff scope. |
| US-011 | Holiday calendar | Done | Holidays, weekly offs (2nd/4th Saturday), `next_working_day`; CSV upload (`/api/v1/holidays/upload`) becomes one approval. |
| US-012 | Territory masters | Done | 37 states/UTs seeded with GST codes; CSV upload of districts, cities and pincodes (all-or-nothing, idempotent `load_territory`); `/api/v1/states`, `/api/v1/pincodes/{pincode}`. |
| US-013 | Enumerations and system properties | Done | Read APIs plus maker-checker edits (`POST /enumerations/{type}`, `PUT /system-properties/{key}`); formats enforced by the database; values are deactivated, never deleted. |
| US-015 | Number series with check digit | Done | Luhn check digit, prefixes that cannot overlap (checked by the database) and gap-safe sequences. |
| US-016 | Business date only through EOD | Done | A database trigger rejects direct changes and backwards moves; the date moves only through `advance_business_date()`. |
| US-017 | Tax rates by effective date | Done | Overlapping periods are rejected by an exclusion constraint. The rate is resolved by value date, and a new rate closes the previous open-ended period. |
| US-018 | Login with password and TOTP | Done | Keycloak realm: TOTP required, lockout after 5 failures, 30-minute idle timeout (ADR-007). |
| US-020 | Roles, permissions and branch scope | Done | `@PreAuthorize` on every endpoint. Branch scope is a data filter (`platform.visible_branches`): customers, loans, vouchers, approvals and GL views; staff profiles and scope via `/api/v1/staff` (maker-checker; makers grant only what they see). |
| US-022 | Maker-checker framework | Done | Rules by entity, action and amount (vouchers of ₹10 lakh or more need two checkers). The maker can never approve their own request, the checker sees current vs proposed, and approval replays the change through the module's applier. |
| US-023 | Approval queue | Done | Filters, SLA ageing, bulk approve (each item in its own transaction) and a pending badge in the console. |
| US-025 | Tamper-evident audit | Done | Hash-chained, immutable `audit.event`, plus `GET /audit/events` and `/audit/verify`. |
| US-026 | Password policy | Done | Keycloak: minimum length 12, complexity rules, history of 5, 90-day expiry. |
| US-028 | Two-step customer creation | Partial (by plan) | Basics, personal, identification and contact are done. Demography, bank accounts and KYC documents (FSD §5) are Phase 2 scope. |
| US-029 | Customer API with idempotency | Done | The `Idempotency-Key` header is honoured, and a create with a PAN already on file returns the existing customer (200). |
| US-030 | Dedupe | Done | Keyed-hash matching on PAN (exact), Aadhaar reference (exact), mobile (strong) and name + date of birth (possible). Overriding a match needs a reason and approval. |
| US-100 | Chart of accounts starter kit | Done | NBFC chart of 51 heads; the BANK kit adds deposits and CRR/SLR. Parent and category rules are enforced. |
| US-101 | Posting engine | Done | Balanced lots, append-only entries and reversal lots, checked twice: in Java and by database triggers. |
| US-102 | Automatic inter-branch entries | Done | Inter-branch (IBR) legs are added by `TransactionLot.Builder`. Branch trial balances net to zero, and EOD checks this. |
| US-103 | Manual vouchers | Done | Contra, receipt, payment and journal vouchers with maker-checker and reversal; CSV upload (`/api/v1/gl/vouchers/upload`), one approval per voucher, all or nothing. |
| US-104 | Trial balance and statements | Done | Trial balance by branch and date, drill-down to entries, P&L, balance sheet and daily GL snapshot. The console downloads each as CSV. |
| US-108 | EOD orchestrator | Done | Step graph, partitions on virtual threads, checkpointed idempotent restart (ADR-014). |
| US-109 | Per-account isolation and exception report | Done | The exception report is built. Alerts are logged with their recipients; email delivery is OI-06. |
| US-110 | Scheduler | Done | Manual or cron (IST), set up through maker-checker, one run at a time. The console has schedule and run screens. |
| US-132 | OWASP ASVS L2 and scanning in CI | Done | ASVS 5.0 L2 code review (`docs/security/asvs-l2-review.md`): 9 defects fixed, 4 tracked (SEC-02…SEC-08, D-13). All actions pinned to commit SHAs, enforced in CI. A penetration test is still to be scheduled before the first production tenant. |
| US-133 | Encryption and masking of personal data | Done | AES-GCM + keyed hashes + masking; logs, audit, errors and exports reviewed (`docs/security/pii-logs-exports-review.md`). Clear display name and date of birth are tracked as SEC-03. |

**Totals:** 28 Done, 1 Partial by plan (US-028: the rest of the customer form is Phase 2 scope).

Phase 1 exit still needs the first AWS sandbox deployment (AWS accounts, OI-04) and a penetration test (US-132).
