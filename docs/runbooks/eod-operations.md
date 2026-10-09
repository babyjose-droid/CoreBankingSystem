# Runbook: end-of-day (EOD) operations

> **Skeleton.** The EOD engine is not built yet (Phase 1 later increments). This runbook fixes the
> operational contract so the implementation, dashboards and alerts are built to match it. Replace
> every `TBD` when the feature ships.

Audience: tenant operations users (role OPERATIONS) and the platform on-call.

## What EOD does (per tenant, per business date)

1. Cut-off: stop accepting postings for business date *D* (new postings go to *D+1*).
2. Accruals: interest accrual on loans (and deposits in Phase 3), fee/penal charges, GST.
3. Dues: instalment demand generation, DPD / NPA ageing (IRACP norms), provisioning.
4. GL: day-end balances, trial balance check (sum of debits = sum of credits; any imbalance halts EOD).
5. Reports and outbound files; business date advances to *D+1*.

Every step is idempotent and restartable from the failed step (ADR-005: postings are append-only;
corrections are reversals, never updates).

## Permissions

| Action | Permission | Default role |
|--------|------------|--------------|
| See EOD status/history | `eod:view` | OPERATIONS, MAKER, CHECKER, AUDITOR |
| Start / resume EOD | `eod:run` | OPERATIONS |
| Change the schedule | `eod:schedule` | TENANT_ADMIN |

## Monitoring

- Console → EOD: current business date, step, progress, last error. (TBD screen)
- Metrics (`/actuator/prometheus`, labelled by tenant): `eod_run_duration_seconds`, `eod_step_failures_total`,
  `eod_business_date_lag_days`. (TBD names)
- Alerts (TBD thresholds):
  - EOD not started 30 min after the scheduled time.
  - Any step failed.
  - Business date lag > 0 at 06:00 IST (branches would open on the wrong date).
  - Trial balance mismatch — **Sev-1**, page immediately.

## Normal run

1. Scheduled time (default 23:30 IST, per tenant) or manual start by OPERATIONS.
2. Watch progress; typical duration TBD.
3. On completion verify: business date advanced, trial balance report generated, no exceptions queue items.

### Pending approvals

The pre-check step records a **warning** on the run (never a failure) listing the dated approvals still pending:
vouchers, disbursements and their reversals, waivers, loan transaction reversals, amendments, restructures,
sanction changes and NPA overrides. They stay pending and, once approved, apply on the business date of their
approval, not the day they were proposed (ADR-015). The console shows the same list before a run is started
(`GET /api/v1/eod/pending-approvals`) and on the run page. A tenant that prefers to clear them first sets the system
property `eod.block-on-pending-approvals` to `true`: the start (manual or scheduled) is then refused with 409 naming
the count until they are approved or rejected.

### Loans: non-working days

The loan day-end of business date *D* also closes every calendar day up to the next business date (Saturday's
run closes Sunday; the run before a holiday closes the holiday), each with its own accrual, penal charge, demands and
DPD history, posted in *D*'s books with the day as value date. See docs/lending-day-end.md.

## Restart after a failure

1. Read the failed step and error in the console / logs (`[tenant]` in the log correlation prefix).
2. Classify:
   - **Infrastructure** (DB failover, pod restart, timeout): resume from the failed step (`eod:run` → Resume).
   - **Data exception** (e.g. an account with invalid state): the engine parks the item in the
     exceptions queue and continues when the step allows it; otherwise fix via maker-checker and resume.
   - **Trial balance mismatch**: do **not** resume. Escalate to platform on-call + tenant finance.
     Investigate with the SQL invariant suite (`backend/app/src/test/resources/db/ledger_invariants_test.sql`,
     read-only checks) against the tenant DB.
3. Never edit ledger tables by hand. Corrections are reversal lots posted through `PostingService`.
4. Record the incident and actions in the ticket; operator actions on tenant data go to
   `control.operator_action` with a reason.

## Pod / deployment interaction

- Do not deploy or scale down the backend while an EOD is running (check the console first). The
  Helm chart's PodDisruptionBudget protects against voluntary node drains, not against rollouts.
- If the pod running EOD dies, another pod resumes after the lease expires (TBD lease mechanism).

## Exception handling checklist

- [ ] Exceptions queue reviewed and each item assigned.
- [ ] Customer-impacting items (wrong dues, wrong DPD) flagged to the tenant's operations head.
- [ ] Anything touching NPA classification or provisioning is reviewed by the tenant's finance/compliance.

## Escalation

| Severity | Example | Who |
|----------|---------|-----|
| Sev-1 | trial balance mismatch; EOD not done by 06:00 IST | platform on-call + engineering lead + tenant ops head |
| Sev-2 | step failed, resumable | platform on-call |
| Sev-3 | single exception item | tenant operations |

CERT-In requires reporting of specified cyber incidents within 6 hours; RBI incident reporting per the
tenant's obligations — the tenant's compliance team decides, platform provides the facts.
