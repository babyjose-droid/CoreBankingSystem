-- P2-6 product completion (V18): product parameters for the new repayment methods and rate bases, benchmarks,
-- product templates, tranches, the new amendment kinds, and GST credit notes. Run on a fresh tenant database after
-- migrations. All names are test data (CLAUDE-TEST).
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
-- Posts one balanced lot: lines are [side, gl, account, amount, narration]. Returns the lot id.
CREATE FUNCTION pg_temp.post(p_id uuid, p_type text, p_date date, p_branch text, p_lines jsonb) RETURNS uuid LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (p_id, p_type, p_date, p_date, 'test');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount, narration)
  SELECT p_id, p_date, p_branch, l->>1, l->>2, l->>0, (l->>3)::numeric, l->>4 FROM jsonb_array_elements(p_lines) WITH ORDINALITY x(l, o) ORDER BY o;
  RETURN p_id;
END $$;
CREATE FUNCTION pg_temp.txn(p_id uuid, p_loan uuid, p_seq int, p_type text, p_date date, p_amount numeric, p_lots uuid[], p_state jsonb)
RETURNS void LANGUAGE sql AS $$
  INSERT INTO lending.loan_txn (id, loan_id, seq, txn_type, value_date, business_date, amount, lot_ids, state_before, created_by)
  VALUES (p_id, p_loan, p_seq, p_type, p_date, p_date, p_amount, p_lots, p_state, 'maker')
$$;
-- A product row with the columns a test varies; the rest take their defaults.
CREATE FUNCTION pg_temp.product(p_code text, p_extra jsonb) RETURNS void LANGUAGE sql AS $$
  INSERT INTO lending.loan_product (code, name, repayment_method, min_amount, max_amount, min_tenor_months, max_tenor_months, min_rate, max_rate,
      gl_principal, gl_interest_income, gl_interest_receivable, status, rate_type, day_count, frequency, interest_basis, bpi_mode, step_percent,
      step_every, principal_every, multiple_disbursements, pre_emi, top_up_allowed, benchmark_code, spread, reset_frequency_months)
  VALUES (p_code, 'CLAUDE-TEST ' || p_code, coalesce(p_extra->>'method', 'EQUATED'), 10000, 500000, 6, 60, 12, 24, '1101', '4101', '1102', 'ACTIVE',
      coalesce(p_extra->>'rateType', 'FIXED'), coalesce(p_extra->>'dayCount', 'ACTUAL_365'), coalesce(p_extra->>'frequency', 'MONTHLY'),
      coalesce(p_extra->>'basis', 'DAILY_REDUCING'), coalesce(p_extra->>'bpi', 'NONE'), (p_extra->>'stepPercent')::numeric,
      (p_extra->>'stepEvery')::int, coalesce((p_extra->>'principalEvery')::int, 1), coalesce((p_extra->>'tranches')::boolean, false),
      coalesce((p_extra->>'preEmi')::boolean, false), coalesce((p_extra->>'topUp')::boolean, false), p_extra->>'benchmark',
      (p_extra->>'spread')::numeric, (p_extra->>'reset')::int)
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Fixture: two branches (Kerala 32, Maharashtra 27). Business date 10-Sep-2026.
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.branch VALUES ('HO','Head Office Kochi',NULL,'32',NULL,true,'ACTIVE'), ('MUM','Mumbai',NULL,'27','HO',false,'ACTIVE');
INSERT INTO platform.business_day VALUES (1,'2026-09-10','OPEN');
SELECT ledger.load_starter_kit('NBFC');
SELECT ledger.load_lending_heads();
INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch) VALUES
  ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST One','HO');

-- ---------------------------------------------------------------------------------------------------------
-- P: product parameters
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.product('PL01', '{}');
SELECT pg_temp.check((SELECT (frequency, interest_basis, bpi_mode, principal_every, multiple_disbursements, pre_emi, top_up_allowed)
                           = ('MONTHLY', 'DAILY_REDUCING', 'NONE', 1, false, false, false) FROM lending.loan_product WHERE code = 'PL01'),
                     'P1 a product as before P2-6: monthly, daily reducing, single disbursement');
SELECT pg_temp.product('STEP01', '{"method":"STEP_EQUATED","stepPercent":10,"stepEvery":12}');
SELECT pg_temp.product('STR01', '{"method":"STRUCTURED","frequency":"HALF_YEARLY"}');
SELECT pg_temp.product('WK01', '{"frequency":"WEEKLY","dayCount":"ACTUAL_364"}');
SELECT pg_temp.product('FL01', '{"basis":"FLAT"}');
SELECT pg_temp.product('FP03', '{"method":"FIXED_PRINCIPAL","principalEvery":3}');
SELECT pg_temp.check((SELECT count(*) FROM lending.loan_product WHERE code IN ('STEP01','STR01','WK01','FL01','FP03')) = 5,
                     'P2 step, structured, weekly, flat and differing-interval products are accepted');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD01', '{"method":"STEP_EQUATED"}') $q$, '23514', 'P3 a step product needs its step');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD02', '{"stepPercent":10,"stepEvery":12}') $q$, '23514', 'P4 steps only on a step product');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD03', '{"method":"STEP_EQUATED","stepPercent":0,"stepEvery":12}') $q$, '23514', 'P5 a step of zero is refused');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD04', '{"method":"FIXED_PRINCIPAL","basis":"FLAT"}') $q$, '23514', 'P6 a flat rate is for equated loans');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD05', '{"principalEvery":3}') $q$, '23514', 'P7 principal interval only on fixed-principal loans');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD06', '{"method":"STRUCTURED","basis":"PERIODIC_REDUCING"}') $q$, '23514', 'P8 a structured loan accrues daily');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD07', '{"frequency":"BIMONTHLY"}') $q$, '23514', 'P9 unknown frequency refused');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD08', '{"method":"OVERDRAFT"}') $q$, '23514', 'P10 overdraft is not a repayment method here');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD09', '{"dayCount":"ACTUAL_999"}') $q$, '23514', 'P11 unknown day count refused');
DO $$
DECLARE d text; n int := 0;
BEGIN
  FOREACH d IN ARRAY ARRAY['ACTUAL_365','ACTUAL_360','ACTUAL_ACTUAL','THIRTY_360','THIRTY_E_360','ACTUAL_366','ACTUAL_364','ACTUAL_336','ACTUAL_372'] LOOP
    n := n + 1;
    PERFORM pg_temp.product('DC0' || n, jsonb_build_object('dayCount', d));
  END LOOP;
END $$;
SELECT pg_temp.check((SELECT count(DISTINCT day_count) FROM lending.loan_product WHERE code LIKE 'DC0%') = 9, 'P12 all nine day-count conventions are accepted');
-- tranches and pre-EMI
SELECT pg_temp.product('HL01', '{"tranches":true,"preEmi":true,"topUp":true}');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD10', '{"preEmi":true}') $q$, '23514', 'P13 pre-EMI needs disbursement in tranches');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD11', '{"method":"FIXED_PRINCIPAL","tranches":true,"preEmi":true}') $q$, '23514', 'P14 pre-EMI is for equated loans');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD12', '{"method":"STEP_EQUATED","stepPercent":10,"stepEvery":12,"tranches":true}') $q$, '23514',
                           'P15 a step loan cannot be disbursed in tranches');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD13', '{"basis":"FLAT","topUp":true}') $q$, '23514', 'P16 nor can a flat-rate loan be topped up');
-- fee rules
INSERT INTO lending.fee_rule (product_code, code, name, event, calc_type, amount, deduct_from_disbursal)
VALUES ('HL01', 'TF', 'Stage disbursement charge', 'EVERY_DISBURSEMENT', 'FIXED', 500, true);
SELECT pg_temp.check((SELECT deduct_from_disbursal FROM lending.fee_rule WHERE product_code = 'HL01' AND code = 'TF'),
                     'P17 a fee on every disbursement can be deducted from the payout');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_rule (product_code, code, name, event, calc_type, amount, deduct_from_disbursal)
                               VALUES ('HL01', 'FC', 'Foreclosure', 'PRECLOSURE', 'FIXED', 500, true) $q$, '23514',
                           'P18 only disbursement fees can be deducted from the payout');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_rule (product_code, code, name, event, calc_type, amount)
                               VALUES ('HL01', 'XX', 'Unknown', 'ANNUAL', 'FIXED', 500) $q$, '23514', 'P19 unknown fee event refused');

-- ---------------------------------------------------------------------------------------------------------
-- B: benchmarks and floating-rate link
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT array_agg(code ORDER BY code) FROM lending.benchmark) = ARRAY['LENDER_PLR','REPO','TBILL91']
                     AND NOT EXISTS (SELECT 1 FROM lending.benchmark_rate),
                     'B1 benchmarks are seeded without rates: the lender records them');
SELECT pg_temp.check(lending.benchmark_rate_on('REPO', '2026-09-10') IS NULL, 'B2 no rate until one is recorded');
INSERT INTO lending.benchmark_rate (benchmark_code, effective_from, rate, recorded_by) VALUES
  ('REPO', '2026-01-01', 6.50, 'CLAUDE-TEST treasury'), ('REPO', '2026-08-15', 6.25, 'CLAUDE-TEST treasury');
SELECT pg_temp.check(lending.benchmark_rate_on('REPO', '2026-08-14') = 6.50 AND lending.benchmark_rate_on('REPO', '2026-08-15') = 6.25
                     AND lending.benchmark_rate_on('REPO', '2025-12-31') IS NULL,
                     'B3 the rate in force on a date is the latest one effective on or before it');
SELECT pg_temp.expect_fail($q$ UPDATE lending.benchmark_rate SET rate = 7 WHERE effective_from = '2026-01-01' $q$, '42501', 'B4 rate history cannot be rewritten');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.benchmark_rate $q$, '42501', 'B5 nor deleted');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.benchmark_rate (benchmark_code, effective_from, rate, recorded_by) VALUES ('MCLR', '2026-01-01', 8, 'x') $q$,
                           '23503', 'B6 a rate needs a known benchmark');
SELECT pg_temp.product('FLT01', '{"rateType":"FLOATING","benchmark":"REPO","spread":2.75,"reset":3}');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD14', '{"benchmark":"REPO","spread":2.75,"reset":3}') $q$, '23514', 'B7 a benchmark link makes the product floating');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.product('BAD15', '{"rateType":"FLOATING","benchmark":"REPO"}') $q$, '23514', 'B8 benchmark, spread and reset frequency go together');

-- ---------------------------------------------------------------------------------------------------------
-- T: product templates
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT array_agg(code ORDER BY sort_order) FROM lending.loan_product_template WHERE active)
                     = ARRAY['PERSONAL_EMI','BUSINESS_EMI','BUSINESS_STEP_UP','GOLD_BULLET','MICRO_WEEKLY','CONSUMER_FLAT','HOME_FLOATING','AGRI_SEASONAL'],
                     'T1 eight templates, in wizard order');
SELECT pg_temp.check((SELECT bool_and(jsonb_typeof(product->'fees') = 'array' AND jsonb_typeof(product->'appropriationSequence') = 'array'
                                      AND (product->>'minAmount')::numeric <= (product->>'maxAmount')::numeric
                                      AND (product->>'minRate')::numeric <= (product->>'maxRate')::numeric)
                        FROM lending.loan_product_template),
                     'T2 every template carries fees, an appropriation sequence and consistent ranges');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_product_template (code, name, description, category, product)
                               VALUES ('BAD', 'x', 'x', 'RETAIL', '{"code":"PL99","name":"x","repaymentMethod":"EQUATED","minAmount":1,"maxAmount":2,
                                        "minTenorMonths":1,"maxTenorMonths":2,"minRate":1,"maxRate":2}') $q$, '23514',
                           'T3 a template is not a product: it carries no code, status or version');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_product_template (code, name, description, category, product)
                               VALUES ('BAD', 'x', 'x', 'RETAIL', '{"name":"x"}') $q$, '23514', 'T4 a template needs the product''s key terms');
-- every template, saved as a product the way the approval applier saves it, satisfies every product and fee constraint
CREATE TEMP TABLE tpl AS SELECT 'TP' || row_number() OVER (ORDER BY sort_order) AS pcode, code, product AS p FROM lending.loan_product_template;
INSERT INTO lending.loan_product (code, name, repayment_method, min_amount, max_amount, min_tenor_months, max_tenor_months, min_rate, max_rate,
    rate_type, day_count, rounding, penal_charge_rate, max_moratorium_months, cooling_off_days, secured, appropriation_sequence,
    appropriation_mode, prepayment_mode, frequency, interest_basis, bpi_mode, step_percent, step_every, principal_every,
    multiple_disbursements, pre_emi, top_up_allowed, benchmark_code, spread, reset_frequency_months,
    gl_principal, gl_interest_income, gl_interest_receivable, status)
SELECT pcode, p->>'name', p->>'repaymentMethod', (p->>'minAmount')::numeric, (p->>'maxAmount')::numeric, (p->>'minTenorMonths')::int,
       (p->>'maxTenorMonths')::int, (p->>'minRate')::numeric, (p->>'maxRate')::numeric, p->>'rateType', p->>'dayCount', p->>'rounding',
       (p->>'penalChargeRate')::numeric, (p->>'maxMoratoriumMonths')::int, (p->>'coolingOffDays')::int, (p->>'secured')::boolean,
       ARRAY(SELECT jsonb_array_elements_text(p->'appropriationSequence')), p->>'appropriationMode', p->>'prepaymentMode',
       p->>'frequency', p->>'interestBasis', p->>'bpiMode', (p->>'stepPercent')::numeric, (p->>'stepEvery')::int,
       coalesce((p->>'principalEvery')::int, 1), coalesce((p->>'multipleDisbursements')::boolean, false), coalesce((p->>'preEmi')::boolean, false),
       coalesce((p->>'topUpAllowed')::boolean, false), p->>'benchmarkCode', (p->>'spread')::numeric, (p->>'resetFrequencyMonths')::int,
       '1101', '4101', '1102', 'ACTIVE'
  FROM tpl;
INSERT INTO lending.fee_rule (product_code, code, name, event, calc_type, amount, percent, min_amount, max_amount, gst_rate, tax_treatment, deduct_from_disbursal)
SELECT pcode, f->>'code', f->>'name', f->>'event', f->>'calcType', (f->>'amount')::numeric, (f->>'percent')::numeric, (f->>'minAmount')::numeric,
       (f->>'maxAmount')::numeric, (f->>'gstRate')::numeric, f->>'taxTreatment', (f->>'deductFromDisbursal')::boolean
  FROM tpl, jsonb_array_elements(p->'fees') f;
SELECT pg_temp.check((SELECT count(*) FROM lending.loan_product WHERE code LIKE 'TP%') = 8
                     AND (SELECT count(*) FROM lending.fee_rule WHERE product_code LIKE 'TP%') = (SELECT sum(jsonb_array_length(p->'fees')) FROM tpl),
                     'T5 every template saves as a valid product with its fee rules');
SELECT pg_temp.check((SELECT (frequency, min_tenor_months, max_tenor_months) = ('WEEKLY', 12, 104) FROM lending.loan_product p JOIN tpl ON tpl.pcode = p.code
                       WHERE tpl.code = 'MICRO_WEEKLY')
                     AND (SELECT (interest_basis, repayment_method) = ('FLAT', 'EQUATED') FROM lending.loan_product p JOIN tpl ON tpl.pcode = p.code
                           WHERE tpl.code = 'CONSUMER_FLAT')
                     AND (SELECT (rate_type, benchmark_code, spread, reset_frequency_months, multiple_disbursements, pre_emi)
                                 = ('FLOATING', 'REPO', 2.75::numeric, 3, true, true)
                            FROM lending.loan_product p JOIN tpl ON tpl.pcode = p.code WHERE tpl.code = 'HOME_FLOATING')
                     AND (SELECT (step_percent, step_every) = (10::numeric, 12) FROM lending.loan_product p JOIN tpl ON tpl.pcode = p.code
                           WHERE tpl.code = 'BUSINESS_STEP_UP'),
                     'T6 weekly, flat, floating multi-tranche and step-up templates carry their parameters');
SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM tpl, jsonb_array_elements(p->'fees') f
                                  WHERE tpl.code = 'HOME_FLOATING' AND f->>'event' IN ('PRECLOSURE','PART_PREPAYMENT'))
                     AND NOT EXISTS (SELECT 1 FROM tpl, jsonb_array_elements(p->'fees') f
                                      WHERE tpl.code = 'MICRO_WEEKLY' AND f->>'event' IN ('PRECLOSURE','PART_PREPAYMENT')),
                     'T7 no foreclosure or prepayment charge on the floating home loan or the micro-loan templates');

-- ---------------------------------------------------------------------------------------------------------
-- L: loan account - tranches, sanctioned amount, floating link, asset-class override
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,
    kfs_accepted_at,state,product_snapshot,disbursed_amount,net_disbursed,disbursed_on,principal_outstanding,customer_state) VALUES
  ('00000000-0000-0000-0000-00000000aa01','10010000000017','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,18,12,'2026-06-30','ACTIVE',
   now(),'{"chargeSeq":0,"demands":[]}','{"loanNo":"10010000000017","branch":"HO","supplierState":"32","recipientState":"32"}',100000,99115,'2026-06-30',100000,'32'),
  ('00000000-0000-0000-0000-00000000aa02','10010000000025','00000000-0000-0000-0000-0000000000c1','PL01','MUM',200000,18,24,'2026-07-10','ACTIVE',
   now(),'{"chargeSeq":0,"demands":[]}','{"loanNo":"10010000000025","branch":"MUM","supplierState":"27","recipientState":"32"}',200000,200000,'2026-07-10',200000,'32'),
  ('00000000-0000-0000-0000-00000000aa03','10010000000033','00000000-0000-0000-0000-0000000000c1','HL01','HO',300000,12,24,'2026-06-30','ACTIVE',
   now(),'{"chargeSeq":0,"demands":[]}','{"loanNo":"10010000000033","branch":"HO","supplierState":"32","recipientState":"32"}',100000,98230,'2026-06-30',100000,'32'),
  ('00000000-0000-0000-0000-00000000aa04','10010000000041','00000000-0000-0000-0000-0000000000c1','FLT01','HO',500000,9.25,120,'2026-03-01','ACTIVE',
   now(),'{"chargeSeq":0,"demands":[]}','{"loanNo":"10010000000041","branch":"HO"}',500000,500000,'2026-03-01',480000,'32'),
  ('00000000-0000-0000-0000-00000000aa05','10010000000058','00000000-0000-0000-0000-0000000000c1','FLT01','HO',500000,9.25,120,'2026-08-01','ACTIVE',
   now(),'{"chargeSeq":0,"demands":[]}','{"loanNo":"10010000000058","branch":"HO"}',500000,500000,'2026-08-01',500000,'32');
SELECT pg_temp.check((SELECT bool_and(frequency = 'MONTHLY' AND undrawn_amount = 0 AND class_floor IS NULL) FROM lending.loan_account),
                     'L1 a loan defaults to monthly, nothing undrawn, no override');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET disbursed_amount = 100001 WHERE loan_no = '10010000000017' $q$, '23514',
                           'L2 the amount disbursed never exceeds the sanctioned amount');
-- tranches of loan 3: sanctioned 300000
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b301','00000000-0000-0000-0000-00000000aa03',1,'DISBURSEMENT','2026-06-30',100000,'{}','{}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b302','00000000-0000-0000-0000-00000000aa03',2,'DISBURSEMENT','2026-08-14',150000,'{}','{"chargeSeq":0}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b303','00000000-0000-0000-0000-00000000aa03',3,'DISBURSEMENT','2026-09-10',60000,'{}','{"chargeSeq":0}');
INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, fees_deducted, interest_deducted, created_by)
VALUES ('00000000-0000-0000-0000-00000000aa03', 1, '00000000-0000-0000-0000-00000000b301', '2026-06-30', 100000, 1770, 0, 'maker');
SELECT pg_temp.check((SELECT net_disbursed = 98230 FROM lending.loan_tranche WHERE tranche_no = 1), 'L3 net of a tranche = amount less fees and interest deducted');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, created_by)
                               VALUES ('00000000-0000-0000-0000-00000000aa03', 3, '00000000-0000-0000-0000-00000000b302', '2026-08-14', 150000, 'maker') $q$,
                           '23514', 'L4 tranches are numbered without gaps');
INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, fees_deducted, created_by)
VALUES ('00000000-0000-0000-0000-00000000aa03', 2, '00000000-0000-0000-0000-00000000b302', '2026-08-14', 150000, 590, 'maker');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, created_by)
                               VALUES ('00000000-0000-0000-0000-00000000aa03', 3, '00000000-0000-0000-0000-00000000b303', '2026-09-10', 60000, 'maker') $q$,
                           '23514', 'L5 tranches cannot add up to more than the sanctioned amount');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, interest_deducted, created_by)
                               VALUES ('00000000-0000-0000-0000-00000000aa03', 3, '00000000-0000-0000-0000-00000000b303', '2026-09-10', 50000, 100, 'maker') $q$,
                           '23514', 'L6 broken-period interest is deducted from the first tranche only');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, fees_deducted, created_by)
                               VALUES ('00000000-0000-0000-0000-00000000aa03', 3, '00000000-0000-0000-0000-00000000b303', '2026-09-10', 500, 600, 'maker') $q$,
                           '23514', 'L7 deductions cannot exceed the tranche');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_tranche SET amount = 1 WHERE tranche_no = 1 $q$, '42501', 'L8 a tranche cannot be edited');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.loan_tranche $q$, '42501', 'L9 nor deleted');
-- a top-up raises the sanctioned amount; the third tranche then fits
UPDATE lending.loan_account SET sanctioned_amount = 310000, undrawn_amount = 60000 WHERE loan_no = '10010000000033';
INSERT INTO lending.loan_tranche (loan_id, tranche_no, txn_id, business_date, amount, created_by)
VALUES ('00000000-0000-0000-0000-00000000aa03', 3, '00000000-0000-0000-0000-00000000b303', '2026-09-10', 60000, 'maker');
SELECT pg_temp.check((SELECT sum(amount) = 310000 AND count(*) = 3 FROM lending.loan_tranche WHERE loan_id = '00000000-0000-0000-0000-00000000aa03'),
                     'L10 after a top-up of the sanctioned amount the further tranche is accepted');
-- floating link and reset
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET benchmark_code = 'REPO' WHERE loan_no = '10010000000041' $q$, '23514',
                           'L11 benchmark, spread and reset frequency go together on the loan');
UPDATE lending.loan_account SET benchmark_code = 'REPO', spread = 2.75, reset_frequency_months = 3, current_rate = 9.25,
       next_rate_reset = CASE loan_no WHEN '10010000000041' THEN '2026-09-01'::date ELSE '2026-11-01'::date END
 WHERE loan_no IN ('10010000000041','10010000000058');
SELECT pg_temp.check((SELECT array_agg(loan_no) = ARRAY['10010000000041'] AND bool_and(benchmark_rate = 6.25 AND new_rate = 9.00 AND current_rate = 9.25)
                        FROM lending.rate_reset_due),
                     'L12 a floating loan is due for reset when its reset date has come and benchmark + spread has moved (6.25 + 2.75 = 9.00)');
UPDATE lending.loan_account SET current_rate = 9.00 WHERE loan_no = '10010000000041';
SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM lending.rate_reset_due), 'L13 once the rate equals benchmark + spread nothing is due');
-- asset-class override columns
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET class_floor = 'SUBSTANDARD' WHERE loan_no = '10010000000017' $q$, '23514',
                           'L14 an override needs its expiry date');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET class_floor = 'STANDARD', class_floor_until = '2026-12-31' WHERE loan_no = '10010000000017' $q$,
                           '23514', 'L15 an override can only hold an NPA class - it never upgrades');
UPDATE lending.loan_account SET class_floor = 'SUBSTANDARD', class_floor_until = '2026-12-31' WHERE loan_no = '10010000000017';
SELECT pg_temp.check((SELECT class_floor = 'SUBSTANDARD' FROM lending.loan_account WHERE loan_no = '10010000000017'), 'L16 override recorded');
-- product options frozen into the account at booking
SELECT pg_temp.check((SELECT bool_and(NOT multiple_disbursements AND NOT pre_emi AND NOT top_up_allowed) FROM lending.loan_account),
                     'L17 a loan is single-disbursement, without pre-EMI or top-up, unless booked otherwise');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET pre_emi = true WHERE loan_no = '10010000000025' $q$, '23514',
                           'L18 pre-EMI only on a loan disbursed in tranches');
UPDATE lending.loan_account SET multiple_disbursements = true, pre_emi = true, top_up_allowed = true WHERE loan_no = '10010000000025';
-- the figures LoanStore.save writes after a top-up, and after the un-mark of an override
UPDATE lending.loan_account SET sanctioned_amount = 250000, disbursed_amount = 200000, undrawn_amount = 50000 WHERE loan_no = '10010000000025';
SELECT pg_temp.check((SELECT sanctioned_amount = 250000 AND undrawn_amount = 50000 FROM lending.loan_account WHERE loan_no = '10010000000025'),
                     'L19 a top-up raises the sanctioned amount and leaves the extra undrawn');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_account SET sanctioned_amount = 150000 WHERE loan_no = '10010000000025' $q$, '23514',
                           'L20 the sanctioned amount cannot be reduced below the amount disbursed');
UPDATE lending.loan_account SET sanctioned_amount = 200000, undrawn_amount = 0 WHERE loan_no = '10010000000025';

-- ---------------------------------------------------------------------------------------------------------
-- A: amendment kinds and maker-checker rules
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT checkers_required FROM platform.approval_rule WHERE entity_type = 'LOAN_NPA_OVERRIDE' AND action = 'OVERRIDE') = 2,
                     'A1 an asset-class override needs two checkers');
SELECT pg_temp.check((SELECT checkers_required FROM platform.approval_rule WHERE entity_type = 'LOAN_SANCTION_CHANGE' AND action = 'AMEND') = 1
                     AND (SELECT checkers_required FROM platform.approval_rule WHERE entity_type = 'BENCHMARK_RATE' AND action = 'CREATE') = 1,
                     'A2 a sanctioned-amount change and a benchmark rate need one checker');
SELECT pg_temp.check((SELECT checkers_required FROM platform.approval_rule WHERE entity_type = 'LOAN_NPA_OVERRIDE' AND action = 'RELEASE') = 2,
                     'A2a releasing an override (un-mark) needs two checkers');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b111','00000000-0000-0000-0000-00000000aa01',1,'AMENDMENT','2026-09-10',NULL,'{}','{}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b112','00000000-0000-0000-0000-00000000aa01',2,'SANCTION_CHANGE','2026-09-10',NULL,'{}','{}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b113','00000000-0000-0000-0000-00000000aa01',3,'NPA_OVERRIDE','2026-09-10',NULL,'{}','{}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b114','00000000-0000-0000-0000-00000000aa01',4,'REVERSAL','2026-09-10',NULL,'{}','{}');
INSERT INTO platform.approval_request (id,entity_type,entity_id,action,payload,maker) VALUES
  ('00000000-0000-0000-0000-00000000cc01','LOAN_AMENDMENT','10010000000017','AMEND','{}','maker');
INSERT INTO lending.loan_amendment (id,loan_id,seq,txn_id,kind,parameters,emi_before,emi_after,tenure_before,tenure_after,rate_before,rate_after,
    maturity_before,maturity_after,interest_before,interest_after,applied_figures,approval_id,made_by,checked_by,business_date,reason)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-00000000aa01', n, ('00000000-0000-0000-0000-00000000b11' || n)::uuid, k, '{}', 9168, 9168, 9, 9, 18, 18,
       '2027-06-30', '2027-06-30', 5000, 5000, '{}', '00000000-0000-0000-0000-00000000cc01', 'maker', 'checker1', '2026-09-10', 'CLAUDE-TEST'
  FROM (VALUES (1, 'MATURITY_CHANGE'), (2, 'SANCTION_CHANGE'), (3, 'NPA_OVERRIDE')) v(n, k);
SELECT pg_temp.check((SELECT count(*) FROM lending.loan_amendment WHERE kind IN ('MATURITY_CHANGE','SANCTION_CHANGE','NPA_OVERRIDE')) = 3,
                     'A3 maturity, sanctioned-amount and asset-class changes are kept in the amendment history');
SELECT pg_temp.expect_fail($q$ UPDATE lending.loan_amendment SET reversed_by = '00000000-0000-0000-0000-00000000b114' WHERE kind = 'NPA_OVERRIDE' $q$,
                           '23514', 'A4 an asset-class override is never marked reversed');
UPDATE lending.loan_amendment SET reversed_by = '00000000-0000-0000-0000-00000000b114' WHERE kind = 'MATURITY_CHANGE';
SELECT pg_temp.check((SELECT reversed_by IS NOT NULL FROM lending.loan_amendment WHERE kind = 'MATURITY_CHANGE'), 'A5 a maturity change can be reversed');

-- ---------------------------------------------------------------------------------------------------------
-- N: number series
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT (prefix, width) = ('7002', 9::smallint) FROM platform.number_series WHERE family = 'GST_CREDIT_NOTE'),
                     'N1 credit notes have their own number series');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.number_series VALUES ('DEBIT_NOTE','7003',9,'platform.seq_gst_credit_note') $q$, '23514',
                           'N2 the list of series is still closed');

-- ---------------------------------------------------------------------------------------------------------
-- D: tax invoices for fees deducted from several tranches (loan 3)
-- ---------------------------------------------------------------------------------------------------------
-- tranche 1: processing fee and stage charge; tranche 2: stage charge. (Loan 3's disbursement transactions were
-- recorded above without lots; these are separate transactions carrying the lots.)
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d301','DISBURSEMENT','2026-06-30','HO', '[
  ["DR","1101","10010000000033",100000,"Disbursement"],
  ["CR","4102","4102",1000,"Processing fee 10010000000033"],["CR","2201","2201",90,"CGST Processing fee 10010000000033"],["CR","2202","2202",90,"SGST Processing fee 10010000000033"],
  ["CR","4102","4102",500,"Stage disbursement charge 10010000000033"],["CR","2201","2201",45,"CGST Stage disbursement charge 10010000000033"],["CR","2202","2202",45,"SGST Stage disbursement charge 10010000000033"],
  ["CR","1202","1202",98230,"Net disbursal 10010000000033"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d302','DISBURSEMENT','2026-08-14','HO', '[
  ["DR","1101","10010000000033",150000,"Disbursement"],
  ["CR","4102","4102",500,"Stage disbursement charge 10010000000033"],["CR","2201","2201",45,"CGST Stage disbursement charge 10010000000033"],["CR","2202","2202",45,"SGST Stage disbursement charge 10010000000033"],
  ["CR","1202","1202",149410,"Net disbursal 10010000000033"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b311','00000000-0000-0000-0000-00000000aa03',11,'DISBURSEMENT','2026-06-30',100000,'{00000000-0000-0000-0000-00000000d301}','{}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b312','00000000-0000-0000-0000-00000000aa03',12,'DISBURSEMENT','2026-08-14',150000,'{00000000-0000-0000-0000-00000000d302}','{"chargeSeq":0}');
SELECT pg_temp.check(lending.issue_fee_invoices('00000000-0000-0000-0000-00000000aa03') = 3, 'D1 one invoice per fee deducted, across both tranches');
SELECT pg_temp.check((SELECT array_agg(charge_ref || ':' || description || ':' || invoice_date ORDER BY invoice_no) FROM lending.fee_invoice
                       WHERE loan_id = '00000000-0000-0000-0000-00000000aa03')
                     = ARRAY['D1:Processing fee:2026-06-30','D2:Stage disbursement charge:2026-06-30','D3:Stage disbursement charge:2026-08-14'],
                     'D2 the second tranche''s fee continues the numbering (D3) instead of colliding with D1');
SELECT pg_temp.check(lending.issue_fee_invoices(NULL) = 0, 'D3 issuing again adds nothing');

-- ---------------------------------------------------------------------------------------------------------
-- C: GST credit notes
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check(lending.credit_note_in_time('2026-07-01', '2027-11-30') AND NOT lending.credit_note_in_time('2026-07-01', '2027-12-01')
                     AND lending.credit_note_in_time('2027-03-31', '2027-11-30') AND NOT lending.credit_note_in_time('2027-03-31', '2027-12-01')
                     AND lending.credit_note_in_time('2027-04-01', '2028-11-30'),
                     'C1 time limit: 30 November after the financial year of the invoice (same rule as the engine)');
-- loan 1 (Kochi, intra-state): a bounce charge of 500 + 18% is charged on 5-Sep and invoiced
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d101','FEE_CHARGE','2026-09-05','HO', '[
  ["DR","1103","10010000000017",590,"Bounce charge"],["CR","4102","4102",500,"Bounce charge 10010000000017"],
  ["CR","2201","2201",45,"CGST Bounce charge 10010000000017"],["CR","2202","2202",45,"SGST Bounce charge 10010000000017"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b121','00000000-0000-0000-0000-00000000aa01',21,'FEE_CHARGE','2026-09-05',NULL,'{00000000-0000-0000-0000-00000000d101}','{"chargeSeq":0}');
SELECT pg_temp.check(lending.issue_fee_invoices('00000000-0000-0000-0000-00000000aa01') = 1, 'C2 the invoice is issued');
SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM lending.fee_credit_note), 'C2b no credit note without a waiver');
-- part waiver of 236 on 8-Sep, as the engine posts it (LoanPostings.feeWaiver)
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d102','WAIVER','2026-09-08','HO', '[
  ["DR","4102","4102",200,"Bounce charge 10010000000017"],["DR","2201","2201",18,"CGST Bounce charge 10010000000017"],
  ["DR","2202","2202",18,"SGST Bounce charge 10010000000017"],["CR","1103","10010000000017",236,"FEE waiver C1"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b122','00000000-0000-0000-0000-00000000aa01',22,'WAIVER','2026-09-08',236,'{00000000-0000-0000-0000-00000000d102}','{"chargeSeq":1}');
SELECT pg_temp.check(lending.issue_fee_invoices('00000000-0000-0000-0000-00000000aa01') = 0, 'C3 a waiver issues no invoice');
SELECT pg_temp.check((SELECT (c.credit_note_no, c.credit_note_date, c.reason, c.taxable_value, c.cgst, c.sgst, c.igst, c.total, c.tax_adjustable, c.status, i.charge_ref, i.status)
                             = ('7002000000001' || platform.luhn_digit('7002000000001'), '2026-09-08'::date, 'WAIVER', 200::numeric, 18::numeric, 18::numeric,
                                0::numeric, 236::numeric, true, 'ISSUED', 'C1', 'ISSUED')
                        FROM lending.fee_credit_note c JOIN lending.fee_invoice i ON i.id = c.invoice_id),
                     'C4 the waiver has a credit note against the charge''s invoice, from the ledger: taxable 200, CGST 18, SGST 18');
SELECT pg_temp.check(lending.issue_fee_credit_notes(NULL) = 0, 'C5 issuing again adds nothing');
SELECT pg_temp.check((SELECT count(*) FROM lending.fee_credit_note) = 1, 'C5b still one note');
-- the rest (354) is waived the next day
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d103','WAIVER','2026-09-09','HO', '[
  ["DR","4102","4102",300,"Bounce charge 10010000000017"],["DR","2201","2201",27,"CGST Bounce charge 10010000000017"],
  ["DR","2202","2202",27,"SGST Bounce charge 10010000000017"],["CR","1103","10010000000017",354,"FEE waiver C1"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b123','00000000-0000-0000-0000-00000000aa01',23,'WAIVER','2026-09-09',354,'{00000000-0000-0000-0000-00000000d103}','{"chargeSeq":1}');
-- a penal waiver carries no GST: no credit note
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d104','WAIVER','2026-09-09','HO', '[
  ["DR","4103","4103",30,"PENAL waiver 10010000000017"],["CR","1104","10010000000017",30,"PENAL waiver"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b124','00000000-0000-0000-0000-00000000aa01',24,'WAIVER','2026-09-09',30,'{00000000-0000-0000-0000-00000000d104}','{"chargeSeq":1}');
SELECT pg_temp.check(lending.issue_fee_credit_notes('00000000-0000-0000-0000-00000000aa01') = 1, 'C6 one more note for the second waiver; none for the penal waiver');
SELECT pg_temp.check((SELECT (sum(c.taxable_value), sum(c.cgst), sum(c.sgst)) = (max(i.taxable_value), max(i.cgst), max(i.sgst))
                        FROM lending.fee_credit_note c JOIN lending.fee_invoice i ON i.id = c.invoice_id WHERE i.charge_ref = 'C1'),
                     'C7 the two notes together credit the whole invoice');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_credit_note (credit_note_no, credit_note_date, invoice_id, loan_id, txn_id, lot_id, reason, taxable_value, cgst, sgst, tax_adjustable)
    SELECT 'X1', '2026-09-10', i.id, i.loan_id, '00000000-0000-0000-0000-00000000b124', '00000000-0000-0000-0000-00000000d104', 'WAIVER', 1, 0.09, 0.09, true
      FROM lending.fee_invoice i WHERE i.loan_id = '00000000-0000-0000-0000-00000000aa01' AND i.charge_ref = 'C1' $q$, '23514',
  'C8 credit notes cannot exceed their invoice');
SELECT pg_temp.expect_fail($q$ UPDATE lending.fee_credit_note SET taxable_value = 1 $q$, '42501', 'C9 a credit note cannot be edited');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.fee_credit_note $q$, '42501', 'C10 nor deleted');
-- the second waiver is reversed: its note is cancelled, the first stays
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d105','REVERSAL','2026-09-10','HO', '[
  ["CR","4102","4102",300,"REV Bounce charge 10010000000017"],["CR","2201","2201",27,"REV CGST"],["CR","2202","2202",27,"REV SGST"],
  ["DR","1103","10010000000017",354,"REV FEE waiver C1"],["DR","1104","10010000000017",30,"REV PENAL waiver"],["CR","4103","4103",30,"REV PENAL waiver"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b125','00000000-0000-0000-0000-00000000aa01',25,'REVERSAL','2026-09-10',NULL,'{00000000-0000-0000-0000-00000000d105}','{"chargeSeq":1}');
UPDATE lending.loan_txn SET reversed_by = '00000000-0000-0000-0000-00000000b125' WHERE id IN ('00000000-0000-0000-0000-00000000b123','00000000-0000-0000-0000-00000000b124');
SELECT pg_temp.check(lending.issue_fee_invoices('00000000-0000-0000-0000-00000000aa01') = 0, 'C11 reversal issues nothing new');
SELECT pg_temp.check((SELECT array_agg(status || ':' || taxable_value::numeric || ':' || coalesce(cancelled_on::text, '-') ORDER BY credit_note_no)
                        FROM lending.fee_credit_note) = ARRAY['ISSUED:200.0000:-', 'CANCELLED:300.0000:2026-09-10'],
                     'C12 the reversed waiver''s note is cancelled with its number kept; the other stands');
SELECT pg_temp.expect_fail($q$ UPDATE lending.fee_credit_note SET status = 'ISSUED', cancelled_on = NULL, cancelled_by_txn = NULL WHERE status = 'CANCELLED' $q$,
                           '42501', 'C13 a cancelled note stays cancelled');
-- a fee charged and reversed in the same month: the invoice is cancelled, no credit note (as before V18)
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d106','FEE_CHARGE','2026-09-10','HO', '[
  ["DR","1103","10010000000017",118,"Late fee"],["CR","4102","4102",100,"Late fee 10010000000017"],
  ["CR","2201","2201",9,"CGST Late fee 10010000000017"],["CR","2202","2202",9,"SGST Late fee 10010000000017"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b126','00000000-0000-0000-0000-00000000aa01',26,'FEE_CHARGE','2026-09-10',NULL,'{00000000-0000-0000-0000-00000000d106}','{"chargeSeq":1}');
SELECT pg_temp.check(lending.issue_fee_invoices('00000000-0000-0000-0000-00000000aa01') = 1, 'C14 late fee invoiced (C2)');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b127','00000000-0000-0000-0000-00000000aa01',27,'REVERSAL','2026-09-10',NULL,'{}','{"chargeSeq":2}');
UPDATE lending.loan_txn SET reversed_by = '00000000-0000-0000-0000-00000000b127' WHERE id = '00000000-0000-0000-0000-00000000b126';
SELECT pg_temp.check(lending.issue_fee_invoices('00000000-0000-0000-0000-00000000aa01') = 0, 'C15 the reversal issues nothing');
SELECT pg_temp.check((SELECT status = 'CANCELLED' FROM lending.fee_invoice WHERE loan_id = '00000000-0000-0000-0000-00000000aa01' AND charge_ref = 'C2')
                     AND (SELECT count(*) FROM lending.fee_credit_note) = 2,
                     'C15b reversed in the month of its invoice: the invoice is cancelled, no credit note');

-- loan 2 (Mumbai branch, Kerala borrower: IGST): a fee invoiced in July is reversed in September -> credit note
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d201','FEE_CHARGE','2026-07-10','MUM', '[
  ["DR","1103","10010000000025",1180,"Documentation charge"],["CR","4102","4102",1000,"Documentation charge 10010000000025"],
  ["CR","2203","2203",180,"IGST Documentation charge 10010000000025"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b201','00000000-0000-0000-0000-00000000aa02',1,'FEE_CHARGE','2026-07-10',NULL,'{00000000-0000-0000-0000-00000000d201}','{"chargeSeq":0}');
SELECT pg_temp.check(lending.issue_fee_invoices('00000000-0000-0000-0000-00000000aa02') = 1, 'C16 July invoice issued');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b202','00000000-0000-0000-0000-00000000aa02',2,'REVERSAL','2026-09-10',NULL,'{}','{"chargeSeq":1}');
UPDATE lending.loan_txn SET reversed_by = '00000000-0000-0000-0000-00000000b202' WHERE id = '00000000-0000-0000-0000-00000000b201';
SELECT pg_temp.check(lending.issue_fee_invoices('00000000-0000-0000-0000-00000000aa02') = 0, 'C17 the reversal issues no invoice');
SELECT pg_temp.check((SELECT (c.reason, c.credit_note_date, c.taxable_value, c.cgst, c.sgst, c.igst, c.lot_id IS NULL, c.txn_id, c.tax_adjustable, i.status, i.cancelled_on IS NULL)
                             = ('REVERSAL', '2026-09-10'::date, 1000::numeric, 0::numeric, 0::numeric, 180::numeric, true,
                                '00000000-0000-0000-0000-00000000b202'::uuid, true, 'CREDITED', true)
                        FROM lending.fee_credit_note c JOIN lending.fee_invoice i ON i.id = c.invoice_id WHERE c.loan_id = '00000000-0000-0000-0000-00000000aa02'),
                     'C18 reversed in a later month: the invoice stands as CREDITED and a credit note reverses it in full (IGST)');
SELECT pg_temp.check(lending.issue_fee_invoices(NULL) = 0, 'C19 issuing again adds no invoice');
SELECT pg_temp.check((SELECT count(*) FROM lending.fee_credit_note WHERE loan_id = '00000000-0000-0000-0000-00000000aa02') = 1, 'C19b and no second note');
-- the engine's charge id C1 is free again after the reversal: a new fee gets a new invoice with the same reference
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d203','FEE_CHARGE','2026-09-10','MUM', '[
  ["DR","1103","10010000000025",590,"Documentation charge"],["CR","4102","4102",500,"Documentation charge 10010000000025"],
  ["CR","2203","2203",90,"IGST Documentation charge 10010000000025"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b203','00000000-0000-0000-0000-00000000aa02',3,'FEE_CHARGE','2026-09-10',NULL,'{00000000-0000-0000-0000-00000000d203}','{"chargeSeq":0}');
SELECT pg_temp.check(lending.issue_fee_invoices('00000000-0000-0000-0000-00000000aa02') = 1, 'C20 the new fee is invoiced');
SELECT pg_temp.check((SELECT array_agg(status ORDER BY invoice_no) FROM lending.fee_invoice WHERE loan_id = '00000000-0000-0000-0000-00000000aa02' AND charge_ref = 'C1')
                     = ARRAY['CREDITED','ISSUED'],
                     'C20b a credited invoice does not block a new invoice for the re-used charge id');
SELECT pg_temp.expect_fail($q$ UPDATE lending.fee_invoice SET status = 'ISSUED' WHERE status = 'CREDITED' $q$, '42501', 'C21 a credited invoice stays credited');
-- guards on a credit note
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_credit_note (credit_note_no, credit_note_date, invoice_id, loan_id, txn_id, reason, taxable_value, igst, tax_adjustable)
    SELECT 'X2', '2026-09-10', i.id, '00000000-0000-0000-0000-00000000aa01', '00000000-0000-0000-0000-00000000b202', 'REVERSAL', 10, 1.8, true
      FROM lending.fee_invoice i WHERE i.loan_id = '00000000-0000-0000-0000-00000000aa02' AND i.status = 'ISSUED' $q$, '23514',
  'C22 a credit note belongs to its invoice''s loan');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_credit_note (credit_note_no, credit_note_date, invoice_id, loan_id, txn_id, reason, taxable_value, cgst, sgst, tax_adjustable)
    SELECT 'X3', '2026-09-10', i.id, i.loan_id, '00000000-0000-0000-0000-00000000b202', 'REVERSAL', 10, 0.9, 0.9, true
      FROM lending.fee_invoice i WHERE i.loan_id = '00000000-0000-0000-0000-00000000aa02' AND i.status = 'ISSUED' $q$, '23514',
  'C23 a credit note keeps its invoice''s tax heads (no CGST/SGST against an IGST invoice)');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_credit_note (credit_note_no, credit_note_date, invoice_id, loan_id, txn_id, reason, taxable_value, igst, tax_adjustable)
    SELECT 'X4', '2026-09-09', i.id, i.loan_id, '00000000-0000-0000-0000-00000000b202', 'REVERSAL', 10, 1.8, true
      FROM lending.fee_invoice i WHERE i.loan_id = '00000000-0000-0000-0000-00000000aa02' AND i.status = 'ISSUED' $q$, '23514',
  'C24 a credit note is not dated before its invoice');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_credit_note (credit_note_no, credit_note_date, invoice_id, loan_id, txn_id, reason, taxable_value, igst, tax_adjustable)
    SELECT 'X5', '2027-12-01', i.id, i.loan_id, '00000000-0000-0000-0000-00000000b202', 'REVERSAL', 10, 1.8, true
      FROM lending.fee_invoice i WHERE i.loan_id = '00000000-0000-0000-0000-00000000aa02' AND i.status = 'ISSUED' $q$, '23514',
  'C25 after 30 November of the next financial year a note cannot claim a tax adjustment');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_credit_note (credit_note_no, credit_note_date, invoice_id, loan_id, txn_id, reason, taxable_value, cgst, sgst, tax_adjustable)
    SELECT 'X6', '2026-09-10', i.id, i.loan_id, '00000000-0000-0000-0000-00000000b127', 'REVERSAL', 10, 0.9, 0.9, true
      FROM lending.fee_invoice i WHERE i.loan_id = '00000000-0000-0000-0000-00000000aa01' AND i.charge_ref = 'C2' $q$, '23514',
  'C26 a cancelled invoice has nothing to credit');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_credit_note (credit_note_no, credit_note_date, invoice_id, loan_id, txn_id, reason, taxable_value, igst, tax_adjustable)
    SELECT 'X7', '2026-09-10', i.id, i.loan_id, '00000000-0000-0000-0000-00000000b202', 'WAIVER', 10, 1.8, true
      FROM lending.fee_invoice i WHERE i.loan_id = '00000000-0000-0000-0000-00000000aa02' AND i.status = 'ISSUED' $q$, '23514',
  'C27 a waiver note names the waiver''s lot');
-- net output for the return: invoices less live, tax-adjustable credit notes
SELECT pg_temp.check((SELECT (sum(taxable_value), sum(cgst + sgst), sum(igst)) = ((500 - 200)::numeric, (90 - 36)::numeric, 0::numeric)
                        FROM lending.gst_output_document WHERE loan_id = '00000000-0000-0000-0000-00000000aa01')
                     AND (SELECT (sum(taxable_value), sum(igst)) = ((1000 - 1000 + 500)::numeric, (180 - 180 + 90)::numeric)
                            FROM lending.gst_output_document WHERE loan_id = '00000000-0000-0000-0000-00000000aa02'),
                     'C28 net GST output = invoices (issued or credited) less live credit notes; cancelled documents are left out');

-- ---------------------------------------------------------------------------------------------------------
-- F: foreclosure charges are income of 4104 (V22); invoices and credit notes read either fee income head
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT bool_and(foreclosure_income_gl = '4104' AND fee_income_gl = '4102') FROM lending.loan_gl),
                     'F1 a loan whose snapshot names no foreclosure head uses 4104');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d204','FEE_CHARGE','2026-09-10','MUM', '[
  ["DR","1103","10010000000025",1180,"Foreclosure charge"],["CR","4104","4104",1000,"Foreclosure charge 10010000000025"],
  ["CR","2203","2203",180,"IGST Foreclosure charge 10010000000025"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b204','00000000-0000-0000-0000-00000000aa02',4,'FEE_CHARGE','2026-09-10',NULL,'{00000000-0000-0000-0000-00000000d204}','{"chargeSeq":1}');
SELECT pg_temp.check(lending.issue_fee_invoices('00000000-0000-0000-0000-00000000aa02') = 1, 'F2 a foreclosure charge booked to 4104 is invoiced');
SELECT pg_temp.check((SELECT (description, charge_ref, taxable_value, igst, status) = ('Foreclosure charge', 'C2', 1000::numeric, 180::numeric, 'ISSUED')
                        FROM lending.fee_invoice WHERE lot_id = '00000000-0000-0000-0000-00000000d204'),
                     'F3 with the taxable value from the 4104 line');
-- half of it waived, as the engine posts it for a foreclosure charge (LoanPostings.feeWaiver, foreclosure head)
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d205','WAIVER','2026-09-10','MUM', '[
  ["DR","4104","4104",500,"Foreclosure charge 10010000000025"],["DR","2203","2203",90,"IGST Foreclosure charge 10010000000025"],
  ["CR","1103","10010000000025",590,"FEE waiver C2"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b205','00000000-0000-0000-0000-00000000aa02',5,'WAIVER','2026-09-10',590,'{00000000-0000-0000-0000-00000000d205}','{"chargeSeq":2}');
SELECT pg_temp.check(lending.issue_fee_credit_notes('00000000-0000-0000-0000-00000000aa02') = 1, 'F4 its waiver gets a credit note');
SELECT pg_temp.check((SELECT (c.reason, c.taxable_value, c.igst) = ('WAIVER', 500::numeric, 90::numeric)
                        FROM lending.fee_credit_note c WHERE c.lot_id = '00000000-0000-0000-0000-00000000d205'),
                     'F5 with the taxable value from the 4104 line');
SELECT pg_temp.check(lending.issue_fee_invoices(NULL) = 0 AND lending.issue_fee_credit_notes(NULL) = 0, 'F6 issuing again adds nothing');

-- ---------------------------------------------------------------------------------------------------------
-- E: the instalment now payable (V22)
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT bool_and(current_emi IS NULL) FROM lending.loan_account), 'E1 current_emi is empty until the engine saves the loan');
UPDATE lending.loan_account SET current_emi = 5678 WHERE id = '00000000-0000-0000-0000-00000000aa01';
SELECT pg_temp.check((SELECT (coalesce(current_emi, emi) = 5678 AND emi IS DISTINCT FROM 5678) FROM lending.loan_account
                       WHERE id = '00000000-0000-0000-0000-00000000aa01'),
                     'E2 the EMI as sanctioned stays beside the instalment now payable');

\echo ALL PRODUCT COMPLETION TESTS PASSED
