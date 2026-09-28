#!/usr/bin/env bash
# Runs once, on the first start of an empty postgres volume (docker-entrypoint-initdb.d).
# Creates databases and owners only. Schemas and tables are created by the app itself
# (TenantMigrator runs Flyway for control and for each ACTIVE tenant) — do not add DDL here.
# LOCAL DEVELOPMENT ONLY: passwords come from docker-compose defaults.
set -euo pipefail

: "${CONTROL_DB_PASSWORD:?CONTROL_DB_PASSWORD must be set}"
: "${TENANT_DEMO_NBFC_DB_PASSWORD:?TENANT_DEMO_NBFC_DB_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v control_pw="$CONTROL_DB_PASSWORD" \
  -v tenant_pw="$TENANT_DEMO_NBFC_DB_PASSWORD" <<'SQL'
-- Control plane: tenants, editions, entitlements. No customer data (ADR-003).
CREATE ROLE control LOGIN PASSWORD :'control_pw';
CREATE DATABASE control OWNER control;
REVOKE ALL ON DATABASE control FROM PUBLIC;

-- One database per tenant (ADR-003). The owner needs CREATE on the database so Flyway
-- can create the platform/ledger/customer/lending/audit schemas.
CREATE ROLE tenant_demo_nbfc LOGIN PASSWORD :'tenant_pw';
CREATE DATABASE tenant_demo_nbfc OWNER tenant_demo_nbfc;
REVOKE ALL ON DATABASE tenant_demo_nbfc FROM PUBLIC;
SQL
