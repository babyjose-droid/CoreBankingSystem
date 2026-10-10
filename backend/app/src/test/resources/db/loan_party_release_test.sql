-- V26: release of a guarantor or co-applicant: the row stays, exposure drops, the borrower cannot be released.
-- Run on a fresh tenant database after migrations. All data is fake (CLAUDE-TEST).
\set QUIET on
CREATE FUNCTION pg_temp.expect_fail(sql text, code text, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE sql;
    SET CONSTRAINTS ALL IMMEDIATE;
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

-- Fixtures ---------------------------------------------------------------------------------------------------
INSERT INTO platform.branch VALUES ('HO','Head Office Kochi',NULL,'32',NULL,true,'ACTIVE');
INSERT INTO platform.business_day VALUES (1,'2026-10-20','OPEN');
SELECT ledger.load_starter_kit('NBFC');
SELECT ledger.load_lending_heads();
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status)
VALUES ('PL01','CLAUDE-TEST Personal loan','EQUATED',10000,500000,6,60,12,24,24,'1101','4101','1102','ACTIVE');
INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch,status) VALUES
  ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST Borrower','HO','ACTIVE'),
  ('00000000-0000-0000-0000-0000000000c2','90010000000021','INDIVIDUAL','CLAUDE-TEST Guarantor','HO','ACTIVE'),
  ('00000000-0000-0000-0000-0000000000c3','90010000000039','INDIVIDUAL','CLAUDE-TEST Co-applicant','HO','ACTIVE'),
  ('00000000-0000-0000-0000-0000000000c4','90010000000047','INDIVIDUAL','CLAUDE-TEST Blocked','HO','BLOCKED'),
  ('00000000-0000-0000-0000-0000000000c5','90010000000054','NON_INDIVIDUAL','CLAUDE-TEST Traders LLP','HO','ACTIVE'),
  ('00000000-0000-0000-0000-0000000000c6','90010000000062','INDIVIDUAL','CLAUDE-TEST No loans','HO','ACTIVE');
INSERT INTO platform.approval_request (id,entity_type,action,payload,maker) VALUES
  ('00000000-0000-0000-0000-00000000cc01','LOAN_PARTY_RELEASE','RELEASE','{}','maker');
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,created_by)
  VALUES ('00000000-0000-0000-0000-00000000aa01','10010000000017','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,18,12,'2026-10-20','SANCTIONED','maker');
INSERT INTO lending.loan_party (loan_id,customer_id,role,added_by) VALUES
  ('00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000c2','GUARANTOR','maker'),
  ('00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000c3','CO_APPLICANT','maker');

SELECT pg_temp.check((SELECT as_guarantor FROM customer.exposure WHERE customer_id = '00000000-0000-0000-0000-0000000000c2') = 100000, 'R1 the guarantor carries the exposure');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_party SET released_on = '2026-10-20', released_by = 'maker', release_reason = 'x'
  WHERE role = 'BORROWER' $q$, '23514', 'R2 the borrower cannot be released');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_party SET released_on = '2026-10-20' WHERE role = 'GUARANTOR' $q$, '23514',
  'R3 a release needs who and why');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_party SET released_on = '2026-10-20', released_by = 'maker', release_reason = 'x', role = 'CO_APPLICANT'
  WHERE role = 'GUARANTOR' $q$, '42501', 'R4 nothing but the release columns changes');
UPDATE lending.loan_party SET released_on = '2026-10-20', released_by = 'maker', release_reason = 'CLAUDE-TEST guarantee discharged',
       release_approval_id = '00000000-0000-0000-0000-00000000cc01'
 WHERE loan_id = '00000000-0000-0000-0000-00000000aa01' AND customer_id = '00000000-0000-0000-0000-0000000000c2';
SELECT pg_temp.check((SELECT as_guarantor = 0 AND loans_as_guarantor = 0 FROM customer.exposure WHERE customer_id = '00000000-0000-0000-0000-0000000000c2'),
                     'R5 a released guarantor drops out of exposure');
SELECT pg_temp.check((SELECT as_co_applicant FROM customer.exposure WHERE customer_id = '00000000-0000-0000-0000-0000000000c3') = 100000,
                     'R6 the co-applicant is unaffected');
SELECT pg_temp.check((SELECT count(*) FROM lending.loan_party WHERE loan_id = '00000000-0000-0000-0000-00000000aa01') = 3
                     AND (SELECT released_by FROM lending.loan_party WHERE role = 'GUARANTOR') = 'maker', 'R7 the row and its history are kept');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_party SET released_on = '2026-10-21' WHERE role = 'GUARANTOR' $q$, '42501', 'R8 a release is not undone or redated');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_party SET released_on = NULL, released_by = NULL, release_reason = NULL, release_approval_id = NULL
  WHERE role = 'GUARANTOR' $q$, '42501', 'R9 a release cannot be cleared');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.loan_party WHERE role = 'GUARANTOR' $q$, '42501', 'R10 parties are never deleted');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_party (loan_id,customer_id,role,added_by)
  VALUES ('00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000c2','GUARANTOR','maker') $q$, '23514', 'R11 a released party is not added a second time');
SELECT pg_temp.check((SELECT checkers_required FROM platform.approval_rule WHERE entity_type = 'LOAN_PARTY_RELEASE' AND action = 'RELEASE') = 1
                 AND (SELECT checkers_required FROM platform.approval_rule WHERE entity_type = 'LOAN_PARTY_RELEASE' AND action = 'RELEASE_STRESSED') = 2,
                     'R12 one checker normally, two on a stressed loan');
SELECT 'TESTS PASSED' AS result;
