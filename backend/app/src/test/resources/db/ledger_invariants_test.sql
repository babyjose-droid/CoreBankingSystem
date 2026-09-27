-- Database-level invariant tests. Run after migrations: psql -v ON_ERROR_STOP=1 -f this file.
-- Each block must raise the named error; if it doesn't, the test fails.
\set QUIET on
BEGIN;
INSERT INTO platform.branch VALUES ('HO','Head Office',NULL,'32',NULL,true,'ACTIVE'),('MUM','Mumbai',NULL,'27','HO',false,'ACTIVE');
INSERT INTO platform.business_day VALUES (1,'2026-09-27','OPEN');
INSERT INTO ledger.gl_head VALUES
 ('1000','Assets','ASSET',NULL,false,'ACTIVE'),
 ('1100','Loans - principal','ASSET','1000',true,'ACTIVE'),
 ('1200','Bank - operating','ASSET','1000',true,'ACTIVE'),
 ('1900','Inter-branch','ASSET','1000',true,'ACTIVE'),
 ('4000','Income','INCOME',NULL,false,'ACTIVE'),
 ('4100','Processing fee','INCOME','4000',true,'ACTIVE'),
 ('2300','GST output','LIABILITY',NULL,true,'ACTIVE'),
 ('9999','Frozen head','ASSET',NULL,true,'FROZEN');
COMMIT;

-- T1: a balanced lot commits.
BEGIN;
INSERT INTO ledger.transaction_lot (id,lot_type,business_date,value_date,reference,reverses,approval_id,created_by) VALUES ('00000000-0000-0000-0000-000000000001','DISBURSEMENT','2026-09-27','2026-09-27',NULL,NULL,NULL,'test');
INSERT INTO ledger.account_entry (lot_id,business_date,branch_code,gl_code,account_no,side,amount) VALUES
 ('00000000-0000-0000-0000-000000000001','2026-09-27','HO','1100','10010000000017','DR',100000),
 ('00000000-0000-0000-0000-000000000001','2026-09-27','HO','1200','1200','CR',99115),
 ('00000000-0000-0000-0000-000000000001','2026-09-27','HO','4100','4100','CR',750),
 ('00000000-0000-0000-0000-000000000001','2026-09-27','HO','2300','2300','CR',135);
COMMIT;
\echo PASS T1 balanced lot commits

CREATE FUNCTION pg_temp.expect_fail(sql text, code text, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE sql;
    SET CONSTRAINTS ALL IMMEDIATE;   -- fire deferred triggers now
  EXCEPTION WHEN others THEN
    IF SQLSTATE = code THEN RAISE NOTICE 'PASS % [%]', label, SQLERRM; RETURN; END IF;
    RAISE EXCEPTION 'FAIL % : wrong error % %', label, SQLSTATE, SQLERRM;
  END;
  RAISE EXCEPTION 'FAIL % : no error raised', label;
END $$;

-- T2: unbalanced lot rejected at commit.
SELECT pg_temp.expect_fail($q$
 INSERT INTO ledger.transaction_lot (id,lot_type,business_date,value_date,reference,reverses,approval_id,created_by) VALUES ('00000000-0000-0000-0000-000000000002','X','2026-09-27','2026-09-27',NULL,NULL,NULL,'t');
 INSERT INTO ledger.account_entry (lot_id,business_date,branch_code,gl_code,account_no,side,amount) VALUES
  ('00000000-0000-0000-0000-000000000002','2026-09-27','HO','1100','A','DR',100),
  ('00000000-0000-0000-0000-000000000002','2026-09-27','HO','1200','B','CR',99.99);
$q$, '23514', 'T2 unbalanced lot rejected');

-- T3: cross-branch lot without IBR legs rejected (balanced overall, not per branch).
SELECT pg_temp.expect_fail($q$
 INSERT INTO ledger.transaction_lot (id,lot_type,business_date,value_date,reference,reverses,approval_id,created_by) VALUES ('00000000-0000-0000-0000-000000000003','X','2026-09-27','2026-09-27',NULL,NULL,NULL,'t');
 INSERT INTO ledger.account_entry (lot_id,business_date,branch_code,gl_code,account_no,side,amount) VALUES
  ('00000000-0000-0000-0000-000000000003','2026-09-27','HO','1200','B','DR',500),
  ('00000000-0000-0000-0000-000000000003','2026-09-27','MUM','1200','B','CR',500);
$q$, '23514', 'T3 per-branch balance enforced');

-- T4: single-entry lot rejected.
SELECT pg_temp.expect_fail($q$
 INSERT INTO ledger.transaction_lot (id,lot_type,business_date,value_date,reference,reverses,approval_id,created_by) VALUES ('00000000-0000-0000-0000-000000000004','X','2026-09-27','2026-09-27',NULL,NULL,NULL,'t');
$q$, '23514', 'T4 lot needs two entries');

-- T5: posting to a non-leaf head rejected.
SELECT pg_temp.expect_fail($q$
 INSERT INTO ledger.transaction_lot (id,lot_type,business_date,value_date,reference,reverses,approval_id,created_by) VALUES ('00000000-0000-0000-0000-000000000005','X','2026-09-27','2026-09-27',NULL,NULL,NULL,'t');
 INSERT INTO ledger.account_entry (lot_id,business_date,branch_code,gl_code,account_no,side,amount) VALUES
  ('00000000-0000-0000-0000-000000000005','2026-09-27','HO','1000','A','DR',1);
$q$, '23514', 'T5 non-posting GL rejected');

-- T6: frozen head rejected.
SELECT pg_temp.expect_fail($q$
 INSERT INTO ledger.transaction_lot (id,lot_type,business_date,value_date,reference,reverses,approval_id,created_by) VALUES ('00000000-0000-0000-0000-000000000006','X','2026-09-27','2026-09-27',NULL,NULL,NULL,'t');
 INSERT INTO ledger.account_entry (lot_id,business_date,branch_code,gl_code,account_no,side,amount) VALUES
  ('00000000-0000-0000-0000-000000000006','2026-09-27','HO','9999','A','DR',1);
$q$, '23514', 'T6 frozen GL rejected');

-- T7/T8: ledger is append-only.
SELECT pg_temp.expect_fail($q$ UPDATE ledger.account_entry SET amount = 1 $q$, '42501', 'T7 update blocked');
SELECT pg_temp.expect_fail($q$ DELETE FROM ledger.transaction_lot $q$, '42501', 'T8 delete blocked');

-- T9: zero / negative amounts rejected.
SELECT pg_temp.expect_fail($q$
 INSERT INTO ledger.transaction_lot (id,lot_type,business_date,value_date,reference,reverses,approval_id,created_by) VALUES ('00000000-0000-0000-0000-000000000009','X','2026-09-27','2026-09-27',NULL,NULL,NULL,'t');
 INSERT INTO ledger.account_entry (lot_id,business_date,branch_code,gl_code,account_no,side,amount) VALUES
  ('00000000-0000-0000-0000-000000000009','2026-09-27','HO','1100','A','DR',0);
$q$, '23514', 'T9 zero amount rejected');

-- T19: legs cannot be added to a committed lot.
SELECT pg_temp.expect_fail($q$
 INSERT INTO ledger.account_entry (lot_id,business_date,branch_code,gl_code,account_no,side,amount) VALUES
  ('00000000-0000-0000-0000-000000000001','2026-09-27','HO','1100','A','DR',5);
$q$, '23514', 'T19 committed lot is closed to new legs');

-- T10: entry date must equal lot date.
SELECT pg_temp.expect_fail($q$
 INSERT INTO ledger.transaction_lot (id,lot_type,business_date,value_date,reference,reverses,approval_id,created_by) VALUES ('00000000-0000-0000-0000-000000000010','X','2026-09-27','2026-09-27',NULL,NULL,NULL,'t');
 INSERT INTO ledger.account_entry (lot_id,business_date,branch_code,gl_code,account_no,side,amount) VALUES
  ('00000000-0000-0000-0000-000000000010','2026-09-26','HO','1100','A','DR',1),
  ('00000000-0000-0000-0000-000000000010','2026-09-26','HO','1200','B','CR',1);
$q$, '23514', 'T10 back-dated entry rejected');

-- T11: reversal lot commits; a lot can only be reversed once.
BEGIN;
INSERT INTO ledger.transaction_lot (id,lot_type,business_date,value_date,reference,reverses,approval_id,created_by) VALUES ('00000000-0000-0000-0000-000000000011','REVERSAL','2026-09-27','2026-09-27','test','00000000-0000-0000-0000-000000000001',NULL,'test');
INSERT INTO ledger.account_entry (lot_id,business_date,branch_code,gl_code,account_no,side,amount) VALUES
 ('00000000-0000-0000-0000-000000000011','2026-09-27','HO','1100','10010000000017','CR',100000),
 ('00000000-0000-0000-0000-000000000011','2026-09-27','HO','1200','1200','DR',99115),
 ('00000000-0000-0000-0000-000000000011','2026-09-27','HO','4100','4100','DR',750),
 ('00000000-0000-0000-0000-000000000011','2026-09-27','HO','2300','2300','DR',135);
COMMIT;
\echo PASS T11 reversal lot commits
SELECT pg_temp.expect_fail($q$
 INSERT INTO ledger.transaction_lot (id,lot_type,business_date,value_date,reference,reverses,approval_id,created_by) VALUES ('00000000-0000-0000-0000-000000000012','REVERSAL','2026-09-27','2026-09-27',NULL,'00000000-0000-0000-0000-000000000001',NULL,'t');
$q$, '23505', 'T12 double reversal rejected');

-- T13: trial balance nets to zero and account nets to zero after reversal.
DO $$
DECLARE s numeric; a numeric;
BEGIN
  SELECT sum(net) INTO s FROM ledger.trial_balance('2026-09-27');
  SELECT net_debit INTO a FROM ledger.account_balance WHERE account_no = '10010000000017';
  IF s <> 0 OR a <> 0 THEN RAISE EXCEPTION 'FAIL T13 trial balance % account %', s, a; END IF;
  RAISE NOTICE 'PASS T13 trial balance nets to zero';
END $$;

-- T14: overlapping number-series prefix rejected.
SELECT pg_temp.expect_fail($q$ UPDATE platform.number_series SET prefix = '20' WHERE family = 'TERM_DEPOSIT' $q$, '23514', 'T14 overlapping prefix rejected');

-- T15: maker cannot check own request.
SELECT pg_temp.expect_fail($q$
 INSERT INTO platform.approval_request (id,entity_type,action,payload,maker,status,checker,checked_at)
 VALUES (gen_random_uuid(),'LOAN_PRODUCT','CREATE','{}','alice','APPROVED','alice',now());
$q$, '23514', 'T15 maker-checker separation');

-- T16: audit chain verifies, and is immutable.
INSERT INTO audit.event (actor,action,entity_type,entity_id,detail) VALUES
 ('alice','LOGIN','USER','alice',NULL),('alice','CREATE','LOAN_PRODUCT','PL01','{"rate":18}'),('bob','APPROVE','LOAN_PRODUCT','PL01',NULL);
DO $$ BEGIN
  IF audit.verify_chain() IS NOT NULL THEN RAISE EXCEPTION 'FAIL T16 chain broken at %', audit.verify_chain(); END IF;
  RAISE NOTICE 'PASS T16 audit hash chain verifies';
END $$;
SELECT pg_temp.expect_fail($q$ UPDATE audit.event SET actor = 'mallory' WHERE id = 2 $q$, '42501', 'T17 audit immutable');

-- T18: loan cannot go ACTIVE without KFS acceptance.
SELECT pg_temp.expect_fail($q$
 INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch) VALUES ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST Customer','HO');
 INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,gl_principal,gl_interest_income,gl_interest_receivable)
   VALUES ('PL01','Personal loan','EQUATED',10000,500000,6,60,12,24,'1100','4100','1100');
 INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status)
   VALUES (gen_random_uuid(),'10010000000017','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,18,12,'2026-09-27','ACTIVE');
$q$, '23514', 'T18 KFS required before activation');
\echo ALL DB INVARIANT TESTS PASSED
