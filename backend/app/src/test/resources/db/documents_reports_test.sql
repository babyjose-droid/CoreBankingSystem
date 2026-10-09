-- P2-4 documents and reports (V16): loan balance and statement from the ledger, GST fee invoices, the report
-- functions with branch scope, the bureau extract and the dashboard. Run on a fresh tenant database after migrations.
-- All names are test data (CLAUDE-TEST).
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

-- ---------------------------------------------------------------------------------------------------------
-- Fixture: two branches (Kerala 32, Maharashtra 27), three users, six loans. Business date 10-Sep-2026.
-- ---------------------------------------------------------------------------------------------------------
INSERT INTO platform.branch VALUES ('HO','Head Office Kochi',NULL,'32',NULL,true,'ACTIVE'), ('MUM','Mumbai',NULL,'27','HO',false,'ACTIVE');
INSERT INTO platform.business_day VALUES (1,'2026-09-10','OPEN');
SELECT ledger.load_starter_kit('NBFC');
SELECT ledger.load_lending_heads();
INSERT INTO platform.staff_user (user_id, username, display_name, home_branch, all_branches, status) VALUES
  ('sub-all','all.user','CLAUDE-TEST All branches','HO',true,'ACTIVE'),
  ('sub-ho','ho.user','CLAUDE-TEST Kochi','HO',false,'ACTIVE'),
  ('sub-mum','mum.user','CLAUDE-TEST Mumbai','MUM',false,'ACTIVE');
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status)
VALUES ('PL01','CLAUDE-TEST Personal loan','EQUATED',10000,500000,6,60,12,24,24,'1101','4101','1102','ACTIVE');
INSERT INTO customer.customer (id,customer_no,customer_type,display_name,date_of_birth,gender,pan_cipher,mobile_cipher,home_branch) VALUES
  ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST One','1990-01-15','FEMALE','\x0101','\x0102','HO'),
  ('00000000-0000-0000-0000-0000000000c2','90010000000021','INDIVIDUAL','CLAUDE-TEST Two','1985-05-20','MALE',NULL,'\x0202','MUM');
INSERT INTO customer.address VALUES
  ('00000000-0000-0000-0000-0000000000c1','COMMUNICATION','\x0a01','Kochi','32','682001'),
  ('00000000-0000-0000-0000-0000000000c2','COMMUNICATION','\x0a02','Kochi','KL','682002');
INSERT INTO platform.system_property (key, value, updated_by) VALUES ('bureau.account-type.default','05','test');

-- L1 Kochi: active, one instalment paid, one overdue (10 days), GL heads left to the starter chart.
-- L2 Mumbai branch, borrower in Kerala (inter-state): disbursed today, GL heads set on the product snapshot.
-- L3 Mumbai: NPA.  L4 Kochi: closed this month.  L5 Kochi: closed in July.  L6 Kochi: sanctioned, not disbursed.
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,emi,status,
    kfs_accepted_at,state,product_snapshot,disbursed_amount,net_disbursed,disbursed_on,principal_outstanding,overdue_amount,dpd,asset_class,
    npa_since,suspense,provision_held,customer_state,closed_on,first_due_date,source) VALUES
  ('00000000-0000-0000-0000-00000000aa01','10010000000017','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,18,12,'2026-06-30',9168,'ACTIVE',
   now(), '{"chargeSeq":2,"demands":[{"instalmentNo":1,"dueDate":"2026-07-31","principalDue":7689,"interestDue":1479,"principalPaid":7689,"interestPaid":1479},
                                      {"instalmentNo":2,"dueDate":[2026,8,31],"principalDue":7757,"interestDue":1411,"principalPaid":0,"interestPaid":0}]}',
   '{"loanNo":"10010000000017","branch":"HO","supplierState":"32","recipientState":"32","fees":[{"code":"PF","name":"Processing fee","gstRatePercent":18}]}',
   100000,99115,'2026-06-30',92311,9788,10,'SMA0',NULL,0,369.24,'32',NULL,'2026-07-31','CONSOLE'),
  ('00000000-0000-0000-0000-00000000aa02','10010000000025','00000000-0000-0000-0000-0000000000c2','PL01','MUM',200000,18,24,'2026-09-10',9985,'ACTIVE',
   now(), '{"chargeSeq":1,"demands":[]}',
   '{"loanNo":"10010000000025","branch":"MUM","supplierState":"27","recipientState":"32","gl":{"principal":"1101","interestReceivable":"1102","feeReceivable":"1103","penalReceivable":"1104","interestIncome":"4101","feeIncome":"4105","penalIncome":"4103","cgst":"2201","sgst":"2202","igst":"2203","disbursementBank":"1202","collectionBank":"1203","excessReceipts":"2302","interestSuspense":"2305"},"fees":[]}',
   200000,200000,'2026-09-10',200000,0,0,'STANDARD',NULL,0,800,'32',NULL,'2026-10-10','API'),
  ('00000000-0000-0000-0000-00000000aa03','10010000000033','00000000-0000-0000-0000-0000000000c2','PL01','MUM',50000,18,12,'2026-01-15',4584,'ACTIVE',
   now(), '{"demands":[{"instalmentNo":7,"dueDate":"2026-09-05","principalDue":4000,"interestDue":600,"principalPaid":0,"interestPaid":300}]}',
   '{"loanNo":"10010000000033","branch":"MUM","supplierState":"27","recipientState":"32"}',
   50000,50000,'2026-01-15',40000,12000,130,'SUBSTANDARD','2026-08-01',1200,3000,'32',NULL,'2026-02-15','CONSOLE'),
  ('00000000-0000-0000-0000-00000000aa04','10010000000041','00000000-0000-0000-0000-0000000000c1','PL01','HO',20000,18,12,'2026-05-01',1834,'CLOSED',
   now(), '{"demands":[]}', '{"loanNo":"10010000000041","branch":"HO","supplierState":"32","recipientState":"32"}',
   20000,20000,'2026-05-01',0,0,0,'STANDARD',NULL,0,0,'32','2026-09-03','2026-06-01','CONSOLE'),
  ('00000000-0000-0000-0000-00000000aa05','10010000000058','00000000-0000-0000-0000-0000000000c1','PL01','HO',10000,18,12,'2026-04-10',917,'CLOSED',
   now(), '{"demands":[]}', '{"loanNo":"10010000000058","branch":"HO"}',
   10000,10000,'2026-04-10',0,0,0,'STANDARD',NULL,0,0,'32','2026-07-15','2026-05-10','CONSOLE'),
  ('00000000-0000-0000-0000-00000000aa06','10010000000066','00000000-0000-0000-0000-0000000000c1','PL01','HO',30000,18,12,'2026-09-10',NULL,'SANCTIONED',
   NULL, NULL, '{"loanNo":"10010000000066","branch":"HO"}', NULL,NULL,NULL,0,0,0,'STANDARD',NULL,0,0,'32',NULL,NULL,'CONSOLE');

-- L1 ledger and transactions -----------------------------------------------------------------------------------
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d101','DISBURSEMENT','2026-06-30','HO', '[
  ["DR","1101","10010000000017",100000,"Disbursement"],
  ["CR","4102","4102",750,"Processing fee 10010000000017"],
  ["CR","2201","2201",67.5,"CGST Processing fee 10010000000017"],
  ["CR","2202","2202",67.5,"SGST Processing fee 10010000000017"],
  ["CR","1202","1202",99115,"Net disbursal 10010000000017"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d102','ACCRUAL','2026-07-31','HO', '[["DR","1102","10010000000017",1479,"Interest accrual"],["CR","4101","4101",1479,"Interest accrual 10010000000017"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d103','REPAYMENT','2026-07-31','HO', '[
  ["DR","1203","1203",9168,"Receipt 10010000000017"],["CR","1101","10010000000017",7689,"Principal"],["CR","1102","10010000000017",1479,"Interest"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d104','ACCRUAL','2026-08-30','HO', '[["DR","1102","10010000000017",700,"Interest accrual"],["CR","4101","4101",700,"Interest accrual 10010000000017"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d105','ACCRUAL','2026-08-31','HO', '[["DR","1102","10010000000017",711,"Interest accrual"],["CR","4101","4101",711,"Interest accrual 10010000000017"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d106','PENAL_CHARGE','2026-09-05','HO', '[["DR","1104","10010000000017",30,"Penal charge"],["CR","4103","4103",30,"Penal charge 10010000000017"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d107','FEE_CHARGE','2026-09-05','HO', '[
  ["DR","1103","10010000000017",590,"Bounce charge"],["CR","4102","4102",500,"Bounce charge 10010000000017"],
  ["CR","2201","2201",45,"CGST Bounce charge 10010000000017"],["CR","2202","2202",45,"SGST Bounce charge 10010000000017"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d108','FEE_CHARGE','2026-09-06','HO', '[
  ["DR","1103","10010000000017",118,"Late fee"],["CR","4102","4102",100,"Late fee 10010000000017"],
  ["CR","2201","2201",9,"CGST Late fee 10010000000017"],["CR","2202","2202",9,"SGST Late fee 10010000000017"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d110','REPAYMENT','2026-09-08','HO', '[["DR","1203","1203",500,"Receipt 10010000000017"],["CR","2302","10010000000017",500,"Excess receipt"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b101','00000000-0000-0000-0000-00000000aa01',1,'DISBURSEMENT','2026-06-30',100000,'{00000000-0000-0000-0000-00000000d101}','{}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b102','00000000-0000-0000-0000-00000000aa01',2,'EOD','2026-07-31',NULL,'{00000000-0000-0000-0000-00000000d102}','{"chargeSeq":0}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b103','00000000-0000-0000-0000-00000000aa01',3,'REPAYMENT','2026-07-31',9168,'{00000000-0000-0000-0000-00000000d103}','{"chargeSeq":0}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b104','00000000-0000-0000-0000-00000000aa01',4,'EOD','2026-08-30',NULL,'{00000000-0000-0000-0000-00000000d104}','{"chargeSeq":0}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b105','00000000-0000-0000-0000-00000000aa01',5,'EOD','2026-08-31',NULL,'{00000000-0000-0000-0000-00000000d105}','{"chargeSeq":0}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b106','00000000-0000-0000-0000-00000000aa01',6,'EOD','2026-09-05',NULL,'{00000000-0000-0000-0000-00000000d106}','{"chargeSeq":0}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b107','00000000-0000-0000-0000-00000000aa01',7,'FEE_CHARGE','2026-09-05',NULL,'{00000000-0000-0000-0000-00000000d107}','{"chargeSeq":1}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b108','00000000-0000-0000-0000-00000000aa01',8,'FEE_CHARGE','2026-09-06',NULL,'{00000000-0000-0000-0000-00000000d108}','{"chargeSeq":2}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b110','00000000-0000-0000-0000-00000000aa01',10,'REPAYMENT','2026-09-08',500,'{00000000-0000-0000-0000-00000000d110}','{"chargeSeq":2}');
-- L2: disbursed today; a documentation charge is charged to the account (not deducted), inter-state so IGST
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d201','DISBURSEMENT','2026-09-10','MUM', '[["DR","1101","10010000000025",200000,"Disbursement"],["CR","1202","1202",200000,"Net disbursal 10010000000025"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d202','FEE_CHARGE','2026-09-10','MUM', '[
  ["DR","1103","10010000000025",1180,"Documentation charge"],["CR","4105","4105",1000,"Documentation charge 10010000000025"],
  ["CR","2203","2203",180,"IGST Documentation charge 10010000000025"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b201','00000000-0000-0000-0000-00000000aa02',1,'DISBURSEMENT','2026-09-10',200000,
                   '{00000000-0000-0000-0000-00000000d201,00000000-0000-0000-0000-00000000d202}','{}');
-- L3: NPA; interest accrues to suspense
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d301','DISBURSEMENT','2026-01-15','MUM', '[["DR","1101","10010000000033",50000,"Disbursement"],["CR","1202","1202",50000,"Net disbursal 10010000000033"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d302','REPAYMENT','2026-03-01','MUM', '[["DR","1203","1203",10000,"Receipt 10010000000033"],["CR","1101","10010000000033",10000,"Principal"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d303','ACCRUAL','2026-09-05','MUM', '[["DR","1102","10010000000033",600,"Interest accrual"],["CR","2305","2305",600,"Interest accrual 10010000000033"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d304','REPAYMENT','2026-09-10','MUM', '[["DR","1203","1203",300,"Receipt 10010000000033"],["CR","1102","10010000000033",300,"Interest"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b301','00000000-0000-0000-0000-00000000aa03',1,'DISBURSEMENT','2026-01-15',50000,'{00000000-0000-0000-0000-00000000d301}','{}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b302','00000000-0000-0000-0000-00000000aa03',2,'REPAYMENT','2026-03-01',10000,'{00000000-0000-0000-0000-00000000d302}','{}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b303','00000000-0000-0000-0000-00000000aa03',3,'EOD','2026-09-05',NULL,'{00000000-0000-0000-0000-00000000d303}','{}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b304','00000000-0000-0000-0000-00000000aa03',4,'REPAYMENT','2026-09-10',300,'{00000000-0000-0000-0000-00000000d304}','{}');
-- L4: disbursed in May, pre-closed on 3-Sep
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d401','DISBURSEMENT','2026-05-01','HO', '[["DR","1101","10010000000041",20000,"Disbursement"],["CR","1202","1202",20000,"Net disbursal 10010000000041"]]');
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d402','PREPAYMENT','2026-09-03','HO', '[["DR","1203","1203",20000,"Prepayment 10010000000041"],["CR","1101","10010000000041",20000,"Principal prepayment"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b401','00000000-0000-0000-0000-00000000aa04',1,'DISBURSEMENT','2026-05-01',20000,'{00000000-0000-0000-0000-00000000d401}','{}');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b402','00000000-0000-0000-0000-00000000aa04',2,'PRECLOSURE','2026-09-03',20000,'{00000000-0000-0000-0000-00000000d402}','{}');
INSERT INTO lending.dpd_history VALUES
  ('00000000-0000-0000-0000-00000000aa01','2026-08-31',0,'STANDARD',92311,0),
  ('00000000-0000-0000-0000-00000000aa03','2026-08-31',120,'SUBSTANDARD',44000,8000);

-- ---------------------------------------------------------------------------------------------------------
-- N: number series
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check(platform.luhn_digit('1001000000001') = 5 AND platform.luhn_digit('7001000000004') = 6,
                     'N1 check digit equals the Java NumberSeries (10010000000015, 70010000000046)');
SELECT pg_temp.expect_fail($q$ SELECT platform.next_number('NOPE') $q$, 'P0002', 'N2 an unknown series is refused');
SELECT pg_temp.expect_fail($q$ INSERT INTO platform.number_series VALUES ('INVOICE2','7002',9,'platform.seq_gst_invoice') $q$, '23514',
                           'N3 the list of series is closed');

-- ---------------------------------------------------------------------------------------------------------
-- G: GST fee invoices
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check(lending.issue_fee_invoices(NULL) = 4, 'G1 one invoice per fee charged: 3 on the Kochi loan, 1 on the Mumbai loan');
SELECT pg_temp.check((SELECT array_agg(invoice_no || ':' || charge_ref || ':' || description ORDER BY invoice_no) FROM lending.fee_invoice)
                     = ARRAY['70010000000012:D1:Processing fee','70010000000020:C2:Bounce charge','70010000000038:C3:Late fee',
                             '70010000000046:C1:Documentation charge'],
                     'G2 numbered in date order from the GST_INVOICE series; charge ids follow the engine (penal took P1)');
SELECT pg_temp.check((SELECT (taxable_value, cgst, sgst, igst, total, gst_rate, supplier_state, place_of_supply, invoice_date)
                        = (750::numeric, 67.5::numeric, 67.5::numeric, 0::numeric, 885::numeric, 18::numeric, '32', '32', '2026-06-30'::date)
                        FROM lending.fee_invoice WHERE charge_ref = 'D1'),
                     'G3 fee deducted at disbursement: intra-state, CGST + SGST, rate from the product snapshot');
SELECT pg_temp.check((SELECT (taxable_value, cgst, sgst, igst, total, gst_rate, supplier_state, place_of_supply)
                        = (1000::numeric, 0::numeric, 0::numeric, 180::numeric, 1180::numeric, 18::numeric, '27', '32')
                        FROM lending.fee_invoice WHERE description = 'Documentation charge'),
                     'G4 Mumbai branch to a Kerala borrower: inter-state, IGST only, rate worked out from the tax');
SELECT pg_temp.check(lending.issue_fee_invoices(NULL) = 0, 'G5 issuing again adds nothing');
SELECT pg_temp.check((SELECT count(*) FROM lending.fee_invoice) = 4, 'G5b still four invoices');
-- the late fee is reversed
SELECT pg_temp.post('00000000-0000-0000-0000-00000000d109','REVERSAL','2026-09-06','HO', '[
  ["CR","1103","10010000000017",118,"Reversal: Late fee"],["DR","4102","4102",100,"Reversal"],["DR","2201","2201",9,"Reversal"],["DR","2202","2202",9,"Reversal"]]');
SELECT pg_temp.txn('00000000-0000-0000-0000-00000000b109','00000000-0000-0000-0000-00000000aa01',9,'REVERSAL','2026-09-06',NULL,'{00000000-0000-0000-0000-00000000d109}','{"chargeSeq":3}');
UPDATE lending.loan_txn SET reversed_by = '00000000-0000-0000-0000-00000000b109' WHERE id = '00000000-0000-0000-0000-00000000b108';
SELECT pg_temp.check(lending.issue_fee_invoices('00000000-0000-0000-0000-00000000aa01') = 0, 'G6 the reversal issues no new invoice');
SELECT pg_temp.check((SELECT (invoice_no, status, cancelled_on, cancelled_by_txn)
                             = ('70010000000038', 'CANCELLED', '2026-09-06'::date, '00000000-0000-0000-0000-00000000b109'::uuid)
                        FROM lending.fee_invoice WHERE charge_ref = 'C3'),
                     'G6b a reversed fee has its invoice cancelled, number kept');
SELECT pg_temp.check((SELECT count(*) FROM lending.fee_invoice WHERE status = 'ISSUED') = 3, 'G7 other invoices stay issued');
SELECT pg_temp.expect_fail($q$ UPDATE lending.fee_invoice SET taxable_value = 1 WHERE charge_ref = 'D1' $q$, '42501', 'G8 an invoice cannot be edited');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.fee_invoice $q$, '42501', 'G9 an invoice cannot be deleted');
SELECT pg_temp.expect_fail($q$ UPDATE lending.fee_invoice SET status = 'ISSUED', cancelled_on = NULL, cancelled_by_txn = NULL WHERE charge_ref = 'C3' $q$,
                           '42501', 'G10 a cancelled invoice cannot be re-issued');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_invoice (invoice_no,invoice_date,loan_id,txn_id,lot_id,charge_ref,description,branch_code,
      supplier_state,place_of_supply,taxable_value,cgst,sgst,igst)
    VALUES ('X1','2026-09-10','00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-00000000b107','00000000-0000-0000-0000-00000000d107',
            'C9','CLAUDE-TEST','HO','32','32',100,0,0,18) $q$, '23514', 'G11 IGST on an intra-state supply is refused');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.fee_invoice (invoice_no,invoice_date,loan_id,txn_id,lot_id,charge_ref,description,branch_code,
      supplier_state,place_of_supply,taxable_value,cgst,sgst,igst)
    VALUES ('X2','2026-09-10','00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-00000000b107','00000000-0000-0000-0000-00000000d107',
            'C2','CLAUDE-TEST again','HO','32','32',100,9,9,0) $q$, '23505', 'G12 one live invoice per charge');

-- ---------------------------------------------------------------------------------------------------------
-- B: balance and statement from the ledger
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT (principal, interest, charges, advance) = (92311::numeric, 1411::numeric, 620::numeric, 500::numeric)
                        FROM lending.loan_balance('00000000-0000-0000-0000-00000000aa01', '2026-09-10')),
                     'B1 balance by component, with the advance held apart (starter GL heads when the snapshot names none)');
SELECT pg_temp.check((SELECT (principal, interest, charges, advance) = (92311::numeric, 1411::numeric, 0::numeric, 0::numeric)
                        FROM lending.loan_balance('00000000-0000-0000-0000-00000000aa01', '2026-08-31')),
                     'B2 balance as at an earlier date');
SELECT pg_temp.check((SELECT (principal, interest, charges, advance) = (0::numeric, 0::numeric, 0::numeric, 0::numeric)
                        FROM lending.loan_balance('00000000-0000-0000-0000-00000000aa06', '2026-09-10')),
                     'B3 a loan with no entries has a zero balance (one row, not none)');
SELECT pg_temp.check((SELECT count(*) FROM lending.loan_statement('00000000-0000-0000-0000-00000000aa01', '2026-06-30', '2026-09-10')) = 9,
                     'B4 statement: one line per transaction, day-end accruals summed between transactions');
SELECT pg_temp.check((SELECT array_agg(particulars ORDER BY line_no) FROM lending.loan_statement('00000000-0000-0000-0000-00000000aa01', '2026-06-30', '2026-09-10'))
                     = ARRAY['Loan disbursed', 'Interest 31-Jul-2026', 'Payment received', 'Interest 30-Aug-2026 to 31-Aug-2026', 'Penal charges 05-Sep-2026',
                             'Charge: Bounce charge', 'Charge: Late fee', 'Reversal of earlier entries', 'Payment received'],
                     'B5 lines in true order with plain descriptions');
SELECT pg_temp.check((SELECT (sum(principal), sum(interest), sum(charges), sum(advance), sum(received))
                        = (92311::numeric, 1411::numeric, 620::numeric, 500::numeric, 9668::numeric)
                        FROM lending.loan_statement('00000000-0000-0000-0000-00000000aa01', '2026-06-30', '2026-09-10')),
                     'B6 lines add up to the closing balance');
SELECT pg_temp.check((SELECT (received, principal, interest, charges) = (9168::numeric, -7689::numeric, -1479::numeric, 0::numeric)
                        FROM lending.loan_statement('00000000-0000-0000-0000-00000000aa01', '2026-06-30', '2026-09-10') WHERE line_no = 3),
                     'B7 a receipt shows its principal / interest split');
SELECT pg_temp.check((SELECT (received, principal, interest, charges, advance) = (500::numeric, 0::numeric, 0::numeric, 0::numeric, 500::numeric)
                        FROM lending.loan_statement('00000000-0000-0000-0000-00000000aa01', '2026-06-30', '2026-09-10') WHERE line_no = 9),
                     'B8 money held as an advance is shown as received without reducing the dues');
SELECT pg_temp.check((SELECT (count(*), sum(principal), sum(interest), sum(charges)) = (5::bigint, 0::numeric, 0::numeric, 620::numeric)
                        FROM lending.loan_statement('00000000-0000-0000-0000-00000000aa01', '2026-09-01', '2026-09-10')),
                     'B9 a part period: opening (B2) plus its lines gives the closing balance (B1)');
SELECT pg_temp.check((SELECT (line_date, interest) = ('2026-08-31'::date, 1411::numeric)
                        FROM lending.loan_statement('00000000-0000-0000-0000-00000000aa01', '2026-06-30', '2026-09-10') WHERE line_no = 4),
                     'B10 the summed interest line is dated at its last day');
SELECT pg_temp.check((SELECT count(*) = 2 AND bool_and(due_date IN ('2026-07-31', '2026-08-31')) FROM lending.loan_demand WHERE loan_no = '10010000000017'),
                     'B11 demands read from the engine state (ISO date or [y,m,d])');

-- ---------------------------------------------------------------------------------------------------------
-- R: reports and branch scope
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) FROM reporting.report_definition) >= 9
                     AND (SELECT bool_and(to_regprocedure(sql_function || '(text,jsonb)') IS NOT NULL) FROM reporting.report_definition),
                     'R1 the nine reports of V16 (and those added later) in the catalogue, each with its function');
SELECT pg_temp.check((SELECT bool_and(permission = CASE code WHEN 'BUREAU_CONSUMER' THEN 'bureau:export' ELSE 'report:run' END)
                             AND bool_and(contains_pii = (code = 'BUREAU_CONSUMER')) AND bool_and(all_branches_only = (code = 'BUREAU_CONSUMER'))
                        FROM reporting.report_definition),
                     'R2 the bureau file needs bureau:export and all-branch access; the rest need report:run');
SELECT pg_temp.check((SELECT array_agg(branch_code || ':' || loans || ':' || principal_outstanding::int || ':' || overdue_amount::int || ':' || npa_loans || ':' || npa_principal::int
                                       ORDER BY branch_code) FROM reporting.rpt_loan_book('all.user', '{}'))
                     = ARRAY['HO:1:92311:9788:0:0', 'MUM:2:240000:12000:1:40000'],
                     'R3 loan book by branch and product: live loans only (closed and sanctioned loans are out)');
SELECT pg_temp.check((SELECT count(*) = 1 AND min(branch_code) = 'HO' FROM reporting.rpt_loan_book('ho.user', '{}'))
                     AND (SELECT count(*) = 1 AND min(branch_code) = 'MUM' FROM reporting.rpt_loan_book('mum.user', '{}'))
                     AND (SELECT count(*) = 0 FROM reporting.rpt_loan_book('nobody', '{}')),
                     'R4 branch scope: a user sees only their branches; an unknown user sees nothing');
SELECT pg_temp.check((SELECT array_agg(branch_code || ':' || loans || ':' || principal_outstanding::int ORDER BY branch_code)
                        FROM reporting.rpt_loan_book('all.user', '{"asOf":"2026-08-31"}'))
                     = ARRAY['HO:1:92311', 'MUM:1:44000'],
                     'R5 loan book as at an earlier day-end comes from the daily history');
SELECT pg_temp.check((SELECT count(*) = 14 FROM reporting.rpt_dpd_ageing('all.user', '{}'))
                     AND (SELECT array_agg(branch_code || ':' || bucket || ':' || loans || ':' || principal_outstanding::int ORDER BY branch_code, bucket)
                            FROM reporting.rpt_dpd_ageing('all.user', '{}') WHERE loans > 0)
                         = ARRAY['HO:1-30:1:92311', 'MUM:0:1:200000', 'MUM:91-180:1:40000'],
                     'R6 ageing: all seven buckets per branch, loans in the right bucket');
SELECT pg_temp.check((SELECT array_agg(reporting.dpd_bucket(d) ORDER BY d) FROM unnest(ARRAY[0,1,30,31,60,61,90,91,180,181,365,366]) d)
                     = ARRAY['0','1-30','1-30','31-60','31-60','61-90','61-90','91-180','91-180','181-365','181-365','>365'],
                     'R7 bucket edges');
SELECT pg_temp.check((SELECT (instalments_due, demand_principal, demand_interest, demand_total, collected_against_demand, demand_unpaid,
                              collection_efficiency_pct, receipts_in_period)
                             = (2::bigint, 15446::numeric, 2890::numeric, 18336::numeric, 9168::numeric, 9168::numeric, 50.00::numeric, 29668::numeric)
                        FROM reporting.rpt_collections_vs_demand('all.user', '{"from":"2026-07-01","to":"2026-09-10"}') WHERE branch_code = 'HO'),
                     'R8 collections against demand: Kochi 50% of two instalments; receipts include the advance and the pre-closure');
SELECT pg_temp.check((SELECT (demand_total, collected_against_demand, collection_efficiency_pct, receipts_in_period)
                             = (4600::numeric, 300::numeric, 6.52::numeric, 300::numeric)
                        FROM reporting.rpt_collections_vs_demand('mum.user', '{"from":"2026-07-01","to":"2026-09-10"}')),
                     'R9 collections against demand for the Mumbai user: only Mumbai');
SELECT pg_temp.check((SELECT array_agg(loan_no || ':' || disbursed_amount::int || ':' || deducted_at_disbursal::int || ':' || net_disbursed::int ORDER BY disbursed_on)
                        FROM reporting.rpt_disbursement_register('all.user', '{"from":"2026-06-01","to":"2026-09-10"}'))
                     = ARRAY['10010000000017:100000:885:99115', '10010000000025:200000:0:200000'],
                     'R10 disbursement register for the period with deductions and net payout');
SELECT pg_temp.check((SELECT count(*) = 0 FROM reporting.rpt_disbursement_register('ho.user', '{"from":"2026-09-01","to":"2026-09-10"}')),
                     'R11 disbursement register respects branch scope');
SELECT pg_temp.check((SELECT (loan_no, asset_class, npa_since, principal_outstanding, interest_suspense, provision_required, provision_held, provision_shortfall)
                             = ('10010000000033', 'SUBSTANDARD', '2026-08-01'::date, 40000::numeric, 1200::numeric, 4000::numeric, 3000::numeric, 1000::numeric)
                        FROM reporting.rpt_npa_register('all.user', '{}'))
                     AND (SELECT count(*) = 0 FROM reporting.rpt_npa_register('ho.user', '{}')),
                     'R12 NPA register: provision required at the table rate (10% sub-standard), held and shortfall');
SELECT pg_temp.check((SELECT array_agg(invoice_no || ':' || status || ':' || supply_type || ':' || place_of_supply_name ORDER BY invoice_no)
                        FROM reporting.rpt_gst_output_register('all.user', '{"from":"2026-09-01","to":"2026-09-10"}'))
                     = ARRAY['70010000000020:ISSUED:INTRA_STATE:Kerala', '70010000000038:CANCELLED:INTRA_STATE:Kerala', '70010000000046:ISSUED:INTER_STATE:Kerala'],
                     'R13 GST output register lists the period''s invoices, cancelled ones marked');
SELECT pg_temp.check((SELECT array_agg(supplier_state || '>' || place_of_supply || ':' || invoices || ':' || taxable_value::int || ':' || cgst::int || ':' || sgst::int || ':' || igst::int
                                       ORDER BY supplier_state)
                        FROM reporting.rpt_gst_output_summary('all.user', '{"from":"2026-06-01","to":"2026-09-10"}'))
                     = ARRAY['27>32:1:1000:0:0:180', '32>32:2:1250:113:113:0'],
                     'R14 GST by state and rate leaves cancelled invoices out (750 + 500 taxable; 112.50 each of CGST and SGST)');
SELECT pg_temp.check((SELECT count(*) = 1 FROM reporting.rpt_gst_output_register('mum.user', '{"from":"2026-06-01","to":"2026-09-10"}')),
                     'R15 GST register respects branch scope');
SELECT pg_temp.check((SELECT array_agg(movement || ':' || coalesce(debit::int::text, '-') || ':' || coalesce(credit::int::text, '-') || ':' || coalesce(balance::int::text, '-')
                                       ORDER BY CASE movement WHEN 'Opening balance' THEN 1 WHEN 'Closing balance' THEN 3 ELSE 2 END, movement)
                        FROM reporting.rpt_interest_accrual_suspense('all.user', '{"from":"2026-09-01","to":"2026-09-10"}')
                       WHERE branch_code = 'MUM' AND gl_code = '1102')
                     = ARRAY['Opening balance:-:-:0', 'ACCRUAL:600:0:-', 'REPAYMENT:0:300:-', 'Closing balance:-:-:300'],
                     'R16 interest receivable movement in Mumbai: accrued 600, received 300');
SELECT pg_temp.check((SELECT (balance) = -600 FROM reporting.rpt_interest_accrual_suspense('all.user', '{"from":"2026-09-01","to":"2026-09-10"}')
                       WHERE branch_code = 'MUM' AND gl_code = '2305' AND movement = 'Closing balance')
                     AND (SELECT (balance) = -2890 FROM reporting.rpt_interest_accrual_suspense('all.user', '{"from":"2026-09-01","to":"2026-09-10"}')
                           WHERE branch_code = 'HO' AND gl_code = '4101' AND movement = 'Opening balance')
                     AND (SELECT count(*) = 0 FROM reporting.rpt_interest_accrual_suspense('ho.user', '{"from":"2026-09-01","to":"2026-09-10"}')
                           WHERE branch_code = 'MUM'),
                     'R17 NPA interest sits in suspense (credit 600); income accrued earlier is the opening balance; branch scope holds');
SELECT pg_temp.expect_fail($q$ SELECT * FROM reporting.rpt_loan_book('all.user', '{"asOf":"not-a-date"}') $q$, '22007', 'R18 a parameter that is not a date is an error');
-- run log
INSERT INTO reporting.report_run (id, report_code, requested_by, business_date, parameters, status)
  VALUES ('00000000-0000-0000-0000-0000000000e1', 'LOAN_BOOK', 'all.user', '2026-09-10', '{}', 'RUNNING');
SELECT pg_temp.expect_fail($q$ UPDATE reporting.report_run SET status = 'COMPLETED', finished_at = now(), row_count = 2 WHERE id = '00000000-0000-0000-0000-0000000000e1' $q$,
                           '23514', 'R19 a completed run must name its artifact');
SELECT pg_temp.expect_fail($q$ UPDATE reporting.report_run SET status = 'COMPLETED', finished_at = now(), row_count = 2, artifact_key = '../etc/passwd'
                                WHERE id = '00000000-0000-0000-0000-0000000000e1' $q$, '23514', 'R20 an artifact key must be inside the tenant''s report folder');
UPDATE reporting.report_run SET status = 'COMPLETED', finished_at = now(), row_count = 2,
       artifact_key = 'tenants/claude-test/reports/2026-09-10/LOAN_BOOK-00000000-0000-0000-0000-0000000000e1.csv' WHERE id = '00000000-0000-0000-0000-0000000000e1';
SELECT pg_temp.expect_fail($q$ UPDATE reporting.report_run SET row_count = 3 WHERE id = '00000000-0000-0000-0000-0000000000e1' $q$, '42501',
                           'R21 a finished run cannot be changed');
SELECT pg_temp.expect_fail($q$ DELETE FROM reporting.report_run $q$, '42501', 'R22 report runs cannot be deleted');
SELECT pg_temp.expect_fail($q$ INSERT INTO reporting.report_definition (code, name, description, permission, sql_function)
                                VALUES ('BAD_ONE', 'x', 'x', 'report:run', 'pg_catalog.pg_sleep') $q$, '23514',
                           'R23 a report can only name a function in the reporting schema');

-- ---------------------------------------------------------------------------------------------------------
-- U: consumer-bureau extract
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT array_agg(loan_no ORDER BY loan_no) FROM reporting.bureau_consumer_rows('all.user', '{}'))
                     = ARRAY['10010000000017', '10010000000025', '10010000000033', '10010000000041'],
                     'U1 reported: live loans and the loan closed this month; not the one closed in July nor the undisbursed one');
SELECT pg_temp.check((SELECT (account_type, date_opened, date_last_payment, date_closed, current_balance, amount_overdue, dpd, asset_class, status,
                              customer_name, date_of_birth, gender, state_code, pincode)
                             = ('05', '2026-06-30'::date, '2026-09-08'::date, NULL::date, 94342::numeric, 9788::numeric, 10, 'SMA0', 'ACTIVE',
                                'CLAUDE-TEST One', '1990-01-15'::date, 'FEMALE', '32', '682001') IS NOT FALSE
                             AND date_closed IS NULL AND pan_cipher = '\x0101' AND mobile_cipher = '\x0102' AND address_cipher = '\x0a01'
                        FROM reporting.bureau_consumer_rows('all.user', '{}') WHERE loan_no = '10010000000017'),
                     'U2 one loan: balance from the ledger (principal + interest + charges), personal data still encrypted');
SELECT pg_temp.check((SELECT (status, date_closed, current_balance) = ('CLOSED', '2026-09-03'::date, 0::numeric)
                        FROM reporting.bureau_consumer_rows('all.user', '{}') WHERE loan_no = '10010000000041'),
                     'U3 a loan closed this month is reported closed with a zero balance');
SELECT pg_temp.check((SELECT (state_code, pan_cipher IS NULL, current_balance, asset_class) = ('32', true, 40300::numeric, 'SUBSTANDARD')
                        FROM reporting.bureau_consumer_rows('all.user', '{}') WHERE loan_no = '10010000000033'),
                     'U4 a state stored as KL is reported with its GST code 32');
SELECT pg_temp.check((SELECT array_agg(loan_no || ':' || dpd || ':' || asset_class || ':' || amount_overdue::int || ':' || current_balance::int ORDER BY loan_no)
                        FROM reporting.bureau_consumer_rows('all.user', '{"asOf":"2026-08-31"}'))
                     = ARRAY['10010000000017:0:STANDARD:0:93722', '10010000000033:120:SUBSTANDARD:8000:40000'],
                     'U5 an earlier reporting date uses that day-end''s DPD and class, and the ledger balance of that date');
SELECT pg_temp.check((SELECT count(*) = 2 FROM reporting.bureau_consumer_rows('ho.user', '{}')), 'U6 the extract is branch-scoped too');
DELETE FROM platform.system_property WHERE key = 'bureau.account-type.default';
SELECT pg_temp.check((SELECT bool_and(account_type IS NULL) FROM reporting.bureau_consumer_rows('all.user', '{}')),
                     'U7 no account type is assumed when none is configured');
INSERT INTO platform.system_property (key, value, updated_by) VALUES ('bureau.account-type.pl01','05','test');
SELECT pg_temp.check((SELECT bool_and(account_type = '05') FROM reporting.bureau_consumer_rows('all.user', '{}')),
                     'U8 the account type can be set per product');

-- ---------------------------------------------------------------------------------------------------------
-- D: dashboard
-- ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT (active_loans, portfolio_outstanding, overdue_amount, gross_npa, npa_loans, npa_percent)
                             = (3::bigint, 332311::numeric, 21788::numeric, 40000::numeric, 1::bigint, 12.04::numeric)
                        FROM reporting.dashboard_portfolio('all.user')),
                     'D1 portfolio: 3 live loans, NPA % = gross NPA / gross advances');
SELECT pg_temp.check((SELECT (active_loans, portfolio_outstanding, gross_npa, npa_percent) = (1::bigint, 92311::numeric, 0::numeric, 0.00::numeric)
                        FROM reporting.dashboard_portfolio('ho.user'))
                     AND (SELECT (active_loans, portfolio_outstanding) = (0::bigint, 0::numeric) AND npa_percent IS NULL
                            FROM reporting.dashboard_portfolio('nobody')),
                     'D2 portfolio for one branch; nothing (and no percentage) for a user with no scope');
SELECT pg_temp.check((SELECT (business_date, disbursed_today, disbursed_mtd, disbursements_today, disbursements_mtd, collected_today, collected_mtd,
                              demand_mtd, collected_against_demand_mtd, collection_efficiency_mtd)
                             = ('2026-09-10'::date, 200000::numeric, 200000::numeric, 1::bigint, 1::bigint, 300::numeric, 20800::numeric,
                                4600::numeric, 300::numeric, 6.52::numeric)
                        FROM reporting.dashboard_flows('all.user')),
                     'D3 flows: disbursed and collected today and month to date, efficiency on instalments due this month');
SELECT pg_temp.check((SELECT (disbursed_mtd, collected_today, collected_mtd, demand_mtd) = (0::numeric, 0::numeric, 20500::numeric, 0::numeric)
                             AND collection_efficiency_mtd IS NULL
                        FROM reporting.dashboard_flows('ho.user')),
                     'D4 flows for Kochi only; no efficiency when nothing fell due');
SELECT pg_temp.check((SELECT array_agg(bucket || ':' || loans || ':' || amount::int ORDER BY sort_order) FROM reporting.dashboard_dpd('all.user'))
                     = ARRAY['0:1:200000', '1-30:1:92311', '31-60:0:0', '61-90:0:0', '91-180:1:40000', '181-365:0:0', '>365:0:0'],
                     'D5 DPD distribution: count and amount for every bucket');
INSERT INTO platform.approval_request (id, entity_type, action, payload, maker, branch_code) VALUES
  (gen_random_uuid(), 'CUSTOMER', 'CREATE', '{}', 'maker', 'HO'), (gen_random_uuid(), 'CUSTOMER', 'CREATE', '{}', 'maker', 'MUM'),
  (gen_random_uuid(), 'GL_HEAD', 'CREATE', '{}', 'maker', NULL);
INSERT INTO platform.approval_request (id, entity_type, action, payload, maker, branch_code, status, checker, checked_at)
  VALUES (gen_random_uuid(), 'CUSTOMER', 'CREATE', '{}', 'maker', 'HO', 'APPROVED', 'checker', now());
SELECT pg_temp.check((SELECT pending_approvals = 3 AND last_eod_status IS NULL FROM reporting.dashboard_operations('all.user'))
                     AND (SELECT pending_approvals = 1 FROM reporting.dashboard_operations('mum.user')),
                     'D6 pending approvals in scope (those without a branch only for all-branch users); no end of day yet');
INSERT INTO platform.eod_run (business_date, finished_at, status) VALUES ('2026-09-08', now(), 'COMPLETED'), ('2026-09-09', now(), 'COMPLETED_WITH_EXCEPTIONS');
INSERT INTO platform.eod_exception (run_id, step, account_no, error) SELECT max(id), 'Loan day-end', '10010000000017', 'CLAUDE-TEST failure' FROM platform.eod_run;
SELECT pg_temp.check((SELECT (last_eod_business_date, last_eod_status, open_eod_exceptions) = ('2026-09-09'::date, 'COMPLETED_WITH_EXCEPTIONS', 1::bigint)
                        FROM reporting.dashboard_operations('all.user'))
                     AND (SELECT open_eod_exceptions = 0 FROM reporting.dashboard_operations('ho.user')),
                     'D7 last end of day and its open exceptions (exceptions shown to all-branch users only)');
\echo ALL DOCUMENT AND REPORT TESTS PASSED
