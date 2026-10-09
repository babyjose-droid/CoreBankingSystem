-- V25: floating interest rate slab (SPREAD tables), elapsed-tenure rate steps and the tranche bullet method.
-- Run on a fresh tenant database after migrations.
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
INSERT INTO platform.business_day VALUES (1,'2026-10-01','OPEN');
SELECT ledger.load_starter_kit('NBFC');
SELECT ledger.load_lending_heads();

-- 1. Floating interest rate slab ---------------------------------------------------------------------------------
INSERT INTO lending.interest_table VALUES ('HLS','CLAUDE-TEST Home loan spreads',0,'SPREAD','2026-01-01'),
                                          ('PL1','CLAUDE-TEST Personal loan card',0,'ABSOLUTE','2026-01-01');
INSERT INTO lending.interest_slab VALUES ('HLS',10000,2500000,6,240,3.25),('HLS',2500000.01,10000000,6,240,2.75),
                                         ('PL1',10000,500000,6,60,18);
SELECT pg_temp.check((SELECT rate FROM lending.resolve_rate('HLS', 3000000, 120)) = 2.75, 'S1 a spread slab gives the spread');
SELECT pg_temp.check((SELECT explanation FROM lending.resolve_rate('HLS', 100000, 120)) = 'slab spread 3.25%', 'S2 explained as a spread');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.interest_table VALUES ('BAD','CLAUDE-TEST',7,'SPREAD','2026-01-01') $q$, '23514',
                           'S3 a spread table has no base rate: its base is the benchmark');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.interest_table VALUES ('BAD','CLAUDE-TEST',0,'OTHER','2026-01-01') $q$, '23514',
                           'S4 unknown mode');

INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status,rate_type,
                                  benchmark_code,spread,reset_frequency_months,interest_table_code)
VALUES ('HL02','CLAUDE-TEST Floating slab','EQUATED',10000,10000000,6,240,7,12,24,'1101','4101','1102','ACTIVE','FLOATING','REPO',NULL,3,'HLS');
SELECT pg_temp.check(true, 'S5 a benchmark-linked product takes its spread from a SPREAD table');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_product SET spread = 3 WHERE code = 'HL02' $q$, '23514',
                           'S6 one source of spread: the product or its table, not both');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_product SET interest_table_code = NULL WHERE code = 'HL02' $q$, '23514',
                           'S7 a benchmark-linked product needs a spread from somewhere');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_product SET interest_table_code = 'PL1' WHERE code = 'HL02' $q$, '23514',
                           'S8 a benchmark-linked product takes only a SPREAD table');
SELECT pg_temp.expect_fail($q$
  INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                    penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status,interest_table_code)
  VALUES ('PL09','CLAUDE-TEST','EQUATED',10000,500000,6,60,12,24,24,'1101','4101','1102','ACTIVE','HLS') $q$, '23514',
  'S9 a SPREAD table needs a benchmark to add to');

-- 2. Elapsed-tenure rate steps -----------------------------------------------------------------------------------
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status,rate_steps)
VALUES ('PL02','CLAUDE-TEST Stepped rate','EQUATED',10000,500000,12,60,10,14,24,'1101','4101','1102','ACTIVE',
        '[{"fromMonth":1,"ratePercent":10.5},{"fromMonth":13,"ratePercent":12}]');
SELECT pg_temp.check(jsonb_array_length((SELECT rate_steps FROM lending.loan_product WHERE code = 'PL02')) = 2, 'E1 steps are kept on the product');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_product SET rate_type = 'FLOATING' WHERE code = 'PL02' $q$, '23514',
                           'E2 a stepped rate is a fixed rate (RBI reset circular reading)');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_product SET repayment_method = 'FIXED_PRINCIPAL' WHERE code = 'PL02' $q$, '23514',
                           'E3 steps apply to EQUATED products');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_product SET multiple_disbursements = true WHERE code = 'PL02' $q$, '23514',
                           'E4 not with tranches');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_product SET rate_steps = '[{"fromMonth":1,"ratePercent":10}]' WHERE code = 'PL02' $q$,
                           '23514', 'E5 a table of one step is a plain rate');

-- 3. Tranche bullet -----------------------------------------------------------------------------------------------
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status,multiple_disbursements)
VALUES ('TB01','CLAUDE-TEST Tranche bullet','TRANCHE_BULLET',100000,5000000,3,24,10,16,24,'1101','4101','1102','ACTIVE',true);
SELECT pg_temp.check(true, 'B1 method 18 is a product method');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_product SET multiple_disbursements = false WHERE code = 'TB01' $q$, '23514',
                           'B2 a tranche bullet product is disbursed in tranches');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_product SET interest_basis = 'FLAT' WHERE code = 'TB01' $q$, '23514',
                           'B3 interest by days on the balance');

INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch,status) VALUES
  ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST Borrower','HO','ACTIVE');
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,created_by)
VALUES ('00000000-0000-0000-0000-00000000aa01','1001000000017','00000000-0000-0000-0000-0000000000c1','TB01','HO',300000,12,12,'2026-10-01',
        'SANCTIONED','maker');
INSERT INTO lending.loan_txn (id,loan_id,seq,txn_type,value_date,business_date,amount,state_before,created_by) VALUES
  ('00000000-0000-0000-0000-00000000bb01','00000000-0000-0000-0000-00000000aa01',1,'DISBURSEMENT','2026-10-01','2026-10-01',100000,'{}','system'),
  ('00000000-0000-0000-0000-00000000bb02','00000000-0000-0000-0000-00000000aa01',2,'DISBURSEMENT','2026-10-01','2026-10-01',50000,'{}','system');
INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, created_by, maturity_date)
VALUES ('00000000-0000-0000-0000-00000000aa01', 1, '00000000-0000-0000-0000-00000000bb01', '2026-10-01', 100000, 'maker', '2027-10-01');
SELECT pg_temp.check((SELECT maturity_date FROM lending.loan_tranche WHERE tranche_no = 1) = '2027-10-01', 'B4 a tranche keeps its maturity');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, created_by, maturity_date)
  VALUES ('00000000-0000-0000-0000-00000000aa01', 2, '00000000-0000-0000-0000-00000000bb02', '2026-10-01', 50000, 'maker', '2026-10-01') $q$,
  '23514', 'B5 a tranche matures after it is disbursed');

SELECT 'TESTS PASSED' AS result;
