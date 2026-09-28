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
