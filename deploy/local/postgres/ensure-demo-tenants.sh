#!/usr/bin/env bash
# Creates the databases and owners of the demo tenants added after the first release of this stack (Phase 4:
# demo-bank and demo-nbfc-d), when they do not exist yet. Run by the `postgres-demo-tenants` service on every
# `docker compose up`, so a stack whose postgres volume was created earlier gets them without a reset; the
# scripts in init/ run only on an empty volume. Databases and owners only: the app creates schemas and tables.
# LOCAL DEVELOPMENT ONLY: passwords come from docker-compose defaults.
set -euo pipefail

: "${TENANT_DEMO_BANK_DB_PASSWORD:?TENANT_DEMO_BANK_DB_PASSWORD must be set}"
: "${TENANT_DEMO_NBFC_D_DB_PASSWORD:?TENANT_DEMO_NBFC_D_DB_PASSWORD must be set}"

ensure() {   # ensure <role and database name> <password>
  psql -v ON_ERROR_STOP=1 --dbname postgres -v name="$1" -v pw="$2" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'name', :'pw')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'name') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'name', :'name')
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'name') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'name') \gexec
SQL
}

ensure tenant_demo_bank "$TENANT_DEMO_BANK_DB_PASSWORD"
ensure tenant_demo_nbfc_d "$TENANT_DEMO_NBFC_D_DB_PASSWORD"
echo "demo tenant databases ready"
