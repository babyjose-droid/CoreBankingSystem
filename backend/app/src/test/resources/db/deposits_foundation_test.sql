-- Phase 4 (P4-0, tenant V28): deposit-taking status, the rule set by institution type, and the deposit GL heads.
-- Run on a fresh tenant database after the tenant migrations.
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
-- The test switches the legal entity between institution types; a real tenant has one type for life.
CREATE FUNCTION pg_temp.become(p_type text) RETURNS void LANGUAGE sql AS $$
  UPDATE platform.legal_entity SET deposit_taking = false WHERE id = 1;
  UPDATE platform.legal_entity SET entity_type = p_type WHERE id = 1;
$$;

-- before the legal entity exists -----------------------------------------------------------------------------
SELECT pg_temp.check(deposits.rule_kind() IS NULL AND deposits.rule_value('tds.rate-percent') IS NULL
                     AND (SELECT count(*) = 0 FROM deposits.rules_in_force()), 'K1 no legal entity: no rule set');
SELECT pg_temp.expect_fail($q$ SELECT deposits.assert_can_offer('TERM_DEPOSIT') $q$, '23514', 'K2 and no deposits');
SELECT pg_temp.check(ledger.load_deposit_heads() = 0, 'K3 and no deposit heads');

INSERT INTO platform.branch (code, name, state_code, is_head_office) VALUES ('HO', 'Head Office', '32', true);
INSERT INTO platform.business_day VALUES (1, '2026-10-10', 'OPEN');
INSERT INTO platform.legal_entity (legal_name, entity_type, registered_state) VALUES ('CLAUDE-TEST Finance Ltd', 'NBFC', '32');
SELECT ledger.load_starter_kit('NBFC');

-- an NBFC that is not registered to take deposits ------------------------------------------------------------------
SELECT pg_temp.check(deposits.rule_kind() IS NULL, 'N1 an NBFC has no rule set until its registration is recorded');
SELECT pg_temp.expect_fail($q$ SELECT deposits.assert_can_offer('TERM_DEPOSIT') $q$, '23514', 'N2 it cannot offer term deposits');
SELECT pg_temp.expect_fail($q$ SELECT deposits.assert_can_offer('CASA') $q$, '23514', 'N3 nor savings or current accounts');
SELECT pg_temp.check(ledger.load_deposit_heads() = 0 AND NOT EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '2400'),
                     'N4 its chart gets no deposit heads');
SELECT pg_temp.expect_fail($q$ SELECT deposits.set_deposit_taking(true, '') $q$, '23514', 'N5 the registration reference is required');
SELECT pg_temp.expect_fail($q$ UPDATE platform.legal_entity SET deposit_taking = true $q$, '23514', 'N6 the flag cannot be set without one');

-- a deposit-taking NBFC --------------------------------------------------------------------------------------------
SELECT deposits.set_deposit_taking(true, ' CLAUDE-TEST-COR-0001 ');
SELECT pg_temp.check(deposits.rule_kind() = 'NBFC_DEPOSIT'
                     AND (SELECT deposit_registration_ref = 'CLAUDE-TEST-COR-0001' FROM platform.legal_entity), 'D1 the registration gives the NBFC_DEPOSIT rule set');
SELECT deposits.assert_can_offer('TERM_DEPOSIT');
SELECT pg_temp.check(true, 'D2 it can offer term deposits');
SELECT pg_temp.expect_fail($q$ SELECT deposits.assert_can_offer('CASA') $q$, '23514', 'D3 but never savings or current accounts');
SELECT pg_temp.expect_fail($q$ SELECT deposits.assert_can_offer('LOCKER') $q$, '22023', 'D4 an unknown family is an error');
SELECT pg_temp.check((SELECT array_agg(code ORDER BY code COLLATE "C") FROM ledger.gl_head WHERE code IN
                        ('2400','2401','2402','2403','2404','2405','2406','2407','2408','2409','2205','5107','5108','4107','4108','1207'))
                     = ARRAY['2205','2400','2403','2404','2405','2406','2407','2408','5107'],
                     'D5 switching it on loads the term deposit heads and no savings, current or clearing heads');
SELECT pg_temp.check((SELECT bool_and(category = 'LIABILITY') FROM ledger.gl_head WHERE code LIKE '24__' OR code = '2205')
                     AND (SELECT category = 'EXPENSE' AND is_posting FROM ledger.gl_head WHERE code = '5107')
                     AND (SELECT NOT is_posting FROM ledger.gl_head WHERE code = '2400'), 'D6 deposits are liabilities; interest on them is an expense');
SELECT pg_temp.check(ledger.load_deposit_heads() = 0, 'D7 loading the heads again adds nothing');
SELECT pg_temp.check(deposits.rule_value('td.min-tenure-months') = 12 AND deposits.rule_value('td.max-tenure-months') = 60
                     AND deposits.rule_value('td.max-rate-percent') = 12.5 AND deposits.rule_value('td.min-compounding-rest-months') = 1,
                     'D8 tenure 12 to 60 months, rate ceiling 12.5%, rests not shorter than monthly');
SELECT pg_temp.check(deposits.rule_value('td.lock-in-months') = 3 AND deposits.rule_value('td.premature.no-interest-before-months') = 6
                     AND deposits.rule_value('td.premature.rate-cut-percent') = 2 AND deposits.rule_value('td.premature.rate-cut-from-minimum-percent') = 3,
                     'D9 premature repayment: 3-month lock-in, no interest before 6 months, then 2 points (or 3 below the minimum) lower');
SELECT pg_temp.check(deposits.rule_value('td.emergency.tiny-deposit-max') = 10000 AND deposits.rule_value('td.emergency.max-percent') = 50
                     AND deposits.rule_value('td.emergency.max-amount') = 500000, 'D10 emergency repayment: tiny deposits in full, others 50% up to 5 lakh');
SELECT pg_temp.check(deposits.rule_value('td.maturity-notice-days') = 14 AND deposits.rule_value('td.loan-max-percent') = 75
                     AND deposits.rule_value('td.loan-spread-percent') = 2 AND deposits.rule_value('td.deposits-to-nof-multiple') = 1.5
                     AND deposits.rule_value('td.renewal-needs-consent') = 1 AND deposits.rule_value('nominees.max') = 1
                     AND deposits.rule_value('insured') = 0 AND deposits.rule_value('demand-deposits-allowed') = 0,
                     'D11 notice 14 days, loan 75% at +2, deposits 1.5 x NOF, renewal by consent, one nominee, not insured');
SELECT pg_temp.check(deposits.rule_value('tds.threshold') = 10000 AND deposits.rule_value('tds.threshold-senior') = 10000
                     AND deposits.rule_value('tds.rate-percent') = 10, 'D12 tax at source: 10% above 10,000 for this kind of payer');
SELECT pg_temp.check(deposits.rule_value('savings.uniform-rate-up-to') IS NULL AND deposits.rule_value('casa.inoperative-after-months') IS NULL,
                     'D13 bank-only rules do not apply');
SELECT pg_temp.expect_fail($q$ SELECT deposits.rule_required('savings.uniform-rate-up-to') $q$, 'P0002', 'D14 asking for a rule that must exist names it');
SELECT pg_temp.check((SELECT count(*) = 24 AND bool_and(source <> '') FROM deposits.rules_in_force()), 'D15 24 rules in force, each with its source');
SELECT pg_temp.check((SELECT count(*) = 19 FROM deposits.rules_in_force() WHERE verified)
                     AND (SELECT bool_and(NOT verified) FROM deposits.rules_in_force() WHERE code LIKE 'tds.%' OR code = 'senior-citizen-age'),
                     'D16 the 19 figures read in the RBI Directions are marked verified; the tax figures are not');
SELECT deposits.set_deposit_taking(false, NULL);
SELECT pg_temp.check(deposits.rule_kind() IS NULL AND (SELECT deposit_registration_ref = 'CLAUDE-TEST-COR-0001' FROM platform.legal_entity)
                     AND EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '2403'),
                     'D17 withdrawing the registration ends the rule set; the reference and the heads stay');

-- a bank -------------------------------------------------------------------------------------------------------------
SELECT pg_temp.become('BANK');
SELECT pg_temp.check(deposits.rule_kind() = 'BANK', 'B1 a bank has the BANK rule set without any registration flag');
SELECT pg_temp.expect_fail($q$ SELECT deposits.set_deposit_taking(true, 'X') $q$, '23514', 'B2 the NBFC registration cannot be recorded for a bank');
SELECT deposits.assert_can_offer('CASA');
SELECT deposits.assert_can_offer('TERM_DEPOSIT');
SELECT pg_temp.check(true, 'B3 it can offer savings, current and term deposits');
SELECT pg_temp.check(ledger.load_deposit_heads() = 7, 'B4 its chart gains seven heads');
SELECT pg_temp.check((SELECT count(*) = 7 FROM ledger.gl_head WHERE code IN ('2401','2402','2409','1207','5108','4107','4108')),
                     'B4a savings, current, inward and outward clearing, savings interest and the two charge heads');
SELECT pg_temp.check((SELECT category = 'ASSET' AND parent_code = '1200' FROM ledger.gl_head WHERE code = '1207')
                     AND (SELECT bool_and(category = 'INCOME') FROM ledger.gl_head WHERE code IN ('4107','4108')), 'B5 outward clearing is an asset; charges are income');
SELECT pg_temp.check(deposits.rule_value('demand-deposits-allowed') = 1 AND deposits.rule_value('nominees.max') = 4
                     AND deposits.rule_value('savings.uniform-rate-up-to') = 100000 AND deposits.rule_value('td.premature.mandatory-up-to') = 10000000
                     AND deposits.rule_value('insured') = 1, 'B6 demand deposits, four nominees, one savings rate to 1 lakh, premature withdrawal to 1 crore');
SELECT pg_temp.check(deposits.rule_value('casa.review-after-months') = 12 AND deposits.rule_value('casa.inoperative-after-months') = 24
                     AND deposits.rule_value('unclaimed-after-years') = 10, 'B7 review after 1 year, inoperative after 2, unclaimed after 10');
SELECT pg_temp.check(deposits.rule_value('tds.threshold') = 50000 AND deposits.rule_value('tds.threshold-senior') = 100000
                     AND deposits.rule_value('tds.on-savings-interest') = 0, 'B8 tax at source: above 50,000, or 1,00,000 for senior citizens; not on savings interest');
SELECT pg_temp.check(deposits.rule_value('td.max-rate-percent') IS NULL AND deposits.rule_value('td.lock-in-months') IS NULL
                     AND deposits.rule_value('td.min-tenure-months') IS NULL, 'B9 NBFC ceilings and the lock-in do not apply to a bank');
SELECT pg_temp.check((SELECT count(*) = 14 AND bool_and(NOT verified) FROM deposits.rules_in_force()),
                     'B10 14 bank rules in force, none marked verified (the RBI text was not opened)');
SELECT pg_temp.become('SFB');
SELECT pg_temp.check(deposits.rule_kind() = 'BANK', 'B11 a small finance bank uses the BANK rule set');
SELECT pg_temp.become('COOP_BANK');
SELECT pg_temp.check(deposits.rule_kind() = 'BANK', 'B12 a co-operative bank uses the BANK rule set');
SELECT pg_temp.become('HFC');
SELECT pg_temp.check(deposits.rule_kind() IS NULL, 'B13 an HFC has no rule set');
SELECT pg_temp.expect_fail($q$ SELECT deposits.assert_can_offer('TERM_DEPOSIT') $q$, '23514', 'B14 and cannot offer deposits');
SELECT pg_temp.become('MFI');
SELECT pg_temp.check(deposits.rule_kind() IS NULL, 'B15 nor can an MFI');
SELECT pg_temp.become('BANK');

-- heads the tenant already has ---------------------------------------------------------------------------------------
UPDATE ledger.gl_head SET name = 'Fixed deposits (renamed by the tenant)' WHERE code = '2403';
SELECT pg_temp.check(ledger.load_deposit_heads() = 0, 'C1 loading again adds nothing');
SELECT pg_temp.check((SELECT name = 'Fixed deposits (renamed by the tenant)' FROM ledger.gl_head WHERE code = '2403'),
                     'C2 a head the tenant already has is left as it is');

-- rules are history ---------------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ UPDATE deposits.rule SET value = 60000 WHERE kind = 'BANK' AND code = 'tds.threshold' $q$, '42501',
                           'H1 a rule value cannot be edited');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.rule WHERE kind = 'BANK' AND code = 'tds.threshold' $q$, '42501', 'H2 nor deleted');
SELECT pg_temp.expect_fail($q$ TRUNCATE deposits.rule $q$, '42501', 'H3 nor truncated');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.rule (kind, code, value, effective_from, source)
                               VALUES ('BANK', 'tds.threshold', 60000, '2027-04-01', 'CLAUDE-TEST Finance Act') $q$, '23P01',
                           'H4 a new value cannot overlap the one in force');
UPDATE deposits.rule SET effective_to = '2027-03-31' WHERE kind = 'BANK' AND code = 'tds.threshold';
INSERT INTO deposits.rule (kind, code, value, effective_from, source, created_by)
VALUES ('BANK', 'tds.threshold', 60000, '2027-04-01', 'CLAUDE-TEST Finance Act', 'checker1');
SELECT pg_temp.check(deposits.rule_value('tds.threshold') = 50000 AND deposits.rule_value('tds.threshold', '2027-03-31') = 50000
                     AND deposits.rule_value('tds.threshold', '2027-04-01') = 60000, 'H5 ended and replaced: the value follows the date asked for');
SELECT pg_temp.check((SELECT value = 60000 AND effective_from = '2027-04-01' FROM deposits.rules_in_force('2027-06-30') WHERE code = 'tds.threshold')
                     AND (SELECT count(*) = 14 FROM deposits.rules_in_force('2027-06-30')), 'H6 the rule set of a later date shows the new value once');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.rule SET effective_to = '2027-06-30' WHERE kind = 'BANK' AND code = 'tds.threshold' AND effective_from = '2026-04-01' $q$,
                           '42501', 'H7 an ended rule cannot be re-opened or moved');
UPDATE deposits.rule SET verified = true, source = 'CLAUDE-TEST primary text', note = 'read by compliance'
 WHERE kind = 'BANK' AND code = 'tds.threshold' AND effective_from = '2026-04-01';
SELECT pg_temp.check((SELECT verified FROM deposits.rule WHERE kind = 'BANK' AND code = 'tds.threshold' AND effective_from = '2026-04-01'),
                     'H8 its source and verified flag can be corrected');
SELECT pg_temp.check(deposits.rule_value('tds.threshold', '2026-03-31') IS NULL, 'H9 before a rule starts it has no value');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.rule (kind, code, value, effective_from, source) VALUES ('BANK', 'Bad Code', 1, '2026-04-01', 'x') $q$,
                           '23514', 'H10 a rule code is lower-case with dots and hyphens');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.rule (kind, code, value, effective_from, source) VALUES ('BANK', 'new.rule', 1, '2026-04-01', ' ') $q$,
                           '23514', 'H11 a rule needs a source');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.rule (kind, code, value, effective_from, source) VALUES ('HFC', 'new.rule', 1, '2026-04-01', 'x') $q$,
                           '23514', 'H12 only the two rule sets exist');

-- lending is untouched -------------------------------------------------------------------------------------------------
SELECT pg_temp.check(platform.next_number('CASA') LIKE '2001%' AND platform.next_number('TERM_DEPOSIT') LIKE '3001%'
                     AND platform.next_number('LOAN') LIKE '1001%', 'L1 savings/current, term deposit and loan numbers come from separate series');
SELECT pg_temp.expect_fail($q$ UPDATE platform.number_series SET prefix = '20010' WHERE family = 'TERM_DEPOSIT' $q$, '23514',
                           'L2 a term deposit prefix that could collide with savings/current numbers is refused');
SELECT 'TESTS PASSED' AS result;
