-- Control plane (control V4): which institution may be licensed for the deposit modules CASA and TD.
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
  ('00000000-0000-0000-0000-00000000d001', 'claude-test-bank', 'CLAUDE-TEST Bank Ltd',      'BANK', 'DEDICATED', 'ENTERPRISE', 'ACTIVE'),
  ('00000000-0000-0000-0000-00000000d002', 'claude-test-nbfc', 'CLAUDE-TEST Finance Ltd',   'NBFC', 'POOLED',    'ENTERPRISE', 'ACTIVE'),
  ('00000000-0000-0000-0000-00000000d003', 'claude-test-hfc',  'CLAUDE-TEST Housing Ltd',   'HFC',  'POOLED',    'ENTERPRISE', 'ACTIVE'),
  ('00000000-0000-0000-0000-00000000d004', 'claude-test-sfb',  'CLAUDE-TEST Small Finance Bank Ltd', 'SFB', 'POOLED', 'ENTERPRISE', 'ACTIVE');

-- the rule itself ------------------------------------------------------------------------------------------
SELECT pg_temp.check(control.module_allowed('BANK', false, 'CASA') AND control.module_allowed('SFB', false, 'CASA')
                     AND control.module_allowed('COOP_BANK', false, 'CASA'), 'M1 banks may have CASA');
SELECT pg_temp.check(NOT control.module_allowed('NBFC', false, 'CASA') AND NOT control.module_allowed('NBFC', true, 'CASA')
                     AND NOT control.module_allowed('HFC', false, 'CASA') AND NOT control.module_allowed('MFI', false, 'CASA'),
                     'M2 no CASA for an NBFC, even a deposit-taking one, nor for an HFC or MFI');
SELECT pg_temp.check(control.module_allowed('BANK', false, 'TD') AND control.module_allowed('NBFC', true, 'TD')
                     AND NOT control.module_allowed('NBFC', false, 'TD') AND NOT control.module_allowed('NBFC', NULL, 'TD')
                     AND NOT control.module_allowed('HFC', false, 'TD') AND NOT control.module_allowed('MFI', false, 'TD'),
                     'M3 term deposits: banks and a registered deposit-taking NBFC only');
SELECT pg_temp.check(control.module_allowed('NBFC', false, 'LENDING') AND control.module_allowed('MFI', false, 'GL')
                     AND control.module_allowed('HFC', false, 'REPORTS'), 'M4 every other module is unaffected');

-- provisioning: the modules of the edition that the institution may have (the provisioner's statement) -----------
INSERT INTO control.tenant_module (tenant_id, module_code)
SELECT t.id, e.module_code FROM control.tenant t JOIN control.edition_module e ON e.edition_code = t.edition_code
 WHERE control.module_allowed(t.entity_type, t.deposit_taking, e.module_code);
SELECT pg_temp.check((SELECT array_agg(module_code ORDER BY module_code COLLATE "C") FROM control.tenant_module
                       WHERE tenant_id = '00000000-0000-0000-0000-00000000d001')
                     = ARRAY['CASA','COLLECTIONS','CO_LENDING','GL','LENDING','REPORTS','TD'], 'P1 an ENTERPRISE bank gets every module');
SELECT pg_temp.check((SELECT array_agg(module_code ORDER BY module_code COLLATE "C") FROM control.tenant_module
                       WHERE tenant_id = '00000000-0000-0000-0000-00000000d002')
                     = ARRAY['COLLECTIONS','CO_LENDING','GL','LENDING','REPORTS'], 'P2 an ENTERPRISE NBFC gets the edition without CASA and TD');
SELECT pg_temp.check((SELECT count(*) = 7 FROM control.tenant_module WHERE tenant_id = '00000000-0000-0000-0000-00000000d004'),
                     'P3 a small finance bank gets CASA and TD');

-- enabling a module by hand ------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ INSERT INTO control.tenant_module (tenant_id, module_code) VALUES ('00000000-0000-0000-0000-00000000d002', 'CASA') $q$,
                           '23514', 'E1 CASA is refused for an NBFC');
SELECT pg_temp.expect_fail($q$ INSERT INTO control.tenant_module (tenant_id, module_code) VALUES ('00000000-0000-0000-0000-00000000d002', 'TD') $q$,
                           '23514', 'E2 term deposits are refused for an NBFC that is not registered to take deposits');
SELECT pg_temp.expect_fail($q$ INSERT INTO control.tenant_module (tenant_id, module_code) VALUES ('00000000-0000-0000-0000-00000000d003', 'TD') $q$,
                           '23514', 'E3 term deposits are refused for an HFC');
INSERT INTO control.tenant_module (tenant_id, module_code, enabled) VALUES ('00000000-0000-0000-0000-00000000d002', 'TD', false);
SELECT pg_temp.check(true, 'E4 a module row that is switched off may exist');
SELECT pg_temp.expect_fail($q$ UPDATE control.tenant_module SET enabled = true
                                WHERE tenant_id = '00000000-0000-0000-0000-00000000d002' AND module_code = 'TD' $q$,
                           '23514', 'E5 and cannot be switched on');
-- the statement TenantProvisioner.setModules sends
SELECT pg_temp.expect_fail($q$ INSERT INTO control.tenant_module (tenant_id, module_code, enabled) VALUES ('00000000-0000-0000-0000-00000000d002', 'CASA', true)
                                ON CONFLICT (tenant_id, module_code) DO UPDATE SET enabled = true $q$,
                           '23514', 'E6 the set-modules statement is refused the same way');

-- recording the registration of a deposit-taking NBFC ------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ SELECT control.set_deposit_taking('claude-test-nbfc', true, '  ', 'operator1') $q$, '23514',
                           'R1 the RBI registration reference is required');
SELECT pg_temp.expect_fail($q$ SELECT control.set_deposit_taking('claude-test-bank', true, 'X', 'operator1') $q$, '23514',
                           'R2 the registration is recorded for an NBFC only');
SELECT pg_temp.expect_fail($q$ SELECT control.set_deposit_taking('no-such-tenant', true, 'X', 'operator1') $q$, 'P0002',
                           'R3 an unknown tenant is an error');
SELECT pg_temp.expect_fail($q$ UPDATE control.tenant SET deposit_taking = true WHERE code = 'claude-test-nbfc' $q$, '23514',
                           'R4 the flag cannot be set without a reference');
SELECT pg_temp.expect_fail($q$ UPDATE control.tenant SET deposit_taking = true, deposit_registration_ref = 'X' WHERE code = 'claude-test-hfc' $q$, '23514',
                           'R5 nor for an HFC');
SELECT control.set_deposit_taking('claude-test-nbfc', true, ' CLAUDE-TEST-COR-0001 ', 'operator1');
SELECT pg_temp.check((SELECT deposit_taking AND deposit_registration_ref = 'CLAUDE-TEST-COR-0001' FROM control.tenant WHERE code = 'claude-test-nbfc'),
                     'R6 the registration is recorded, trimmed');
SELECT pg_temp.check((SELECT count(*) = 1 FROM control.operator_action
                       WHERE tenant_id = '00000000-0000-0000-0000-00000000d002' AND action = 'DEPOSIT_TAKING_ON'
                         AND operator = 'operator1' AND reason = 'CLAUDE-TEST-COR-0001'), 'R7 and written to the operator log');
UPDATE control.tenant_module SET enabled = true WHERE tenant_id = '00000000-0000-0000-0000-00000000d002' AND module_code = 'TD';
SELECT pg_temp.check((SELECT enabled FROM control.tenant_module WHERE tenant_id = '00000000-0000-0000-0000-00000000d002' AND module_code = 'TD'),
                     'R8 term deposits can now be switched on');
SELECT pg_temp.expect_fail($q$ INSERT INTO control.tenant_module (tenant_id, module_code) VALUES ('00000000-0000-0000-0000-00000000d002', 'CASA') $q$,
                           '23514', 'R9 CASA is still refused');

-- a change that would strand an enabled module -------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ SELECT control.set_deposit_taking('claude-test-nbfc', false, NULL, 'operator1') $q$, '23514',
                           'S1 the registration cannot be withdrawn while term deposits are enabled');
SELECT pg_temp.expect_fail($q$ UPDATE control.tenant SET entity_type = 'NBFC' WHERE code = 'claude-test-bank' $q$, '23514',
                           'S2 a bank with CASA cannot be re-typed as an NBFC');
UPDATE control.tenant_module SET enabled = false WHERE tenant_id = '00000000-0000-0000-0000-00000000d002' AND module_code = 'TD';
SELECT control.set_deposit_taking('claude-test-nbfc', false, NULL, 'operator2');
SELECT pg_temp.check((SELECT NOT deposit_taking AND deposit_registration_ref = 'CLAUDE-TEST-COR-0001' FROM control.tenant WHERE code = 'claude-test-nbfc')
                     AND (SELECT count(*) = 1 FROM control.operator_action WHERE action = 'DEPOSIT_TAKING_OFF' AND operator = 'operator2'),
                     'S3 after disabling the module it can; the old reference is kept and the change is logged');
UPDATE control.tenant SET legal_name = 'CLAUDE-TEST Bank Limited' WHERE code = 'claude-test-bank';
SELECT pg_temp.check(true, 'S4 other changes to a tenant are unaffected');

SELECT 'TESTS PASSED' AS result;
