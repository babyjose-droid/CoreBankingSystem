# Runbook: local development

Audience: developers. Full reference: [`deploy/local/README.md`](../../deploy/local/README.md).

## Prerequisites

- Docker Engine 24+ with Compose v2 (4 GB RAM for Docker is enough).
- JDK 21 and Gradle 8.14 only if you run the backend outside Docker (`gradle :backend:app:bootRun`).
- Node 22 only if you run the console outside Docker.

## Daily loop

```bash
cd deploy/local
docker compose up -d --build        # rebuilds the backend image from backend/app/Dockerfile
docker compose logs -f backend
```

On first start the backend provisions the demo tenant automatically (`COREBANKING_BOOTSTRAP_DEMO_TENANT=true`); see the README.

Run the backend from the IDE instead of Docker: `docker compose up -d postgres keycloak`, then start
`CoreApplication` with the same environment variables as the `backend` service in
`docker-compose.yml`. Use `jdbc:postgresql://localhost:5432/...` for both URLs; the issuer
stays `http://localhost:8081/realms/demo-nbfc` and the JWK-set URI becomes
`http://localhost:8081/realms/demo-nbfc/protocol/openid-connect/certs`.

## Logging in

Users `maker`, `checker`, `ops`, `auditor`, `admin` in realm `demo-nbfc`, temporary password
`ChangeMe#2026`; first login forces a password change and TOTP enrolment. Local only.

### Development sign-in (local only)

The login page of the Docker console (http://localhost:5173) also has a **Development sign-in (local only)**
form: username and password, no redirect to Keycloak, no TOTP. Use it when the SSO page does not work, for
example in an embedded test browser.

- Users: `dev-maker`, `dev-checker`, `dev-ops`, `dev-auditor`, `dev-admin`; password `LocalDev#2026`.
- Local only: it needs `VITE_DEV_LOGIN=1` on the Vite dev server and the realm's `console-dev` client. Neither
  exists outside this stack; tenant realms never contain that client (ADR-007 amendment, checked in CI).
- An existing stack must be reset once so the new realm and staff profiles load. This deletes all local data:

  ```bash
  cd deploy/local
  docker compose --profile console down -v
  docker compose --profile console up -d --build
  ```

- Running the console on the host instead: put `VITE_MOCK=0` and `VITE_DEV_LOGIN=1` in
  `frontend/console/.env.local`; the `/realms` proxy then targets `http://localhost:8081` by default.
- Errors come from Keycloak: "Invalid user credentials" (wrong user or password; five failures lock the user for
  15 minutes), "Account is not fully set up" (the user has a pending action, e.g. the non-dev users or an expired
  password). "Token issuer mismatch" means the proxy or `KC_HOSTNAME` is misconfigured.

## Resetting

| Want to reset | Command |
|---------------|---------|
| Databases (all data) | `docker compose down -v && docker compose up -d` then re-seed |
| Only the tenant DB | `docker compose exec postgres psql -U postgres -c 'DROP DATABASE tenant_demo_nbfc WITH (FORCE)'` then re-create it with owner `tenant_demo_nbfc` and restart the backend |
| Keycloak realm | `docker compose rm -sf keycloak && docker compose up -d keycloak` |

## Rules

- Synthetic data only, prefixed `CLAUDE-TEST`. Never load production extracts locally (DPDP Act:
  personal data must not leave controlled environments).
- Do not commit `.env` files (already git-ignored).
- The SQL invariant suite and migrations can be run directly against the local Postgres — see the root README.
