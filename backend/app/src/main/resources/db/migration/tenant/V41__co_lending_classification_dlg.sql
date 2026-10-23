-- Phase 5 (P5-3): co-lending once the partner holds its share: each lender provisions and reports its own share, the
-- borrower's classification is kept in step with the partner, and the originator's default loss guarantee (DLG).
--   1. lending.co_lent_position.provision_partner: the partner's share of the loan's provision, written back daily (each
--      lender provisions its own exposure).
--   2. lending.co_lending_partner_class: the classification the partner reports for a borrower's loan (its file), and
--      what was done with it: an NPA at the partner makes the loan NPA here (Directions para 33: borrower level, at the
--      latest by the end of the next working day).
--   3. The DLG (Directions para 32; Digital Lending Directions, 2025): covers lodged with the partner (cash, a fixed
--      deposit under lien or a bank guarantee) up to the arrangement's percentage of the partner's share funded (5% at
--      most); invocations by the partner within 120 days overdue, not set off against the loan; recoveries shared back.
--      Heads: DLG deposits (1304), DLG paid (5109), recoveries under DLG (4111).
--   4. The bureau file reports the originator's own share of a co-lent loan (para 31).
--   5. lending.co_lending_problems(): the DLG deposit head agrees with the cash covers.

-- ---------------------------------------------------------------------------------------------------------
-- 1. Provision on the own share
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE lending.co_lent_position ADD COLUMN provision_partner numeric(18,2) NOT NULL DEFAULT 0 CHECK (provision_partner >= 0);

-- ---------------------------------------------------------------------------------------------------------
-- 2. The partner's classification of the borrower
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE lending.co_lending_partner_class (
    id            uuid PRIMARY KEY,
    loan_id       uuid NOT NULL REFERENCES lending.co_lent_loan(loan_id),
    reported_on   date NOT NULL,                 -- the business date the report was recorded
    asset_class   text NOT NULL CHECK (asset_class IN ('STANDARD','SMA0','SMA1','SMA2','SUBSTANDARD','DOUBTFUL1','DOUBTFUL2','DOUBTFUL3','LOSS')),
    npa_since     date,
    partner_dpd   int CHECK (partner_dpd IS NULL OR partner_dpd >= 0),
    outcome       text NOT NULL,                 -- what was done: applied, recorded, or why nothing changed
    approval_id   uuid REFERENCES platform.approval_request(id),
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CHECK ((asset_class IN ('SUBSTANDARD','DOUBTFUL1','DOUBTFUL2','DOUBTFUL3','LOSS')) = (npa_since IS NOT NULL))
);
CREATE INDEX co_lending_partner_class_loan ON lending.co_lending_partner_class (loan_id, reported_on DESC);

-- ---------------------------------------------------------------------------------------------------------
-- 3. Default loss guarantee
-- ---------------------------------------------------------------------------------------------------------
CREATE FUNCTION ledger.load_co_lending_dlg_heads() RETURNS int LANGUAGE plpgsql AS $$
DECLARE before int; after int;
BEGIN
  SELECT count(*) INTO before FROM ledger.gl_head;
  IF EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '1300') THEN
    INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting)
    VALUES ('1304', 'Default loss guarantee deposits (co-lending)', 'ASSET', '1300', true) ON CONFLICT (code) DO NOTHING;
  END IF;
  IF EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '5000') THEN
    INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting)
    VALUES ('5109', 'Default loss guarantee paid (co-lending)', 'EXPENSE', '5000', true) ON CONFLICT (code) DO NOTHING;
  END IF;
  IF EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '4000') THEN
    INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting)
    VALUES ('4111', 'Recoveries under default loss guarantee', 'INCOME', '4000', true) ON CONFLICT (code) DO NOTHING;
  END IF;
  SELECT count(*) INTO after FROM ledger.gl_head;
  RETURN after - before;
END $$;
-- tenants that already lend under an arrangement get the heads now; others when their first arrangement is agreed
SELECT ledger.load_co_lending_dlg_heads() WHERE EXISTS (SELECT 1 FROM lending.co_lending_arrangement);

INSERT INTO platform.system_property (key, value, updated_by) VALUES
  ('lending.co-lending.dlg-deposit-gl', '1304', 'migration'),
  ('lending.co-lending.dlg-loss-gl', '5109', 'migration'),
  ('lending.co-lending.dlg-recovery-gl', '4111', 'migration'),
  ('lending.co-lending.dlg-bank-gl', '1202', 'migration')
ON CONFLICT (key) DO NOTHING;

CREATE SEQUENCE lending.co_lending_dlg_seq;
CREATE TABLE lending.co_lending_dlg_cover (
    id                uuid PRIMARY KEY,
    cover_no          text NOT NULL UNIQUE,
    arrangement_code  text NOT NULL REFERENCES lending.co_lending_arrangement(code),
    form              text NOT NULL CHECK (form IN ('CASH','FD_LIEN','BANK_GUARANTEE')),
    amount            numeric(18,2) NOT NULL CHECK (amount > 0),
    instrument_ref    text CHECK (instrument_ref IS NULL OR length(instrument_ref) <= 60),   -- the FD or guarantee number
    issued_on         date NOT NULL,
    valid_until       date NOT NULL,
    status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RELEASED')),
    released_on       date,
    lot_id            uuid REFERENCES ledger.transaction_lot(id),                            -- cash only
    release_lot_id    uuid REFERENCES ledger.transaction_lot(id),
    approval_id       uuid REFERENCES platform.approval_request(id),
    created_by        text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CHECK (valid_until > issued_on),
    CHECK ((form = 'CASH') = (lot_id IS NOT NULL)),
    CHECK (form = 'CASH' OR instrument_ref IS NOT NULL),
    CHECK ((status = 'RELEASED') = (released_on IS NOT NULL))
);

CREATE TABLE lending.co_lending_dlg_invocation (
    id             uuid PRIMARY KEY,
    cover_id       uuid NOT NULL REFERENCES lending.co_lending_dlg_cover(id),
    loan_id        uuid NOT NULL REFERENCES lending.co_lent_loan(loan_id),
    business_date  date NOT NULL,
    amount         numeric(18,2) NOT NULL CHECK (amount > 0),
    dpd            int NOT NULL CHECK (dpd > 0),
    reason         text NOT NULL CHECK (btrim(reason) <> '' AND length(reason) <= 500),
    lot_id         uuid NOT NULL UNIQUE REFERENCES ledger.transaction_lot(id),
    approval_id    uuid REFERENCES platform.approval_request(id),
    created_by     text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE lending.co_lending_dlg_recovery (
    id             uuid PRIMARY KEY,
    invocation_id  uuid NOT NULL REFERENCES lending.co_lending_dlg_invocation(id),
    business_date  date NOT NULL,
    amount         numeric(18,2) NOT NULL CHECK (amount > 0),
    reference      text CHECK (reference IS NULL OR length(reference) <= 100),
    lot_id         uuid NOT NULL UNIQUE REFERENCES ledger.transaction_lot(id),
    approval_id    uuid REFERENCES platform.approval_request(id),
    created_by     text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION lending.guard_co_lending_dlg() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cov lending.co_lending_dlg_cover; used numeric; inv lending.co_lending_dlg_invocation; back numeric;
BEGIN
  IF TG_TABLE_NAME = 'co_lending_partner_class' THEN
    RAISE EXCEPTION 'a classification the partner reported is a record: it never changes' USING ERRCODE = '42501';
  END IF;
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'a default loss guarantee record is never deleted' USING ERRCODE = '42501'; END IF;
  IF TG_TABLE_NAME = 'co_lending_dlg_cover' THEN
    IF TG_OP = 'INSERT' THEN
      IF NEW.lot_id IS NOT NULL AND (SELECT lot_type FROM ledger.transaction_lot WHERE id = NEW.lot_id) IS DISTINCT FROM 'CO_LENDING_DLG' THEN
        RAISE EXCEPTION 'a cash cover is recorded with its CO_LENDING_DLG lot' USING ERRCODE = '23514';
      END IF;
      IF NEW.status <> 'ACTIVE' THEN RAISE EXCEPTION 'a cover starts active' USING ERRCODE = '23514'; END IF;
      RETURN NEW;
    END IF;
    IF OLD.status = 'RELEASED' OR NEW.status <> 'RELEASED'
       OR (to_jsonb(NEW) - 'status' - 'released_on' - 'release_lot_id') <> (to_jsonb(OLD) - 'status' - 'released_on' - 'release_lot_id') THEN
      RAISE EXCEPTION 'cover %: a cover only changes by being released, once', OLD.cover_no USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
  END IF;
  IF TG_OP = 'UPDATE' THEN RAISE EXCEPTION 'an invocation or a recovery never changes' USING ERRCODE = '42501'; END IF;
  IF (SELECT lot_type FROM ledger.transaction_lot WHERE id = NEW.lot_id) IS DISTINCT FROM 'CO_LENDING_DLG' THEN
    RAISE EXCEPTION 'recorded with its CO_LENDING_DLG lot' USING ERRCODE = '23514';
  END IF;
  IF TG_TABLE_NAME = 'co_lending_dlg_invocation' THEN
    SELECT * INTO cov FROM lending.co_lending_dlg_cover WHERE id = NEW.cover_id FOR UPDATE;
    IF cov.status <> 'ACTIVE' THEN RAISE EXCEPTION 'cover % is released', cov.cover_no USING ERRCODE = '23514'; END IF;
    IF (SELECT arrangement_code FROM lending.co_lent_loan WHERE loan_id = NEW.loan_id) <> cov.arrangement_code
       OR (SELECT status FROM lending.co_lent_loan WHERE loan_id = NEW.loan_id) <> 'ACCEPTED' THEN
      RAISE EXCEPTION 'cover % covers loans of % whose share the partner holds', cov.cover_no, cov.arrangement_code USING ERRCODE = '23514';
    END IF;
    SELECT coalesce(sum(amount), 0) INTO used FROM lending.co_lending_dlg_invocation WHERE cover_id = cov.id;
    IF used + NEW.amount > cov.amount THEN
      RAISE EXCEPTION 'cover %: % is left of %', cov.cover_no, cov.amount - used, cov.amount USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
  END IF;
  SELECT * INTO inv FROM lending.co_lending_dlg_invocation WHERE id = NEW.invocation_id;
  SELECT coalesce(sum(amount), 0) INTO back FROM lending.co_lending_dlg_recovery WHERE invocation_id = inv.id;
  IF back + NEW.amount > inv.amount THEN
    RAISE EXCEPTION 'recoveries shared back are at most what was invoked (% of %)', back, inv.amount USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER co_lending_dlg_cover_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.co_lending_dlg_cover
  FOR EACH ROW EXECUTE FUNCTION lending.guard_co_lending_dlg();
CREATE TRIGGER co_lending_dlg_invocation_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.co_lending_dlg_invocation
  FOR EACH ROW EXECUTE FUNCTION lending.guard_co_lending_dlg();
CREATE TRIGGER co_lending_dlg_recovery_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.co_lending_dlg_recovery
  FOR EACH ROW EXECUTE FUNCTION lending.guard_co_lending_dlg();
CREATE TRIGGER co_lending_partner_class_guard BEFORE UPDATE OR DELETE ON lending.co_lending_partner_class
  FOR EACH ROW EXECUTE FUNCTION lending.guard_co_lending_dlg();

-- ---------------------------------------------------------------------------------------------------------
-- 4. The bureau file reports the originator's own share of a co-lent loan
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION reporting.bureau_consumer_rows(p_user text, p_params jsonb)
RETURNS TABLE (loan_no text, account_type text, date_opened date, date_last_payment date, date_closed date, sanctioned_amount numeric,
               current_balance numeric, amount_overdue numeric, dpd int, asset_class text, status text, restructured boolean,
               emi numeric, tenure_months int, rate numeric, customer_name text, date_of_birth date, gender text,
               pan_cipher bytea, mobile_cipher bytea, address_cipher bytea, city text, state_code text, pincode text)
LANGUAGE sql STABLE AS $$
  WITH prm AS (SELECT reporting.param_date(p_params, 'asOf', reporting.business_date()) AS d, reporting.business_date() AS today)
  SELECT l.loan_no,
         coalesce((SELECT value FROM platform.system_property WHERE key = 'bureau.account-type.' || lower(l.product_code)),
                  (SELECT value FROM platform.system_property WHERE key = 'bureau.account-type.default')),
         coalesce(l.disbursed_on, l.open_date),
         (SELECT max(t.business_date) FROM lending.loan_txn t
           WHERE t.loan_id = l.id AND t.reversed_by IS NULL AND t.business_date <= prm.d
             AND t.txn_type IN ('REPAYMENT','PREPAYMENT','PRECLOSURE','CANCELLATION')),
         CASE WHEN l.status IN ('CLOSED','CANCELLED') THEN l.closed_on END,
         round(l.sanctioned_amount::numeric * own.f, 2),
         round((SELECT b.principal + b.interest + b.charges FROM lending.loan_balance(l.id, prm.d) b) * own.f, 2),
         round(coalesce(h.overdue_amount, l.overdue_amount)::numeric * own.f, 2),
         coalesce(h.dpd, l.dpd), coalesce(h.asset_class, l.asset_class), l.status, l.restructured_on IS NOT NULL AND l.restructured_on <= prm.d,
         round(l.emi::numeric * own.f, 2), l.tenor_months, coalesce(l.current_rate, l.rate)::numeric,
         c.display_name, c.date_of_birth, c.gender, c.pan_cipher, c.mobile_cipher, a.line_cipher, a.city,
         platform.gst_state_code(a.state_code), a.pincode
    FROM lending.loan_account l
    JOIN customer.customer c ON c.id = l.customer_id
    LEFT JOIN customer.address a ON a.customer_id = c.id AND a.address_type = 'COMMUNICATION'
   CROSS JOIN prm
    -- co-lent and the partner took its share by this date: the originator reports its own share (Directions para 31)
    CROSS JOIN LATERAL (SELECT coalesce((SELECT cl.originator_share / 100 FROM lending.co_lent_loan cl
                                           WHERE cl.loan_id = l.id AND cl.status = 'ACCEPTED' AND cl.decided_on <= prm.d), 1) AS f) own
    LEFT JOIN LATERAL (SELECT x.* FROM lending.dpd_history x
                        WHERE prm.d < prm.today AND x.loan_id = l.id AND x.business_date <= prm.d
                        ORDER BY x.business_date DESC LIMIT 1) h ON true
   WHERE coalesce(l.disbursed_on, l.open_date) <= prm.d
     AND (l.status IN ('ACTIVE','FROZEN','WRITTEN_OFF')
          OR (l.status IN ('CLOSED','CANCELLED') AND l.disbursed_on IS NOT NULL
              AND l.closed_on BETWEEN date_trunc('month', prm.d)::date AND prm.d))
     AND l.branch_code IN (SELECT v.branch_code FROM platform.visible_branches(p_user) v)
   ORDER BY l.loan_no
$$;

-- ---------------------------------------------------------------------------------------------------------
-- 5. The books agree: the DLG deposit head holds the cash covers not invoked or released
-- ---------------------------------------------------------------------------------------------------------
DROP FUNCTION lending.co_lending_problems();
CREATE FUNCTION lending.co_lending_problems() RETURNS TABLE (branch_code text, head text, kept numeric, ledger numeric) LANGUAGE sql STABLE AS $$
  WITH h AS (SELECT coalesce((SELECT value FROM platform.system_property WHERE key = 'lending.co-lending.partner-share-gl'), '1105') AS contra,
                    coalesce((SELECT value FROM platform.system_property WHERE key = 'lending.co-lending.partner-payable-gl'), '2303') AS payable,
                    coalesce((SELECT value FROM platform.system_property WHERE key = 'lending.co-lending.dlg-deposit-gl'), '1304') AS dlg,
                    (SELECT code FROM platform.branch WHERE is_head_office LIMIT 1) AS ho),
       loans AS (SELECT c.loan_id, c.partner_principal, l.branch_code, l.loan_no FROM lending.co_lent_loan c JOIN lending.loan_account l ON l.id = c.loan_id
                  WHERE c.status = 'ACCEPTED'),
       contra_kept AS (SELECT b.branch_code, sum(b.partner_principal - coalesce((SELECT sum(x.principal) FROM lending.co_lending_split x WHERE x.loan_id = b.loan_id), 0)) AS amount
                         FROM loans b GROUP BY b.branch_code),
       contra_booked AS (SELECT e.branch_code, sum(CASE e.side WHEN 'CR' THEN e.amount ELSE -e.amount END) AS amount
                           FROM ledger.account_entry e, h WHERE e.gl_code = h.contra GROUP BY e.branch_code),
       payable_kept AS (SELECT b.branch_code, sum(x.net) AS amount FROM loans b JOIN lending.co_lending_split x ON x.loan_id = b.loan_id
                         WHERE x.settlement_id IS NULL GROUP BY b.branch_code),
       payable_booked AS (SELECT e.branch_code, sum(CASE e.side WHEN 'CR' THEN e.amount ELSE -e.amount END) AS amount
                            FROM ledger.account_entry e, h WHERE e.gl_code = h.payable AND e.account_no IN (SELECT loan_no FROM loans) GROUP BY e.branch_code),
       dlg_kept AS (SELECT h.ho AS branch_code,
                           sum(CASE WHEN v.status = 'RELEASED' THEN 0
                                    ELSE v.amount - coalesce((SELECT sum(i.amount) FROM lending.co_lending_dlg_invocation i WHERE i.cover_id = v.id), 0) END) AS amount
                      FROM lending.co_lending_dlg_cover v, h WHERE v.form = 'CASH' GROUP BY h.ho),
       dlg_booked AS (SELECT e.branch_code, sum(CASE e.side WHEN 'DR' THEN e.amount ELSE -e.amount END) AS amount
                        FROM ledger.account_entry e, h WHERE e.gl_code = h.dlg GROUP BY e.branch_code)
  SELECT coalesce(k.branch_code, b.branch_code), h.contra, coalesce(k.amount, 0), coalesce(b.amount, 0)
    FROM contra_kept k FULL JOIN contra_booked b ON b.branch_code = k.branch_code, h
   WHERE coalesce(k.amount, 0) <> coalesce(b.amount, 0)
  UNION ALL
  SELECT coalesce(k.branch_code, b.branch_code), h.payable, coalesce(k.amount, 0), coalesce(b.amount, 0)
    FROM payable_kept k FULL JOIN payable_booked b ON b.branch_code = k.branch_code, h
   WHERE coalesce(k.amount, 0) <> coalesce(b.amount, 0)
  UNION ALL
  SELECT coalesce(k.branch_code, b.branch_code), h.dlg, coalesce(k.amount, 0), coalesce(b.amount, 0)
    FROM dlg_kept k FULL JOIN dlg_booked b ON b.branch_code = k.branch_code, h
   WHERE coalesce(k.amount, 0) <> coalesce(b.amount, 0)
$$;
