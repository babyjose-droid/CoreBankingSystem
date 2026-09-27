# Contributing

## Definition of done (every PR)

1. CI green: golden tests, unit tests, modularity check, SQL invariant suite, secrets scan.
2. Money is `BigDecimal` / `platform.money`; no `double` for amounts or rates (ADR-011).
3. Anything that moves money goes through `PostingService` (ADR-005). No module writes `ledger.*` directly.
4. Financial and master-data changes go through maker-checker (ADR-012).
5. No real customer data anywhere in the repo, tests or fixtures. Test data is prefixed `CLAUDE-TEST`.
6. New or changed behaviour has tests; changed golden values carry a reason and reviewer sign-off.
7. Migrations are forward-only; never edit a released migration.
8. ADR added or amended when a decision changes; FSD section updated when behaviour changes.
9. Reviewed and approved by the named human reviewer (ADR-010).

## Branches and commits

`main` is protected. Work on `feature/<story-id>-<slug>` (story IDs from the product backlog, e.g. `US-012`).
