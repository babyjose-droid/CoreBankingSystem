-- Control plane (control V3): usage metering per tenant and the index of support access requests.
-- Run on a fresh control database after the control migrations.
\set QUIET on
CREATE FUNCTION pg_temp.expect_fail(sql text, code text, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE sql;
  EXCEPTION WHEN others THEN
    IF SQLSTATE = code THEN RAISE NOTICE 'PASS % [%]', label, SQLERRM; RETURN; END IF;
    RAISE EXCEPTION 'FAIL % : wrong error % %', label, SQLSTATE, SQLERRM;
  END;
  RAISE EXCEPTION 'FAIL % : no error raised', label;
END $$;
CREATE FUNCTION pg_temp.check(ok boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF ok IS NOT TRUE THEN RAISE EXCEPTION 'FAIL %', label; END IF;
  RAISE NOTICE 'PASS %', label;
END $$;

INSERT INTO control.tenant (id, code, legal_name, entity_type, deployment_tier, edition_code, status) VALUES
  ('00000000-0000-0000-0000-00000000a001', 'claude-test-a', 'CLAUDE-TEST Finance A Ltd', 'NBFC', 'POOLED', 'STARTER', 'ACTIVE'),
  ('00000000-0000-0000-0000-00000000a002', 'claude-test-b', 'CLAUDE-TEST Finance B Ltd', 'NBFC', 'POOLED', 'GROWTH', 'ACTIVE');

-- API calls: added through the day by several instances ---------------------------------------------------
SELECT pg_temp.check(control.add_api_calls('claude-test-a', '2026-10-01', 120), 'U1 the first flush of the day creates the row');
SELECT pg_temp.check(control.add_api_calls('claude-test-a', '2026-10-01', 30), 'U2 a second instance adds to it');
SELECT pg_temp.check((SELECT api_calls = 150 AND captured_at IS NULL AND active_loans IS NULL FROM control.usage_daily
                       WHERE tenant_id = '00000000-0000-0000-0000-00000000a001' AND day = '2026-10-01'), 'U3 calls add up; no snapshot figures yet');
SELECT pg_temp.check(NOT control.add_api_calls('no-such-tenant', '2026-10-01', 5), 'U4 an unknown tenant is ignored, not an error');
SELECT pg_temp.check(NOT control.add_api_calls('claude-test-a', '2026-10-01', 0) AND NOT control.add_api_calls('claude-test-a', '2026-10-01', -3)
                     AND (SELECT api_calls FROM control.usage_daily WHERE day = '2026-10-01') = 150, 'U5 zero or negative counts change nothing');

-- snapshot figures ----------------------------------------------------------------------------------------------
SELECT control.record_usage('claude-test-a', '2026-10-01', 100, 80, 5, 1000, 9000);
SELECT pg_temp.check((SELECT active_loans = 100 AND active_customers = 80 AND staff_users = 5 AND storage_bytes = 10000 AND api_calls = 150
                             AND captured_at IS NOT NULL
                        FROM control.usage_daily WHERE day = '2026-10-01'), 'U6 the snapshot keeps the API calls and adds the counts; storage = documents + database');
SELECT control.record_usage('claude-test-a', '2026-10-01', 101, 80, 5, 1000, 9500);
SELECT pg_temp.check((SELECT count(*) = 1 AND max(active_loans) = 101 AND max(storage_bytes) = 10500 AND max(api_calls) = 150 FROM control.usage_daily
                       WHERE day = '2026-10-01'), 'U7 a second snapshot of the same day replaces the first');
SELECT pg_temp.expect_fail($q$ SELECT control.record_usage('no-such-tenant', '2026-10-01', 1, 1, 1, 1, 1) $q$, 'P0002', 'U8 a snapshot for an unknown tenant is an error');
SELECT pg_temp.expect_fail($q$ SELECT control.record_usage('claude-test-a', '2026-10-02', -1, 1, 1, 1, 1) $q$, '23514', 'U9 counts cannot be negative');
SELECT pg_temp.expect_fail($q$ DELETE FROM control.usage_daily $q$, '42501', 'U10 usage rows cannot be deleted');

-- monthly figures for billing ----------------------------------------------------------------------------------
SELECT control.record_usage('claude-test-a', '2026-10-15', 140, 90, 6, 2000, 12000);
SELECT control.add_api_calls('claude-test-a', '2026-10-15', 1000);
SELECT control.record_usage('claude-test-a', '2026-10-30', 120, 95, 4, 2500, 11000);
SELECT control.add_api_calls('claude-test-a', '2026-10-31', 50);            -- calls on the last day, snapshot not taken yet
SELECT control.record_usage('claude-test-a', '2026-11-01', 999, 999, 9, 1, 1);
SELECT control.add_api_calls('claude-test-a', '2026-09-30', 7777);
SELECT control.record_usage('claude-test-b', '2026-10-10', 10, 10, 2, 100, 5000);
SELECT pg_temp.check((SELECT peak_active_loans = 140 AND closing_active_loans = 120 AND peak_active_customers = 95 AND closing_active_customers = 95
                             AND peak_staff_users = 6 AND api_calls = 1200 AND peak_storage_bytes = 14000 AND closing_storage_bytes = 13500
                             AND days_measured = 3 AND month = '2026-10' AND edition = 'STARTER'
                        FROM control.usage_monthly('2026-10-17', 'claude-test-a')),
                     'B1 a month gives the peak and the closing value of each count and the calls of the month only');
SELECT pg_temp.check((SELECT string_agg(tenant_code, ',' ORDER BY tenant_code) FROM control.usage_monthly('2026-10-01')) = 'claude-test-a,claude-test-b',
                     'B2 without a tenant every tenant with usage in the month is listed');
SELECT pg_temp.check((SELECT count(*) FROM control.usage_monthly('2026-12-01')) = 0, 'B3 a month without usage is empty');
SELECT pg_temp.check((SELECT api_calls = 7777 AND peak_active_loans IS NULL AND days_measured = 0 FROM control.usage_monthly('2026-09-01', 'claude-test-a')),
                     'B4 a month with calls but no snapshot shows the calls and no counts');

-- support request index -------------------------------------------------------------------------------------------
INSERT INTO control.support_request (id, tenant_id, engineer_subject, engineer, ticket, duration_minutes)
VALUES ('00000000-0000-0000-0000-0000000005a1', '00000000-0000-0000-0000-00000000a001', 'kc-sub-eng-1', 'eng.one', 'SUP-1001', 120);
SELECT pg_temp.expect_fail($q$ INSERT INTO control.support_request (id, tenant_id, engineer_subject, engineer, ticket, duration_minutes)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000a001', 'kc-sub-eng-1', 'eng.one', 'SUP-1002', 481) $q$, '23514', 'S1 more than 8 hours cannot be asked for');
SELECT pg_temp.expect_fail($q$ UPDATE control.support_request SET duration_minutes = 480 $q$, '42501', 'S2 a request in the index cannot be edited');
SELECT pg_temp.expect_fail($q$ DELETE FROM control.support_request $q$, '42501', 'S3 or deleted');
SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'control' AND table_name = 'support_request'
                                  AND column_name IN ('status','expires_at','approved_by')),
                     'S4 the index holds no approval: only the tenant database can say that access was given');
\echo ALL CONTROL P2-7 TESTS PASSED
