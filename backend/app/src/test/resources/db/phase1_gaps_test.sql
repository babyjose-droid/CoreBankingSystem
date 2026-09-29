-- Phase 1 gap closure (V13): branch scope, territory load, enumeration formats.
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

-- Fixture: HO, two Mumbai-region branches in a set, one Chennai branch.
INSERT INTO platform.branch VALUES ('HO','Head Office Kochi',NULL,'32',NULL,true,'ACTIVE'),
  ('MUM','Mumbai',NULL,'27','HO',false,'ACTIVE'),('PUN','Pune',NULL,'27','HO',false,'ACTIVE'),
  ('CHN','Chennai',NULL,'33','HO',false,'ACTIVE');
INSERT INTO platform.branch_set VALUES ('WEST','West region');
INSERT INTO platform.branch_set_member VALUES ('WEST','MUM'),('WEST','PUN');
INSERT INTO platform.staff_user (user_id, username, display_name, home_branch, all_branches, status) VALUES
  ('sub-ho','ho.admin','CLAUDE-TEST HO','HO',true,'ACTIVE'),
  ('sub-w','west.mgr','CLAUDE-TEST West','MUM',false,'ACTIVE'),
  ('sub-c','chn.officer','CLAUDE-TEST Chennai','CHN',false,'ACTIVE'),
  ('sub-x','gone','CLAUDE-TEST Exited','HO',true,'EXITED');
INSERT INTO platform.staff_branch_set_scope VALUES ('sub-w','WEST');
INSERT INTO platform.staff_branch_scope VALUES ('sub-c','HO');

-- Branch scope --------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) FROM platform.visible_branches('ho.admin')) = 4, 'S1 all-branches user sees every branch');
SELECT pg_temp.check((SELECT string_agg(branch_code, ',' ORDER BY branch_code) FROM platform.visible_branches('west.mgr')) = 'MUM,PUN',
                     'S2 branch-set grant gives the set members');
SELECT pg_temp.check((SELECT string_agg(branch_code, ',' ORDER BY branch_code) FROM platform.visible_branches('sub-c')) = 'CHN,HO',
                     'S3 home branch plus direct grant, found by subject');
SELECT pg_temp.check((SELECT count(*) FROM platform.visible_branches('gone')) = 0, 'S4 exited user sees nothing');
SELECT pg_temp.check((SELECT count(*) FROM platform.visible_branches('nobody')) = 0, 'S5 user without a staff profile sees nothing');
SELECT pg_temp.check(platform.can_see_branch('WEST.MGR','PUN') AND NOT platform.can_see_branch('west.mgr','CHN'),
                     'S6 can_see_branch, username case-insensitive');
SELECT pg_temp.check(NOT platform.can_see_branch('ho.admin', NULL), 'S7 NULL branch is never visible');
SELECT pg_temp.check(platform.sees_all_branches('ho.admin') AND NOT platform.sees_all_branches('west.mgr')
                     AND NOT platform.sees_all_branches('gone'), 'S8 sees_all_branches only for active all-branch users');
INSERT INTO platform.branch_set_member VALUES ('WEST','CHN');
SELECT pg_temp.check(platform.can_see_branch('west.mgr','CHN'), 'S9 adding a branch to a set extends scope immediately');

-- Territory -----------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) FROM platform.state WHERE country_code = 'IN') = 37, 'T1 37 states/UTs seeded');
SELECT pg_temp.check((SELECT name FROM platform.state WHERE gst_state_code = '32') = 'Kerala'
                     AND (SELECT code FROM platform.state WHERE gst_state_code = '27') = 'MH', 'T2 GST state codes');
SELECT pg_temp.check(platform.load_territory($j$[
  {"state":"32","district":"ernakulam","city":"Kochi","pincode":"682001"},
  {"state":"KL","district":"Ernakulam","city":"kochi","pincode":"682011"},
  {"state":"27","district":"Mumbai","city":"Mumbai","pincode":"400001"}]$j$) = 3, 'T3 load by GST code or state code');
SELECT pg_temp.check(platform.load_territory($j$[{"state":"32","district":"Ernakulam","city":"Kochi","pincode":"682001"}]$j$) = 0,
                     'T4 reload is idempotent');
SELECT pg_temp.check((SELECT count(*) FROM platform.district) = 2 AND (SELECT count(*) FROM platform.city) = 2,
                     'T5 names normalised, no duplicate district/city');
SELECT pg_temp.check((SELECT city || '/' || district || '/' || state_code || '/' || gst_state_code
                        FROM platform.pincode_lookup WHERE pincode = '682011') = 'Kochi/Ernakulam/KL/32', 'T6 pincode lookup');
SELECT pg_temp.expect_fail($q$ SELECT platform.load_territory('[{"state":"32","district":"A","city":"B","pincode":"682002"},
                                                                {"state":"XX","district":"A","city":"B","pincode":"100001"}]') $q$,
                           '22023', 'T7 unknown state rejected');
SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM platform.pincode WHERE pincode = '682002'), 'T8 a bad row rolls back the whole load');
SELECT pg_temp.expect_fail($q$ SELECT platform.load_territory('[{"state":"32","district":"A","city":"B","pincode":"012345"}]') $q$,
                           '22023', 'T9 pincode cannot start with 0');
SELECT pg_temp.expect_fail($q$ SELECT platform.load_territory('[{"state":"32","district":"","city":"B","pincode":"682003"}]') $q$,
                           '22023', 'T10 district required');

-- Enumerations ----------------------------------------------------------------------------------------
INSERT INTO platform.enumeration (enum_type, code, label) VALUES ('OCCUPATION','SALARIED','Salaried');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.enumeration (enum_type, code, label) VALUES ('OCCUPATION','self employed','x') $q$,
                           '23514', 'N1 enumeration code format enforced');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.enumeration (enum_type, code, label) VALUES ('occupation','X','x') $q$,
                           '23514', 'N2 enumeration type format enforced');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.system_property (key, value, updated_by) VALUES ('Bad Key','1','t') $q$,
                           '23514', 'N3 property key format enforced');
\echo ALL PHASE 1 GAP TESTS PASSED
