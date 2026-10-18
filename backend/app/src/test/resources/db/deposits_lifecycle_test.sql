-- Phase 4 (P4-4, tenant V34): the life of a deposit account - charges with GST, the minimum balance months, closing an account,
-- a term deposit matured and renewed, consent to renew on a deposit-taking NBFC, maturity notices, and the GST register.
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
  INSERT INTO deposits.casa_account VALUES (aid, CASE WHEN p_product = 'SB01' THEN DATE '2026-06-30' END);
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
INSERT INTO platform.branch (code, name, state_code, parent_code) VALUES ('MUM', 'Mumbai', '27', 'HO');
INSERT INTO platform.business_day VALUES (1, '2026-06-30', 'OPEN');
INSERT INTO platform.legal_entity (legal_name, entity_type, registered_state) VALUES ('CLAUDE-TEST Bank Ltd', 'BANK', '32');
SELECT ledger.load_starter_kit('BANK');
SELECT ledger.load_lending_heads();
SELECT ledger.load_deposit_heads();
SELECT pg_temp.customer(1, 'Asha Depositor');
SELECT pg_temp.customer(2, 'Binu Other');
INSERT INTO deposits.product (code, name, family, product_type, day_count, min_balance, interest_bands, band_mode, interest_posting_months,
                              cheque_facility, gl_liability, gl_interest_expense, effective_from, updated_by)
VALUES ('SB01', 'CLAUDE-TEST Savings', 'CASA', 'SAVINGS', 'ACTUAL_365', 1000, '[{"fromBalance": 0, "ratePercent": 2.75}]', 'INCREMENTAL', 3,
        true, '2401', '5108', '2026-06-30', 'maker');
INSERT INTO deposits.product (code, name, family, product_type, min_balance, cheque_facility, gl_liability, effective_from, updated_by)
VALUES ('CA01', 'CLAUDE-TEST Current', 'CASA', 'CURRENT', 10000, true, '2402', '2026-06-30', 'maker');
INSERT INTO deposits.product (code, name, family, product_type, day_count, min_amount, min_tenure_months, min_tenure_days, max_tenure_months,
    max_tenure_days, interest_period_months, rate_card, add_ons, premature_allowed, premature_penalty_percent, premature_min_days,
    auto_renewal_allowed, gl_liability, gl_interest_expense, effective_from, updated_by)
VALUES ('FDC1', 'CLAUDE-TEST FD', 'TERM_DEPOSIT', 'FD_CUMULATIVE', 'ACTUAL_365', 10000, 0, 7, 120, 0, 3,
        '[{"fromAmount": 0, "fromMonths": 0, "fromDays": 7, "ratePercent": 4.5}, {"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 7.1}]',
        '[]', true, 1, 7, true, '2403', '5107', '2026-06-30', 'maker');
CREATE TEMP TABLE acct (name text PRIMARY KEY, id uuid);
INSERT INTO acct VALUES ('sb', pg_temp.casa('SB01', 1)), ('ca_mum', pg_temp.casa('CA01', 1, 'MUM')), ('sb_other', pg_temp.casa('SB01', 2));
CREATE FUNCTION pg_temp.a(p text) RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT id FROM acct WHERE name = p $$;

INSERT INTO acct VALUES ('fd', pg_temp.fd(1, 'CASA', pg_temp.a('sb'))), ('fd2', pg_temp.fd(1, 'CASA', pg_temp.a('sb')));
CREATE TEMP TABLE mv (name text PRIMARY KEY, id uuid);
-- a two-leg lot: Dr p_dr_gl / Cr p_cr_gl, with the given account numbers on each leg
CREATE FUNCTION pg_temp.lot(p_dr_gl text, p_dr_no text, p_cr_gl text, p_cr_no text, p_amount numeric, p_type text DEFAULT 'TEST') RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid();
BEGIN
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (lot, p_type, pg_temp.today(), pg_temp.today(), 'eod');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (lot, pg_temp.today(), 'HO', p_dr_gl, p_dr_no, 'DR', p_amount), (lot, pg_temp.today(), 'HO', p_cr_gl, p_cr_no, 'CR', p_amount);
  RETURN lot;
END $$;
-- interest credited from payable to an account, as the day-end does
CREATE FUNCTION pg_temp.credit(p_account uuid, p_amount numeric) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := pg_temp.lot('2404', '2404', pg_temp.head(p_account), pg_temp.no(p_account), p_amount, 'DEPOSIT_INTEREST_CREDIT'); mid uuid;
BEGIN
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, lot_id, created_by)
  VALUES (p_account, 'INTEREST_CREDIT', 'CREDIT', p_amount, 0, pg_temp.today(), lot, 'eod') RETURNING id INTO mid;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN mid;
END $$;
-- a deposit's interest paid to a savings account
CREATE FUNCTION pg_temp.payout(p_deposit uuid, p_to uuid, p_amount numeric, p_counterparty boolean DEFAULT true) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := pg_temp.lot(pg_temp.head(p_deposit), pg_temp.no(p_deposit), pg_temp.head(p_to), pg_temp.no(p_to), p_amount, 'DEPOSIT_INTEREST_PAYOUT'); mid uuid;
BEGIN
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, lot_id, counterparty_id, created_by)
  VALUES (p_deposit, 'INTEREST_PAYOUT', 'DEBIT', p_amount, 0, pg_temp.today(), lot, CASE WHEN p_counterparty THEN p_to END, 'eod') RETURNING id INTO mid;
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, lot_id, counterparty_id, created_by)
  VALUES (p_to, 'TRANSFER_IN', 'CREDIT', p_amount, 0, pg_temp.today(), lot, p_deposit, 'eod');
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN mid;
END $$;
INSERT INTO mv VALUES ('cash', pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 200000));
INSERT INTO mv VALUES ('fund', pg_temp.fund(pg_temp.a('fd'), 'ACCOUNT', jsonb_build_object('from', pg_temp.a('sb'))));
SELECT ledger.load_deposit_heads();
-- tax taken from an account, as the day-end does after a credit
CREATE FUNCTION pg_temp.tax(p_account uuid, p_amount numeric) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := pg_temp.lot(pg_temp.head(p_account), pg_temp.no(p_account), '2205', '2205', p_amount, 'DEPOSIT_TAX_DEDUCTED'); mid uuid;
BEGIN
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, lot_id, created_by)
  VALUES (p_account, 'TAX_DEDUCTED', 'DEBIT', p_amount, 0, pg_temp.today(), lot, 'eod') RETURNING id INTO mid;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN mid;
END $$;
UPDATE customer.customer SET pan_hash = decode(repeat('ab', 32), 'hex') WHERE id = '00000000-0000-0000-0000-0000000000c1';
INSERT INTO customer.customer (id, customer_no, customer_type, display_name, home_branch, status)
VALUES ('00000000-0000-0000-0000-0000000000c9', '9001000000009' || platform.luhn_digit('9001000000009'), 'NON_INDIVIDUAL', 'CLAUDE-TEST Traders', 'HO', 'ACTIVE');

-- a charge as the application levies it: Dr the account (charge + GST) / Cr 4107 and the GST heads, the movement and the invoice
CREATE FUNCTION pg_temp.charge(p_account uuid, p_taxable numeric, p_cgst numeric, p_sgst numeric, p_igst numeric, p_extra jsonb DEFAULT '{}')
RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid(); mid uuid; cid uuid; total numeric := p_taxable + p_cgst + p_sgst + p_igst;
BEGIN
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (lot, 'DEPOSIT_CHARGE', pg_temp.today(), pg_temp.today(), 'maker');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (lot, pg_temp.today(), pg_temp.branch(p_account), pg_temp.head(p_account), pg_temp.no(p_account), 'DR', total),
         (lot, pg_temp.today(), pg_temp.branch(p_account), '4107', '4107', 'CR', p_taxable - coalesce((p_extra->>'short')::numeric, 0));
  IF p_cgst > 0 THEN INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
    VALUES (lot, pg_temp.today(), pg_temp.branch(p_account), '2201', '2201', 'CR', p_cgst), (lot, pg_temp.today(), pg_temp.branch(p_account), '2202', '2202', 'CR', p_sgst); END IF;
  IF p_igst > 0 THEN INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
    VALUES (lot, pg_temp.today(), pg_temp.branch(p_account), '2203', '2203', 'CR', p_igst); END IF;
  IF p_extra ? 'short' THEN INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
    VALUES (lot, pg_temp.today(), pg_temp.branch(p_account), '4105', '4105', 'CR', (p_extra->>'short')::numeric); END IF;
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, lot_id, created_by)
  VALUES (p_account, coalesce(p_extra->>'kind', 'CHARGE'), 'DEBIT', coalesce((p_extra->>'movement')::numeric, total), 0, pg_temp.today(), lot, 'maker') RETURNING id INTO mid;
  INSERT INTO deposits.charge (account_id, charge_code, description, business_date, taxable_value, gst_rate, cgst, sgst, igst, branch_code,
                               supplier_state, place_of_supply, invoice_no, movement_id, lot_id, created_by)
  VALUES (p_account, 'DUP_STATEMENT', 'Duplicate statement', '1999-01-01', p_taxable, 18, p_cgst, p_sgst, p_igst, pg_temp.branch(p_account), '32',
          coalesce(p_extra->>'place', CASE WHEN p_igst > 0 THEN '27' ELSE '32' END), platform.next_number('GST_INVOICE'), mid, lot, 'maker') RETURNING id INTO cid;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN cid;
END $$;
-- the whole balance paid out of an account (to cash), as a closure does; p_kind CLOSURE or RENEWAL (with p_to)
CREATE FUNCTION pg_temp.payout_all(p_account uuid, p_amount numeric DEFAULT NULL, p_kind text DEFAULT 'CLOSURE', p_to uuid DEFAULT NULL) RETURNS uuid
LANGUAGE plpgsql AS $$
DECLARE amt numeric := coalesce(p_amount, pg_temp.balance(p_account)); lot uuid; mid uuid;
BEGIN
  lot := CASE WHEN p_to IS NULL THEN pg_temp.lot(pg_temp.head(p_account), pg_temp.no(p_account), '1201', '1201', amt, 'DEPOSIT_CLOSURE')
              ELSE pg_temp.lot(pg_temp.head(p_account), pg_temp.no(p_account), pg_temp.head(p_to), pg_temp.no(p_to), amt, 'DEPOSIT_RENEWAL') END;
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, lot_id, counterparty_id, created_by)
  VALUES (p_account, p_kind, 'DEBIT', amt, 0, pg_temp.today(), lot, p_to, 'maker') RETURNING id INTO mid;
  IF p_to IS NOT NULL THEN
    INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, lot_id, counterparty_id, created_by)
    VALUES (p_to, 'FUNDING', 'CREDIT', amt, 0, pg_temp.today(), lot, p_account, 'maker');
  END IF;
  RETURN mid;
END $$;

-- 1. settings, rules, product -----------------------------------------------------------------------------------------
SELECT pg_temp.check((SELECT count(*) = 6 FROM platform.system_property WHERE key IN ('deposits.gl.charge-income','deposits.gl.cgst','deposits.gl.sgst',
                        'deposits.gl.igst','deposits.overdue-rate-percent','deposits.maturity-notice-days')), 'S1 the settings are there');
SELECT pg_temp.check(deposits.rule_value('td.overdue-interest') = 1, 'S2 a bank pays interest on a matured deposit left unpaid (rule, unverified)');
UPDATE deposits.product SET charges = '{"gstRatePercent": 18, "minBalancePercent": 10, "minBalanceMax": 300, "service": [{"code": "DUP_STATEMENT", "name": "Duplicate statement", "amount": 100}]}',
       updated_by = 'maker' WHERE code = 'SB01';
SELECT pg_temp.check((SELECT charges->>'minBalanceMax' = '300' FROM deposits.product WHERE code = 'SB01')
                     AND (SELECT snapshot ? 'charges' FROM deposits.product_history WHERE product_code = 'SB01' ORDER BY version DESC LIMIT 1),
                     'S3 a savings product takes a schedule of charges, kept in its history');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.product SET charges = '{}', updated_by = 'maker' WHERE code = 'FDC1' $q$, '23514',
                           'S4 a term deposit product has no schedule of charges');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.product SET charges = '{"service": {}}', updated_by = 'maker' WHERE code = 'SB01' $q$, '23514',
                           'S5 the service charges are a list');

INSERT INTO platform.staff_user (user_id, username, display_name, home_branch, all_branches, status)
VALUES ('u-all', 'all.user', 'All Branches', 'HO', true, 'ACTIVE');
-- 2. charges ------------------------------------------------------------------------------------------------------------
CREATE TEMP TABLE ch (name text PRIMARY KEY, id uuid);
INSERT INTO ch VALUES ('dup', pg_temp.charge(pg_temp.a('sb'), 100, 9, 9, 0));
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 100000 - 118
                     AND (SELECT total = 118 AND status = 'ISSUED' AND business_date = pg_temp.today() AND invoice_no LIKE '7001%' FROM deposits.charge WHERE id = (SELECT id FROM ch WHERE name = 'dup')),
                     'C1 a charge takes its value and GST from the account, with a tax invoice from the loans'' series');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.charge(pg_temp.a('sb'), 100, 9, 9, 0, '{"movement": 100}') $q$, '23514', 'C2 the movement is the charge with its tax');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.charge(pg_temp.a('sb'), 100, 9, 9, 0, '{"short": 10}') $q$, '23514', 'C3 the lot credits the charge head with its whole value');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.charge(pg_temp.a('sb'), 100, 0, 0, 18, '{"place": "32"}') $q$, '23514', 'C4 IGST is for a supply to another state only');
INSERT INTO ch VALUES ('igst', pg_temp.charge(pg_temp.a('sb'), 100, 0, 0, 18) );
SELECT pg_temp.check(true, 'C5 (a charge to a depositor in another state takes IGST)');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.charge(pg_temp.a('fd'), 100, 9, 9, 0) $q$, '23514', 'C6 a term deposit is not charged');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.charge SET taxable_value = 1 WHERE id = (SELECT id FROM ch WHERE name = 'dup') $q$, '42501', 'C7 a charge never changes');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.charge SET status = 'CANCELLED', cancelled_on = pg_temp.today(), credit_note_no = 'X1',
                              cancel_movement_id = (SELECT id FROM mv WHERE name = 'cash') WHERE id = (SELECT id FROM ch WHERE name = 'dup') $q$, '23514',
                           'C8 it is cancelled only by the reversal of its own movement');
SELECT pg_temp.reverse((SELECT movement_id FROM deposits.charge WHERE id = (SELECT id FROM ch WHERE name = 'dup')), 'charged in error');
UPDATE deposits.charge SET status = 'CANCELLED', cancelled_on = pg_temp.today(), credit_note_no = platform.next_number('GST_CREDIT_NOTE'),
       cancel_movement_id = (SELECT reversed_by FROM deposits.movement WHERE id = movement_id) WHERE id = (SELECT id FROM ch WHERE name = 'dup');
SELECT pg_temp.check((SELECT status = 'CANCELLED' AND credit_note_no LIKE '7002%' FROM deposits.charge WHERE id = (SELECT id FROM ch WHERE name = 'dup'))
                     AND pg_temp.balance(pg_temp.a('sb')) = 100000 - 118, 'C9 a reversed charge is cancelled with a credit note and its money is back');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.charge $q$, '42501', 'C10 a charge is never deleted');
SELECT pg_temp.check((SELECT count(*) = 2 FROM reporting.rpt_gst_output_register('all.user', '{"from": "2026-06-01", "to": "2026-06-30"}') WHERE loan_no = pg_temp.no(pg_temp.a('sb')))
                     AND (SELECT sum(igst) = 18 AND sum(cgst) = 0 FROM reporting.rpt_gst_output_summary('all.user', '{"from": "2026-06-01", "to": "2026-06-30"}')),
                     'C11 the GST register lists deposit charges, and the summary counts only those not cancelled');

-- 3. minimum balance months ----------------------------------------------------------------------------------------------
INSERT INTO deposits.min_balance_month (account_id, month, from_date, to_date, required, average, shortfall, notified)
VALUES (pg_temp.a('sb'), '2026-06-01', '2026-06-01', '2026-06-30', 1000, 400, 600, true);
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.min_balance_month (account_id, month, from_date, to_date, required, average, shortfall, notified)
                              VALUES (pg_temp.a('sb'), '2026-05-02', '2026-05-02', '2026-05-31', 1000, 1200, 0, false) $q$, '23514', 'M1 a month starts on its first day');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.min_balance_month (account_id, month, from_date, to_date, required, average, shortfall, notified)
                              VALUES (pg_temp.a('sb'), '2026-05-01', '2026-05-01', '2026-05-31', 1000, 400, 600, false) $q$, '23514', 'M2 a short month is notified');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.min_balance_month SET notified = false $q$, '42501', 'M3 a month evaluated is kept as it was');

-- 4. closing a savings or current account ------------------------------------------------------------------------------
SELECT pg_temp.move(pg_temp.a('ca_mum'), 'CASH_DEPOSIT', 5000);
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.payout_all(pg_temp.a('ca_mum'), 4000) $q$, '23514', 'K1 a closure takes the whole balance');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.account SET status = 'CLOSED' WHERE id = pg_temp.a('ca_mum') $q$, '23514', 'K2 who closed it is recorded');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.immediate($x$ UPDATE deposits.account SET status = 'CLOSED', closed_by = 'checker' WHERE id = pg_temp.a('ca_mum') $x$) $q$,
                           '23514', 'K3 a closed account holds nothing');
SELECT pg_temp.hold(pg_temp.a('ca_mum'), 'CREDIT_FREEZE');
SELECT pg_temp.payout_all(pg_temp.a('ca_mum'));
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.immediate($x$ UPDATE deposits.account SET status = 'CLOSED', closed_by = 'checker' WHERE id = pg_temp.a('ca_mum') $x$) $q$,
                           '23514', 'K4 nor anything held on it');
SELECT pg_temp.release((SELECT id FROM deposits.account_hold WHERE account_id = pg_temp.a('ca_mum') AND status = 'ACTIVE'));
SELECT pg_temp.immediate($x$ UPDATE deposits.account SET status = 'CLOSED', closed_by = 'checker', close_reason = 'depositor moved away' WHERE id = pg_temp.a('ca_mum') $x$);
SELECT pg_temp.check((SELECT status = 'CLOSED' AND closed_on = pg_temp.today() FROM deposits.account WHERE id = pg_temp.a('ca_mum')), 'K5 an empty account is closed on the business date');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('ca_mum'), 'CASH_DEPOSIT', 10) $q$, '23514', 'K6 a closed account takes no money');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.account SET status = 'ACTIVE' WHERE id = pg_temp.a('ca_mum') $q$, '23514', 'K7 and it is never reopened');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.account SET close_reason = 'other' WHERE id = pg_temp.a('ca_mum') $q$, '42501', 'K8 its closure never changes');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.immediate($x$ SELECT pg_temp.payout_all(pg_temp.a('sb')); UPDATE deposits.account SET status = 'CLOSED', closed_by = 'checker' WHERE id = pg_temp.a('sb') $x$) $q$,
                           '23514', 'K9 the account a live deposit pays out to is not closed');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, lot_id, counterparty_id, created_by)
                              VALUES (pg_temp.a('sb'), 'RENEWAL', 'DEBIT', 1, 0, pg_temp.today(), (SELECT lot_id FROM deposits.movement LIMIT 1), pg_temp.a('fd'), 'x') $q$,
                           '23514', 'K10 a savings account is not renewed');
INSERT INTO deposits.closure (account_id, kind, reason, business_date, paid_amount, pay_to, pay_reference, created_by)
VALUES (pg_temp.a('ca_mum'), 'CASA', 'DEPOSITOR_REQUEST', pg_temp.today(), 5000, 'CASH', 'SLIP 1', 'checker');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.closure (account_id, kind, reason, business_date, paid_amount, pay_to, created_by)
                              VALUES (pg_temp.a('ca_mum'), 'CASA', 'DEPOSITOR_REQUEST', pg_temp.today(), 5000, 'CASH', 'checker') $q$, '23505', 'K11 one closure in force per account');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.closure (account_id, kind, reason, business_date, paid_amount, pay_to, created_by)
                              VALUES (pg_temp.a('sb_other'), 'CASA', 'DEPOSITOR_REQUEST', pg_temp.today(), 5000, 'ACCOUNT', 'checker') $q$, '23514', 'K12 a payout to an account names it');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.closure (account_id, kind, reason, business_date, paid_amount, pay_to, created_by)
                              VALUES (pg_temp.a('sb_other'), 'MATURITY', 'DEPOSITOR_REQUEST', pg_temp.today(), 5000, 'CASH', 'checker') $q$, '23514', 'K13 a maturity is closed for maturity');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.closure SET paid_amount = 1 $q$, '42501', 'K14 a closure is kept as written');

-- 5. a term deposit: matured, closed, reopened when its payout comes back, renewed -------------------------------------
SELECT pg_temp.expect_fail($q$ UPDATE deposits.account SET status = 'MATURED' WHERE id = pg_temp.a('fd2') $q$, '23514', 'D1 a booked deposit does not mature');
UPDATE deposits.account SET status = 'MATURED' WHERE id = pg_temp.a('fd');
SELECT pg_temp.check(pg_temp.credit(pg_temp.a('fd'), 41.10) IS NOT NULL, 'D2 a matured deposit still takes interest (overdue interest when it is paid)');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('fd'), 'CASH_WITHDRAWAL', 10) $q$, '23514', 'D3 a deposit pays out by its closure only');
SELECT pg_temp.payout_all(pg_temp.a('fd'));
SELECT pg_temp.immediate($x$ UPDATE deposits.account SET status = 'CLOSED', closed_by = 'eod' WHERE id = pg_temp.a('fd') $x$);
SELECT pg_temp.check((SELECT status = 'CLOSED' FROM deposits.account WHERE id = pg_temp.a('fd')) AND pg_temp.balance(pg_temp.a('fd')) = 0, 'D4 a matured deposit paid out is closed');
UPDATE deposits.account SET status = 'MATURED' WHERE id = pg_temp.a('fd');
SELECT pg_temp.check((SELECT closed_on IS NULL AND closed_by IS NULL FROM deposits.account WHERE id = pg_temp.a('fd')),
                     'D5 a deposit whose payout came back is MATURED again, its closure cleared');
SELECT pg_temp.reverse((SELECT id FROM deposits.movement WHERE account_id = pg_temp.a('fd') AND kind = 'CLOSURE'), 'returned by the bank');
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('fd')) = 100041.10, 'D6 and the money is back on it');
-- renewal into a new deposit, booked as the application books it
INSERT INTO acct VALUES ('fd3', pg_temp.fd(1, 'CASA', pg_temp.a('sb')));
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.immediate($x$ SELECT pg_temp.payout_all(pg_temp.a('fd'), NULL, 'RENEWAL', pg_temp.a('fd3'));
    UPDATE deposits.term_deposit SET start_date = pg_temp.today(), maturity_date = (pg_temp.today() + interval '12 months')::date, funding_source = 'RENEWAL',
           funding_movement_id = (SELECT id FROM deposits.movement WHERE account_id = pg_temp.a('fd3') AND kind = 'FUNDING') WHERE account_id = pg_temp.a('fd3') $x$) $q$,
                           '23514', 'D7 a renewal funding names the deposit it renews');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.immediate($x$ SELECT pg_temp.payout_all(pg_temp.a('fd'), 100000, 'RENEWAL', pg_temp.a('fd3')) $x$) $q$,
                           '23514', 'D8 a renewal takes the whole balance');
-- principal 1,00,000: the 41.10 of interest is paid out first, then the principal renews
SELECT pg_temp.payout(pg_temp.a('fd'), pg_temp.a('sb'), 41.10);
DO $$ BEGIN
PERFORM pg_temp.payout_all(pg_temp.a('fd'), NULL, 'RENEWAL', pg_temp.a('fd3'));
UPDATE deposits.term_deposit SET start_date = pg_temp.today(), maturity_date = (pg_temp.today() + interval '12 months')::date, funding_source = 'RENEWAL',
       renewed_from = pg_temp.a('fd'), funding_movement_id = (SELECT id FROM deposits.movement WHERE account_id = pg_temp.a('fd3') AND kind = 'FUNDING')
 WHERE account_id = pg_temp.a('fd3');
UPDATE deposits.account SET status = 'ACTIVE' WHERE id = pg_temp.a('fd3');
UPDATE deposits.account SET status = 'CLOSED', closed_by = 'eod' WHERE id = pg_temp.a('fd');
END $$;
SELECT pg_temp.check((SELECT status = 'ACTIVE' FROM deposits.account WHERE id = pg_temp.a('fd3')) AND pg_temp.balance(pg_temp.a('fd3')) = 100000
                     AND (SELECT status = 'CLOSED' FROM deposits.account WHERE id = pg_temp.a('fd')), 'D9 the matured deposit funds the new one and is closed');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.term_deposit SET start_date = NULL, maturity_date = NULL, funding_source = NULL, funding_movement_id = NULL,
                              renewed_from = NULL WHERE account_id = pg_temp.a('fd3') $q$, '42501', 'D10 the funding of a renewal is not reversed');
INSERT INTO acct VALUES ('fd_other', pg_temp.fd(2, 'BANK_ACCOUNT'));
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.immediate($x$ SELECT pg_temp.fund(pg_temp.a('fd_other'), 'CASH', '{"bank": "HDFC0001234"}');
    UPDATE deposits.term_deposit SET funding_source = 'RENEWAL', renewed_from = pg_temp.a('fd2') WHERE account_id = pg_temp.a('fd_other') $x$) $q$,
                           '23514', 'D11 a deposit renews a deposit of the same depositor');

-- 6. consent to renew on a deposit-taking NBFC ---------------------------------------------------------------------------
UPDATE platform.legal_entity SET entity_type = 'NBFC', deposit_taking = true, deposit_registration_ref = 'CLAUDE-TEST N-01';
SELECT pg_temp.check(deposits.rule_kind() = 'NBFC_DEPOSIT' AND deposits.rule_value('td.overdue-interest') = 0, 'N1 an NBFC pays no interest after maturity (rule, unverified)');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.term_deposit SET maturity_instruction = 'RENEW_PRINCIPAL' WHERE account_id = pg_temp.a('fd3') $q$, '23514',
                           'N2 no renewal instruction without the depositor''s consent');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.renewal_consent (account_id, instruction, reference, received_on, recorded_by)
                              VALUES (pg_temp.a('fd2'), 'RENEW_PRINCIPAL', 'LETTER 1', pg_temp.today(), 'maker') $q$, '23514', 'N3 consent is for a funded deposit');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.renewal_consent (account_id, instruction, reference, received_on, recorded_by)
                              VALUES (pg_temp.a('fd3'), 'RENEW_PRINCIPAL', 'LETTER 1', pg_temp.today() + 1, 'maker') $q$, '23514', 'N4 received by today');
INSERT INTO deposits.renewal_consent (account_id, instruction, reference, received_on, recorded_by) VALUES (pg_temp.a('fd3'), 'RENEW_WITH_INTEREST', 'LETTER 1', pg_temp.today(), 'maker');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.term_deposit SET maturity_instruction = 'RENEW_PRINCIPAL' WHERE account_id = pg_temp.a('fd3') $q$, '23514',
                           'N5 the consent is for the instruction given');
UPDATE deposits.term_deposit SET maturity_instruction = 'RENEW_WITH_INTEREST' WHERE account_id = pg_temp.a('fd3');
SELECT pg_temp.check((SELECT maturity_instruction = 'RENEW_WITH_INTEREST' FROM deposits.term_deposit WHERE account_id = pg_temp.a('fd3')), 'N6 with the consent recorded the deposit renews');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.renewal_consent (account_id, instruction, reference, received_on, recorded_by)
                              VALUES (pg_temp.a('fd3'), 'RENEW_PRINCIPAL', 'LETTER 2', pg_temp.today(), 'maker') $q$, '23505', 'N7 one consent in force at a time');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.renewal_consent SET reference = 'X' $q$, '42501', 'N8 a consent is not edited');
UPDATE deposits.renewal_consent SET status = 'USED', used_on = pg_temp.today() WHERE account_id = pg_temp.a('fd3');
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.term_deposit (account_id, principal, tenure_months, tenure_days, booked_on, rate_percent, card_rate_percent,
      card_from_amount, card_from_months, card_from_days, add_ons, interest_period_months, cumulative, day_count, rounding, tds_applicable,
      premature_allowed, premature_penalty_percent, premature_min_days, payout_to, payout_account_id, maturity_instruction)
    SELECT pg_temp.a('fd2'), 100000, 12, 0, '1999-01-01', 7.1, 7.1, 0, 12, 0, '[]', 3, true, 'ACTUAL_365', 'PAISE_HALF_UP', true, true, 1, 7, 'BANK_ACCOUNT', NULL, 'RENEW_PRINCIPAL' $q$,
                           '23514', 'N9 an instruction given at booking is not that consent');
UPDATE platform.legal_entity SET entity_type = 'BANK', deposit_taking = false;

-- 7. maturity notices ----------------------------------------------------------------------------------------------------
INSERT INTO deposits.maturity_notice (account_id, maturity_date, notice_on, instruction, expected_amount)
VALUES (pg_temp.a('fd3'), '2027-06-30', '2027-06-16', 'RENEW_WITH_INTEREST', 107185.90);
SELECT pg_temp.expect_fail($q$ INSERT INTO deposits.maturity_notice (account_id, maturity_date, notice_on, instruction, expected_amount)
                              VALUES (pg_temp.a('fd3'), '2027-06-30', '2027-06-17', 'PAY_OUT', 1) $q$, '23505', 'T1 one notice per maturity');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.maturity_notice SET expected_amount = 1 $q$, '42501', 'T2 a notice is kept as sent');
SELECT pg_temp.check((SELECT checkers_required FROM platform.approval_rule WHERE entity_type = 'TERM_DEPOSIT' AND action = 'CLOSE') = 1
                     AND (SELECT checkers_required FROM platform.approval_rule WHERE entity_type = 'DEPOSIT_TXN' AND action = 'CHARGE') = 1,
                     'T3 closing and charging need a checker');

SELECT pg_temp.check(NOT EXISTS (SELECT 1 FROM deposits.balance_problems()), 'Z1 at the end every balance agrees with the ledger');
SELECT pg_temp.check((SELECT count(*) = 0 FROM (SELECT lot_id FROM ledger.account_entry GROUP BY lot_id, branch_code
                        HAVING sum(CASE side WHEN 'DR' THEN amount ELSE -amount END) <> 0) x), 'Z2 every lot balances in each branch');
SELECT 'TESTS PASSED' AS result;
