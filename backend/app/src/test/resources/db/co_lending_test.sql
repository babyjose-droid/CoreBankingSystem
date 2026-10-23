-- Phase 5 (P5-1, tenant V39): co-lending heads, arrangements (the Directions' 10% and 5%), co-lent loans at the blended rate,
-- the offer at disbursement and its 15 calendar days, and the contra head against the loans accepted.
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

-- ---- heads and settings ---------------------------------------------------------------------------------------------------
SELECT pg_temp.check(ledger.load_co_lending_heads() = 3, 'H1 the co-lending heads are loaded into a loan book (2303 is already in the kit)');
SELECT pg_temp.check((SELECT count(*) = 4 FROM ledger.gl_head WHERE code IN ('1105','1208','2303','4110') AND is_posting), 'H2 contra 1105, escrow 1208, payable 2303, fee 4110');
SELECT pg_temp.check(ledger.load_co_lending_heads() = 0, 'H3 loading them again adds nothing');
SELECT pg_temp.check((SELECT count(*) = 4 FROM platform.system_property WHERE key IN ('lending.co-lending.partner-share-gl', 'lending.co-lending.escrow-gl',
                     'lending.co-lending.partner-payable-gl', 'lending.co-lending.servicing-fee-gl')), 'H4 the settings name the heads');

-- ---- arrangements ----------------------------------------------------------------------------------------------------------
CREATE FUNCTION pg_temp.arrangement(p_code text, p_share numeric, p_dlg numeric DEFAULT 5, p_fee numeric DEFAULT 0.5, p_products text[] DEFAULT ARRAY['PL01'])
RETURNS void LANGUAGE sql AS $$
  INSERT INTO lending.co_lending_arrangement (code, name, partner_name, partner_kind, agreement_reference, originator_share, originator_rate, partner_rate,
                                              servicing_fee, dlg_percent, product_codes, contact, effective_from, created_by)
  VALUES (p_code, 'CLAUDE-TEST ' || p_code, 'CLAUDE-TEST BankX Ltd', 'BANK', 'CLA/1', p_share, 18, 9.5, p_fee, p_dlg, p_products, 'care 1800', '2026-07-01', 'maker') $$;
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.arrangement('BANKX', 9.99) $q$, '23514', 'A1 each lender keeps at least 10% (the originator 10 to 90)');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.arrangement('BANKX', 90.01) $q$, '23514', 'A2 and the partner at least 10%');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.arrangement('BANKX', 20, 5.01) $q$, '23514', 'A3 the default loss guarantee is at most 5%');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.arrangement('BANKX', 20, 5, 9.5) $q$, '23514', 'A4 the servicing fee is below the partner''s rate');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.arrangement('BANKX', 20, 5, 0.5, ARRAY['NOPE']) $q$, '23514', 'A5 the products exist');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.arrangement('bankx', 20) $q$, '23514', 'A6 the code is in capitals');
SELECT pg_temp.arrangement('BANKX', 20, 5, 0.5, ARRAY['PL01','HL01']);
SELECT pg_temp.arrangement('OTHER', 30);
UPDATE lending.co_lending_arrangement SET partner_rate = 9.75 WHERE code = 'OTHER';
SELECT pg_temp.check((SELECT version = 2 FROM lending.co_lending_arrangement WHERE code = 'OTHER'), 'A7 a change is a new version');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lending_arrangement SET partner_name = 'Someone else' WHERE code = 'OTHER' $q$, '42501', 'A8 the partner never changes');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.co_lending_arrangement WHERE code = 'OTHER' $q$, '42501', 'A9 an arrangement is never deleted');
UPDATE lending.co_lending_arrangement SET status = 'CLOSED' WHERE code = 'OTHER';
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lending_arrangement SET status = 'ACTIVE' WHERE code = 'OTHER' $q$, '42501', 'A10 a closed arrangement stays closed');

-- ---- co-lent loans at sanction ------------------------------------------------------------------------------------------------
SELECT pg_temp.loan(1, 'PL01', 11.2), pg_temp.loan(2, 'PL01', 12), pg_temp.loan(3, 'PL02', 11.2), pg_temp.loan(4, 'HL01', 11.2), pg_temp.loan(5, 'PL01', 11.2),
       pg_temp.loan(6, 'PL01', 11.2), pg_temp.loan(7, 'PL01', 11.2);
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.attach('00000000-0000-0000-0000-0000000000a2') $q$, '23514', 'L1 the borrower pays the blended rate (11.2%)');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.attach('00000000-0000-0000-0000-0000000000a3') $q$, '23514', 'L2 only products under the arrangement');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.attach('00000000-0000-0000-0000-0000000000a4') $q$, '23514', 'L3 not a loan disbursed in tranches');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.attach('00000000-0000-0000-0000-0000000000a1', 'OTHER') $q$, '23514', 'L4 not under a closed arrangement');
SELECT pg_temp.expect_fail($q$ INSERT INTO lending.co_lent_loan (loan_id, arrangement_code, arrangement_version, originator_share, originator_rate, partner_rate,
    blended_rate, servicing_fee, fee_sharing, penal_sharing, dlg_percent)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'BANKX', 1, 25, 18, 9.5, 11.2, 0.5, 'ORIGINATOR', 'BY_SHARE', 5) $q$, '23514', 'L5 the terms are the arrangement''s');
SELECT pg_temp.attach(('00000000-0000-0000-0000-0000000000a' || n)::uuid) FROM generate_series(5, 7) n;
SELECT pg_temp.attach('00000000-0000-0000-0000-0000000000a1');
SELECT pg_temp.check((SELECT status = 'SANCTIONED' AND blended_rate = 11.2 FROM lending.co_lent_loan WHERE loan_id = '00000000-0000-0000-0000-0000000000a1'),
                     'L6 a loan sanctioned under the arrangement at 11.2%');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lent_loan SET partner_rate = 9 WHERE loan_id = '00000000-0000-0000-0000-0000000000a1' $q$, '42501', 'L7 its terms are frozen');

-- ---- the offer and the 15 days ----------------------------------------------------------------------------------------------
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lent_loan SET status = 'OFFERED' WHERE loan_id = '00000000-0000-0000-0000-0000000000a1' $q$, '23514',
                           'O1 offered only once disbursed');
SELECT pg_temp.disburse(('00000000-0000-0000-0000-0000000000a' || n)::uuid) FROM unnest(ARRAY[1, 5, 6, 7]) n;
UPDATE lending.co_lent_loan SET status = 'OFFERED' WHERE status = 'SANCTIONED';
SELECT pg_temp.check((SELECT offered_on = '2026-07-01' AND deadline = '2026-07-16' FROM lending.co_lent_loan WHERE loan_id = '00000000-0000-0000-0000-0000000000a1'),
                     'O2 offered on the disbursement date, open 15 calendar days');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lent_loan SET status = 'ACCEPTED', partner_principal = 80000,
    transfer_lot_id = pg_temp.transfer('00000000-0000-0000-0000-0000000000a1', 80000, 'VOUCHER') WHERE loan_id = '00000000-0000-0000-0000-0000000000a1' $q$, '23514',
    'O3 an acceptance is recorded with its co-lending transfer');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lent_loan SET status = 'ACCEPTED' WHERE loan_id = '00000000-0000-0000-0000-0000000000a1' $q$, '23514',
                           'O4 an acceptance books the partner''s principal');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lent_loan SET status = 'LAPSED' WHERE loan_id = '00000000-0000-0000-0000-0000000000a5' $q$, '23514',
                           'O5 not lapsed before its 15th day');
UPDATE lending.co_lent_loan SET status = 'DECLINED', decision_reason = 'policy' WHERE loan_id = '00000000-0000-0000-0000-0000000000a6';
SELECT pg_temp.day('2026-07-16');
UPDATE lending.co_lent_loan SET status = 'ACCEPTED', partner_principal = 80000, transfer_lot_id = pg_temp.transfer('00000000-0000-0000-0000-0000000000a1', 80000)
 WHERE loan_id = '00000000-0000-0000-0000-0000000000a1';
SELECT pg_temp.check((SELECT status = 'ACCEPTED' AND decided_on = '2026-07-16' FROM lending.co_lent_loan WHERE loan_id = '00000000-0000-0000-0000-0000000000a1'),
                     'O6 accepted on the 15th day, in time');
UPDATE lending.co_lent_loan SET status = 'LAPSED', decision_reason = 'not accepted' WHERE loan_id = '00000000-0000-0000-0000-0000000000a5';
SELECT pg_temp.check((SELECT status = 'LAPSED' FROM lending.co_lent_loan WHERE loan_id = '00000000-0000-0000-0000-0000000000a5'), 'O7 the day-end of the 15th day lapses it');
SELECT pg_temp.day('2026-07-17');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lent_loan SET status = 'ACCEPTED', partner_principal = 80000,
    transfer_lot_id = pg_temp.transfer('00000000-0000-0000-0000-0000000000a7', 80000) WHERE loan_id = '00000000-0000-0000-0000-0000000000a7' $q$, '23514',
    'O8 not accepted after the 15th day: the loan stays with the originator');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lent_loan SET status = 'OFFERED' WHERE loan_id = '00000000-0000-0000-0000-0000000000a1' $q$, '42501',
                           'O9 a settled allocation never changes');
SELECT pg_temp.expect_fail($q$ UPDATE lending.co_lent_loan SET status = 'SANCTIONED' WHERE loan_id = '00000000-0000-0000-0000-0000000000a7' $q$, '23514',
                           'O10 taken back to sanction only when the disbursement is reversed');
UPDATE lending.loan_account SET status = 'SANCTIONED', disbursed_on = NULL WHERE id = '00000000-0000-0000-0000-0000000000a7';
UPDATE lending.co_lent_loan SET status = 'SANCTIONED' WHERE loan_id = '00000000-0000-0000-0000-0000000000a7';
SELECT pg_temp.check((SELECT offered_on IS NULL AND deadline IS NULL FROM lending.co_lent_loan WHERE loan_id = '00000000-0000-0000-0000-0000000000a7'),
                     'O11 a reversed disbursement takes the offer back; it is made again at the next');
SELECT pg_temp.expect_fail($q$ DELETE FROM lending.co_lent_loan $q$, '42501', 'O12 a co-lent loan is never deleted');

-- ---- the books --------------------------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) = 0 FROM lending.co_lending_problems()), 'B1 the contra head agrees with the partner''s principal accepted');
SELECT pg_temp.transfer('00000000-0000-0000-0000-0000000000a6', 100);
SELECT pg_temp.check((SELECT kept = 80000 AND ledger = 80100 FROM lending.co_lending_problems()), 'B2 a posting to the head with no acceptance behind it shows up');
\echo ALL CO-LENDING TESTS PASSED
