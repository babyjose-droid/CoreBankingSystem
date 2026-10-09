# Local development stack

> **LOCAL DEVELOPMENT ONLY.** All passwords, the Keycloak admin account, the demo users, the
> `corebanking-service` client secret (`change-me`) and the `corebanking-admin` client are throwaway values. Never reuse them in any
> shared, UAT or production environment. Use synthetic `CLAUDE-TEST` data only (CONTRIBUTING.md #5).

| Service | URL | Notes |
|---------|-----|-------|
| PostgreSQL 16 | `localhost:5432` | databases `control`, `tenant_demo_nbfc`; superuser `postgres` / `postgres-local-only` |
| Keycloak 26 (dev mode) | http://localhost:8081 | admin console: `admin` / `admin-local-only`; realm `demo-nbfc` |
| Backend | http://localhost:8080 | health: `/actuator/health` |
| Staff console (optional) | http://localhost:5173 | profile `console`; real backend + Keycloak (`VITE_MOCK=0`), API via the Vite `/api` proxy |

All ports bind to 127.0.0.1 only. The console container shares the backend's network namespace (so the
Vite proxy's `localhost:8080` is the backend); restart it after restarting the backend. For UI work without
any backend, run the console on the host with `VITE_MOCK=1` (see `frontend/console/.env.example`).

## Start / stop

```bash
cd deploy/local
docker compose up -d --build                # postgres + keycloak + backend
docker compose --profile console up -d      # also start the React console (needs frontend/console sources)
docker compose logs -f backend
docker compose down                         # stop, keep data
docker compose down -v                      # stop and wipe the database volume (re-runs the init script)
```

Override any default password with environment variables or a git-ignored `.env` file next to
`docker-compose.yml`: `POSTGRES_PASSWORD`, `CONTROL_DB_PASSWORD`, `TENANT_DEMO_NBFC_DB_PASSWORD`,
`KEYCLOAK_ADMIN_PASSWORD`.

## Demo tenant (first run)

The Postgres init script only creates the databases and their owners. The backend does the rest on start:
it migrates the control plane and, because `COREBANKING_BOOTSTRAP_DEMO_TENANT=true` in the compose file,
provisions the tenant `demo-nbfc` once through the same code path a platform operator uses
(`POST /platform/v1/tenants`). It:

- migrates `tenant_demo_nbfc`;
- loads the NBFC chart of accounts and the GST/TDS rates;
- creates the HO (Kochi) and MUM (Mumbai) branches, with the business date 30-Jun-2026;
- enables the GROWTH modules;
- adds staff profiles for the demo users.

On every start (also for a demo tenant created by an earlier build) it then adds whatever is missing of the
simulator set-up, because the compose file enables the `SIMULATOR` provider
(`COREBANKING_INTEGRATION_PROVIDERS`): the simulator as the ACTIVE provider for PAYOUT, COLLECTION, MANDATE, SMS
and EMAIL (with a random `webhookSecret`, stored encrypted, so `POST /api/v1/integrations/simulator/callbacks`
works), the tenant properties `nach.sponsor-bank-code` and `nach.utility-code`, and English SMS templates for
`LOAN_DISBURSED` and `PAYMENT_RECEIVED`. It also records one rate for the `REPO` benchmark (6.00% from
01-Jan-2026 — demo data, not the Reserve Bank's rate) while that benchmark has none, so the floating-rate product
template can book a loan. Nothing that is already configured is changed; these rows are written
without maker-checker, which is why this exists only behind the local bootstrap flag.

```bash
./init-env.sh                       # once: writes random local-only keys and the Keycloak admin client secret to .env (git-ignored)
docker compose up -d --build
docker compose logs -f backend      # look for "tenant demo-nbfc registered"
```

The backend finds the tenant database through `COREBANKING_TENANT_DEMO_NBFC_URL/_USER/_PASSWORD`, using the
tenant code upper-cased with `-` replaced by `_`; see `TenancyConfig.envCredentials`. Personal data is
encrypted with local-only keys that `init-env.sh` generates into `.env`. If you delete `.env`,
the existing demo customers can no longer be decrypted, so recreate the database volume as well.

## Demo users (realm `demo-nbfc`)

Every user starts with password `ChangeMe#2026` (temporary) and must set a new password and enrol
TOTP (Google Authenticator, FreeOTP, ...) on first login — MFA is mandatory for staff (ADR-007).

| User | Realm role | What it can do |
|------|-----------|----------------|
| `maker` | MAKER | view masters, propose branch/holiday/tax/GL-head changes, create customers and vouchers, reverse vouchers, GL reports |
| `checker` | CHECKER | view, approve/reject proposals, GL reports |
| `ops` | OPERATIONS | view/run end-of-day, GL view and reports |
| `auditor` | AUDITOR | read-only including the audit trail |
| `admin` | TENANT_ADMIN | every permission — do not use it for maker-checker testing (ADR-012) |

## Development sign-in (local only)

The console's login page on this stack also shows a **Development sign-in (local only)** form above the SSO
button. It signs in with a username and password directly, without the redirect to Keycloak, which helps in
embedded test browsers that block the SSO page. Nothing to enrol: no password change, no TOTP.

| User | Realm role | Password |
|------|-----------|----------|
| `dev-maker` | MAKER | `LocalDev#2026` |
| `dev-checker` | CHECKER | `LocalDev#2026` |
| `dev-ops` | OPERATIONS | `LocalDev#2026` |
| `dev-auditor` | AUDITOR | `LocalDev#2026` |
| `dev-admin` | TENANT_ADMIN | `LocalDev#2026` |

How it works and why it cannot reach a real tenant (ADR-007 amendment):

- The form appears only when the console runs on the Vite dev server with `VITE_DEV_LOGIN=1` and `VITE_MOCK=0`
  (the `console` service sets both). A production build compiles the flag to false.
- It uses the realm's `console-dev` client (password grant). That client and the `dev-*` users exist only in
  `keycloak/demo-nbfc-realm.json`; `infra/keycloak/new-tenant-realm.py` removes them from every tenant realm and
  CI fails if a rendered realm has a password-grant client.
- The browser posts to the same-origin `/realms/...` path; the dev server forwards it to
  `VITE_KEYCLOAK_PROXY_TARGET` (`http://keycloak:8081` here) with `Host: localhost:8081`, so the token's issuer
  stays `http://localhost:8081/realms/demo-nbfc`. The console refuses a token with any other issuer and says so.
- The `maker` / `checker` / ... users above still sign in through SSO with password change and TOTP.

The realm is imported and the staff profiles are created only on first start, so an existing stack must be
reset once to get the `dev-*` users (**this deletes all local data**):

```bash
cd deploy/local
docker compose --profile console down -v
docker compose --profile console up -d --build
```

The password expires after 90 days like any other (realm policy); the sign-in then reports "Account is not fully
set up" — reset the stack as above.

## Tokens

Tokens carry `tenant: "demo-nbfc"`, `permissions: [...]` (client roles of `api`) and `aud: api`.
Get a token for API testing (browser flow via the console, or the service account):

```bash
curl -s -d grant_type=client_credentials -d client_id=corebanking-service -d client_secret=change-me \
  http://localhost:8081/realms/demo-nbfc/protocol/openid-connect/token | jq -r .access_token
```

## Keycloak admin client (sessions, API clients)

The realm carries a **local-only** confidential client `corebanking-admin` whose service account holds four
`realm-management` roles and nothing else: `view-users` and `manage-users` (the Sessions page: find a user,
list and end sessions; and the role mappings of an API client's service account), `view-clients` and
`manage-clients` (API clients: create, enable or disable, new secret, scope mappings). No user can sign in
through it and it has no permission on the CoreBanking API.

Its secret is not in the repository: `./init-env.sh` writes a random `LOCAL_KEYCLOAK_ADMIN_CLIENT_SECRET` to
`.env`; Keycloak substitutes it for the placeholder in the realm file when it imports the realm, and the compose
file hands the same value to the backend (`COREBANKING_KEYCLOAK_ADMIN_CLIENT_SECRET`, with
`COREBANKING_KEYCLOAK_ADMIN_ENABLED=true` and `COREBANKING_KEYCLOAK_ADMIN_REALM=demo-nbfc`). With that,
`GET /api/v1/sessions` no longer answers 503 and approving an API client no longer answers 409.

- **A stack started before this client existed** has neither the secret nor the client: run `./init-env.sh`
  again (it only adds what is missing), then re-create Keycloak as below and restart the backend.
- If you delete `.env`, re-create Keycloak too: the realm keeps the secret it was imported with.
- `infra/keycloak/new-tenant-realm.py` always removes this client and its service account, and refuses to render
  a realm that still holds a placeholder or a `realm-management` role; CI checks both. A tenant's own
  session-admin client is created at onboarding with `view-users` and `manage-users` only (OI-08).
- These calls have not been run against a live Keycloak by the author of this set-up; if the Sessions page
  answers 502, the backend log names the status Keycloak returned.

The realm is imported only when it does not exist yet. After editing `keycloak/demo-nbfc-realm.json`,
re-create Keycloak: `docker compose rm -sf keycloak && docker compose up -d keycloak`.
To render a realm for another tenant use `infra/keycloak/new-tenant-realm.py` (see
`docs/runbooks/tenant-onboarding.md`).

## Troubleshooting

- **Backend exits with `Required key 'COREBANKING_TENANT_..._URL' not found`** — a tenant is
  ACTIVE in `control.tenant` but has no credentials in the environment. Add the three variables.
- **401 with a valid-looking token** — issuer mismatch. Tokens must be obtained via
  `http://localhost:8081` (the backend expects `iss=http://localhost:8081/realms/demo-nbfc`).
- **Init script did not run** — it runs only on an empty volume: `docker compose down -v`.
