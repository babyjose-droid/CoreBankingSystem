# CoreBanking Console

Staff web console for CoreBanking, built with React 19, TypeScript, Vite, React Router and TanStack Query.

## Run it

```bash
npm ci
cp .env.example .env.local      # VITE_MOCK=1 by default
npm run dev                     # http://localhost:5173
```

### Mock mode

With `VITE_MOCK=1` the console runs against an in-memory API that follows `docs/api/openapi.yaml`. It enforces the real rules:

- Maker-checker approvals, including the rule that a maker cannot approve their own request.
- Vouchers must balance before they can be posted.
- Duplicate checks on customers: PAN, mobile, and name plus date of birth.
- End-of-day runs with exceptions and a business-date roll.

You pick a demo user at login:

| User | Can |
|------|-----|
| maker | view everything; propose masters, customers, vouchers, GL heads |
| checker | view everything; approve or reject |
| ops | run and view end-of-day; view the ledger |
| auditor | view everything, including the audit trail |
| admin | everything |

### Against the real backend

Set `VITE_MOCK=0`. Start the backend on :8080 and Keycloak on :8081; `deploy/local/docker-compose.yml` starts both. `npm run dev` proxies `/api` to `http://localhost:8080`, and login uses OIDC Authorization Code + PKCE against `VITE_OIDC_AUTHORITY`.

| Variable | Meaning |
|----------|---------|
| `VITE_MOCK` | `1` = mock API and demo login |
| `VITE_API_BASE_URL` | API origin; leave empty to use same-origin `/api` |
| `VITE_OIDC_AUTHORITY` | Keycloak realm URL, for example `http://localhost:8081/realms/demo-nbfc` |
| `VITE_OIDC_CLIENT_ID` | public client id (`console`) |

## Scripts

| Script | Does |
|--------|------|
| `npm run gen:api` | regenerates `src/api/schema.d.ts` from the OpenAPI spec; commit the result |
| `npm run typecheck` | runs `tsc --noEmit` |
| `npm test` | runs Vitest: unit tests, mock-API rules and component tests |
| `npm run build` | typechecks, then runs the production build into `dist/` |

## Permissions

The access token's `permissions` claim carries client roles of the Keycloak `api` client. `src/auth/permissions.ts` lists the codes, and they must match `deploy/local/keycloak/demo-nbfc-realm.json`.

The console hides navigation items and buttons the user lacks. A direct URL to a page the user can't open shows a 403 page. The API enforces every permission again on the server, so hiding something in the UI is a convenience, not a security control.

## Structure

```
src/api       typed client (openapi-fetch), problem+json errors, query hooks
src/auth      OIDC, demo users, permission guards
src/layout    shell: header with business date, navigation, approvals badge
src/pages     approvals, customers, ledger, eod, masters, audit, home
src/mock      in-memory API used in mock mode and in tests
src/ui        small in-house component set
src/lib       money (en-IN), masking, dates, CSV, Luhn
src/test      component and flow tests
```
