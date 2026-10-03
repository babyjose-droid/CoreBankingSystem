#!/usr/bin/env bash
# Creates deploy/local/.env with random LOCAL-ONLY secrets: the keys for encrypting demo personal data and the
# secret of the realm's corebanking-admin client. Safe to re-run: existing values are kept (changing the keys would
# make existing demo customers unreadable; the client secret is read by Keycloak only when it imports the realm).
set -euo pipefail
cd "$(dirname "$0")"
touch .env
chmod 600 .env
gen() { head -c 32 /dev/urandom | base64 | tr -d '\n'; }
grep -q '^DEMO_PII_KEY=' .env || echo "DEMO_PII_KEY=$(gen)" >> .env
grep -q '^DEMO_PII_INDEX_KEY=' .env || echo "DEMO_PII_INDEX_KEY=$(gen)" >> .env
# hex: the value is substituted into the realm JSON and sent in a form body, so no characters that need escaping
grep -q '^LOCAL_KEYCLOAK_ADMIN_CLIENT_SECRET=' .env || echo "LOCAL_KEYCLOAK_ADMIN_CLIENT_SECRET=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')" >> .env
echo "deploy/local/.env ready (local development only)"
