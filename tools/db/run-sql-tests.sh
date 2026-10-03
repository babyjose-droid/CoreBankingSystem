#!/usr/bin/env bash
# Run every SQL test file on its own fresh database: db/*_test.sql on a tenant database, db/control/*_test.sql on a
# control-plane database. Usage: tools/db/run-sql-tests.sh <psql connection args...>
# Example: tools/db/run-sql-tests.sh -h localhost -U ar
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
i=0; failed=0
for t in "$root"/backend/app/src/test/resources/db/*_test.sql "$root"/backend/app/src/test/resources/db/control/*_test.sql; do
  i=$((i+1)); db="sqltest_$$_$i"
  kind=tenant; [ "$(basename "$(dirname "$t")")" = control ] && kind=control
  psql -v ON_ERROR_STOP=1 -q "$@" -d postgres -c "create database $db" >/dev/null
  "$root/tools/db/apply-migrations.sh" "$kind" "$@" -d "$db" >/dev/null
  if out=$(psql -v ON_ERROR_STOP=1 -q "$@" -d "$db" -f "$t" 2>&1) && grep -q "TESTS PASSED" <<<"$out"; then
    echo "PASS $(basename "$t") ($(grep -c 'PASS ' <<<"$out") checks)"
  else
    echo "FAIL $(basename "$t")"; echo "$out" | grep -E "FAIL|ERROR" | head -5; failed=1
  fi
  psql -q "$@" -d postgres -c "drop database $db" >/dev/null
done
exit $failed
