-- Interest tables as the maker-checker applier writes them (InterestTableService): rows are replaced whole, bands cannot
-- overlap, and the product trigger's message names the "interest table" (ProblemHandler maps it to 422).
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

INSERT INTO lending.interest_table VALUES ('IT1','CLAUDE-TEST spreads',0,'SPREAD','2026-01-01');
INSERT INTO lending.interest_slab VALUES ('IT1',0,100000,12,60,3),('IT1',100000.01,500000,12,60,2.5);
-- applier replace: delete the rows, insert the new ones in the same transaction
BEGIN;
DELETE FROM lending.interest_slab WHERE table_code = 'IT1';
INSERT INTO lending.interest_slab VALUES ('IT1',0,50000,6,36,3.5),('IT1',50000.01,500000,6,36,3),('IT1',0,50000,37,120,4);
COMMIT;
SELECT pg_temp.check((SELECT count(*) FROM lending.interest_slab WHERE table_code = 'IT1') = 3, 'T1 rows replaced whole');
SELECT pg_temp.check((SELECT rate FROM lending.resolve_rate('IT1', 40000, 60)) = 4, 'T2 the new bands are used');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.interest_slab VALUES ('IT1',50000,60000,6,12,3) $q$, '23P01', 'T3 overlapping bands are refused by the database too');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.interest_slab VALUES ('IT1',10,5,6,12,3) $q$, '23514', 'T4 an inverted amount band');

INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status,rate_type,
                                  benchmark_code,spread,reset_frequency_months,interest_table_code)
VALUES ('HL03','CLAUDE-TEST floating','EQUATED',10000,10000000,6,240,7,12,24,'1101','4101','1102','ACTIVE','FLOATING','REPO',NULL,3,'IT1');
DO $$ BEGIN
  BEGIN
    INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                      penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status,interest_table_code)
    VALUES ('PL10','CLAUDE-TEST fixed','EQUATED',10000,500000,6,60,12,24,24,'1101','4101','1102','ACTIVE','IT1');
    RAISE EXCEPTION 'FAIL T5 no error';
  EXCEPTION WHEN check_violation THEN
    IF SQLERRM NOT LIKE '%interest table%' THEN RAISE EXCEPTION 'FAIL T5 message does not name the interest table: %', SQLERRM; END IF;
    RAISE NOTICE 'PASS T5 SPREAD table on a non-benchmark product: check violation naming the interest table';
  END;
END $$;

-- A loan on a floating-rate slab product stores the spread resolved from the table (V18 loan_floating_link).
INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch,status)
  VALUES ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST Borrower','HO','ACTIVE');
INSERT INTO lending.benchmark_rate (benchmark_code, effective_from, rate, recorded_by) VALUES ('REPO', '2026-01-01', 6.00, 'CLAUDE-TEST');
SELECT pg_temp.check((SELECT rate FROM lending.resolve_rate('IT1', 40000, 60)) = 4, 'T6 the slab spread for the loan');
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,created_by,
                                  benchmark_code,spread,reset_frequency_months)
  VALUES ('00000000-0000-0000-0000-00000000aa01','10010000000017','00000000-0000-0000-0000-0000000000c1','HL03','HO',40000,10,60,'2026-10-01','SANCTIONED','maker',
          'REPO',4,3);
SELECT pg_temp.check((SELECT spread FROM lending.loan_account WHERE loan_no = '10010000000017') = 4, 'T7 a slab loan carries the resolved spread');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,created_by,
                                  benchmark_code,spread,reset_frequency_months)
  VALUES ('00000000-0000-0000-0000-00000000aa02','10010000000025','00000000-0000-0000-0000-0000000000c1','HL03','HO',40000,10,60,'2026-10-01','SANCTIONED','maker',
          'REPO',NULL,3) $q$, '23514', 'T8 a benchmark loan without a spread is refused (what a slab loan did before it stored the resolved spread)');
SELECT 'TESTS PASSED' AS result;
