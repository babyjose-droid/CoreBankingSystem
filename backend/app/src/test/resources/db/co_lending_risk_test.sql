-- Phase 5 (P5-3, tenant V41): the bureau file with the originator's own share of a co-lent loan, the partner's classification
-- reports, and the default loss guarantee (covers, invocations, recoveries) with the check of its deposit head.
-- Run on a fresh tenant database after the tenant migrations.
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

INSERT INTO lending.co_lent_position (loan_id, base_interest, base_penal, base_fees, as_of) VALUES ('00000000-0000-0000-0000-0000000000a1', 0, 0, '{}', '2026-07-01');
SELECT ledger.load_co_lending_dlg_heads();
INSERT INTO platform.staff_user (user_id, username, display_name, home_branch, all_branches, status) VALUES ('sub-m', 'maker', 'Maker', 'HO', true, 'ACTIVE');

-- ---- bureau: the originator's own share ---------------------------------------------------------------------------
UPDATE lending.loan_account SET emi = 9000 WHERE id = '00000000-0000-0000-0000-0000000000a1';
SELECT pg_temp.check((SELECT sanctioned_amount = 20000 AND emi = 1800 FROM reporting.bureau_consumer_rows('maker', '{}'::jsonb) WHERE loan_no = '10010000000001'),
                     'B1 a co-lent loan in the bureau file: the originator''s 20% (para 31)');
SELECT pg_temp.check((SELECT sanctioned_amount = 100000 FROM reporting.bureau_consumer_rows('maker', '{}'::jsonb) WHERE loan_no = '10010000000002'),
                     'B2 a loan the partner holds no share of: in full');

-- ---- the partner's classification ------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.co_lending_partner_class (id, loan_id, reported_on, asset_class, outcome, created_by)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000a1', '2026-07-01', 'SUBSTANDARD', 'x', 'maker') $q$, '23514', 'K1 an NPA has its date');
INSERT INTO lending.co_lending_partner_class (id, loan_id, reported_on, asset_class, npa_since, outcome, created_by)
VALUES ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000a1', '2026-07-01', 'SUBSTANDARD', '2026-06-20', 'made SUBSTANDARD here', 'maker');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lending_partner_class SET outcome = 'y' $q$, '42501', 'K2 a report is a record: it never changes');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.co_lending_partner_class $q$, '42501', 'K3 nor is it deleted');

-- ---- the guarantee ---------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) = 3 FROM ledger.gl_head WHERE code IN ('1304','5109','4111') AND is_posting), 'G1 the guarantee heads');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.co_lending_dlg_cover (id, cover_no, arrangement_code, form, amount, issued_on, valid_until, created_by)
    VALUES (gen_random_uuid(), 'DLG1', 'BANKX', 'CASH', 5000, '2026-07-01', '2027-08-01', 'maker') $q$, '23514', 'G2 a cash cover comes with its posting');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.co_lending_dlg_cover (id, cover_no, arrangement_code, form, amount, issued_on, valid_until, created_by)
    VALUES (gen_random_uuid(), 'DLG1', 'BANKX', 'BANK_GUARANTEE', 5000, '2026-07-01', '2027-08-01', 'maker') $q$, '23514', 'G3 a guarantee comes with its number');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.co_lending_dlg_cover (id, cover_no, arrangement_code, form, amount, issued_on, valid_until, lot_id, created_by)
    VALUES (gen_random_uuid(), 'DLG1', 'BANKX', 'CASH', 5000, '2026-07-01', '2027-08-01', pg_temp.post('VOUCHER', '1304', 'DLG1', '1202', '1202', 5000), 'maker') $q$,
    '23514', 'G4 with its CO_LENDING_DLG lot');
INSERT INTO lending.co_lending_dlg_cover (id, cover_no, arrangement_code, form, amount, issued_on, valid_until, lot_id, created_by)
VALUES ('00000000-0000-0000-0000-0000000000d1', 'DLG000001', 'BANKX', 'CASH', 4000, '2026-07-01', '2027-08-01',
        pg_temp.post('CO_LENDING_DLG', '1304', 'DLG000001', '1202', '1202', 4000), 'maker');
SELECT pg_temp.check((SELECT count(*) = 0 FROM lending.co_lending_problems()), 'G5 the deposit head holds the cash cover');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lending_dlg_cover SET amount = 5000 $q$, '42501', 'G6 a cover only changes by being released');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.co_lending_dlg_invocation (id, cover_id, loan_id, business_date, amount, dpd, reason, lot_id, created_by)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000a1', '2026-07-01', 4000.01, 95, 'claim',
            pg_temp.post('CO_LENDING_DLG', '5109', '5109', '1304', 'DLG000001', 4000.01), 'maker') $q$, '23514', 'I1 not more than the cover');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.co_lending_dlg_invocation (id, cover_id, loan_id, business_date, amount, dpd, reason, lot_id, created_by)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000a2', '2026-07-01', 100, 95, 'claim',
            pg_temp.post('CO_LENDING_DLG', '5109', '5109', '1304', 'DLG000001', 100), 'maker') $q$, '23514', 'I2 only a loan whose share the partner holds');
INSERT INTO lending.co_lending_dlg_invocation (id, cover_id, loan_id, business_date, amount, dpd, reason, lot_id, created_by)
VALUES ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000a1', '2026-07-01', 1500, 95, 'claim',
        pg_temp.post('CO_LENDING_DLG', '5109', '5109', '1304', 'DLG000001', 1500), 'maker');
SELECT pg_temp.check((SELECT count(*) = 0 FROM lending.co_lending_problems()), 'I3 the deposit head holds what is left (2,500)');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lending_dlg_invocation SET amount = 1 $q$, '42501', 'I4 an invocation never changes');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.co_lending_dlg_recovery (id, invocation_id, business_date, amount, lot_id, created_by)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e1', '2026-07-01', 1500.01, pg_temp.post('CO_LENDING_DLG', '1202', '1202', '4111', '4111', 1500.01), 'maker') $q$,
    '23514', 'R1 recoveries are at most what was invoked');
INSERT INTO lending.co_lending_dlg_recovery (id, invocation_id, business_date, amount, lot_id, created_by)
VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e1', '2026-07-01', 500, pg_temp.post('CO_LENDING_DLG', '1202', '1202', '4111', '4111', 500), 'maker');
SELECT pg_temp.post('VOUCHER', '1304', 'DLG000001', '1202', '1202', 1);
SELECT pg_temp.check((SELECT count(*) = 1 FROM lending.co_lending_problems() WHERE head = '1304' AND kept = 2500 AND ledger = 2501), 'R2 a posting to the deposit head with no cover behind it shows');
SELECT pg_temp.post('VOUCHER', '1202', '1202', '1304', 'DLG000001', 1);
UPDATE lending.co_lending_dlg_cover SET status = 'RELEASED', released_on = '2026-07-01',
       release_lot_id = pg_temp.post('CO_LENDING_DLG', '1202', '1202', '1304', 'DLG000001', 2500);
SELECT pg_temp.check((SELECT count(*) = 0 FROM lending.co_lending_problems()), 'R3 released: the deposit head is empty');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lending_dlg_cover SET status = 'ACTIVE', released_on = NULL $q$, '42501', 'R4 released once');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.co_lending_dlg_invocation (id, cover_id, loan_id, business_date, amount, dpd, reason, lot_id, created_by)
    VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000a1', '2026-07-01', 1, 95, 'claim',
            pg_temp.post('CO_LENDING_DLG', '5109', '5109', '1202', '1202', 1), 'maker') $q$, '23514', 'R5 a released cover is not invoked');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.co_lending_dlg_cover $q$, '42501', 'R6 a cover is never deleted');
\echo ALL CO-LENDING RISK TESTS PASSED
