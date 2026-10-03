-- V21: tranches (P2-6) and payouts (P2-2) together — a reversed disbursement can be made again, and every tranche
-- has its own payout. Run on a fresh tenant database after migrations. All data is fake (CLAUDE-TEST).
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
INSERT INTO platform.business_day VALUES (1,'2026-10-30','OPEN');
SELECT ledger.load_starter_kit('NBFC');
SELECT ledger.load_lending_heads();
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status)
VALUES ('PL01','CLAUDE-TEST Personal loan','EQUATED',10000,500000,6,60,12,24,24,'1101','4101','1102','ACTIVE');
INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch,status) VALUES
  ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST Borrower','HO','ACTIVE');
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,created_by) VALUES
  ('00000000-0000-0000-0000-00000000aa01','1001000000017','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,18,12,'2026-10-30','SANCTIONED','maker'),
  ('00000000-0000-0000-0000-00000000aa02','1001000000025','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,18,12,'2026-10-30','SANCTIONED','maker');
INSERT INTO lending.loan_txn (id,loan_id,seq,txn_type,value_date,business_date,amount,state_before,created_by) VALUES
  ('00000000-0000-0000-0000-00000000bb01','00000000-0000-0000-0000-00000000aa01',1,'DISBURSEMENT','2026-10-30','2026-10-30',100000,'{}','system'),
  ('00000000-0000-0000-0000-00000000bb02','00000000-0000-0000-0000-00000000aa01',2,'REVERSAL','2026-10-30','2026-10-30',NULL,'{}','system'),
  ('00000000-0000-0000-0000-00000000bb03','00000000-0000-0000-0000-00000000aa01',3,'DISBURSEMENT','2026-10-30','2026-10-30',60000,'{}','system'),
  ('00000000-0000-0000-0000-00000000bb04','00000000-0000-0000-0000-00000000aa01',4,'DISBURSEMENT','2026-10-30','2026-10-30',40000,'{}','system'),
  ('00000000-0000-0000-0000-00000000bb05','00000000-0000-0000-0000-00000000aa02',1,'REVERSAL','2026-10-30','2026-10-30',NULL,'{}','system'),
  ('00000000-0000-0000-0000-00000000bb06','00000000-0000-0000-0000-00000000aa01',5,'REPAYMENT','2026-10-30','2026-10-30',5200,'{}','system');

-- 1. A reversed disbursement leaves its tranche as history and frees the number and the amount ---------------------
INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, fees_deducted, created_by)
VALUES ('00000000-0000-0000-0000-00000000aa01', 1, '00000000-0000-0000-0000-00000000bb01', '2026-10-30', 100000, 1770, 'maker');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, created_by)
  VALUES ('00000000-0000-0000-0000-00000000aa01', 1, '00000000-0000-0000-0000-00000000bb03', '2026-10-30', 60000, 'maker') $q$,
  '23514', 'T1 while the first disbursement stands there is no second tranche 1');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_tranche SET amount = 1 $q$, '42501', 'T2 a tranche cannot be edited');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_tranche SET reversed_by = '00000000-0000-0000-0000-00000000bb02', amount = 1 $q$,
  '42501', 'T3 nor edited while it is marked reversed');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.loan_tranche $q$, '42501', 'T4 nor deleted');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_tranche SET reversed_by = '00000000-0000-0000-0000-00000000bb06' $q$,
  '23514', 'T5 only a REVERSAL transaction reverses a tranche');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_tranche SET reversed_by = '00000000-0000-0000-0000-00000000bb05' $q$,
  '23514', 'T6 and only one of the same loan');
UPDATE lending.loan_tranche SET reversed_by = '00000000-0000-0000-0000-00000000bb02' WHERE txn_id = '00000000-0000-0000-0000-00000000bb01';
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_tranche SET reversed_by = NULL $q$, '42501', 'T7 a reversed tranche stays reversed');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, created_by, reversed_by)
  VALUES ('00000000-0000-0000-0000-00000000aa01', 1, '00000000-0000-0000-0000-00000000bb03', '2026-10-30', 60000, 'maker', '00000000-0000-0000-0000-00000000bb02') $q$,
  '23514', 'T8 a tranche is not created reversed');
INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, created_by)
VALUES ('00000000-0000-0000-0000-00000000aa01', 1, '00000000-0000-0000-0000-00000000bb03', '2026-10-30', 60000, 'maker');
INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, created_by)
VALUES ('00000000-0000-0000-0000-00000000aa01', 2, '00000000-0000-0000-0000-00000000bb04', '2026-10-30', 40000, 'maker');
SELECT pg_temp.check((SELECT count(*) = 3 AND count(*) FILTER (WHERE reversed_by IS NULL) = 2
                             AND sum(amount) FILTER (WHERE reversed_by IS NULL) = 100000
                        FROM lending.loan_tranche WHERE loan_id = '00000000-0000-0000-0000-00000000aa01'),
  'T9 after the reversal the loan is disbursed again, in tranches numbered from 1, up to the sanctioned amount');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, created_by)
  VALUES ('00000000-0000-0000-0000-00000000aa01', 3, '00000000-0000-0000-0000-00000000bb06', '2026-10-30', 1, 'maker') $q$,
  '23514', 'T10 live tranches still cannot exceed the sanctioned amount');

-- 2. One live payout per disbursement ------------------------------------------------------------------------------
INSERT INTO integration.beneficiary (id, loan_id, customer_id, holder_name_cipher, account_cipher, account_last4, account_hash, ifsc, created_by)
VALUES ('00000000-0000-0000-0000-0000000b0001','00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000c1','\x01','\x02','1234','\x03','TEST0000001','maker');
INSERT INTO integration.payout_instruction (id, reference, loan_id, loan_no, customer_id, branch_code, attempt_no, amount, beneficiary_id, disbursement_txn) VALUES
  ('00000000-0000-0000-0000-0000000d0001','PO1001000000017A1','00000000-0000-0000-0000-00000000aa01','1001000000017','00000000-0000-0000-0000-0000000000c1','HO',1,60000,
   '00000000-0000-0000-0000-0000000b0001','00000000-0000-0000-0000-00000000bb03');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.payout_instruction (id, reference, loan_id, loan_no, customer_id, branch_code, attempt_no, amount, disbursement_txn)
  VALUES (gen_random_uuid(),'PO1001000000017A2','00000000-0000-0000-0000-00000000aa01','1001000000017','00000000-0000-0000-0000-0000000000c1','HO',2,60000,
          '00000000-0000-0000-0000-00000000bb03') $q$,
  '23505', 'P1 a disbursement never has two payouts in flight');
INSERT INTO integration.payout_instruction (id, reference, loan_id, loan_no, customer_id, branch_code, attempt_no, amount, beneficiary_id, disbursement_txn) VALUES
  ('00000000-0000-0000-0000-0000000d0002','PO1001000000017A2','00000000-0000-0000-0000-00000000aa01','1001000000017','00000000-0000-0000-0000-0000000000c1','HO',2,40000,
   '00000000-0000-0000-0000-0000000b0001','00000000-0000-0000-0000-00000000bb04');
SELECT pg_temp.check((SELECT count(*) = 2 FROM integration.payout_instruction WHERE loan_id = '00000000-0000-0000-0000-00000000aa01' AND status = 'INITIATED'),
  'P2 the second tranche of the same loan has its own payout');
INSERT INTO integration.payout_instruction (id, reference, loan_id, loan_no, customer_id, branch_code, attempt_no, amount) VALUES
  (gen_random_uuid(),'PO1001000000025A1','00000000-0000-0000-0000-00000000aa02','1001000000025','00000000-0000-0000-0000-0000000000c1','HO',1,1000);
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.payout_instruction (id, reference, loan_id, loan_no, customer_id, branch_code, attempt_no, amount)
  VALUES (gen_random_uuid(),'PO1001000000025A2','00000000-0000-0000-0000-00000000aa02','1001000000025','00000000-0000-0000-0000-0000000000c1','HO',2,1000) $q$,
  '23505', 'P3 payouts without a disbursement transaction stay one per loan');
\echo ALL V21 ALIGNMENT TESTS PASSED
