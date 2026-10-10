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
SELECT 'TESTS PASSED' AS result;
