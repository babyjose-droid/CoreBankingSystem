-- Phase 4 (P4-0, tenant V28): a tenant provisioned as a bank. The BANK starter kit already has the deposit group
-- (2400 to 2404) and 5107; the deposit heads are added beside them. This is the order TenantProvisioner.seed runs.
\set QUIET on
CREATE FUNCTION pg_temp.check(ok boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF ok IS NOT TRUE THEN RAISE EXCEPTION 'FAIL %', label; END IF;
  RAISE NOTICE 'PASS %', label;
END $$;
INSERT INTO platform.legal_entity (legal_name, entity_type, registered_state) VALUES ('CLAUDE-TEST Bank Ltd', 'BANK', '32');
INSERT INTO platform.branch (code, name, state_code, is_head_office) VALUES ('HO', 'Head Office', '32', true);
INSERT INTO platform.business_day VALUES (1, '2026-10-10', 'OPEN');
SELECT ledger.load_starter_kit('BANK');
SELECT ledger.load_lending_heads();
SELECT pg_temp.check(ledger.load_deposit_heads() = 10, 'S1 ten heads are added to the BANK starter kit');
SELECT pg_temp.check((SELECT count(*) = 17 FROM ledger.gl_head WHERE code IN
                        ('2400','2401','2402','2403','2404','2405','2406','2407','2408','2409','2205','5107','5108','4107','4108','1207','2204')),
                     'S2 the chart now has every deposit head, and the general TDS payable head is still there');
SELECT pg_temp.check((SELECT name = 'Term deposits' FROM ledger.gl_head WHERE code = '2403')
                     AND (SELECT name = 'Interest on deposits' FROM ledger.gl_head WHERE code = '5107'), 'S3 the kit''s own heads keep their names');
SELECT pg_temp.check((SELECT count(*) = 0 FROM ledger.gl_head h JOIN ledger.gl_head p ON p.code = h.parent_code WHERE p.category <> h.category OR p.is_posting),
                     'S4 every head sits under a non-posting head of its own category');
SELECT pg_temp.check((SELECT sum(debit) = sum(credit) FROM ledger.trial_balance('2026-10-10')), 'S5 the trial balance still works with the new heads');
SELECT pg_temp.check(deposits.rule_kind() = 'BANK' AND (SELECT count(*) = 14 FROM deposits.rules_in_force()), 'S6 the bank rule set is in force from the first day');

-- An NBFC provisioned the same way gets none of it.
UPDATE platform.legal_entity SET entity_type = 'NBFC';
SELECT pg_temp.check(deposits.rule_kind() IS NULL AND ledger.load_deposit_heads() = 0, 'S7 the same call for an NBFC without a registration does nothing');
SELECT 'TESTS PASSED' AS result;
