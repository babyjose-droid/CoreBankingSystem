# Local development stack

> **LOCAL DEVELOPMENT ONLY.** All passwords, the Keycloak admin account, the demo users and the
> `corebanking-service` client secret (`change-me`) are throwaway values. Never reuse them in any
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

```bash
./init-env.sh                       # once: writes random local-only encryption keys to .env (git-ignored)
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

Tokens carry `tenant: "demo-nbfc"`, `permissions: [...]` (client roles of `api`) and `aud: api`.
Get a token for API testing (browser flow via the console, or the service account):

```bash
curl -s -d grant_type=client_credentials -d client_id=corebanking-service -d client_secret=change-me \
  http://localhost:8081/realms/demo-nbfc/protocol/openid-connect/token | jq -r .access_token
```

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
