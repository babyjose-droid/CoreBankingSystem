# CoreBanking

Core banking (CASA, term deposits, GL) and loan management for NBFCs and banks — multi-tenant SaaS or standalone install.
Original, clean-room implementation: no vendor code, schema or artefacts are used.

## Layout

| Path | What |
|------|------|
| `backend/calc` | Pure Java calculation library (EMI, schedules, day count, fees + GST, APR/IRR, deposits, foreclosure). Golden-tested. |
| `backend/ledger-core` | Balanced posting lots with automatic inter-branch legs, reversal lots, number series with Luhn check digit. |
| `backend/kernel` | Pure platform rules: maker-checker policy, EOD engine, PII encryption and masking, calendar, tax rates, dedupe. |
| `backend/lending-core` | Pure lending engine: schedules, appropriation, DPD/NPA, accrual, penal charges, fees + GST, provisioning, loan account lifecycle and postings. |
| `backend/app` | Spring Boot 4 modular monolith: tenancy routing, posting engine, Flyway migrations. |
| `backend/app/src/main/resources/db/migration` | `control/` (control plane) and `tenant/` (per-tenant DB) migrations. |
| `backend/app/src/test/resources/db` | SQL rule tests (ledger invariants and Phase 1 rules). |
| `docs/adr` | Architecture decisions 001–014. |
| `docs/golden-values.md`, `docs/open-items.md` | Verified numbers and open questions. |
| `frontend/console` | React staff console, with a mock mode for demos. |
| `deploy/local` | Docker Compose: PostgreSQL, Keycloak (realm per tenant), backend, console. |
| `infra/helm`, `infra/terraform` | Helm chart, and AWS modules for Mumbai with DR in Hyderabad. |
| `docs/api/openapi.yaml` | API contract. The console's types are generated from it. |
| `tools/db` | Applies migrations in version order and runs the SQL rule tests on fresh databases. |

## Build

```bash
gradle test                          # calc + ledger-core + app (needs Docker for Testcontainers)
gradle :backend:calc:test            # golden tests only, no Docker
```

Run the SQL rule tests against any PostgreSQL 16 (each file gets a fresh database):

```bash
tools/db/run-sql-tests.sh -h localhost -U postgres
```

## Status (28-Sep-2026)

Phase 1: 19 of 29 stories done, 10 partial (`docs/phase1-status.md`). Phase 2 increment P2-1 (lending core) is done; see `docs/phase2-status.md`.

| Part | Tests |
|------|-------|
| `backend/calc` | 19 golden tests |
| `backend/ledger-core` | 8 tests |
| `backend/kernel` (maker-checker, EOD engine, PII crypto, calendar, tax, dedupe) | 16 tests |
| `backend/lending-core` (Phase 2 engine, incl. whole-life GL scenarios) | 25 tests |
| Database rules (`tools/db/run-sql-tests.sh`) | 79 checks |
| `frontend/console` (React) | 82 tests |
| `backend/app` (Spring) | compiled and tested in CI: modularity, Testcontainers, unit tests |

Quick start: `deploy/local/README.md`. See `CONTRIBUTING.md` for the definition of done.
