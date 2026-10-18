-- Phase 4 (P4-5, tenant V35): recurring deposits - the product type, booking, the first instalment as the funding, later
-- instalments in order with the late charge, the instalment record against the movements, and what a recurring deposit refuses.
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
INSERT INTO platform.business_day VALUES (1, '2026-06-25', 'OPEN');
INSERT INTO platform.legal_entity (legal_name, entity_type, registered_state) VALUES ('CLAUDE-TEST Bank Ltd', 'BANK', '32');
SELECT ledger.load_starter_kit('BANK');
SELECT ledger.load_lending_heads();
SELECT ledger.load_deposit_heads();
SELECT pg_temp.customer(1, 'Asha Depositor');
INSERT INTO deposits.product (code, name, family, product_type, day_count, min_balance, interest_bands, band_mode, interest_posting_months,
                              cheque_facility, gl_liability, gl_interest_expense, effective_from, updated_by)
VALUES ('SB01', 'CLAUDE-TEST Savings', 'CASA', 'SAVINGS', 'ACTUAL_365', 1000, '[{"fromBalance": 0, "ratePercent": 2.75}]', 'INCREMENTAL', 3,
        true, '2401', '5108', '2026-06-25', 'maker');
INSERT INTO deposits.product (code, name, family, product_type, day_count, min_amount, min_tenure_months, min_tenure_days, max_tenure_months,
    max_tenure_days, interest_period_months, rate_card, add_ons, premature_allowed, premature_penalty_percent, premature_min_days,
    auto_renewal_allowed, gl_liability, gl_interest_expense, effective_from, updated_by)
VALUES ('FDC1', 'CLAUDE-TEST FD cumulative', 'TERM_DEPOSIT', 'FD_CUMULATIVE', 'ACTUAL_365', 10000, 0, 7, 120, 0, 3,
        '[{"fromAmount": 0, "fromMonths": 0, "fromDays": 7, "ratePercent": 4.5}, {"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 7.1}]',
        '[]', true, 1, 7, true, '2403', '5107', '2026-06-25', 'maker');

-- ---- the product ------------------------------------------------------------------------------------------
CREATE FUNCTION pg_temp.rd_product(p_code text, p_extra jsonb DEFAULT '{}') RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO deposits.product (code, name, family, product_type, day_count, min_amount, min_tenure_months, min_tenure_days, max_tenure_months,
      max_tenure_days, interest_period_months, rate_card, add_ons, premature_allowed, premature_penalty_percent, premature_min_days,
      auto_renewal_allowed, gl_liability, gl_interest_expense, effective_from, updated_by, rd_late_charge_per_100)
  VALUES (p_code, 'CLAUDE-TEST Recurring deposit', 'TERM_DEPOSIT', coalesce(p_extra->>'type', 'RD'), 'ACTUAL_365', 500, 6,
          coalesce((p_extra->>'minDays')::int, 0), 120, 0, 3,
          coalesce(p_extra->'card', '[{"fromAmount": 0, "fromMonths": 6, "fromDays": 0, "ratePercent": 6.5}, {"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 7}]'),
          '[]', true, 1, 0, coalesce((p_extra->>'renew')::boolean, false), '2405', '5107', '2026-06-25', 'maker',
          CASE WHEN p_extra ? 'noLate' THEN NULL ELSE coalesce((p_extra->>'late')::numeric, 1.5) END);
END $$;
SELECT pg_temp.rd_product('RD1');
SELECT pg_temp.check((SELECT product_type = 'RD' AND rd_late_charge_per_100 = 1.5 FROM deposits.product WHERE code = 'RD1'), 'R1 a recurring deposit product with its late charge');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.rd_product('RD2', '{"noLate": true}') $q$, '23514', 'R2 a recurring deposit product needs its late charge');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.rd_product('RD3', '{"renew": true}') $q$, '23514', 'R3 a recurring deposit is never renewed');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.rd_product('RD4', '{"minDays": 15}') $q$, '23514', 'R4 a recurring deposit runs for whole months');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.rd_product('RD5', '{"card": [{"fromAmount": 0, "fromMonths": 6, "fromDays": 10, "ratePercent": 6.5}]}') $q$, '23514',
                           'R5 its rate table steps are whole months');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.rd_product('RD6', '{"type": "FD_CUMULATIVE"}') $q$, '23514', 'R6 a fixed deposit has no late charge');

-- ---- booking and the first instalment ------------------------------------------------------------------------
CREATE FUNCTION pg_temp.rd(p_months int DEFAULT 12, p_extra jsonb DEFAULT '{}') RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE aid uuid; p deposits.product%ROWTYPE; card record; a deposits.account%ROWTYPE;
BEGIN
  INSERT INTO deposits.account (family, product_code, product_version, customer_id, branch_code, operating_mode, nomination_mode, status, opened_on, opened_by)
  VALUES ('TERM_DEPOSIT', 'RD1', 0, '00000000-0000-0000-0000-0000000000c1', 'HO', 'SINGLE', 'NONE', 'ACTIVE', '1999-01-01', 'maker') RETURNING id INTO aid;
  SELECT * INTO a FROM deposits.account WHERE id = aid;
  INSERT INTO deposits.account_holder VALUES (aid, 1, '00000000-0000-0000-0000-0000000000c1');
  SELECT * INTO p FROM deposits.product WHERE code = 'RD1';
  SELECT * INTO card FROM deposits.card_rate(p.rate_card, 1000, a.opened_on, (a.opened_on + make_interval(months => p_months))::date
                                              + coalesce((p_extra->>'days')::int, 0));
  INSERT INTO deposits.term_deposit (account_id, principal, tenure_months, tenure_days, booked_on, rate_percent, card_rate_percent,
      card_from_amount, card_from_months, card_from_days, add_ons, interest_period_months, cumulative, day_count, rounding, tds_applicable,
      premature_allowed, premature_penalty_percent, premature_min_days, payout_to, maturity_instruction)
  VALUES (aid, 1000, p_months, coalesce((p_extra->>'days')::int, 0), '1999-01-01', card.rate_percent, card.rate_percent, card.from_amount, card.from_months,
      card.from_days, '[]', 3, true, 'ACTUAL_365', 'PAISE_HALF_UP', true, true, 1, 0, 'BANK_ACCOUNT', coalesce(p_extra->>'instruction', 'PAY_OUT'));
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN aid;
END $$;
CREATE FUNCTION pg_temp.day(d date) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  PERFORM set_config('corebanking.eod', 'on', false);
  UPDATE platform.business_day SET business_date = d WHERE id = 1;
  PERFORM set_config('corebanking.eod', 'off', false);
END $$;
CREATE TEMP TABLE acct (name text PRIMARY KEY, id uuid);
CREATE FUNCTION pg_temp.a(p text) RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT id FROM acct WHERE name = p $$;
INSERT INTO acct VALUES ('rd', pg_temp.rd());
INSERT INTO acct VALUES ('sb', pg_temp.casa('SB01', 1));
INSERT INTO acct VALUES ('fd', pg_temp.fd(1));
SELECT pg_temp.check((SELECT cumulative AND rate_percent = 7 AND principal = 1000 FROM deposits.term_deposit WHERE account_id = pg_temp.a('rd')),
                     'R7 a recurring deposit of 1,000 a month for 12 months is booked at 7%, compounding');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.rd(12, '{"instruction": "RENEW_PRINCIPAL"}') $q$, '23514', 'R8 it is booked to be paid out');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.rd(12, '{"days": 10}') $q$, '23514', 'R9 and for whole months');

-- The first instalment funds the deposit (P4-2) and is recorded as instalment 1.
CREATE FUNCTION pg_temp.instalment(p_rd uuid, p_no int, p_extra jsonb DEFAULT '{}') RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE lot uuid := gen_random_uuid(); mid uuid; amt numeric := coalesce((p_extra->>'amount')::numeric, 1000); t deposits.term_deposit%ROWTYPE;
        late numeric := coalesce((p_extra->>'late')::numeric, 0); src uuid := (p_extra->>'from')::uuid;
BEGIN
  SELECT * INTO t FROM deposits.term_deposit WHERE account_id = p_rd;
  INSERT INTO ledger.transaction_lot (id, lot_type, business_date, value_date, created_by) VALUES (lot, 'TEST', pg_temp.today(), pg_temp.today(), 'maker');
  INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
  VALUES (lot, pg_temp.today(), 'HO', CASE WHEN src IS NULL THEN '1201' ELSE '2401' END, CASE WHEN src IS NULL THEN '1201' ELSE pg_temp.no(src) END, 'DR', amt + late),
         (lot, pg_temp.today(), 'HO', '2405', pg_temp.no(p_rd), 'CR', amt);
  IF late > 0 THEN
    INSERT INTO ledger.account_entry (lot_id, business_date, branch_code, gl_code, account_no, side, amount)
    VALUES (lot, pg_temp.today(), 'HO', '4107', '4107', 'CR', late);
  END IF;
  INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, created_by)
  VALUES (p_rd, CASE WHEN p_no = 1 THEN 'FUNDING' ELSE coalesce(p_extra->>'kind', 'RD_INSTALMENT') END, 'CREDIT', amt, 0, pg_temp.today(), pg_temp.today(), lot, 'maker')
  RETURNING id INTO mid;
  IF src IS NOT NULL THEN
    INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, counterparty_id, created_by)
    VALUES (src, 'FUNDING_PAID', 'DEBIT', amt, 0, pg_temp.today(), pg_temp.today(), lot, p_rd, 'maker');
    IF late > 0 THEN
      INSERT INTO deposits.movement (account_id, kind, direction, amount, balance_after, business_date, value_date, lot_id, counterparty_id, created_by)
      VALUES (src, 'RD_LATE_CHARGE', 'DEBIT', late, 0, pg_temp.today(), pg_temp.today(), lot, p_rd, 'maker');
    END IF;
  END IF;
  IF p_no = 1 THEN
    UPDATE deposits.term_deposit SET start_date = pg_temp.today(), maturity_date = (pg_temp.today() + make_interval(months => t.tenure_months))::date,
           funding_source = 'CASH', funding_movement_id = mid, payout_bank_name_cipher = '\x01', payout_bank_account_cipher = '\x02',
           payout_bank_account_last4 = '4321', payout_bank_ifsc = 'HDFC0001234'
     WHERE account_id = p_rd;
    UPDATE deposits.account SET status = 'ACTIVE' WHERE id = p_rd;
  END IF;
  IF NOT p_extra ? 'noRecord' THEN
    INSERT INTO deposits.rd_instalment (account_id, instalment_no, due_date, amount, late_charge, paid_on, source, movement_id, lot_id, created_by)
    SELECT p_rd, coalesce((p_extra->>'recordAs')::int, p_no),
           (td.start_date + make_interval(months => coalesce((p_extra->>'recordAs')::int, p_no) - 1))::date, amt, late, pg_temp.today(),
           CASE WHEN src IS NULL THEN 'CASH' ELSE 'ACCOUNT' END, mid, lot, 'maker'
      FROM deposits.term_deposit td WHERE td.account_id = p_rd;
  END IF;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  RETURN mid;
END $$;
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.instalment(pg_temp.a('rd'), 1, '{"noRecord": true}') $q$, '23514', 'R10 a funding of a recurring deposit is recorded as instalment 1');
INSERT INTO acct VALUES ('i1', pg_temp.instalment(pg_temp.a('rd'), 1));
SELECT pg_temp.check((SELECT a.status = 'ACTIVE' AND pg_temp.balance(a.id) = 1000 AND t.start_date = '2026-06-25' AND t.maturity_date = '2027-06-25'
                        FROM deposits.account a JOIN deposits.term_deposit t ON t.account_id = a.id WHERE a.id = pg_temp.a('rd')),
                     'R11 funded by the first instalment: active, 1,000, matures 25-Jun-2027');

-- ---- a funding reversed takes instalment 1 back -----------------------------------------------------------------
INSERT INTO acct VALUES ('rd2', pg_temp.rd(6));
INSERT INTO acct VALUES ('r2i1', pg_temp.instalment(pg_temp.a('rd2'), 1));
SELECT pg_temp.reverse(pg_temp.a('r2i1'), 'CLAUDE-TEST wrong customer');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.rd_instalment SET reversed = true WHERE movement_id = pg_temp.a('i1') $q$, '42501',
                           'R24 an instalment is marked reversed only when its movement is');
UPDATE deposits.rd_instalment SET reversed = true WHERE movement_id = pg_temp.a('r2i1');
SELECT pg_temp.check((SELECT count(*) = 0 FROM deposits.rd_problems()), 'R25 the reversed funding no longer counts as an instalment');
INSERT INTO acct VALUES ('r2i1b', pg_temp.instalment(pg_temp.a('rd2'), 1));
SELECT pg_temp.check((SELECT count(*) = 2 FROM deposits.rd_instalment WHERE account_id = pg_temp.a('rd2'))
                     AND (SELECT count(*) = 0 FROM deposits.rd_problems()), 'R26 funded again: instalment 1 recorded anew');

-- ---- later instalments ------------------------------------------------------------------------------------------
SELECT pg_temp.day('2026-07-25');
INSERT INTO acct VALUES ('i2', pg_temp.instalment(pg_temp.a('rd'), 2));
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('rd')) = 2000 AND (SELECT count(*) = 2 FROM deposits.rd_instalment WHERE account_id = pg_temp.a('rd')),
                     'R12 instalment 2 paid on its due date: 2,000 held, two instalments');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.instalment(pg_temp.a('rd'), 4) $q$, '23514', 'R13 instalments are paid in order');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.instalment(pg_temp.a('rd'), 3, '{"amount": 900}') $q$, '23514', 'R14 an instalment is the monthly amount');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.instalment(pg_temp.a('rd'), 3, '{"noRecord": true}') $q$, '23514', 'R15 an instalment movement is recorded as an instalment');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('sb'), 'RD_INSTALMENT', 100, '1201', '{"direction": "CREDIT"}') $q$, '23514',
                           'R16 a savings account takes no instalment');
SELECT pg_temp.move(pg_temp.a('sb'), 'CASH_DEPOSIT', 5000);
SELECT pg_temp.fund(pg_temp.a('fd'), 'CASH', '{"bank": "HDFC0001234"}');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.move(pg_temp.a('fd'), 'RD_INSTALMENT', 1000, '1201', '{"direction": "CREDIT"}') $q$, '23514',
                           'R17 a fixed deposit takes no instalment');
SELECT pg_temp.day('2026-09-30');
INSERT INTO acct VALUES ('i3', pg_temp.instalment(pg_temp.a('rd'), 3, jsonb_build_object('from', pg_temp.a('sb'), 'late', 30)));
SELECT pg_temp.check(pg_temp.balance(pg_temp.a('sb')) = 3970 AND pg_temp.balance(pg_temp.a('rd')) = 3000
                     AND (SELECT late_charge = 30 AND due_date = '2026-08-25' AND source = 'ACCOUNT' FROM deposits.rd_instalment
                           WHERE account_id = pg_temp.a('rd') AND instalment_no = 3),
                     'R18 instalment 3 paid late from the savings account with a late charge of 30: 1,030 out of it');
SELECT pg_temp.check((SELECT count(*) = 0 FROM deposits.rd_problems()) AND (SELECT count(*) = 0 FROM deposits.balance_problems()),
                     'R19 the instalments agree with the movements, the balances with the ledger');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.rd_instalment SET late_charge = 0 WHERE instalment_no = 3 $q$, '42501', 'R20 an instalment never changes');
SELECT pg_temp.expect_fail($q$ DELETE FROM deposits.rd_instalment WHERE instalment_no = 3 $q$, '42501', 'R21 nor is deleted');
SELECT pg_temp.expect_fail($q$ UPDATE deposits.term_deposit SET maturity_instruction = 'RENEW_PRINCIPAL' WHERE account_id = pg_temp.a('rd') $q$, '23514',
                           'R22 a recurring deposit is not set to renew');
SELECT pg_temp.day('2027-06-25');
SELECT pg_temp.expect_fail($q$ SELECT pg_temp.instalment(pg_temp.a('rd'), 4) $q$, '23514', 'R23 no instalment on or after the maturity date');

\echo 'ALL RECURRING DEPOSIT TESTS PASSED'
