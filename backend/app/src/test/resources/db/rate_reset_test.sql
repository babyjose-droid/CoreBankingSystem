-- V24 automatic floating-rate reset: product default, the loan's choice and flags, history rows of the day-end's
-- rate changes (no approval, no checker), and the two reset reports. Run on a fresh tenant database after migrations.
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

-- Fixture: business date 1-Oct-2026; REPO 6.00% from 1-Jan-2026 and 6.50% from 1-Sep-2026; a floating product with
-- spread 3% and band 8..9.25%; three floating loans in two branches and one fixed loan.
INSERT INTO platform.branch VALUES ('HO','Head Office Kochi',NULL,'32',NULL,true,'ACTIVE'), ('MUM','Mumbai',NULL,'27','HO',false,'ACTIVE');
INSERT INTO platform.business_day VALUES (1,'2026-10-01','OPEN');
SELECT ledger.load_starter_kit('NBFC');
SELECT ledger.load_lending_heads();
INSERT INTO platform.staff_user (user_id, username, display_name, home_branch, all_branches, status) VALUES
  ('sub-all','all.user','CLAUDE-TEST All branches','HO',true,'ACTIVE'),
  ('sub-mum','mum.user','CLAUDE-TEST Mumbai','MUM',false,'ACTIVE');
INSERT INTO lending.benchmark_rate (benchmark_code, effective_from, rate, recorded_by) VALUES
  ('REPO','2026-01-01',6,'CLAUDE-TEST'), ('REPO','2026-09-01',6.5,'CLAUDE-TEST');
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status,rate_type,
                                  benchmark_code,spread,reset_frequency_months)
VALUES ('HL01','CLAUDE-TEST Floating loan','EQUATED',10000,5000000,6,240,8,9.25,24,'1101','4101','1102','ACTIVE','FLOATING','REPO',3,3),
       ('PL01','CLAUDE-TEST Fixed loan','EQUATED',10000,500000,6,60,12,24,24,'1101','4101','1102','ACTIVE','FIXED',NULL,NULL,NULL);
INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch) VALUES
  ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST One','HO');

SELECT pg_temp.check((SELECT rate_reset_option FROM lending.loan_product WHERE code = 'HL01') = 'KEEP_TENURE_CHANGE_EMI',
                     'P1 by default a reset changes the EMI and keeps the tenure (product owner, 10-Oct-2026)');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_product SET rate_reset_option = 'CHANGE_BOTH' WHERE code = 'HL01' $q$, '23514',
                           'P2 the default is one of the two options; a combination is agreed reset by reset');
UPDATE lending.loan_product SET rate_reset_option = 'KEEP_TENURE_CHANGE_EMI' WHERE code = 'HL01';

-- F1 Kochi: reset due 5-Oct (inside 30 days), borrower chose a longer tenure. F2 Mumbai: reset due 20-Dec (outside 30 days).
-- F3 Kochi: reset date already passed (catch-up), current rate 9%. X1: fixed.
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,emi,status,
                                  kfs_accepted_at,state,principal_outstanding,benchmark_code,spread,reset_frequency_months,next_rate_reset,
                                  rate_reset_preference)
VALUES ('00000000-0000-0000-0000-0000000000f1','10010000000017','00000000-0000-0000-0000-0000000000c1','HL01','HO',500000,9,120,'2026-07-05',
        6334,'ACTIVE',now(),'{}',490000,'REPO',3,3,'2026-10-05','KEEP_EMI_CHANGE_TENURE'),
       ('00000000-0000-0000-0000-0000000000f2','10010000000025','00000000-0000-0000-0000-0000000000c1','HL01','MUM',300000,9,60,'2026-09-20',
        6227,'ACTIVE',now(),'{}',300000,'REPO',3,3,'2026-12-20',NULL),
       ('00000000-0000-0000-0000-0000000000f3','10010000000033','00000000-0000-0000-0000-0000000000c1','HL01','HO',200000,9,24,'2026-06-15',
        9137,'ACTIVE',now(),'{}',180000,'REPO',3,3,'2026-09-15',NULL),
       ('00000000-0000-0000-0000-0000000000f4','10010000000041','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,18,12,'2026-06-30',
        9168,'ACTIVE',now(),'{}',100000,NULL,NULL,NULL,NULL,NULL);

-- Loan choice and flags ------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET rate_reset_preference = 'KEEP_EMI_CHANGE_TENURE' WHERE loan_no = '10010000000041' $q$,
                           '23514', 'L1 only a floating loan has a choice at a reset');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET rate_reset_preference = 'CHANGE_BOTH' WHERE loan_no = '10010000000017' $q$,
                           '23514', 'L2 the standing choice is a longer tenure or a higher EMI');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET rate_fixed_since = '2026-10-01' WHERE loan_no = '10010000000017' $q$,
                           '23514', 'L3 a loan switched to fixed has no next reset date');
UPDATE lending.loan_account SET rate_fixed_since = '2026-10-01', next_rate_reset = NULL WHERE loan_no = '10010000000025';
SELECT pg_temp.check((SELECT rate_fixed_since FROM lending.loan_account WHERE loan_no = '10010000000025') = '2026-10-01',
                     'L4 a floating loan can be switched to fixed');
UPDATE lending.loan_account SET rate_fixed_since = NULL, next_rate_reset = '2026-12-20' WHERE loan_no = '10010000000025';
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET rate_fixed_since = '2026-10-01', next_rate_reset = NULL WHERE loan_no = '10010000000041' $q$,
                           '23514', 'L5 a fixed loan cannot be switched to fixed');
SELECT pg_temp.check(NOT (SELECT rate_outside_band FROM lending.loan_account WHERE loan_no = '10010000000017'), 'L6 not flagged until a reset says so');

-- History rows of the day-end's rate changes ------------------------------------------------------------------
INSERT INTO lending.loan_txn (id,loan_id,seq,txn_type,value_date,business_date,state_before,created_by) VALUES
  ('00000000-0000-0000-0000-00000000bb01','00000000-0000-0000-0000-0000000000f3',1,'RATE_RESET','2026-09-15','2026-09-15','{}','eod'),
  ('00000000-0000-0000-0000-00000000bb02','00000000-0000-0000-0000-0000000000f3',2,'AMENDMENT','2026-09-20','2026-09-20','{}','checker1'),
  ('00000000-0000-0000-0000-00000000bb03','00000000-0000-0000-0000-0000000000f3',3,'RATE_RESET','2026-09-30','2026-09-30','{}','eod'),
  ('00000000-0000-0000-0000-00000000bb04','00000000-0000-0000-0000-0000000000f1',1,'RATE_STEP','2026-09-30','2026-09-30','{}','eod'),
  ('00000000-0000-0000-0000-00000000bb05','00000000-0000-0000-0000-0000000000f1',2,'RATE_RESET','2026-09-30','2026-09-30','{}','eod');
INSERT INTO platform.approval_request (id,entity_type,entity_id,action,payload,maker) VALUES
  ('00000000-0000-0000-0000-00000000cc01','LOAN_AMENDMENT','10010000000033','AMEND','{}','maker');

INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,rate_before,
    rate_after,maturity_before,maturity_after,interest_before,interest_after,applied_figures,approval_id,made_by,checked_by,business_date,reason)
VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000000f3',1,'00000000-0000-0000-0000-00000000bb01','RATE_RESET',
        '{"benchmarkCode":"REPO","benchmarkRate":"6.5","spread":"3","requestedOption":"KEEP_EMI_CHANGE_TENURE","appliedOption":"KEEP_TENURE_CHANGE_EMI","fallbackReason":"beyond the maximum tenure","outsideBand":true,"resetDates":["2026-09-15"]}',
        9137,9180,21,21,9,9.5,'2028-06-15','2028-06-15',15000,15800,'{}',NULL,'eod',NULL,'2026-09-15','Rate reset: REPO 6.5% + spread 3%');
SELECT pg_temp.check(true, 'H1 a day-end rate reset is recorded without an approval or a checker');
SELECT pg_temp.expect_fail($q$
  INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,rate_before,
      rate_after,maturity_after,interest_before,interest_after,applied_figures,approval_id,made_by,checked_by,business_date)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000000f3',2,'00000000-0000-0000-0000-00000000bb02','RATE_CHANGE','{}',1,1,1,1,1,1,
          '2027-01-01',0,0,'{}',NULL,'eod',NULL,'2026-09-20') $q$,
  '23514', 'H2 a maker''s rate change still needs its approval and checker');
SELECT pg_temp.expect_fail($q$
  INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,rate_before,
      rate_after,maturity_after,interest_before,interest_after,applied_figures,approval_id,made_by,checked_by,business_date)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000000f3',2,'00000000-0000-0000-0000-00000000bb02','RATE_RESET','{}',1,1,1,1,1,1,
          '2027-01-01',0,0,'{}','00000000-0000-0000-0000-00000000cc01','maker','checker1','2026-09-20') $q$,
  '23514', 'H3 a rate reset is never a maker-checker request');
SELECT pg_temp.expect_fail($q$
  INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,rate_before,
      rate_after,maturity_after,interest_before,interest_after,applied_figures,approval_id,made_by,checked_by,business_date)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000000f3',2,'00000000-0000-0000-0000-00000000bb02','SWITCH_TO_FIXED','{}',1,1,1,1,1,1,
          '2027-01-01',0,0,'{}','00000000-0000-0000-0000-00000000cc01','maker','maker','2026-09-20') $q$,
  '23514', 'H4 a switch to fixed is a maker-checker amendment with four eyes');
INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,rate_before,
    rate_after,maturity_after,interest_before,interest_after,applied_figures,approval_id,made_by,checked_by,business_date,reason)
VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000000f3',2,'00000000-0000-0000-0000-00000000bb02','SWITCH_TO_FIXED','{}',9180,9200,
        21,21,9.5,9.75,'2028-06-15',15800,16000,'{}','00000000-0000-0000-0000-00000000cc01','maker','checker1','2026-09-20','fixed'),
       (gen_random_uuid(),'00000000-0000-0000-0000-0000000000f3',3,'00000000-0000-0000-0000-00000000bb03','RATE_RESET',
        '{"benchmarkCode":"REPO","benchmarkRate":"6.5","spread":"3","outsideBand":false}',9200,9190,21,21,9.75,9.5,'2028-06-15',16000,15800,
        '{}',NULL,'eod',NULL,'2026-09-30','reversed later'),
       (gen_random_uuid(),'00000000-0000-0000-0000-0000000000f1',1,'00000000-0000-0000-0000-00000000bb04','RATE_STEP','{}',6334,6400,117,117,
        9,9.25,'2036-07-05',100000,101000,'{}',NULL,'eod',NULL,'2026-09-30','step'),
       (gen_random_uuid(),'00000000-0000-0000-0000-0000000000f1',2,'00000000-0000-0000-0000-00000000bb05','RATE_RESET',
        '{"benchmarkCode":"REPO","benchmarkRate":"6.5","spread":"3","outsideBand":false}',6400,6400,117,118,9.25,9.5,'2036-07-05',
        101000,102000,'{}',NULL,'eod',NULL,'2026-09-30','reset');
SELECT pg_temp.check((SELECT count(*) FROM lending.loan_amendment WHERE kind IN ('RATE_RESET','RATE_STEP','SWITCH_TO_FIXED')) = 5,
                     'H5 resets, steps and switches are history kinds');
INSERT INTO lending.loan_txn (id,loan_id,seq,txn_type,value_date,business_date,state_before,created_by) VALUES
  ('00000000-0000-0000-0000-00000000bb06','00000000-0000-0000-0000-0000000000f3',4,'REVERSAL','2026-10-01','2026-10-01','{}','checker1');
UPDATE lending.loan_amendment SET reversed_by = '00000000-0000-0000-0000-00000000bb06' WHERE txn_id = '00000000-0000-0000-0000-00000000bb03';
SELECT pg_temp.check((SELECT reversed_by FROM lending.loan_amendment WHERE txn_id = '00000000-0000-0000-0000-00000000bb03') IS NOT NULL,
                     'H6 a reset undone by a reversal is marked reversed, like any amendment');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_amendment SET rate_after = 10 WHERE txn_id = '00000000-0000-0000-0000-00000000bb01' $q$,
                           '42501', 'H7 reset history is immutable');

-- Reports ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) FROM reporting.report_definition WHERE code IN ('RATE_RESETS_DUE','RATE_RESETS_APPLIED')
                        AND to_regprocedure(sql_function || '(text,jsonb)') IS NOT NULL AND permission = 'report:run') = 2,
                     'R1 both reset reports are in the catalogue with their functions');
SELECT pg_temp.check((SELECT count(*) FROM platform.job_definition WHERE code IN ('REPORT_RATE_RESETS_DUE','REPORT_RATE_RESETS_APPLIED')) = 2,
                     'R2 each can be scheduled');
SELECT pg_temp.check((SELECT array_agg(loan_no ORDER BY loan_no) FROM reporting.rpt_rate_resets_due('all.user', '{}')) = ARRAY['10010000000017','10010000000033'],
                     'R3 due in 30 days: the loan due on 5-Oct and the one already past its date; not the one due in December, not the fixed loan');
SELECT pg_temp.check((SELECT count(*) FROM reporting.rpt_rate_resets_due('all.user', '{"days":"90"}')) = 3, 'R4 90 days takes in December');
SELECT pg_temp.check((SELECT count(*) FROM reporting.rpt_rate_resets_due('mum.user', '{"days":"90"}')) = 1, 'R5 branch scope');
SELECT pg_temp.check((SELECT benchmark_rate = 6.5 AND projected_rate = 9.5 AND projected_outside_band AND reset_option = 'KEEP_EMI_CHANGE_TENURE'
                        FROM reporting.rpt_rate_resets_due('all.user', '{}') WHERE loan_no = '10010000000017'),
                     'R6 projected rate = benchmark on the reset date + spread, outside the 9.25% cap; the borrower''s choice wins');
SELECT pg_temp.check((SELECT reset_option FROM reporting.rpt_rate_resets_due('all.user', '{}') WHERE loan_no = '10010000000033') = 'KEEP_TENURE_CHANGE_EMI',
                     'R7 otherwise the product''s default');
UPDATE lending.loan_account SET product_snapshot = '{"floating":{"defaultOption":"KEEP_EMI_CHANGE_TENURE"}}' WHERE loan_no = '10010000000033';
SELECT pg_temp.check((SELECT reset_option FROM reporting.rpt_rate_resets_due('all.user', '{}') WHERE loan_no = '10010000000033') = 'KEEP_EMI_CHANGE_TENURE',
                     'R8 the default frozen into the loan at booking wins over a later product change');
SELECT pg_temp.check((SELECT count(*) FROM reporting.rpt_rate_resets_applied('all.user', '{"from":"2026-09-01","to":"2026-09-30"}')) = 3,
                     'R9 applied in September: resets and steps, not the reversed one, not the switch to fixed');
SELECT pg_temp.check((SELECT array_agg(loan_no) FROM reporting.rpt_rate_resets_applied('all.user', '{"from":"2026-09-01","to":"2026-09-30","outsideBandOnly":"true"}'))
                        = ARRAY['10010000000033'], 'R10 outside the band only (D-14 flag)');
SELECT pg_temp.check((SELECT applied_option = 'KEEP_TENURE_CHANGE_EMI' AND fallback_reason IS NOT NULL AND reset_dates = '2026-09-15'
                             AND rate_before = 9 AND rate_after = 9.5
                        FROM reporting.rpt_rate_resets_applied('all.user', '{"from":"2026-09-15","to":"2026-09-15"}')),
                     'R11 the option applied, why, and the reset dates covered');
SELECT pg_temp.check((SELECT count(*) FROM reporting.rpt_rate_resets_applied('all.user', '{}')) = 0, 'R12 default period: this month so far');
SELECT pg_temp.check((SELECT count(*) FROM reporting.rpt_rate_resets_applied('mum.user', '{"from":"2026-09-01","to":"2026-09-30"}')) = 0,
                     'R13 branch scope');

SELECT 'TESTS PASSED' AS result;
