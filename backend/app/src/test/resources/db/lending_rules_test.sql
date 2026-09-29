-- Phase 2 lending database rules (V12). Run on a fresh tenant database after migrations.
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

INSERT INTO platform.branch VALUES ('HO','Head Office Kochi',NULL,'32',NULL,true,'ACTIVE');
INSERT INTO platform.business_day VALUES (1,'2026-06-30','OPEN');
SELECT ledger.load_starter_kit('NBFC');
SELECT ledger.load_lending_heads();
SELECT pg_temp.check(EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '2305' AND is_posting), 'L1 interest suspense head available');
SELECT pg_temp.check((SELECT count(*) FROM lending.provisioning_rate) = 18, 'L2 provisioning starter table loaded');
SELECT pg_temp.check((SELECT rate FROM lending.provisioning_rate WHERE asset_class = 'DOUBTFUL1' AND NOT secured) = 100,
                     'L3 unsecured doubtful provisioned at 100%');

-- Interest tables ----------------------------------------------------------------------------------------------
INSERT INTO lending.interest_table VALUES ('FD1','Deposit card',7,'ADDITIVE','2026-01-01'),('PL1','Personal loan card',0,'ABSOLUTE','2026-01-01');
INSERT INTO lending.interest_slab VALUES ('FD1',1,100000000,1,120,7.5),('PL1',10000,200000,6,24,18),('PL1',200000.01,500000,6,60,16);
SELECT pg_temp.check((SELECT rate FROM lending.resolve_rate('FD1', 20000, 12)) = 14.5, 'I1 additive: base 7 + slab 7.5 = 14.5 (reference behaviour)');
SELECT pg_temp.check((SELECT explanation FROM lending.resolve_rate('FD1', 20000, 12)) LIKE 'base 7%', 'I2 rate resolution is explained');
SELECT pg_temp.check((SELECT rate FROM lending.resolve_rate('PL1', 300000, 36)) = 16, 'I3 absolute slab rate');
SELECT pg_temp.expect_fail($q$ SELECT * FROM lending.resolve_rate('PL1', 5000, 12) $q$, 'P0002', 'I4 no slab for amount is an error');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.interest_slab VALUES ('PL1',150000,250000,12,24,17) $q$, '23P01', 'I5 overlapping slabs rejected');

-- Products and fee rules -------------------------------------------------------------------------------------
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status,interest_table_code)
VALUES ('PL01','CLAUDE-TEST Personal loan','EQUATED',10000,500000,6,60,12,24,24,'1101','4101','1102','ACTIVE','PL1');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_product SET repayment_method = 'OVERDRAFT' $q$, '23514', 'P1 unsupported repayment method rejected');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_product SET appropriation_sequence = ARRAY['INTEREST','PRINCIPAL'] $q$, '23514', 'P2 appropriation sequence must list every component');
INSERT INTO lending.fee_rule (product_code,code,name,event,calc_type,percent,min_amount,max_amount,deduct_from_disbursal)
  VALUES ('PL01','PF','Processing fee','DISBURSEMENT','PERCENT',0.75,500,10000,true);
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_rule (product_code,code,name,event,calc_type) VALUES ('PL01','BNC','Bounce','BOUNCE','FIXED') $q$,
                           '23514', 'F1 fixed fee needs an amount');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_rule (product_code,code,name,event,calc_type,amount,deduct_from_disbursal) VALUES ('PL01','FC','Foreclosure','PRECLOSURE','FIXED',100,true) $q$,
                           '23514', 'F2 only disbursement fees can be deducted from the payout');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_rule (product_code,code,name,event,calc_type,slabs) VALUES ('PL01','LATE','Late fee','LATE_PAYMENT','SLAB','{}') $q$,
                           '23514', 'F3 slab fee needs a slab array');

-- Loan accounts and transactions -----------------------------------------------------------------------------
INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch) VALUES
  ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST One','HO');
SELECT pg_temp.expect_fail($q$
  INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,kfs_accepted_at)
  VALUES (gen_random_uuid(),'10010000000017','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,18,12,'2026-06-30','ACTIVE',now())
$q$, '23514', 'A1 an active loan must carry engine state');
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,kfs_accepted_at,state,asset_class,npa_since)
  VALUES ('00000000-0000-0000-0000-00000000aa01','10010000000017','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,18,12,'2026-06-30','ACTIVE',now(),'{}','STANDARD',NULL),
         ('00000000-0000-0000-0000-00000000aa02','10010000000025','00000000-0000-0000-0000-0000000000c1','PL01','HO',50000,18,12,'2026-06-30','ACTIVE',now(),'{}','SUBSTANDARD','2026-06-01');
SELECT pg_temp.check((SELECT worst_class FROM lending.borrower_class WHERE customer_id = '00000000-0000-0000-0000-0000000000c1') = 'SUBSTANDARD',
                     'A2 borrower class is the worst of the borrower''s loans');

INSERT INTO lending.loan_txn (id,loan_id,seq,txn_type,value_date,business_date,amount,state_before,created_by) VALUES
  ('00000000-0000-0000-0000-00000000bb01','00000000-0000-0000-0000-00000000aa01',1,'DISBURSEMENT','2026-06-30','2026-06-30',100000,'{}','maker'),
  ('00000000-0000-0000-0000-00000000bb02','00000000-0000-0000-0000-00000000aa01',2,'REPAYMENT','2026-07-31','2026-07-31',9168,'{}','ops');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_txn SET amount = 1 WHERE seq = 2 $q$, '42501', 'T1 loan transactions are immutable');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.loan_txn $q$, '42501', 'T2 loan transactions cannot be deleted');
INSERT INTO lending.loan_txn (id,loan_id,seq,txn_type,value_date,business_date,amount,state_before,reverses,created_by) VALUES
  ('00000000-0000-0000-0000-00000000bb03','00000000-0000-0000-0000-00000000aa01',3,'REVERSAL','2026-07-31','2026-07-31',9168,'{}',
   '00000000-0000-0000-0000-00000000bb02','checker');
UPDATE lending.loan_txn SET reversed_by = '00000000-0000-0000-0000-00000000bb03' WHERE id = '00000000-0000-0000-0000-00000000bb02';
SELECT pg_temp.check((SELECT reversed_by FROM lending.loan_txn WHERE seq = 2) IS NOT NULL, 'T3 reversal link can be recorded once');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_txn SET reversed_by = NULL WHERE seq = 2 $q$, '42501', 'T4 reversal link cannot be removed');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_txn (id,loan_id,seq,txn_type,value_date,business_date,state_before,created_by)
                              VALUES (gen_random_uuid(),'00000000-0000-0000-0000-00000000aa01',3,'X','2026-07-31','2026-07-31','{}','x') $q$,
                           '23505', 'T5 transaction sequence unique per loan');
INSERT INTO lending.dpd_history VALUES ('00000000-0000-0000-0000-00000000aa01','2026-07-31',1,'SMA0',100000,9168);
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.dpd_history VALUES ('00000000-0000-0000-0000-00000000aa01','2026-07-31',2,'SMA0',100000,9168) $q$,
                           '23505', 'H1 one classification per loan per day');
\echo ALL LENDING RULE TESTS PASSED
