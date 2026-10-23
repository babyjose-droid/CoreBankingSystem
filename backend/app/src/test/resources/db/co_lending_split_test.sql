-- Phase 5 (P5-2, tenant V40): the co-lending position, the daily splits and settlements with the partner, and the check that the
-- contra head and the partner payable agree with them. Run on a fresh tenant database after the tenant migrations.
\set QUIET on
CREATE FUNCTION pg_temp.expect_fail(sql text, code text, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    EXECUTE sql;
  EXCEPTION WHEN others THEN
    IF SQLSTATE = code THEN RAISE NOTICE 'PASS % [%]', label, SQLERRM; RETURN; END IF;
    RAISE EXCEPTION 'FAIL % : wrong error % %', label, SQLSTATE, SQLERRM;
  END;
  RAISE EXCEPTION 'FAIL % : no error raised', label;
END $$;
CREATE FUNCTION pg_temp.immediate(sql text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE sql;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
END $$;
CREATE FUNCTION pg_temp.check(ok boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF ok IS NOT TRUE THEN RAISE EXCEPTION 'FAIL %', label; END IF;
  RAISE NOTICE 'PASS %', label;
END $$;
CREATE FUNCTION pg_temp.customer(n int, p_name text) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE cid uuid := ('00000000-0000-0000-0000-0000000000c' || n)::uuid; body text := '9001' || lpad(n::text, 9, '0'); t text; did uuid;
BEGIN
  INSERT INTO customer.customer (id, customer_no, customer_type, display_name, home_branch, status)
  VALUES (cid, body || platform.luhn_digit(body), 'INDIVIDUAL', 'CLAUDE-TEST ' || p_name, 'HO', 'ACTIVE');
  FOREACH t IN ARRAY ARRAY['PAN','ADDRESS_PROOF','PHOTO'] LOOP
    did := gen_random_uuid();
    INSERT INTO customer.kyc_document (id, customer_id, doc_type, store_key, content_type, size_bytes, sha256, uploaded_by)
    VALUES (did, cid, t, 'tenants/claude-test/kyc/' || cid || '/' || did, 'application/pdf', 1024, repeat('ab', 32), 'maker');
  END LOOP;
  UPDATE customer.kyc_document SET status = 'VERIFIED', verified_by = 'checker', verified_at = now() WHERE customer_id = cid;
  RETURN cid;
END $$;
-- A savings or current account in one name, opened as the application opens it.
CREATE FUNCTION pg_temp.casa(p_product text, p_customer int, p_branch text DEFAULT 'HO') RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE aid uuid;
BEGIN
  INSERT INTO deposits.account (family, product_code, product_version, customer_id, branch_code, operating_mode, nomination_mode, status, opened_on, opened_by)
  VALUES ('CASA', p_product, 0, ('00000000-0000-0000-0000-0000000000c' || p_customer)::uuid, p_branch, 'SINGLE', 'NONE', 'ACTIVE', '1999-01-01', 'maker')
  RETURNING id INTO aid;
  INSERT INTO deposits.account_holder VALUES (aid, 1, ('00000000-0000-0000-0000-0000000000c' || p_customer)::uuid);
  INSERT INTO deposits.casa_account VALUES (aid, CASE WHEN p_product LIKE 'SB%' THEN DATE '2026-06-30' END);
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN aid;
END $$;
-- A booked term deposit of 1,00,000 for 12 months on FDC1.
CREATE FUNCTION pg_temp.fd(p_customer int, p_payout text DEFAULT 'BANK_ACCOUNT', p_payout_account uuid DEFAULT NULL) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE aid uuid; p deposits.product%ROWTYPE; card record; a deposits.account%ROWTYPE;
BEGIN
  INSERT INTO deposits.account (family, product_code, product_version, customer_id, branch_code, operating_mode, nomination_mode, status, opened_on, opened_by)
  VALUES ('TERM_DEPOSIT', 'FDC1', 0, ('00000000-0000-0000-0000-0000000000c' || p_customer)::uuid, 'HO', 'SINGLE', 'NONE', 'ACTIVE', '1999-01-01', 'maker')
  RETURNING id INTO aid;
  SELECT * INTO a FROM deposits.account WHERE id = aid;
  INSERT INTO deposits.account_holder VALUES (aid, 1, ('00000000-0000-0000-0000-0000000000c' || p_customer)::uuid);
  SELECT * INTO p FROM deposits.product WHERE code = 'FDC1';
  SELECT * INTO card FROM deposits.card_rate(p.rate_card, 100000, a.opened_on, (a.opened_on + interval '12 months')::date);
  INSERT INTO deposits.term_deposit (account_id, principal, tenure_months, tenure_days, booked_on, rate_percent, card_rate_percent,
      card_from_amount, card_from_months, card_from_days, add_ons, interest_period_months, cumulative, day_count, rounding, tds_applicable,
      premature_allowed, premature_penalty_percent, premature_min_days, payout_to, payout_account_id, maturity_instruction)
  VALUES (aid, 100000, 12, 0, '1999-01-01', card.rate_percent, card.rate_percent, card.from_amount, card.from_months, card.from_days, '[]',
      p.interest_period_months, true, p.day_count, p.rounding, p.tds_applicable, p.premature_allowed, p.premature_penalty_percent,
      p.premature_min_days, p_payout, p_payout_account, 'PAY_OUT');
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN aid;
END $$;
CREATE FUNCTION pg_temp.today() RETURNS date LANGUAGE sql STABLE AS $$ SELECT business_date FROM platform.business_day WHERE id = 1 $$;
CREATE FUNCTION pg_temp.no(p_account uuid) RETURNS text LANGUAGE sql STABLE AS $$ SELECT account_no FROM deposits.account WHERE id = p_account $$;
CREATE FUNCTION pg_temp.head(p_account uuid) RETURNS text LANGUAGE sql STABLE AS $$
  SELECT p.gl_liability FROM deposits.account a JOIN deposits.product p ON p.code = a.product_code WHERE a.id = p_account $$;
CREATE FUNCTION pg_temp.branch(p_account uuid) RETURNS text LANGUAGE sql STABLE AS $$ SELECT branch_code FROM deposits.account WHERE id = p_account $$;
CREATE FUNCTION pg_temp.balance(p_account uuid) RETURNS numeric LANGUAGE sql STABLE AS $$
  SELECT coalesce((SELECT balance FROM deposits.account_balance WHERE account_id = p_account), 0) $$;
-- A movement as the application writes it: a two-leg lot (the account on its product's head against p_gl) and the
-- movement row, in one transaction. p_extra: lotAmount, lotHead, valueDate, party (adds the other bank's account).
CREATE FUNCTION pg_temp.move(p_account uuid, p_kind text, p_amount numeric, p_gl text DEFAULT '1201', p_extra jsonb DEFAULT '{}') RETURNS uuid
LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid(); dir text; mid uuid; amt numeric := coalesce((p_extra->>'lotAmount')::numeric, p_amount);
BEGIN
  dir := CASE WHEN p_kind IN ('CASH_DEPOSIT','BANK_TRANSFER_IN','TRANSFER_IN','FUNDING') THEN 'CREDIT' ELSE 'DEBIT' END;
  dir := coalesce(p_extra->>'direction', dir);
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (lot, 'TEST', pg_temp.today(), pg_temp.today(), 'maker');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (lot, pg_temp.today(), pg_temp.branch(p_account), coalesce(p_extra->>'lotHead', pg_temp.head(p_account)), pg_temp.no(p_account),
          CASE dir WHEN 'CREDIT' THEN 'CR' ELSE 'DR' END, amt),
         (lot, pg_temp.today(), pg_temp.branch(p_account), p_gl, p_gl, CASE dir WHEN 'CREDIT' THEN 'DR' ELSE 'CR' END, amt);
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, created_by,
                                 party_name_cipher, party_account_cipher, party_account_last4, party_ifsc)
  VALUES (p_account, p_kind, dir, p_amount, 0, '1999-01-01', coalesce((p_extra->>'valueDate')::date, pg_temp.today()), lot, 'maker',
          CASE WHEN p_extra ? 'party' THEN '\x01'::bytea END, CASE WHEN p_extra ? 'party' THEN '\x02'::bytea END,
          CASE WHEN p_extra ? 'party' THEN '6789' END, p_extra->>'party')
  RETURNING id INTO mid;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN mid;
END $$;
-- A transfer: one lot, a movement on each account.
CREATE FUNCTION pg_temp.transfer(p_from uuid, p_to uuid, p_amount numeric) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid();
BEGIN
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (lot, 'TEST', pg_temp.today(), pg_temp.today(), 'maker');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (lot, pg_temp.today(), pg_temp.branch(p_from), pg_temp.head(p_from), pg_temp.no(p_from), 'DR', p_amount),
         (lot, pg_temp.today(), pg_temp.branch(p_to), pg_temp.head(p_to), pg_temp.no(p_to), 'CR', p_amount);
  IF pg_temp.branch(p_from) <> pg_temp.branch(p_to) THEN
    INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
    VALUES (lot, pg_temp.today(), pg_temp.branch(p_from), '1900', '1900', 'CR', p_amount),
           (lot, pg_temp.today(), pg_temp.branch(p_to), '1900', '1900', 'DR', p_amount);
  END IF;
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, counterparty_id, created_by)
  VALUES (p_from, 'TRANSFER_OUT', 'DEBIT', p_amount, 0, pg_temp.today(), pg_temp.today(), lot, p_to, 'maker'),
         (p_to, 'TRANSFER_IN', 'CREDIT', p_amount, 0, pg_temp.today(), pg_temp.today(), lot, p_from, 'maker');
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN lot;
END $$;
-- The reversal of every movement of a lot: the mirror lot and a REVERSAL movement for each; a funding reversed takes
-- its deposit back to BOOKED.
CREATE FUNCTION pg_temp.reverse(p_movement uuid, p_reason text DEFAULT 'posted to the wrong account', p_amount numeric DEFAULT NULL,
                                 p_mark_lot boolean DEFAULT true)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE o deposits.movement%ROWTYPE; lot uuid := gen_random_uuid(); m deposits.movement%ROWTYPE; rid uuid;
BEGIN
  SELECT * INTO o FROM deposits.movement WHERE id = p_movement;
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, reverses, created_by)
  VALUES (lot, 'REVERSAL', pg_temp.today(), o.value_date, CASE WHEN p_mark_lot THEN o.lot_id END, 'maker');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  SELECT lot, pg_temp.today(), branch_code, gl_code, account_no, CASE side WHEN 'DR' THEN 'CR' ELSE 'DR' END, amount
    FROM ledger.account_entry WHERE lot_id = o.lot_id AND business_date = o.business_date;
  FOR m IN SELECT * FROM deposits.movement WHERE lot_id = o.lot_id ORDER BY seq LOOP
    INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, reverses,
                                   reversal_reason, created_by)
    VALUES (m.account_id, 'REVERSAL', CASE m.direction WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END, coalesce(p_amount, m.amount), 0,
            pg_temp.today(), pg_temp.today(), lot, m.id, p_reason, 'checker')
    RETURNING id INTO rid;
    UPDATE deposits.movement SET reversed_by = rid WHERE id = m.id;
    IF m.kind = 'FUNDING' THEN
      UPDATE deposits.term_deposit SET start_date = NULL, maturity_date = NULL, funding_source = NULL, funding_account_id = NULL,
             funding_movement_id = NULL, funding_reference = NULL WHERE account_id = m.account_id;
      UPDATE deposits.account SET status = 'BOOKED' WHERE id = m.account_id;
    END IF;
  END LOOP;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
END $$;
-- Funding a booked deposit with its principal, as the application does. p_extra: amount, from (paying account),
-- start (start date), bank (records a payout bank account).
CREATE FUNCTION pg_temp.fund(p_deposit uuid, p_source text, p_extra jsonb DEFAULT '{}') RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid(); amt numeric := coalesce((p_extra->>'amount')::numeric, 100000); from_acct uuid := (p_extra->>'from')::uuid;
        mid uuid; st date := coalesce((p_extra->>'start')::date, pg_temp.today());
BEGIN
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (lot, 'TEST', pg_temp.today(), pg_temp.today(), 'maker');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (lot, pg_temp.today(), 'HO', CASE p_source WHEN 'ACCOUNT' THEN pg_temp.head(from_acct) WHEN 'CASH' THEN '1201' ELSE '1203' END,
          CASE p_source WHEN 'ACCOUNT' THEN pg_temp.no(from_acct) WHEN 'CASH' THEN '1201' ELSE '1203' END, 'DR', amt),
         (lot, pg_temp.today(), 'HO', '2403', pg_temp.no(p_deposit), 'CR', amt);
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, counterparty_id, created_by)
  VALUES (p_deposit, 'FUNDING', 'CREDIT', amt, 0, pg_temp.today(), pg_temp.today(), lot, from_acct, 'maker') RETURNING id INTO mid;
  IF p_source = 'ACCOUNT' THEN
    INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, counterparty_id, created_by)
    VALUES (from_acct, 'FUNDING_PAID', 'DEBIT', amt, 0, pg_temp.today(), pg_temp.today(), lot, p_deposit, 'maker');
  END IF;
  UPDATE deposits.term_deposit SET start_date = st, maturity_date = (st + interval '12 months')::date, funding_source = p_source,
         funding_account_id = from_acct, funding_movement_id = mid,
         payout_bank_name_cipher = CASE WHEN p_extra ? 'bank' THEN '\x01'::bytea END,
         payout_bank_account_cipher = CASE WHEN p_extra ? 'bank' THEN '\x02'::bytea END,
         payout_bank_account_last4 = CASE WHEN p_extra ? 'bank' THEN '4321' END, payout_bank_ifsc = p_extra->>'bank'
   WHERE account_id = p_deposit;
  UPDATE deposits.account SET status = 'ACTIVE' WHERE id = p_deposit;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN mid;
END $$;
CREATE FUNCTION pg_temp.hold(p_account uuid, p_kind text, p_amount numeric DEFAULT NULL) RETURNS uuid LANGUAGE sql AS $$
  INSERT INTO deposits.account_hold (account_id, kind, amount, reason, placed_on, placed_by)
  VALUES (p_account, p_kind, p_amount, 'CLAUDE-TEST order', '1999-01-01', 'maker') RETURNING id $$;
CREATE FUNCTION pg_temp.release(p_hold uuid) RETURNS void LANGUAGE sql AS $$
  UPDATE deposits.account_hold SET status = 'RELEASED', released_on = '1999-01-01', released_by = 'checker', release_reason = 'order lifted'
   WHERE id = p_hold $$;


INSERT INTO platform.branch (code, name, state_code, is_head_office) VALUES ('HO', 'Head Office', '32', true);
INSERT INTO platform.business_day VALUES (1, '2026-07-01', 'OPEN');
INSERT INTO platform.legal_entity (legal_name, entity_type, registered_state) VALUES ('CLAUDE-TEST Finance Ltd', 'NBFC', '32');
SELECT ledger.load_starter_kit('NBFC');
SELECT ledger.load_lending_heads();
SELECT pg_temp.customer(1, 'Asha Borrower');
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status)
VALUES ('PL01','CLAUDE-TEST Personal loan','EQUATED',10000,500000,6,60,8,24,24,'1101','4101','1102','ACTIVE'),
       ('PL02','CLAUDE-TEST Personal loan 2','EQUATED',10000,500000,6,60,8,24,24,'1101','4101','1102','ACTIVE');
INSERT INTO lending.loan_product (code,name,repayment_method,min_amount,max_amount,min_tenor_months,max_tenor_months,min_rate,max_rate,
                                  penal_charge_rate,gl_principal,gl_interest_income,gl_interest_receivable,status,multiple_disbursements)
VALUES ('HL01','CLAUDE-TEST Home loan in tranches','EQUATED',10000,5000000,6,240,8,24,24,'1101','4101','1102','ACTIVE',true);
CREATE FUNCTION pg_temp.loan(n int, p_product text, p_rate numeric) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lid uuid := ('00000000-0000-0000-0000-0000000000a' || n)::uuid;
BEGIN
  INSERT INTO lending.loan_account (id,loan_no,customer_id,product_code,branch_code,sanctioned_amount,rate,tenor_months,open_date,status,kfs_accepted_at,state,
                                    multiple_disbursements)
  VALUES (lid, '1001000000' || lpad(n::text, 4, '0'), '00000000-0000-0000-0000-0000000000c1', p_product, 'HO', 100000, p_rate, 12, '2026-07-01',
          'SANCTIONED', now(), '{}', p_product = 'HL01');
  RETURN lid;
END $$;
CREATE FUNCTION pg_temp.attach(p_loan uuid, p_code text DEFAULT 'BANKX') RETURNS void LANGUAGE sql AS $$
  INSERT INTO lending.co_lent_loan (loan_id, arrangement_code, arrangement_version, originator_share, originator_rate, partner_rate, blended_rate,
                                    servicing_fee, fee_sharing, penal_sharing, dlg_percent)
  SELECT p_loan, code, version, originator_share, originator_rate, partner_rate,
         round((originator_share * originator_rate + (100 - originator_share) * partner_rate) / 100, 2), servicing_fee, fee_sharing, penal_sharing, dlg_percent
    FROM lending.co_lending_arrangement WHERE code = p_code $$;
CREATE FUNCTION pg_temp.disburse(p_loan uuid, p_on date DEFAULT '2026-07-01') RETURNS void LANGUAGE sql AS $$
  UPDATE lending.loan_account SET status = 'ACTIVE', disbursed_on = p_on, principal_outstanding = 100000 WHERE id = p_loan $$;
CREATE FUNCTION pg_temp.day(d date) RETURNS void LANGUAGE sql AS $$
  SELECT set_config('corebanking.eod', 'on', true);
  UPDATE platform.business_day SET business_date = d WHERE id = 1;
  SELECT set_config('corebanking.eod', 'off', true);
$$;
-- the partner's share booked as the application does: Dr escrow / Cr partner's share, on the loan's number
CREATE FUNCTION pg_temp.transfer(p_loan uuid, p_amount numeric, p_type text DEFAULT 'CO_LENDING_TRANSFER') RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid(); bd date := (SELECT business_date FROM platform.business_day WHERE id = 1);
        no text := (SELECT loan_no FROM lending.loan_account WHERE id = p_loan);
BEGIN
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (lot, p_type, bd, bd, 'maker');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (lot, bd, 'HO', '1208', '1208', 'DR', p_amount), (lot, bd, 'HO', '1105', no, 'CR', p_amount);
  RETURN lot;
END $$;

CREATE FUNCTION pg_temp.post(p_type text, dr_gl text, dr_acct text, cr_gl text, cr_acct text, p_amount numeric) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid(); bd date := (SELECT business_date FROM platform.business_day WHERE id = 1);
BEGIN
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (lot, p_type, bd, bd, 'eod');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (lot, bd, 'HO', dr_gl, dr_acct, 'DR', p_amount), (lot, bd, 'HO', cr_gl, cr_acct, 'CR', p_amount);
  RETURN lot;
END $$;
SELECT ledger.load_co_lending_heads();
INSERT INTO lending.co_lending_arrangement (code, name, partner_name, partner_kind, agreement_reference, originator_share, originator_rate, partner_rate,
                                            servicing_fee, dlg_percent, product_codes, contact, effective_from, created_by)
VALUES ('BANKX', 'CLAUDE-TEST BANKX', 'CLAUDE-TEST BankX Ltd', 'BANK', 'CLA/1', 20, 18, 9.5, 0.5, 5, ARRAY['PL01'], 'care 1800', '2026-07-01', 'maker');
SELECT pg_temp.loan(1, 'PL01', 11.2), pg_temp.loan(2, 'PL01', 11.2);
SELECT pg_temp.attach('00000000-0000-0000-0000-0000000000a1'), pg_temp.attach('00000000-0000-0000-0000-0000000000a2');
SELECT pg_temp.disburse('00000000-0000-0000-0000-0000000000a1'), pg_temp.disburse('00000000-0000-0000-0000-0000000000a2');
UPDATE lending.co_lent_loan SET status = 'OFFERED';
UPDATE lending.co_lent_loan SET status = 'ACCEPTED', partner_principal = 80000, transfer_lot_id = pg_temp.transfer('00000000-0000-0000-0000-0000000000a1', 80000)
 WHERE loan_id = '00000000-0000-0000-0000-0000000000a1';

-- ---- the position ------------------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.co_lent_position (loan_id, base_interest, base_penal, as_of)
    VALUES ('00000000-0000-0000-0000-0000000000a2', 0, 0, '2026-07-01') $q$, '23514', 'P1 a position only for a loan whose share the partner holds');
INSERT INTO lending.co_lent_position (loan_id, base_interest, base_penal, base_fees, as_of)
VALUES ('00000000-0000-0000-0000-0000000000a1', 0, 0, '{}', '2026-07-01');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lent_position SET base_interest = 1 $q$, '42501', 'P2 the baseline never changes');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lent_position SET as_of = '2026-06-30' $q$, '23514', 'P3 the split never goes back in time');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.co_lent_position $q$, '42501', 'P4 a position is never deleted');
SELECT pg_temp.check((SELECT count(*) = 0 FROM lending.co_lending_problems()), 'P5 the books agree: 80,000 on the contra head, nothing due');

-- ---- a split ---------------------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.co_lending_split (id, loan_id, business_date, principal, interest, fees, penal, servicing, lot_id)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000a1', '2026-07-01', 1, 0, 0, 0, 0, pg_temp.post('VOUCHER', '1105', '10010000000001', '2303', '10010000000001', 1)) $q$,
    '23514', 'S1 a split is recorded with its CO_LENDING_SPLIT lot');
CREATE TEMP TABLE s1 AS SELECT pg_temp.post('CO_LENDING_SPLIT', '1105', '10010000000001', '2303', '10010000000001', 5000) AS principal_lot,
                               pg_temp.post('CO_LENDING_SPLIT', '4101', '4101', '2303', '10010000000001', 633.33) AS interest_lot;
INSERT INTO lending.co_lending_split (id, loan_id, business_date, principal, interest, fees, penal, servicing, lot_id)
SELECT '00000000-0000-0000-0000-00000000005a', '00000000-0000-0000-0000-0000000000a1', '2026-07-01', 5000, 0, 0, 0, 0, principal_lot FROM s1;
SELECT pg_temp.check((SELECT count(*) = 1 FROM lending.co_lending_problems() WHERE head = '2303' AND kept = 5000 AND ledger = 5633.33),
                     'S2 the payable shows a posting with no split behind it');
INSERT INTO lending.co_lending_split (id, loan_id, business_date, principal, interest, fees, penal, servicing, lot_id)
SELECT '00000000-0000-0000-0000-00000000005b', '00000000-0000-0000-0000-0000000000a1', '2026-07-01', 0, 633.33, 0, 0, 0, interest_lot FROM s1;
SELECT pg_temp.check((SELECT count(*) = 0 FROM lending.co_lending_problems()), 'S3 with its split recorded the books agree (75,000 contra, 5,633.33 due)');
SELECT pg_temp.check((SELECT net = 633.33 FROM lending.co_lending_split WHERE id = '00000000-0000-0000-0000-00000000005b'), 'S4 a split''s net is worked out');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lending_split SET interest = 1 $q$, '42501', 'S5 a split never changes');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.co_lending_split $q$, '42501', 'S6 a split is never deleted');

-- ---- settlement -------------------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.co_lending_settlement (id, settlement_no, arrangement_code, business_date, loans, principal, interest, fees,
    penal, servicing, net, lot_id, created_by) VALUES (gen_random_uuid(), 'CLS1', 'BANKX', '2026-07-01', 1, 5000, 633.33, 0, 0, 0, 5633.33,
    pg_temp.post('VOUCHER', '2303', '10010000000001', '1208', '1208', 5633.33), 'maker') $q$, '23514', 'T1 a settlement is recorded with its CO_LENDING_SETTLEMENT lot');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.co_lending_settlement (id, settlement_no, arrangement_code, business_date, loans, principal, interest, fees,
    penal, servicing, net, lot_id, created_by) VALUES (gen_random_uuid(), 'CLS1', 'BANKX', '2026-07-01', 1, 5000, 633.33, 0, 0, 0, 5000,
    pg_temp.post('CO_LENDING_SETTLEMENT', '2303', '10010000000001', '1208', '1208', 5000), 'maker') $q$, '23514', 'T2 its net is what its parts add up to');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.immediate('INSERT INTO lending.co_lending_settlement (id, settlement_no, arrangement_code, business_date, loans,
    principal, interest, fees, penal, servicing, net, lot_id, created_by) VALUES (gen_random_uuid(), ''CLS2'', ''BANKX'', ''2026-07-01'', 1, 9000, 633.33, 0, 0, 0,
    9633.33, pg_temp.post(''CO_LENDING_SETTLEMENT'', ''2303'', ''10010000000001'', ''1208'', ''1208'', 9633.33), ''maker'')') $q$, '23514',
    'T3 a settlement pays exactly the splits it settles (checked at commit)');
DO $$ BEGIN   -- one transaction, as the application settles: the settlement and the splits it settles
  INSERT INTO lending.co_lending_settlement (id, settlement_no, arrangement_code, business_date, loans, principal, interest, fees, penal, servicing, net, lot_id, created_by)
  VALUES ('00000000-0000-0000-0000-0000000000f1', 'CLS00000001', 'BANKX', '2026-07-01', 1, 5000, 633.33, 0, 0, 0, 5633.33,
          pg_temp.post('CO_LENDING_SETTLEMENT', '2303', '10010000000001', '1208', '1208', 5633.33), 'maker');
  UPDATE lending.co_lending_split SET settlement_id = '00000000-0000-0000-0000-0000000000f1';
END $$;
SELECT pg_temp.check((SELECT count(*) = 0 FROM lending.co_lending_problems()), 'T4 settled: nothing due on the payable, and the books agree');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lending_split SET settlement_id = NULL $q$, '42501', 'T5 a split is settled once');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lending_settlement SET net = 1 $q$, '42501', 'T6 a settlement never changes');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.co_lending_settlement $q$, '42501', 'T7 nor is it deleted');

-- ---- final ------------------------------------------------------------------------------------------------------------
UPDATE lending.co_lent_position SET final = true;
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lent_position SET principal = 1 $q$, '42501', 'F1 a final split is not split again');
\echo ALL CO-LENDING SPLIT TESTS PASSED
