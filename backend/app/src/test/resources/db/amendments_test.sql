-- P2-3 loan amendments and restructuring (V14). Run on a fresh tenant database after migrations.
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

-- Maker-checker rules ---------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT checkers_required FROM platform.approval_rule
                       WHERE entity_type = 'LOAN_RESTRUCTURE' AND action = 'RESTRUCTURE' AND min_amount IS NULL) = 2,
                     'R1 a restructure needs two checkers');
SELECT pg_temp.check((SELECT checkers_required FROM platform.approval_rule
                       WHERE entity_type = 'LOAN_AMENDMENT' AND action = 'AMEND' AND min_amount IS NULL) = 1,
                     'R2 an amendment needs one checker');

-- Fixtures ---------------------------------------------------------------------------------------------------
INSERT INTO platform.branch VALUES ('HO','Head Office Kochi',NULL,'32',NULL,true,'ACTIVE');
INSERT INTO platform.business_day VALUES (1,'2026-10-20','OPEN');
SELECT ledger.load_starter_kit('NBFC');
SELECT ledger.load_lending_heads();
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status)
VALUES ('PL01','CLAUDE-TEST Personal loan','EQUATED',10000,500000,6,60,12,24,24,'1101','4101','1102','ACTIVE');
INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch) VALUES
  ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST One','HO');
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,kfs_accepted_at,state)
  VALUES ('00000000-0000-0000-0000-00000000aa01','10010000000017','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,18,12,'2026-06-30','ACTIVE',now(),'{}');
INSERT INTO lending.loan_txn (id,loan_id,seq,txn_type,value_date,business_date,state_before,created_by) VALUES
  ('00000000-0000-0000-0000-00000000bb01','00000000-0000-0000-0000-00000000aa01',1,'AMENDMENT','2026-10-20','2026-10-20','{}','checker1'),
  ('00000000-0000-0000-0000-00000000bb02','00000000-0000-0000-0000-00000000aa01',2,'RESTRUCTURE','2026-10-20','2026-10-20','{}','checker2'),
  ('00000000-0000-0000-0000-00000000bb03','00000000-0000-0000-0000-00000000aa01',3,'REVERSAL','2026-10-20','2026-10-20','{}','checker1'),
  ('00000000-0000-0000-0000-00000000bb04','00000000-0000-0000-0000-00000000aa01',4,'AMENDMENT','2026-10-20','2026-10-20','{}','checker1');
INSERT INTO platform.approval_request (id,entity_type,entity_id,action,payload,maker) VALUES
  ('00000000-0000-0000-0000-00000000cc01','LOAN_AMENDMENT','10010000000017','AMEND','{}','maker'),
  ('00000000-0000-0000-0000-00000000cc02','LOAN_RESTRUCTURE','10010000000017','RESTRUCTURE','{}','maker');

SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM lending.restructured_loan), 'C1 no restructured loans yet');
SELECT pg_temp.check((SELECT restructure_count FROM lending.loan_account WHERE loan_no = '10010000000017') = 0,
                     'C2 a loan starts unrestructured');

-- History ------------------------------------------------------------------------------------------------------
INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,
    rate_before,rate_after,maturity_before,maturity_after,interest_before,interest_after,applied_figures,approval_id,
    made_by,checked_by,business_date,reason)
VALUES ('00000000-0000-0000-0000-00000000dd01','00000000-0000-0000-0000-00000000aa01',1,'00000000-0000-0000-0000-00000000bb01',
        'RATE_CHANGE','{"kind":"RATE_CHANGE","newRatePercent":"21","rateOption":"KEEP_TENURE_CHANGE_EMI"}',9168,9290,9,9,
        18,21,'2027-06-30','2027-06-30',5000,5800,'{}','00000000-0000-0000-0000-00000000cc01','maker','checker1','2026-10-20','MCLR reset'),
       ('00000000-0000-0000-0000-00000000dd02','00000000-0000-0000-0000-00000000aa01',2,'00000000-0000-0000-0000-00000000bb02',
        'RESTRUCTURE','{"remainingInstalments":18}',9290,6100,9,18,21,21,'2027-06-30','2028-03-31',5800,9000,'{}',
        '00000000-0000-0000-0000-00000000cc02','maker','checker2','2026-10-20','hardship');
SELECT pg_temp.check((SELECT count(*) FROM lending.loan_amendment WHERE loan_id = '00000000-0000-0000-0000-00000000aa01') = 2,
                     'H1 amendments and restructures share one history');
SELECT pg_temp.expect_fail($q$
  INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,rate_before,
      rate_after,maturity_after,interest_before,interest_after,applied_figures,approval_id,made_by,checked_by,business_date)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-00000000aa01',3,'00000000-0000-0000-0000-00000000bb04','WRITE_OFF','{}',1,1,1,1,1,1,
          '2027-01-01',0,0,'{}','00000000-0000-0000-0000-00000000cc01','maker','checker1','2026-10-20') $q$,
  '23514', 'H2 unknown amendment kind rejected');
SELECT pg_temp.expect_fail($q$
  INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,rate_before,
      rate_after,maturity_after,interest_before,interest_after,applied_figures,approval_id,made_by,checked_by,business_date)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-00000000aa01',3,'00000000-0000-0000-0000-00000000bb04','TENURE_CHANGE','{}',1,1,1,1,1,1,
          '2027-01-01',0,0,'{}','00000000-0000-0000-0000-00000000cc01','maker','maker','2026-10-20') $q$,
  '23514', 'H3 maker cannot check their own amendment');
SELECT pg_temp.expect_fail($q$
  INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,rate_before,
      rate_after,maturity_after,interest_before,interest_after,applied_figures,approval_id,made_by,checked_by,business_date)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-00000000aa01',2,'00000000-0000-0000-0000-00000000bb04','TENURE_CHANGE','{}',1,1,1,1,1,1,
          '2027-01-01',0,0,'{}','00000000-0000-0000-0000-00000000cc01','maker','checker1','2026-10-20') $q$,
  '23505', 'H4 amendment sequence unique per loan');
SELECT pg_temp.expect_fail($q$
  INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,rate_before,
      rate_after,maturity_after,interest_before,interest_after,applied_figures,approval_id,made_by,checked_by,business_date)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-00000000aa01',3,'00000000-0000-0000-0000-00000000bb01','TENURE_CHANGE','{}',1,1,1,1,1,1,
          '2027-01-01',0,0,'{}','00000000-0000-0000-0000-00000000cc01','maker','checker1','2026-10-20') $q$,
  '23505', 'H5 one history row per loan transaction');
SELECT pg_temp.expect_fail($q$
  INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,rate_before,
      rate_after,maturity_after,interest_before,interest_after,applied_figures,approval_id,made_by,checked_by,business_date)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-00000000aa01',3,'00000000-0000-0000-0000-00000000bb04','TENURE_CHANGE','[1]',1,1,1,1,1,1,
          '2027-01-01',0,0,'{}','00000000-0000-0000-0000-00000000cc01','maker','checker1','2026-10-20') $q$,
  '23514', 'H6 parameters must be a JSON object');
SELECT pg_temp.expect_fail($q$
  INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,rate_before,
      rate_after,maturity_after,interest_before,interest_after,applied_figures,approval_id,made_by,checked_by,business_date)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-00000000aa01',3,'00000000-0000-0000-0000-00000000bb04','EMI_CHANGE','{}',1,0,1,1,1,1,
          '2027-01-01',0,0,'{}','00000000-0000-0000-0000-00000000cc01','maker','checker1','2026-10-20') $q$,
  '23514', 'H7 the new EMI must be positive');
SELECT pg_temp.expect_fail($q$
  INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,rate_before,
      rate_after,maturity_after,interest_before,interest_after,applied_figures,approval_id,made_by,checked_by,business_date)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-00000000aa01',3,'00000000-0000-0000-0000-00000000bb04','EMI_CHANGE','{}',1,1,1,1,1,1,
          '2027-01-01',0,0,'{}',NULL,'maker','checker1','2026-10-20') $q$,
  '23502', 'H8 every amendment links to its approval');

-- Immutability and reversal ------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_amendment SET emi_after = 1 WHERE seq = 1 $q$, '42501', 'I1 history is immutable');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.loan_amendment $q$, '42501', 'I2 history cannot be deleted');
UPDATE lending.loan_amendment SET reversed_by = '00000000-0000-0000-0000-00000000bb03' WHERE seq = 1;
SELECT pg_temp.check((SELECT reversed_by FROM lending.loan_amendment WHERE seq = 1) IS NOT NULL, 'I3 an amendment reversal is recorded once');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_amendment SET reversed_by = NULL WHERE seq = 1 $q$, '42501', 'I4 reversal link cannot be removed');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_amendment SET reversed_by = '00000000-0000-0000-0000-00000000bb03' WHERE seq = 2 $q$,
                           '23514', 'I5 a restructure cannot be marked reversed');

-- Restructured flag on the account -------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET restructured_on = '2026-10-20' WHERE loan_no = '10010000000017' $q$,
                           '23514', 'F1 restructure date and count go together');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET upgrade_not_before = '2028-01-31' WHERE loan_no = '10010000000017' $q$,
                           '23514', 'F2 a specified period needs a restructuring');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET restructure_count = -1 WHERE loan_no = '10010000000017' $q$,
                           '23514', 'F3 restructure count is not negative');
UPDATE lending.loan_account SET restructured_on = '2026-10-20', restructure_count = 1, upgrade_not_before = '2028-01-31',
       asset_class = 'SUBSTANDARD', npa_since = '2026-10-20', current_rate = 21
 WHERE loan_no = '10010000000017';
SELECT pg_temp.check((SELECT under_monitoring FROM lending.restructured_loan WHERE loan_no = '10010000000017'),
                     'F4 restructured loan listed as under monitoring');
UPDATE lending.loan_account SET upgrade_not_before = NULL, asset_class = 'STANDARD', npa_since = NULL WHERE loan_no = '10010000000017';
SELECT pg_temp.check(NOT (SELECT under_monitoring FROM lending.restructured_loan WHERE loan_no = '10010000000017'),
                     'F5 upgraded: stays listed as restructured, monitoring over');
SELECT pg_temp.check((SELECT count(*) FROM lending.restructured_loan) = 1, 'F6 only restructured loans are listed');
\echo ALL AMENDMENT TESTS PASSED
