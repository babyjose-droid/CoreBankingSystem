-- Phase 1 database rules (V7–V11). Run on a fresh tenant database after migrations.
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

-- Fixture ------------------------------------------------------------------------------------------------
INSERT INTO platform.branch VALUES ('HO','Head Office Kochi',NULL,'32',NULL,true,'ACTIVE'),('MUM','Mumbai',NULL,'27','HO',false,'ACTIVE');
INSERT INTO platform.business_day VALUES (1,'2026-10-09','OPEN');                  -- Friday
INSERT INTO platform.weekly_off VALUES (NULL,7,NULL),(NULL,6,2),(NULL,6,4);        -- Sunday; 2nd & 4th Saturday
INSERT INTO platform.holiday VALUES (NULL,'2026-10-12','CLAUDE-TEST holiday');     -- Monday
SELECT pg_temp.check(ledger.load_starter_kit('NBFC') = 51, 'S1 NBFC starter kit loads 51 heads');

-- Calendar and business date --------------------------------------------------------------------------------
SELECT pg_temp.check(NOT platform.is_working_day('2026-10-10') AND platform.is_working_day('2026-10-03'),
                     'C1 second Saturday off, first Saturday working');
SELECT pg_temp.check(platform.next_working_day('2026-10-09') = '2026-10-13', 'C2 next working day skips Sat(2nd)/Sun/holiday');
SELECT pg_temp.expect_fail($q$ UPDATE platform.business_day SET business_date = '2026-10-13' $q$, '42501', 'C3 business date cannot be changed directly');
SELECT pg_temp.check(platform.advance_business_date() = '2026-10-13', 'C4 advance_business_date moves to next working day');
SELECT pg_temp.check((SELECT business_date FROM platform.business_day) = '2026-10-13', 'C5 business date persisted');
SELECT pg_temp.expect_fail($q$
  SELECT set_config('corebanking.eod','on',false); UPDATE platform.business_day SET business_date = '2026-10-01';
$q$, '23514', 'C6 business date cannot move backwards');
SELECT set_config('corebanking.eod','off',false);
SELECT pg_temp.expect_fail($q$ DELETE FROM platform.business_day $q$, '42501', 'C7 business day row cannot be deleted');

-- Tax rates --------------------------------------------------------------------------------------------------
INSERT INTO platform.tax_rate VALUES ('GST18','GST',18,'2017-07-01',NULL),
                                     ('TDS194A','TDS',10,'2020-04-01','2026-03-31'),('TDS194A','TDS',7.5,'2026-04-01',NULL);
SELECT pg_temp.check(platform.tax_rate_on('TDS194A','2026-03-31') = 10 AND platform.tax_rate_on('TDS194A','2026-04-01') = 7.5,
                     'T1 tax rate chosen by value date');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.tax_rate VALUES ('GST18','GST',12,'2025-01-01',NULL) $q$, '23P01', 'T2 overlapping tax periods rejected');
SELECT pg_temp.expect_fail($q$ SELECT platform.tax_rate_on('GST18','2017-06-30') $q$, 'P0002', 'T3 no rate before effective date');

-- Branch close guard ----------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ UPDATE platform.branch SET status = 'CLOSED' WHERE code = 'HO' $q$, '23514', 'B1 head office cannot close');
INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch) VALUES
  ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST One','MUM');
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,gl_principal,gl_interest_income,gl_interest_receivable)
  VALUES ('PL01','Personal loan','EQUATED',10000,500000,6,60,12,24,'1101','4101','1102');
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,kfs_accepted_at,state)
  VALUES (gen_random_uuid(),'10010000000017','00000000-0000-0000-0000-0000000000c1','PL01','MUM',100000,18,12,'2026-10-13','ACTIVE',now(),'{}'::jsonb);
SELECT pg_temp.expect_fail($q$ UPDATE platform.branch SET status = 'CLOSED' WHERE code = 'MUM' $q$, '23514', 'B2 branch with live loans cannot close');

-- GL heads ----------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ INSERT INTO ledger.gl_head VALUES ('1105','X','ASSET','1101',true,'ACTIVE') $q$, '23514', 'G1 parent must be non-posting');
SELECT pg_temp.expect_fail($q$ INSERT INTO ledger.gl_head VALUES ('4199','X','EXPENSE','4000',true,'ACTIVE') $q$, '23514', 'G2 parent category must match');

-- Approvals ----------------------------------------------------------------------------------------------
INSERT INTO platform.approval_request (id,entity_type,action,payload,maker,branch_code,checkers_required)
  VALUES ('00000000-0000-0000-0000-00000000a001','VOUCHER','CREATE','{}','maker1','HO',2);
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.approval_decision (request_id,checker,decision) VALUES ('00000000-0000-0000-0000-00000000a001','MAKER1','APPROVE') $q$,
                           '23514', 'A1 maker cannot approve own request (case-insensitive)');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.approval_decision (request_id,checker,decision) VALUES ('00000000-0000-0000-0000-00000000a001','checker1','REJECT') $q$,
                           '23514', 'A2 rejection needs a note');
INSERT INTO platform.approval_decision (request_id,checker,decision) VALUES ('00000000-0000-0000-0000-00000000a001','checker1','APPROVE');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.approval_decision (request_id,checker,decision) VALUES ('00000000-0000-0000-0000-00000000a001','checker1','APPROVE') $q$,
                           '23505', 'A3 same checker cannot approve twice');
SELECT pg_temp.check((SELECT approvals_so_far FROM platform.approval_queue WHERE id = '00000000-0000-0000-0000-00000000a001') = 1,
                     'A4 queue shows approvals so far');
UPDATE platform.approval_request SET status = 'APPROVED', checker = 'checker2', checked_at = now() WHERE id = '00000000-0000-0000-0000-00000000a001';
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.approval_decision (request_id,checker,decision) VALUES ('00000000-0000-0000-0000-00000000a001','checker3','APPROVE') $q$,
                           '23514', 'A5 no decisions on a closed request');
SELECT pg_temp.expect_fail($q$ UPDATE platform.approval_decision SET decision = 'REJECT' $q$, '42501', 'A6 decisions are immutable');

-- Vouchers, statements, snapshot, gate -----------------------------------------------------------------------
BEGIN;
INSERT INTO ledger.transaction_lot (id,lot_type,business_date,value_date,created_by) VALUES
  ('00000000-0000-0000-0000-0000000000f1','VOUCHER','2026-10-13','2026-10-13','maker1');
INSERT INTO ledger.account_entry (lot_id,business_date,branch_code,gl_code,account_no,side,amount) VALUES
  ('00000000-0000-0000-0000-0000000000f1','2026-10-13','HO','1202','1202','DR',5000000),
  ('00000000-0000-0000-0000-0000000000f1','2026-10-13','HO','3101','3101','CR',5000000);
INSERT INTO ledger.voucher (id,voucher_no,voucher_type,value_date,business_date,description,amount,lot_id,maker,checker)
  VALUES ('00000000-0000-0000-0000-0000000000e1','80010000000019','JOURNAL','2026-10-13','2026-10-13','CLAUDE-TEST capital',5000000,
          '00000000-0000-0000-0000-0000000000f1','maker1','checker1');
COMMIT;
BEGIN;   -- processing fee ₹750 + GST 18% intra-state (CGST 9% + SGST 9%) collected in bank
INSERT INTO ledger.transaction_lot (id,lot_type,business_date,value_date,created_by) VALUES
  ('00000000-0000-0000-0000-0000000000f2','VOUCHER','2026-10-13','2026-10-13','maker1');
INSERT INTO ledger.account_entry (lot_id,business_date,branch_code,gl_code,account_no,side,amount) VALUES
  ('00000000-0000-0000-0000-0000000000f2','2026-10-13','HO','1203','1203','DR',885),
  ('00000000-0000-0000-0000-0000000000f2','2026-10-13','HO','4102','4102','CR',750),
  ('00000000-0000-0000-0000-0000000000f2','2026-10-13','HO','2201','2201','CR',67.5),
  ('00000000-0000-0000-0000-0000000000f2','2026-10-13','HO','2202','2202','CR',67.5);
COMMIT;
BEGIN;   -- expense ₹200 paid from bank
INSERT INTO ledger.transaction_lot (id,lot_type,business_date,value_date,created_by) VALUES
  ('00000000-0000-0000-0000-0000000000f3','VOUCHER','2026-10-13','2026-10-13','maker1');
INSERT INTO ledger.account_entry (lot_id,business_date,branch_code,gl_code,account_no,side,amount) VALUES
  ('00000000-0000-0000-0000-0000000000f3','2026-10-13','HO','5106','5106','DR',200),
  ('00000000-0000-0000-0000-0000000000f3','2026-10-13','HO','1202','1202','CR',200);
COMMIT;

SELECT pg_temp.expect_fail($q$ UPDATE ledger.voucher SET description = 'edited' $q$, '42501', 'V1 posted voucher cannot be edited');
SELECT pg_temp.expect_fail($q$ DELETE FROM ledger.voucher $q$, '42501', 'V2 voucher cannot be deleted');
SELECT pg_temp.expect_fail($q$ UPDATE ledger.voucher SET status = 'REVERSED' $q$, '23514', 'V3 reversed voucher must carry its reversal lot');

SELECT pg_temp.check((SELECT amount FROM ledger.profit_and_loss('2026-10-01','2026-10-31') WHERE section = 'NET_PROFIT') = 550,
                     'R1 P&L net profit = 750 fee − 200 expense');
SELECT pg_temp.check((SELECT section FROM ledger.profit_and_loss('2026-10-01','2026-10-31') LIMIT 1) = 'INCOME',
                     'R2 P&L lists income first');
SELECT pg_temp.check(
  (SELECT sum(amount) FILTER (WHERE section = 'ASSET') FROM ledger.balance_sheet('2026-10-13')) =
  (SELECT sum(amount) FILTER (WHERE section IN ('LIABILITY','EQUITY')) FROM ledger.balance_sheet('2026-10-13')),
  'R3 balance sheet balances (assets = liabilities + equity incl. current P&L)');
SELECT pg_temp.check((SELECT amount FROM ledger.balance_sheet('2026-10-13') WHERE gl_code = 'P&L') = 550, 'R4 unclosed P&L shown in equity');
SELECT pg_temp.check((SELECT sum(net) FROM ledger.trial_balance('2026-10-13','HO')) = 0, 'R5 branch trial balance nets to zero');
SELECT pg_temp.check((SELECT count(*) FROM ledger.gl_entries('4102','2026-10-01','2026-10-31')) = 1, 'R6 drill-down returns entries for a head');
SELECT pg_temp.check(ledger.snapshot_balances('2026-10-13') = ledger.snapshot_balances('2026-10-13'), 'R7 balance snapshot is idempotent');
SELECT pg_temp.check((SELECT closing_net FROM ledger.gl_daily_balance WHERE gl_code = '1202' AND branch_code = 'HO') = 4999800,
                     'R8 snapshot closing balance');
DO $$ BEGIN PERFORM ledger.trial_balance_gate('2026-10-13'); RAISE NOTICE 'PASS R9 trial balance gate passes on balanced books'; END $$;

-- EOD single instance and one success per date -----------------------------------------------------------------
INSERT INTO platform.eod_run (business_date,status) VALUES ('2026-10-13','RUNNING');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.eod_run (business_date,status) VALUES ('2026-10-13','RUNNING') $q$, '23505', 'E1 only one EOD can run at a time');
UPDATE platform.eod_run SET status = 'COMPLETED', finished_at = now();
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.eod_run (business_date,status) VALUES ('2026-10-13','COMPLETED') $q$, '23505', 'E2 a date is closed only once');
SELECT pg_temp.expect_fail($q$ UPDATE platform.eod_schedule SET mode = 'SCHEDULED' $q$, '23514', 'E3 scheduled mode needs a cron');

-- Dedupe ------------------------------------------------------------------------------------------------
UPDATE customer.customer SET pan_hash = '\x01', mobile_hash = '\x02', name_dob_hash = '\x03' WHERE customer_no = '90010000000013';
INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch,mobile_hash,name_dob_hash) VALUES
  (gen_random_uuid(),'90010000000021','INDIVIDUAL','CLAUDE-TEST Two','HO','\x02','\x09');
SELECT pg_temp.check((SELECT string_agg(customer_no || ':' || rule || ':' || strength, ',') FROM customer.find_duplicates('\x01','\x02','\x03'))
                     = '90010000000013:PAN:EXACT,90010000000013:MOBILE:STRONG,90010000000021:MOBILE:STRONG,90010000000013:NAME_DOB:POSSIBLE',
                     'D1 dedupe returns every rule, strongest first');
SELECT pg_temp.check((SELECT count(*) FROM customer.find_duplicates('\xff',NULL,NULL)) = 0, 'D2 no false matches');
SELECT pg_temp.expect_fail($q$ INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch,pan_hash)
                              VALUES (gen_random_uuid(),'90010000000039','INDIVIDUAL','dup','HO','\x01') $q$, '23505', 'D3 PAN is unique');
\echo ALL PHASE 1 RULE TESTS PASSED
