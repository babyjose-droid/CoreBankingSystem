#!/usr/bin/env bash
# Creates deploy/local/.env with random LOCAL-ONLY keys for encrypting demo personal data. Safe to re-run:
# existing keys are kept (changing them would make existing demo customers unreadable).
set -euo pipefail
cd "$(dirname "$0")"
touch .env
chmod 600 .env
gen() { head -c 32 /dev/urandom | base64 | tr -d '\n'; }
grep -q '^DEMO_PII_KEY=' .env || echo "DEMO_PII_KEY=$(gen)" >> .env
grep -q '^DEMO_PII_INDEX_KEY=' .env || echo "DEMO_PII_INDEX_KEY=$(gen)" >> .env
echo "deploy/local/.env ready (local development only)"
