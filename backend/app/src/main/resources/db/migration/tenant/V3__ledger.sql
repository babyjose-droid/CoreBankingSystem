-- Double-entry ledger. The single posting engine is the only writer (ADR-005).
CREATE TABLE ledger.gl_head (
    code        text PRIMARY KEY,
    name        text NOT NULL,
    category    text NOT NULL CHECK (category IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    parent_code text REFERENCES ledger.gl_head(code),
    is_posting  boolean NOT NULL,                 -- only leaf heads accept entries
    status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED'))
);

CREATE TABLE ledger.transaction_lot (
    id            uuid PRIMARY KEY,
    lot_type      text NOT NULL,
    business_date date NOT NULL,
    value_date    date NOT NULL,
    reference     text,
    reverses      uuid UNIQUE REFERENCES ledger.transaction_lot(id),   -- a lot can be reversed once
    approval_id   uuid REFERENCES platform.approval_request(id),
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_txid  xid8 NOT NULL DEFAULT pg_current_xact_id(),       -- top-level transaction that wrote it
    CHECK (reverses IS NULL OR reverses <> id)
);
CREATE INDEX lot_business_date ON ledger.transaction_lot (business_date);

CREATE TABLE ledger.account_entry (
    id            bigint GENERATED ALWAYS AS IDENTITY,
    lot_id        uuid NOT NULL REFERENCES ledger.transaction_lot(id),
    business_date date NOT NULL,
    branch_code   text NOT NULL REFERENCES platform.branch(code),
    gl_code       text NOT NULL REFERENCES ledger.gl_head(code),
    account_no    text NOT NULL,
    side          char(2) NOT NULL CHECK (side IN ('DR','CR')),
    amount        platform.money NOT NULL CHECK (amount > 0),
    currency      char(3) NOT NULL DEFAULT 'INR',
    narration     text,
    PRIMARY KEY (id, business_date)
) PARTITION BY RANGE (business_date);

-- Yearly partitions (financial year April–March). The platform job creates the next year in advance.
CREATE TABLE ledger.account_entry_fy2026 PARTITION OF ledger.account_entry FOR VALUES FROM ('2026-04-01') TO ('2027-04-01');
CREATE TABLE ledger.account_entry_fy2027 PARTITION OF ledger.account_entry FOR VALUES FROM ('2027-04-01') TO ('2028-04-01');
CREATE TABLE ledger.account_entry_default PARTITION OF ledger.account_entry DEFAULT;
CREATE INDEX entry_account ON ledger.account_entry (account_no, business_date);
CREATE INDEX entry_lot ON ledger.account_entry (lot_id);

-- Row checks at insert: posting GL is a leaf and active; entry date equals lot date.
CREATE FUNCTION ledger.check_entry() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE g ledger.gl_head%ROWTYPE; lot_date date;
BEGIN
  SELECT * INTO g FROM ledger.gl_head WHERE code = NEW.gl_code;
  IF NOT g.is_posting THEN RAISE EXCEPTION 'GL % is not a posting head', NEW.gl_code USING ERRCODE = '23514'; END IF;
  IF g.status <> 'ACTIVE' THEN RAISE EXCEPTION 'GL % is %', NEW.gl_code, g.status USING ERRCODE = '23514'; END IF;
  -- Entries may only be added in the transaction that created the lot, so a committed (and
  -- therefore balance-checked) lot can never gain extra legs later.
  SELECT business_date INTO lot_date FROM ledger.transaction_lot
   WHERE id = NEW.lot_id AND created_txid = pg_current_xact_id();
  IF NOT FOUND THEN
    RAISE EXCEPTION 'lot % is already committed; post a new lot instead', NEW.lot_id USING ERRCODE = '23514';
  END IF;
  IF lot_date <> NEW.business_date THEN
    RAISE EXCEPTION 'entry date % differs from lot date %', NEW.business_date, lot_date USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER entry_check BEFORE INSERT ON ledger.account_entry FOR EACH ROW EXECUTE FUNCTION ledger.check_entry();

-- Append-only: entries and lots can never be changed or deleted. Corrections are reversal lots.
CREATE FUNCTION ledger.forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% on % is not allowed: the ledger is append-only', TG_OP, TG_TABLE_NAME USING ERRCODE = '42501';
END $$;
CREATE TRIGGER entry_immutable BEFORE UPDATE OR DELETE ON ledger.account_entry FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();
CREATE TRIGGER lot_immutable   BEFORE UPDATE OR DELETE ON ledger.transaction_lot FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();
CREATE TRIGGER entry_no_truncate BEFORE TRUNCATE ON ledger.account_entry FOR EACH STATEMENT EXECUTE FUNCTION ledger.forbid_change();

-- Balance check at COMMIT: every lot balances per currency and per branch (IBR legs included),
-- and has at least two entries. Deferred, so the posting engine can insert legs in any order.
CREATE FUNCTION ledger.check_lot_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE bad record; n int;
BEGIN
  SELECT count(*) INTO n FROM ledger.account_entry WHERE lot_id = NEW.id;
  IF n < 2 THEN RAISE EXCEPTION 'lot % has % entries; at least 2 required', NEW.id, n USING ERRCODE = '23514'; END IF;
  SELECT branch_code, currency, sum(CASE side WHEN 'DR' THEN amount ELSE -amount END) AS net INTO bad
    FROM ledger.account_entry WHERE lot_id = NEW.id
    GROUP BY branch_code, currency
    HAVING sum(CASE side WHEN 'DR' THEN amount ELSE -amount END) <> 0
    LIMIT 1;
  IF FOUND THEN
    RAISE EXCEPTION 'lot % not balanced for branch % %: net %', NEW.id, bad.branch_code, bad.currency, bad.net
      USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER lot_balanced AFTER INSERT ON ledger.transaction_lot
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.check_lot_balanced();

-- Trial balance (all dates). Reports filter by business_date through the function below.
CREATE FUNCTION ledger.trial_balance(as_of date)
RETURNS TABLE (gl_code text, gl_name text, category text, debit numeric, credit numeric, net numeric)
LANGUAGE sql STABLE AS $$
  SELECT g.code, g.name, g.category,
         coalesce(sum(e.amount) FILTER (WHERE e.side = 'DR'), 0),
         coalesce(sum(e.amount) FILTER (WHERE e.side = 'CR'), 0),
         coalesce(sum(CASE e.side WHEN 'DR' THEN e.amount ELSE -e.amount END), 0)
    FROM ledger.gl_head g
    LEFT JOIN ledger.account_entry e ON e.gl_code = g.code AND e.business_date <= as_of
   WHERE g.is_posting
   GROUP BY g.code, g.name, g.category
   ORDER BY g.code
$$;

CREATE VIEW ledger.account_balance AS
  SELECT account_no, gl_code, currency,
         sum(CASE side WHEN 'DR' THEN amount ELSE -amount END) AS net_debit
    FROM ledger.account_entry GROUP BY account_no, gl_code, currency;
