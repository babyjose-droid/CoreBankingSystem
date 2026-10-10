-- Control plane (control V4): what the migration does to entitlements recorded before the rule existed.
-- The runner applies every migration first, so this test rebuilds the earlier state: it drops the V4 trigger, records
-- the old entitlements, and runs the two statements of the migration again.
\set QUIET on
CREATE FUNCTION pg_temp.check(ok boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF ok IS NOT TRUE THEN RAISE EXCEPTION 'FAIL %', label; END IF;
  RAISE NOTICE 'PASS %', label;
END $$;

INSERT INTO control.tenant (id, code, legal_name, entity_type, deployment_tier, edition_code, status) VALUES
  ('00000000-0000-0000-0000-00000000e001', 'claude-test-old-nbfc', 'CLAUDE-TEST Old Finance Ltd', 'NBFC', 'POOLED', 'ENTERPRISE', 'ACTIVE'),
  ('00000000-0000-0000-0000-00000000e002', 'claude-test-old-bank', 'CLAUDE-TEST Old Bank Ltd',    'BANK', 'POOLED', 'ENTERPRISE', 'ACTIVE');
ALTER TABLE control.tenant_module DISABLE TRIGGER tenant_module_allowed;
INSERT INTO control.tenant_module (tenant_id, module_code)
SELECT t.id, e.module_code FROM control.tenant t JOIN control.edition_module e ON e.edition_code = t.edition_code;
ALTER TABLE control.tenant_module ENABLE TRIGGER tenant_module_allowed;

INSERT INTO control.operator_action (tenant_id, operator, action, reason)
SELECT m.tenant_id, 'migration-V4', 'MODULE_DISABLED',
       m.module_code || ' is not available to a ' || t.entity_type || ' (deposit module rules)'
  FROM control.tenant_module m JOIN control.tenant t ON t.id = m.tenant_id
 WHERE m.enabled AND NOT control.module_allowed(t.entity_type, t.deposit_taking, m.module_code);
UPDATE control.tenant_module m SET enabled = false
  FROM control.tenant t
 WHERE t.id = m.tenant_id AND m.enabled AND NOT control.module_allowed(t.entity_type, t.deposit_taking, m.module_code);

SELECT pg_temp.check((SELECT array_agg(module_code ORDER BY module_code COLLATE "C") FROM control.tenant_module
                       WHERE tenant_id = '00000000-0000-0000-0000-00000000e001' AND NOT enabled) = ARRAY['CASA','TD'],
                     'G1 CASA and TD of an NBFC are switched off');
SELECT pg_temp.check((SELECT count(*) = 5 FROM control.tenant_module WHERE tenant_id = '00000000-0000-0000-0000-00000000e001' AND enabled),
                     'G2 its other modules stay on');
SELECT pg_temp.check((SELECT count(*) = 7 FROM control.tenant_module WHERE tenant_id = '00000000-0000-0000-0000-00000000e002' AND enabled),
                     'G3 a bank keeps every module');
SELECT pg_temp.check((SELECT count(*) = 2 FROM control.operator_action
                       WHERE tenant_id = '00000000-0000-0000-0000-00000000e001' AND action = 'MODULE_DISABLED' AND operator = 'migration-V4'),
                     'G4 each module switched off is in the operator log');
SELECT 'TESTS PASSED' AS result;
