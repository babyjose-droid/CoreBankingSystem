#!/usr/bin/env bash
# Apply Flyway-style migrations with plain psql, in version order (V1, V2 … V10 — not shell glob order).
# Usage: tools/db/apply-migrations.sh <control|tenant> <psql connection args...>
# Example: tools/db/apply-migrations.sh tenant -h localhost -U ar -d tenant_dev
set -euo pipefail
kind="$1"; shift
dir="$(cd "$(dirname "$0")/../.." && pwd)/backend/app/src/main/resources/db/migration/$kind"
for f in $(ls "$dir"/V*__*.sql | sort -t V -k2 -V); do
  echo "applying $(basename "$f")"
  psql -v ON_ERROR_STOP=1 -q "$@" -f "$f"
done
