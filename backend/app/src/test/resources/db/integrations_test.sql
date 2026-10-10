-- P2-2 (V20): integrations — provider configuration, payouts, gateway collections, mandates and NACH, webhooks,
-- API clients, messages. Run on a fresh tenant database after migrations. All data is fake (CLAUDE-TEST).
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
INSERT INTO platform.business_day VALUES (1,'2026-10-30','OPEN');                      -- a Friday
INSERT INTO platform.weekly_off (branch_code, day_of_week, week_of_month) VALUES (NULL, 7, NULL);
INSERT INTO platform.holiday (branch_code, day, reason) VALUES (NULL, '2026-11-02', 'CLAUDE-TEST holiday');
SELECT ledger.load_starter_kit('NBFC');
SELECT ledger.load_lending_heads();
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status)
VALUES ('PL01','CLAUDE-TEST Personal loan','EQUATED',10000,500000,6,60,12,24,24,'1101','4101','1102','ACTIVE');
INSERT INTO customer.customer (id,customer_no,customer_type,display_name,home_branch,status) VALUES
  ('00000000-0000-0000-0000-0000000000c1','90010000000013','INDIVIDUAL','CLAUDE-TEST Borrower','HO','ACTIVE'),
  ('00000000-0000-0000-0000-0000000000c2','90010000000021','INDIVIDUAL','CLAUDE-TEST Second','HO','ACTIVE');
INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,created_by) VALUES
  ('00000000-0000-0000-0000-00000000aa01','1001000000017','00000000-0000-0000-0000-0000000000c1','PL01','HO',100000,18,12,'2026-10-30','SANCTIONED','maker'),
  ('00000000-0000-0000-0000-00000000aa02','1001000000025','00000000-0000-0000-0000-0000000000c2','PL01','HO',100000,18,12,'2026-10-30','SANCTIONED','maker');
INSERT INTO lending.loan_txn (id,loan_id,seq,txn_type,value_date,business_date,amount,state_before,created_by) VALUES
  ('00000000-0000-0000-0000-00000000bb01','00000000-0000-0000-0000-00000000aa01',1,'REPAYMENT','2026-10-30','2026-10-30',5200,'{}','system'),
  ('00000000-0000-0000-0000-00000000bb02','00000000-0000-0000-0000-00000000aa01',2,'REPAYMENT','2026-10-30','2026-10-30',5200,'{}','system');

-- 1. Helpers --------------------------------------------------------------------------------------------------
SELECT pg_temp.check(integration.property('nach.format', 'GENERIC') = 'GENERIC', 'H1 a missing property gives the default');
INSERT INTO platform.system_property (key, value, updated_by) VALUES
  ('nach.presentation.lead-days', '4', 'maker'), ('webhook.max-attempts', '500', 'maker'), ('notify.max-attempts', 'many', 'maker');
SELECT pg_temp.check(integration.int_property('nach.presentation.lead-days', 2, 0, 10) = 4, 'H2 a number in range is used');
SELECT pg_temp.check(integration.int_property('webhook.max-attempts', 12, 1, 30) = 12, 'H3 a number out of range falls back to the default');
SELECT pg_temp.check(integration.int_property('notify.max-attempts', 5, 1, 20) = 5, 'H4 text that is not a number falls back to the default');
-- Friday 30-Oct; Sunday 1-Nov is the weekly off; Monday 2-Nov is a holiday
SELECT pg_temp.check(integration.working_days_after('2026-10-30', 0) = '2026-10-30', 'H5 zero working days is the date itself');
SELECT pg_temp.check(integration.working_days_after('2026-10-30', 2) = '2026-11-03', 'H6 working days skip the weekly off and the holiday');
SELECT pg_temp.expect_fail($q$ SELECT integration.working_days_after('2026-10-30', -1) $q$, '22023', 'H7 a negative count is refused');

-- 2. Provider configuration -------------------------------------------------------------------------------------
INSERT INTO integration.provider_config (id, kind, provider, settings, secrets_cipher, secret_hints, version, updated_by) VALUES
  ('00000000-0000-0000-0000-0000000f0001','PAYOUT','SIMULATOR','{}','\x01'::bytea,'{"webhookSecret":"t-01"}',1,'maker');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.provider_config (id, kind, provider, settings, version, updated_by)
  VALUES (gen_random_uuid(),'COLLECTION','EASEBUZZ','{"environment":"test","salt":"CLAUDETESTSALT"}',1,'maker') $q$,
  '23514', 'C1 a secret cannot be stored as a plain setting');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.provider_config (id, kind, provider, settings, version, updated_by)
  VALUES (gen_random_uuid(),'COLLECTION','EASEBUZZ','{"key":"CLAUDETESTKEY"}',1,'maker') $q$,
  '23514', 'C2 nor under the name "key"');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.provider_config (id, kind, provider, secret_hints, version, updated_by)
  VALUES (gen_random_uuid(),'COLLECTION','EASEBUZZ','{"salt":"CLAUDETESTSALT"}',1,'maker') $q$,
  '23514', 'C3 a secret hint holds at most four characters');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.provider_config (id, kind, provider, version, updated_by)
  VALUES (gen_random_uuid(),'PAYOUT','EASEBUZZ',2,'maker') $q$, '23505', 'C4 one active provider per kind');
SELECT pg_temp.expect_fail($q$ UPDATE integration.provider_config SET settings = '{"mode":"NEFT"}' WHERE kind = 'PAYOUT' $q$,
  '42501', 'C5 a configuration is replaced by a new version, not edited');
UPDATE integration.provider_config SET status = 'INACTIVE' WHERE kind = 'PAYOUT';
INSERT INTO integration.provider_config (id, kind, provider, version, updated_by) VALUES (gen_random_uuid(),'PAYOUT','SIMULATOR',2,'maker');
SELECT pg_temp.check((SELECT count(*) FROM integration.provider_config WHERE kind = 'PAYOUT') = 2
                     AND (SELECT version FROM integration.provider_config WHERE kind = 'PAYOUT' AND status = 'ACTIVE') = 2,
                     'C6 the old version stays as history and the new one is active');
SELECT pg_temp.expect_fail($q$ UPDATE integration.provider_config SET status = 'ACTIVE' WHERE version = 1 $q$, '42501', 'C7 a superseded version cannot come back');
SELECT pg_temp.expect_fail($q$ DELETE FROM integration.provider_config $q$, '42501', 'C8 configurations are not deleted');

-- 3. Payouts ----------------------------------------------------------------------------------------------------
INSERT INTO integration.beneficiary (id, loan_id, customer_id, holder_name_cipher, account_cipher, account_last4, account_hash, ifsc, validation, created_by) VALUES
  ('00000000-0000-0000-0000-0000000b0001','00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000c1','\x01','\x02','1234','\x03','TEST0000001','VALID','maker');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.beneficiary (id, loan_id, customer_id, holder_name_cipher, account_cipher, account_last4, account_hash, ifsc, created_by)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000c1','\x01','\x02','5678','\x04','TEST0000001','maker') $q$,
  '23505', 'B1 one active beneficiary per loan');
SELECT pg_temp.expect_fail($q$ UPDATE integration.beneficiary SET account_cipher = '\x09' $q$, '42501', 'B2 a beneficiary account is replaced, never edited');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.beneficiary (id, loan_id, customer_id, holder_name_cipher, account_cipher, account_last4, account_hash, ifsc, created_by)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-00000000aa02','00000000-0000-0000-0000-0000000000c2','\x01','\x02','5678','\x04','BADIFSC','maker') $q$,
  '23514', 'B3 the IFSC format is checked');

INSERT INTO integration.payout_instruction (id, reference, loan_id, loan_no, customer_id, branch_code, attempt_no, amount, beneficiary_id) VALUES
  ('00000000-0000-0000-0000-0000000d0001','PO1001000000017A1','00000000-0000-0000-0000-00000000aa01','1001000000017','00000000-0000-0000-0000-0000000000c1','HO',1,98200,'00000000-0000-0000-0000-0000000b0001');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.payout_instruction (id, reference, loan_id, loan_no, customer_id, branch_code, attempt_no, amount)
  VALUES (gen_random_uuid(),'PO1001000000017A2','00000000-0000-0000-0000-00000000aa01','1001000000017','00000000-0000-0000-0000-0000000000c1','HO',2,98200) $q$,
  '23505', 'O1 a loan never has two payouts in flight');
SELECT pg_temp.expect_fail($q$ UPDATE integration.payout_instruction SET status = 'SENT' WHERE attempt_no = 1 $q$, '23514', 'O2 a payout cannot be SENT without a provider');
UPDATE integration.payout_instruction SET status = 'SENT', provider = 'SIMULATOR', provider_ref = 'SIMPOS0001' WHERE attempt_no = 1;
SELECT pg_temp.expect_fail($q$ UPDATE integration.payout_instruction SET status = 'INITIATED' WHERE attempt_no = 1 $q$, '23514', 'O3 a payout does not go backwards');
SELECT pg_temp.expect_fail($q$ UPDATE integration.payout_instruction SET amount = 99000 WHERE attempt_no = 1 $q$, '42501', 'O4 the amount of a payout cannot change');
SELECT pg_temp.expect_fail($q$ UPDATE integration.payout_instruction SET reference = 'PO9' WHERE attempt_no = 1 $q$, '42501', 'O5 nor its reference');
UPDATE integration.payout_instruction SET status = 'SUCCESS', utr = 'SIMUTR0001' WHERE attempt_no = 1;
SELECT pg_temp.expect_fail($q$ UPDATE integration.payout_instruction SET status = 'FAILED' WHERE attempt_no = 1 $q$, '23514', 'O6 a paid payout cannot become FAILED');
UPDATE integration.payout_instruction SET status = 'RETURNED', failure_action = 'PARKED' WHERE attempt_no = 1;
SELECT pg_temp.expect_fail($q$ UPDATE integration.payout_instruction SET status = 'SUCCESS' WHERE attempt_no = 1 $q$, '23514', 'O7 a returned payout is final');
INSERT INTO integration.payout_instruction (id, reference, loan_id, loan_no, customer_id, branch_code, attempt_no, amount, status)
  VALUES (gen_random_uuid(),'PO1001000000017A2','00000000-0000-0000-0000-00000000aa01','1001000000017','00000000-0000-0000-0000-0000000000c1','HO',2,98200,'ON_HOLD');
SELECT pg_temp.check((SELECT count(*) FROM integration.payout_instruction WHERE loan_id = '00000000-0000-0000-0000-00000000aa01') = 2,
                     'O8 after a return a new attempt can be made');
SELECT pg_temp.expect_fail($q$ UPDATE integration.payout_instruction SET status = 'SENT', provider = 'SIMULATOR' WHERE attempt_no = 2 $q$,
  '23514', 'O9 a payout on hold must be resumed before it is sent');
SELECT pg_temp.expect_fail($q$ DELETE FROM integration.payout_instruction $q$, '42501', 'O10 payouts are not deleted');
INSERT INTO integration.payout_event (payout_id, from_status, to_status, source, actor) VALUES ('00000000-0000-0000-0000-0000000d0001','SENT','SUCCESS','WEBHOOK','system');
SELECT pg_temp.expect_fail($q$ UPDATE integration.payout_event SET to_status = 'FAILED' $q$, '42501', 'O11 the payout history is append-only');

-- 4. Gateway collections ------------------------------------------------------------------------------------------
INSERT INTO integration.collection_order (id, reference, loan_id, loan_no, branch_code, amount, methods, provider, expires_at, created_by) VALUES
  ('00000000-0000-0000-0000-0000000e0001','CO1001000000017N1','00000000-0000-0000-0000-00000000aa01','1001000000017','HO',5200,'{UPI,CARD}','SIMULATOR',now() + interval '3 days','maker');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.collection_order (id, reference, loan_id, loan_no, branch_code, amount, methods, provider, expires_at, created_by)
  VALUES (gen_random_uuid(),'CO1001000000017N2','00000000-0000-0000-0000-00000000aa01','1001000000017','HO',5200,'{CHEQUE}','SIMULATOR',now(),'maker') $q$,
  '23514', 'G1 only known payment methods are passed through');
UPDATE integration.collection_order SET status = 'EXPIRED' WHERE reference = 'CO1001000000017N1';
UPDATE integration.collection_order SET status = 'PAID' WHERE reference = 'CO1001000000017N1';
SELECT pg_temp.check((SELECT status FROM integration.collection_order WHERE reference = 'CO1001000000017N1') = 'PAID', 'G2 a payment on an expired link still counts');
SELECT pg_temp.expect_fail($q$ UPDATE integration.collection_order SET status = 'FAILED' WHERE reference = 'CO1001000000017N1' $q$, '23514', 'G3 a paid order stays paid');

INSERT INTO integration.collection_payment (id, provider, provider_payment_id, order_id, loan_id, amount, paid_at, source) VALUES
  ('00000000-0000-0000-0000-0000000a0001','SIMULATOR','SIMPAY0001','00000000-0000-0000-0000-0000000e0001','00000000-0000-0000-0000-00000000aa01',5200,'2026-10-30 10:00+05:30','WEBHOOK'),
  ('00000000-0000-0000-0000-0000000a0002','SIMULATOR','SIMPAY0002',NULL,'00000000-0000-0000-0000-00000000aa01',5200,'2026-10-30 11:00+05:30','WEBHOOK'),
  ('00000000-0000-0000-0000-0000000a0003','SIMULATOR','SIMPAY0003',NULL,NULL,700,'2026-10-30 12:00+05:30','WEBHOOK');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.collection_payment (id, provider, provider_payment_id, amount, paid_at, source)
  VALUES (gen_random_uuid(),'SIMULATOR','SIMPAY0001',5200,now(),'WEBHOOK') $q$, '23505', 'G4 a provider payment id is recorded once');
SELECT pg_temp.expect_fail($q$ UPDATE integration.collection_payment SET status = 'POSTED' WHERE provider_payment_id = 'SIMPAY0001' $q$,
  '23514', 'G5 POSTED needs the loan transaction it posted');
UPDATE integration.collection_payment SET status = 'POSTED', loan_txn_id = '00000000-0000-0000-0000-00000000bb01', value_date = '2026-10-30', next_attempt_at = NULL
 WHERE provider_payment_id = 'SIMPAY0001';
SELECT pg_temp.expect_fail($q$ UPDATE integration.collection_payment SET status = 'POSTED', loan_txn_id = '00000000-0000-0000-0000-00000000bb01', value_date = '2026-10-30'
  WHERE provider_payment_id = 'SIMPAY0002' $q$, '23505', 'G6 one loan transaction belongs to one gateway payment');
SELECT pg_temp.expect_fail($q$ UPDATE integration.collection_payment SET status = 'UNMATCHED', loan_txn_id = NULL WHERE provider_payment_id = 'SIMPAY0001' $q$,
  '42501', 'G7 a posted payment cannot change');
SELECT pg_temp.expect_fail($q$ UPDATE integration.collection_payment SET amount = 1 WHERE provider_payment_id = 'SIMPAY0002' $q$, '42501', 'G8 the amount received cannot be edited');
UPDATE integration.collection_payment SET status = 'UNMATCHED', next_attempt_at = NULL, last_error = 'no order with this reference' WHERE provider_payment_id = 'SIMPAY0003';
SELECT pg_temp.expect_fail($q$ UPDATE integration.collection_payment SET status = 'POSTED', loan_txn_id = '00000000-0000-0000-0000-00000000bb02', value_date = '2026-10-30'
  WHERE provider_payment_id = 'SIMPAY0003' $q$, '23514', 'G9 an unmatched receipt is assigned to a loan before it can be posted');
SELECT pg_temp.expect_fail($q$ DELETE FROM integration.collection_payment $q$, '42501', 'G10 gateway payments are not deleted');

INSERT INTO integration.gateway_settlement (provider, provider_payment_id, amount, fee, settled_on, file_ref, imported_by) VALUES
  ('SIMULATOR','SIMPAY0001',5200,12,'2026-10-31','CLAUDE-TEST-settlement-1','maker'),
  ('SIMULATOR','SIMPAY0009',900,2,'2026-10-31','CLAUDE-TEST-settlement-1','maker');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.gateway_settlement (provider, provider_payment_id, amount, settled_on, file_ref, imported_by)
  VALUES ('SIMULATOR','SIMPAY0001',5200,'2026-10-31','CLAUDE-TEST-settlement-2','maker') $q$, '23505', 'G11 a settlement line is loaded once');
SELECT pg_temp.check((SELECT category FROM integration.collection_reconciliation WHERE provider_payment_id = 'SIMPAY0001') = 'MATCHED', 'R1 posted and settled is MATCHED');
SELECT pg_temp.check((SELECT category FROM integration.collection_reconciliation WHERE provider_payment_id = 'SIMPAY0002') = 'PAYMENT_NOT_POSTED', 'R2 received but not posted is in the queue');
SELECT pg_temp.check((SELECT category FROM integration.collection_reconciliation WHERE provider_payment_id = 'SIMPAY0003') = 'PAYMENT_NOT_POSTED', 'R3 an unmatched receipt is in the queue');
SELECT pg_temp.check((SELECT category FROM integration.collection_reconciliation WHERE provider_payment_id = 'SIMPAY0009') = 'SETTLED_NOT_RECEIVED', 'R4 a settlement without a payment shows the other direction');
UPDATE integration.collection_payment SET status = 'POSTED', loan_txn_id = '00000000-0000-0000-0000-00000000bb02', value_date = '2026-10-30', next_attempt_at = NULL
 WHERE provider_payment_id = 'SIMPAY0002';
SELECT pg_temp.check((SELECT category FROM integration.collection_reconciliation WHERE provider_payment_id = 'SIMPAY0002') = 'POSTED_NOT_SETTLED', 'R5 posted without a settlement line');
INSERT INTO integration.gateway_settlement (provider, provider_payment_id, amount, settled_on, file_ref, imported_by) VALUES ('SIMULATOR','SIMPAY0002',5100,'2026-10-31','CLAUDE-TEST-settlement-3','maker');
SELECT pg_temp.check((SELECT category FROM integration.collection_reconciliation WHERE provider_payment_id = 'SIMPAY0002') = 'AMOUNT_MISMATCH', 'R6 a different settled amount is a mismatch');
UPDATE integration.collection_payment SET status = 'REFUND_DUE', resolved_by = 'ops', resolution_note = 'CLAUDE-TEST refund' WHERE provider_payment_id = 'SIMPAY0003';
SELECT pg_temp.check((SELECT category FROM integration.collection_reconciliation WHERE provider_payment_id = 'SIMPAY0003') = 'REFUND_DUE', 'R7 a receipt marked for refund');

-- provider callbacks: replay protection
INSERT INTO integration.inbound_event (id, kind, provider, event_id, body_cipher, body_sha256, parsed) VALUES
  (gen_random_uuid(),'COLLECTION','SIMULATOR','sim-evt-1','\x01',repeat('a',64),'{"status":"PAID"}');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.inbound_event (id, kind, provider, event_id, body_cipher, body_sha256, parsed)
  VALUES (gen_random_uuid(),'COLLECTION','SIMULATOR','sim-evt-1','\x01',repeat('a',64),'{"status":"PAID"}') $q$, '23505', 'I1 a provider event id is stored once');
INSERT INTO integration.inbound_event (id, kind, provider, event_id, body_cipher, body_sha256, parsed)
  VALUES (gen_random_uuid(),'COLLECTION','SIMULATOR','sim-evt-1','\x01',repeat('a',64),'{"status":"PAID"}') ON CONFLICT (provider, kind, event_id) DO NOTHING;
SELECT pg_temp.check((SELECT count(*) FROM integration.inbound_event) = 1, 'I2 a replayed callback leaves one row');
SELECT pg_temp.expect_fail($q$ UPDATE integration.inbound_event SET parsed = '{"status":"FAILED"}' $q$, '42501', 'I3 what was received cannot be altered');

-- 5. Mandates and NACH --------------------------------------------------------------------------------------------
INSERT INTO integration.mandate (id, mandate_ref, loan_id, loan_no, customer_id, branch_code, max_amount, frequency, start_date, holder_name_cipher,
                                 account_cipher, account_last4, ifsc, account_type, sponsor_bank_code, utility_code, created_by) VALUES
  ('00000000-0000-0000-0000-0000000c0001','MD1001000000017N1','00000000-0000-0000-0000-00000000aa01','1001000000017','00000000-0000-0000-0000-0000000000c1','HO',
   10000,'MONTHLY','2026-11-01','\x01','\x02','1234','TEST0000001','SB','TESTBANK','TESTUTIL01','maker');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.mandate (id, mandate_ref, loan_id, loan_no, customer_id, branch_code, max_amount, frequency, start_date,
    holder_name_cipher, account_cipher, account_last4, ifsc, account_type, sponsor_bank_code, utility_code, created_by)
  VALUES (gen_random_uuid(),'MD1001000000017N2','00000000-0000-0000-0000-00000000aa01','1001000000017','00000000-0000-0000-0000-0000000000c1','HO',
   10000,'MONTHLY','2026-11-01','\x01','\x02','1234','TEST0000001','SB','TESTBANK','TESTUTIL01','maker') $q$, '23505', 'M1 one mandate per loan in registration or in force');
SELECT pg_temp.expect_fail($q$ UPDATE integration.mandate SET status = 'ACTIVE', umrn = 'SIMB0000000000000001' WHERE mandate_ref = 'MD1001000000017N1' $q$,
  '23514', 'M2 a draft mandate is submitted before it can be active');
UPDATE integration.mandate SET status = 'SUBMITTED' WHERE mandate_ref = 'MD1001000000017N1';
SELECT pg_temp.expect_fail($q$ UPDATE integration.mandate SET status = 'ACTIVE' WHERE mandate_ref = 'MD1001000000017N1' $q$, '23514', 'M3 an active mandate needs its UMRN');
SELECT pg_temp.expect_fail($q$ UPDATE integration.mandate SET status = 'ACTIVE', umrn = 'short' WHERE mandate_ref = 'MD1001000000017N1' $q$, '23514', 'M4 the UMRN is 20 characters');
UPDATE integration.mandate SET status = 'ACTIVE', umrn = 'SIMB0000000000000001' WHERE mandate_ref = 'MD1001000000017N1';
SELECT pg_temp.expect_fail($q$ UPDATE integration.mandate SET umrn = 'SIMB0000000000000002' WHERE mandate_ref = 'MD1001000000017N1' $q$, '42501', 'M5 a UMRN, once given, stays');
SELECT pg_temp.expect_fail($q$ UPDATE integration.mandate SET max_amount = 99999 WHERE mandate_ref = 'MD1001000000017N1' $q$, '42501', 'M6 the mandate limit cannot be edited');
SELECT pg_temp.expect_fail($q$ UPDATE integration.mandate SET account_cipher = '\x09' WHERE mandate_ref = 'MD1001000000017N1' $q$, '42501', 'M7 nor the debit account');
SELECT pg_temp.check((SELECT status || '/' || umrn FROM integration.loan_mandate WHERE loan_id = '00000000-0000-0000-0000-00000000aa01') = 'ACTIVE/SIMB0000000000000001',
                     'M8 the mandate status of a loan');
INSERT INTO integration.mandate (id, mandate_ref, loan_id, loan_no, customer_id, branch_code, max_amount, frequency, start_date, holder_name_cipher,
                                 account_cipher, account_last4, ifsc, account_type, sponsor_bank_code, utility_code, status, created_by) VALUES
  (gen_random_uuid(),'MD1001000000025N1','00000000-0000-0000-0000-00000000aa02','1001000000025','00000000-0000-0000-0000-0000000000c2','HO',
   10000,'MONTHLY','2026-11-01','\x01','\x02','0001','TEST0000001','SB','TESTBANK','TESTUTIL01','SUBMITTED','maker');
UPDATE integration.mandate SET status = 'REJECTED', reject_code = 'SIM_REJECTED' WHERE mandate_ref = 'MD1001000000025N1';
SELECT pg_temp.expect_fail($q$ UPDATE integration.mandate SET status = 'ACTIVE', umrn = 'SIMB0000000000000009' WHERE mandate_ref = 'MD1001000000025N1' $q$,
  '23514', 'M9 a rejected mandate cannot become active');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.mandate (id, mandate_ref, loan_id, loan_no, customer_id, branch_code, max_amount, frequency, start_date, end_date,
    holder_name_cipher, account_cipher, account_last4, ifsc, account_type, sponsor_bank_code, utility_code, created_by)
  VALUES (gen_random_uuid(),'MD1001000000025N2','00000000-0000-0000-0000-00000000aa02','1001000000025','00000000-0000-0000-0000-0000000000c2','HO',
   10000,'MONTHLY','2026-11-01','2026-10-01','\x01','\x02','0001','TEST0000001','SB','TESTBANK','TESTUTIL01','maker') $q$, '23514', 'M10 a mandate ends after it starts');

SELECT pg_temp.check((SELECT count(*) FROM integration.nach_return_reason) = 27 AND NOT EXISTS (SELECT 1 FROM integration.nach_return_reason WHERE verified),
                     'N1 the return reason list is loaded, and marked unverified');
SELECT pg_temp.check((SELECT representable FROM integration.nach_return_reason WHERE code = '04')
                     AND NOT (SELECT representable FROM integration.nach_return_reason WHERE code = '01'),
                     'N2 insufficient funds may be presented again, a closed account may not');
INSERT INTO integration.nach_file (id, direction, file_ref, format, encoding, settlement_date, record_count, total_amount, content_sha256, status, created_by) VALUES
  ('00000000-0000-0000-0000-0000000f1001','PRESENTATION','NP20261105-01','GENERIC','FIXED','2026-11-05',1,5200,repeat('a',64),'GENERATED','eod'),
  ('00000000-0000-0000-0000-0000000f1002','RESPONSE','NP20261105-01','GENERIC','FIXED','2026-11-05',1,5200,repeat('b',64),'RECEIVED','ops');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.nach_file (id, direction, file_ref, format, encoding, content_sha256, status, created_by)
  VALUES (gen_random_uuid(),'RESPONSE','NP20261105-01','GENERIC','FIXED',repeat('b',64),'RECEIVED','ops') $q$, '23505', 'N3 the same response file is never taken twice');
UPDATE integration.nach_file SET status = 'PROCESSED', control_key = 'NP20261105-01|1|5200|1|5200' WHERE id = '00000000-0000-0000-0000-0000000f1002';
INSERT INTO integration.nach_file (id, direction, file_ref, format, encoding, content_sha256, status, created_by)
  VALUES ('00000000-0000-0000-0000-0000000f1003','RESPONSE','NP20261105-01','GENERIC','CSV',repeat('c',64),'RECEIVED','ops');
SELECT pg_temp.expect_fail($q$ UPDATE integration.nach_file SET status = 'PROCESSED', control_key = 'NP20261105-01|1|5200|1|5200' WHERE id = '00000000-0000-0000-0000-0000000f1003' $q$,
  '23505', 'N4 a re-sent response with the same control totals is not processed again');
SELECT pg_temp.expect_fail($q$ UPDATE integration.nach_file SET content_sha256 = repeat('d',64) WHERE id = '00000000-0000-0000-0000-0000000f1001' $q$, '42501', 'N5 the hash of a file cannot be changed');

INSERT INTO integration.nach_presentation (id, file_id, seq, item_ref, mandate_id, loan_id, loan_no, due_date, settlement_date, amount, attempt_no) VALUES
  ('00000000-0000-0000-0000-0000000f2001','00000000-0000-0000-0000-0000000f1001',1,'NP20261105-01-000001','00000000-0000-0000-0000-0000000c0001',
   '00000000-0000-0000-0000-00000000aa01','1001000000017','2026-11-05','2026-11-05',5200,1);
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.nach_presentation (id, file_id, seq, item_ref, mandate_id, loan_id, loan_no, due_date, settlement_date, amount, attempt_no)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000f1001',2,'NP20261105-01-000002','00000000-0000-0000-0000-0000000c0001',
   '00000000-0000-0000-0000-00000000aa01','1001000000017','2026-11-05','2026-11-05',5200,2) $q$, '23505', 'N6 a demand is in one open presentation at a time');
SELECT pg_temp.expect_fail($q$ UPDATE integration.nach_presentation SET status = 'BOUNCED' WHERE seq = 1 $q$, '23514', 'N7 a bounce needs its return reason code');
UPDATE integration.nach_presentation SET status = 'BOUNCED', return_code = '04', return_reason = 'Balance insufficient', bounce_charge = 'PENDING',
       represent_on = '2026-11-10', response_file_id = '00000000-0000-0000-0000-0000000f1002', processed_at = now() WHERE seq = 1;
SELECT pg_temp.expect_fail($q$ UPDATE integration.nach_presentation SET status = 'SUCCESS', return_code = NULL WHERE seq = 1 $q$, '23514', 'N8 the outcome of a row is recorded once');
SELECT pg_temp.expect_fail($q$ UPDATE integration.nach_presentation SET return_code = '01' WHERE seq = 1 $q$, '42501', 'N9 a recorded return reason cannot be changed');
UPDATE integration.nach_presentation SET bounce_charge = 'CHARGED', bounce_charge_note = 'Charged Bounce charge 590.00' WHERE seq = 1;
SELECT pg_temp.expect_fail($q$ UPDATE integration.nach_presentation SET bounce_charge = 'PENDING' WHERE seq = 1 $q$, '42501', 'N10 a bounce charge is levied once');
SELECT pg_temp.expect_fail($q$ UPDATE integration.nach_presentation SET posting = 'PENDING' WHERE seq = 1 $q$, '23514', 'N11 a bounce posts no receipt');
SELECT pg_temp.check((SELECT presentations || '/' || last_status || '/' || last_return_code || '/' || represent_on FROM integration.nach_demand_status
                       WHERE loan_id = '00000000-0000-0000-0000-00000000aa01') = '1/BOUNCED/04/2026-11-10', 'N12 the demand shows one bounced presentation and when it may be presented again');
INSERT INTO integration.nach_presentation (id, file_id, seq, item_ref, mandate_id, loan_id, loan_no, due_date, settlement_date, amount, attempt_no) VALUES
  ('00000000-0000-0000-0000-0000000f2002','00000000-0000-0000-0000-0000000f1001',2,'NP20261105-01-000002','00000000-0000-0000-0000-0000000c0001',
   '00000000-0000-0000-0000-00000000aa01','1001000000017','2026-11-05','2026-11-10',5200,2);
SELECT pg_temp.expect_fail($q$ UPDATE integration.nach_presentation SET status = 'SUCCESS', posting = 'POSTED' WHERE seq = 2 $q$, '23514', 'N13 POSTED needs the loan transaction');
UPDATE integration.nach_presentation SET status = 'SUCCESS', posting = 'PENDING', processed_at = now() WHERE seq = 2;
SELECT pg_temp.check((SELECT presentations = 2 AND collected FROM integration.nach_demand_status WHERE loan_id = '00000000-0000-0000-0000-00000000aa01'),
                     'N14 the second presentation collected the demand');
SELECT pg_temp.expect_fail($q$ UPDATE integration.nach_presentation SET amount = 1 WHERE seq = 2 $q$, '42501', 'N15 a presented amount cannot be edited');

-- 6. Webhooks -------------------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.webhook_endpoint (id, name, url, event_types, created_by)
  VALUES (gen_random_uuid(),'CLAUDE-TEST plain','ftp://hooks.example.invalid/in','{loan.disbursed}','maker') $q$, '23514', 'W1 an endpoint must be http(s); http only for allow-listed local hosts, by the application (V27)');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.webhook_endpoint (id, name, url, event_types, created_by)
  VALUES (gen_random_uuid(),'CLAUDE-TEST odd','https://hooks.example.invalid/in','{customer.created}','maker') $q$, '23514', 'W2 only known event types');
INSERT INTO integration.webhook_endpoint (id, name, url, event_types, created_by) VALUES
  ('00000000-0000-0000-0000-0000000e1001','CLAUDE-TEST LOS','https://hooks.example.invalid/in','{loan.disbursed,payment.received}','maker');
INSERT INTO integration.webhook_secret (endpoint_id, kid, secret_cipher, created_by) VALUES ('00000000-0000-0000-0000-0000000e1001',1,'\x01','maker');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.webhook_secret (endpoint_id, kid, secret_cipher, created_by)
  VALUES ('00000000-0000-0000-0000-0000000e1001',2,'\x02','maker') $q$, '23505', 'W3 one current signing secret per endpoint');
UPDATE integration.webhook_secret SET valid_until = now() + interval '24 hours' WHERE kid = 1;
INSERT INTO integration.webhook_secret (endpoint_id, kid, secret_cipher, created_by) VALUES ('00000000-0000-0000-0000-0000000e1001',2,'\x02','maker');
SELECT pg_temp.check((SELECT count(*) FROM integration.webhook_secret WHERE endpoint_id = '00000000-0000-0000-0000-0000000e1001'
                       AND (valid_until IS NULL OR valid_until > now())) = 2, 'W4 during rotation two secrets are in force');
SELECT pg_temp.expect_fail($q$ UPDATE integration.webhook_secret SET secret_cipher = '\x09' WHERE kid = 1 $q$, '42501', 'W5 a signing secret cannot be overwritten');
INSERT INTO integration.webhook_event (id, type, aggregate_id, data, occurred_at, outbox_id) VALUES
  ('00000000-0000-0000-0000-0000000e2001','loan.disbursed','1001000000017','{"loanNo":"1001000000017"}',now(),1);
INSERT INTO integration.webhook_event (id, type, aggregate_id, data, occurred_at, outbox_id)
  VALUES (gen_random_uuid(),'loan.disbursed','1001000000017','{}',now(),1) ON CONFLICT (outbox_id) DO NOTHING;
SELECT pg_temp.check((SELECT count(*) FROM integration.webhook_event) = 1, 'W6 an outbox row becomes one event, however often it is relayed');
SELECT pg_temp.expect_fail($q$ UPDATE integration.webhook_event SET data = '{}' $q$, '42501', 'W7 a stored event cannot be altered');
INSERT INTO integration.webhook_delivery (id, endpoint_id, event_id) VALUES
  ('00000000-0000-0000-0000-0000000e3001','00000000-0000-0000-0000-0000000e1001','00000000-0000-0000-0000-0000000e2001');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.webhook_delivery (id, endpoint_id, event_id)
  VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000e1001','00000000-0000-0000-0000-0000000e2001') $q$, '23505', 'W8 an event is fanned out to an endpoint once');
UPDATE integration.webhook_delivery SET status = 'RETRY', attempts = 1, next_attempt_at = now() + interval '1 minute' WHERE id = '00000000-0000-0000-0000-0000000e3001';
SELECT pg_temp.expect_fail($q$ UPDATE integration.webhook_delivery SET status = 'DEAD' WHERE id = '00000000-0000-0000-0000-0000000e3001' $q$,
  '23514', 'W9 a dead-lettered delivery has no next attempt');
UPDATE integration.webhook_delivery SET status = 'DEAD', next_attempt_at = NULL WHERE id = '00000000-0000-0000-0000-0000000e3001';
INSERT INTO integration.webhook_delivery (id, endpoint_id, event_id, replay_of, requested_by) VALUES
  ('00000000-0000-0000-0000-0000000e3002','00000000-0000-0000-0000-0000000e1001','00000000-0000-0000-0000-0000000e2001','00000000-0000-0000-0000-0000000e3001','ops');
UPDATE integration.webhook_delivery SET status = 'DELIVERED', next_attempt_at = NULL, delivered_at = now() WHERE id = '00000000-0000-0000-0000-0000000e3002';
SELECT pg_temp.check((SELECT count(*) FROM integration.webhook_delivery) = 2, 'W10 a replay is a new delivery; the dead one stays in the log');
SELECT pg_temp.expect_fail($q$ UPDATE integration.webhook_delivery SET status = 'PENDING', next_attempt_at = now() WHERE id = '00000000-0000-0000-0000-0000000e3002' $q$,
  '23514', 'W11 a delivered webhook is not sent again under the same delivery');
SELECT pg_temp.check((SELECT count(*) FROM information_schema.columns WHERE table_schema = 'platform' AND table_name = 'outbox'
                       AND column_name IN ('relay_attempts','relay_error')) = 2, 'W12 the outbox records relay failures');

-- 7. API clients ------------------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.api_client (client_id, name, scopes, home_branch, keycloak_id, service_username, created_by)
  VALUES ('ext-pilot-los','CLAUDE-TEST LOS','{loan:view,approval:approve}','HO','kc-1','service-account-ext-pilot-los','maker') $q$,
  '23514', 'A1 an API client can never hold an approval permission');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.api_client (client_id, name, scopes, home_branch, keycloak_id, service_username, created_by)
  VALUES ('console','CLAUDE-TEST','{loan:view}','HO','kc-1','service-account-console','maker') $q$, '23514', 'A2 managed client ids carry the ext- prefix');
INSERT INTO integration.api_client (client_id, name, scopes, home_branch, keycloak_id, service_username, created_by)
  VALUES ('ext-pilot-los','CLAUDE-TEST LOS','{loan:view,loan:create,loan:stp}','HO','kc-1','service-account-ext-pilot-los','maker');
SELECT pg_temp.check((SELECT count(*) FROM platform.approval_rule WHERE entity_type = 'API_CLIENT' AND checkers_required = 2) = 3,
                     'A3 money-moving scopes and their secret rotation need two checkers');
SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'integration' AND table_name = 'api_client'
                                  AND column_name ILIKE '%secret%' AND column_name NOT IN ('secret_pending_for','secret_issued_at')),
                     'A4 there is no column that could hold a client secret');

-- 8. Messages -----------------------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.message_template (code, channel, body, updated_by)
  VALUES ('DUE_REMINDER','SMS','Dear {{name}}, Rs {{amount}} is due on {{due_date}}.','maker') $q$, '23514', 'T1 an SMS template needs its DLT registration');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.message_template (code, channel, body, updated_by)
  VALUES ('DUE_REMINDER','EMAIL','Dear {{name}}','maker') $q$, '23514', 'T2 an e-mail template needs a subject');
INSERT INTO integration.message_template (code, channel, body, dlt_entity_id, dlt_template_id, dlt_header, updated_by)
  VALUES ('DUE_REMINDER','SMS','Dear {{name}}, Rs {{amount}} is due on {{due_date}}.','1101000000000000001','1107000000000000001','CLDTST','maker');
SELECT pg_temp.check(integration.message_allowed('00000000-0000-0000-0000-0000000000c1','SMS','TRANSACTIONAL') IS NULL, 'T3 a transactional message is always allowed');
SELECT pg_temp.check(integration.message_allowed('00000000-0000-0000-0000-0000000000c1','SMS','PROMOTIONAL') = 'NO_CONSENT', 'T4 a promotional message needs marketing consent');
INSERT INTO customer.consent (id,customer_id,purpose,lawful_basis,notice_version,channel,evidence_ref,granted_at,recorded_by) VALUES
  (gen_random_uuid(),'00000000-0000-0000-0000-0000000000c1','MARKETING','CONSENT','CLAUDE-TEST-notice-v1','WEB','CLAUDE-TEST-otp-1','2026-01-01 10:00+05:30','maker');
SELECT pg_temp.check(integration.message_allowed('00000000-0000-0000-0000-0000000000c1','SMS','PROMOTIONAL') IS NULL, 'T5 with consent it is allowed');
INSERT INTO integration.message_opt_out (customer_id, channel, source, recorded_by) VALUES ('00000000-0000-0000-0000-0000000000c1','SMS','CLAUDE-TEST STOP reply','ops');
SELECT pg_temp.check(integration.message_allowed('00000000-0000-0000-0000-0000000000c1','SMS','PROMOTIONAL') = 'OPT_OUT'
                     AND integration.message_allowed('00000000-0000-0000-0000-0000000000c1','SMS','SERVICE') = 'OPT_OUT', 'T6 an opt-out stops promotional and service messages');
SELECT pg_temp.check(integration.message_allowed('00000000-0000-0000-0000-0000000000c1','SMS','TRANSACTIONAL') IS NULL
                     AND integration.message_allowed('00000000-0000-0000-0000-0000000000c1','EMAIL','SERVICE') IS NULL, 'T7 but not transactional ones, nor the other channel');
INSERT INTO integration.message (id, template_code, channel, language, category, customer_id, event_key, recipient_cipher, recipient_masked, body_cipher, status, next_attempt_at)
  VALUES ('00000000-0000-0000-0000-0000000e4001','DUE_REMINDER','SMS','en','TRANSACTIONAL','00000000-0000-0000-0000-0000000000c1','due:aa01:2026-11-05','\x01','XXXXXX0001','\x02','QUEUED',now());
INSERT INTO integration.message (id, template_code, channel, language, category, customer_id, event_key, recipient_cipher, recipient_masked, body_cipher, status, next_attempt_at)
  VALUES (gen_random_uuid(),'DUE_REMINDER','SMS','en','TRANSACTIONAL','00000000-0000-0000-0000-0000000000c1','due:aa01:2026-11-05','\x01','XXXXXX0001','\x02','QUEUED',now())
  ON CONFLICT (template_code, channel, event_key) DO NOTHING;
SELECT pg_temp.check((SELECT count(*) FROM integration.message) = 1, 'T8 one message per event and channel');
SELECT pg_temp.expect_fail($q$ INSERT INTO integration.message (id, template_code, channel, language, category, event_key, status, next_attempt_at)
  VALUES (gen_random_uuid(),'DUE_REMINDER','SMS','en','TRANSACTIONAL','x','QUEUED',now()) $q$, '23514', 'T9 a queued message has a recipient and a text');
INSERT INTO integration.message (id, template_code, channel, language, category, customer_id, event_key, status, suppress_reason)
  VALUES (gen_random_uuid(),'DUE_REMINDER','EMAIL','en','PROMOTIONAL','00000000-0000-0000-0000-0000000000c2','due:aa02:2026-11-05','SUPPRESSED','NO_CONSENT');
UPDATE integration.message SET status = 'SENT', next_attempt_at = NULL, sent_at = now(), provider = 'SIMULATOR' WHERE id = '00000000-0000-0000-0000-0000000e4001';
SELECT pg_temp.expect_fail($q$ UPDATE integration.message SET status = 'QUEUED', next_attempt_at = now() WHERE id = '00000000-0000-0000-0000-0000000e4001' $q$,
  '42501', 'T10 a sent message is not sent again');
SELECT pg_temp.expect_fail($q$ DELETE FROM integration.message $q$, '42501', 'T11 the delivery log is not deleted');

-- 9. LOS: customer external reference ------------------------------------------------------------------------------------
UPDATE customer.customer SET external_ref = 'CLAUDE-TEST-LOS-0001' WHERE id = '00000000-0000-0000-0000-0000000000c1';
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET external_ref = 'CLAUDE-TEST-LOS-0001' WHERE id = '00000000-0000-0000-0000-0000000000c2' $q$,
  '23505', 'X1 an external reference names one customer');
SELECT pg_temp.expect_fail($q$ UPDATE customer.customer SET external_ref = 'has space' WHERE id = '00000000-0000-0000-0000-0000000000c2' $q$,
  '23514', 'X2 the external reference format is checked');
\echo ALL P2-2 INTEGRATION TESTS PASSED
