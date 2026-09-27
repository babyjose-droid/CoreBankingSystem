# CoreBanking

Core banking (CASA, term deposits, GL) and loan management for NBFCs and banks — multi-tenant SaaS or standalone install.
Original, clean-room implementation: no vendor code, schema or artefacts are used.

## Layout

| Path | What |
|------|------|
| `backend/calc` | Pure Java calculation library (EMI, schedules, day count, fees + GST, APR/IRR, deposits, foreclosure). Golden-tested. |
| `backend/ledger-core` | Balanced posting lots with automatic inter-branch legs, reversal lots, number series with Luhn check digit. |
| `backend/app` | Spring Boot 4 modular monolith: tenancy routing, posting engine, Flyway migrations. |
| `backend/app/src/main/resources/db/migration` | `control/` (control plane) and `tenant/` (per-tenant DB) migrations. |
| `backend/app/src/test/resources/db` | SQL invariant suite: 19 checks the database itself must enforce. |
| `docs/adr` | Architecture decisions 001–013. |
| `docs/golden-values.md`, `docs/open-items.md` | Verified numbers and open questions. |
| `frontend/`, `infra/`, `tools/migration` | Placeholders for Phase 1 weeks 3–6. |

## Build

```bash
gradle test                          # calc + ledger-core + app (needs Docker for Testcontainers)
gradle :backend:calc:test            # golden tests only, no Docker
```

Run the SQL invariant suite against any PostgreSQL 16:

```bash
createdb tenant_dev
for f in backend/app/src/main/resources/db/migration/tenant/V*.sql; do psql -d tenant_dev -v ON_ERROR_STOP=1 -f "$f"; done
psql -d tenant_dev -v ON_ERROR_STOP=1 -f backend/app/src/test/resources/db/ledger_invariants_test.sql
```

## Status (27-Sep-2026)

Phase 1 foundation, increment 1: calc library (19 tests), ledger-core (7 tests), 7 migrations, 19 DB invariant tests — all passing.
See `CONTRIBUTING.md` for the definition of done.
