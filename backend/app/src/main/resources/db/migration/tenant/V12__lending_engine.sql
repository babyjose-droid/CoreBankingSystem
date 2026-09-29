-- Phase 2 lending: product factory (versions, fee rules, interest tables, provisioning), loan state and
-- transactions with reversible state snapshots, daily DPD history. Stories US-038..US-061, US-076..US-081.

-- ---------------------------------------------------------------------------------------------------------
-- Extra GL heads used by lending (added to existing tenants and to the starter kit).
-- ---------------------------------------------------------------------------------------------------------
CREATE FUNCTION ledger.load_lending_heads() RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '2300') THEN
    INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting) VALUES
      ('2305', 'Interest suspense (NPA income not recognised)', 'LIABILITY', '2300', true)
    ON CONFLICT (code) DO NOTHING;
  END IF;
END $$;
SELECT ledger.load_lending_heads();

-- ---------------------------------------------------------------------------------------------------------
-- Product factory (US-038 – US-043)
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE lending.loan_product DROP CONSTRAINT loan_product_repayment_method_check;
ALTER TABLE lending.loan_product
  ADD CONSTRAINT loan_product_repayment_method_check
      CHECK (repayment_method IN ('EQUATED','FIXED_PRINCIPAL','BULLET_TOTAL_INTEREST','BULLET_PERIODIC_INTEREST')),
  ADD COLUMN rate_type              text NOT NULL DEFAULT 'FIXED' CHECK (rate_type IN ('FIXED','FLOATING')),
  ADD COLUMN interest_table_code    text,
  ADD COLUMN max_moratorium_months  int NOT NULL DEFAULT 0 CHECK (max_moratorium_months >= 0),
  ADD COLUMN cooling_off_days       int NOT NULL DEFAULT 3 CHECK (cooling_off_days BETWEEN 1 AND 30),
  ADD COLUMN secured                boolean NOT NULL DEFAULT false,
  ADD COLUMN appropriation_sequence text[] NOT NULL DEFAULT ARRAY['INTEREST','PRINCIPAL','PENAL','FEE'],
  ADD COLUMN appropriation_mode     text NOT NULL DEFAULT 'BY_DEMAND' CHECK (appropriation_mode IN ('BY_DEMAND','BY_COMPONENT')),
  ADD COLUMN prepayment_mode        text NOT NULL DEFAULT 'REDUCE_EMI' CHECK (prepayment_mode IN ('REDUCE_EMI','REDUCE_TENURE')),
  ADD COLUMN gl_map                 jsonb,
  ADD COLUMN effective_from         date,
  ADD COLUMN updated_by             text,
  ADD CONSTRAINT appropriation_sequence_complete CHECK (
      appropriation_sequence @> ARRAY['INTEREST','PRINCIPAL','PENAL','FEE'] AND cardinality(appropriation_sequence) = 4);

-- Every approved change bumps the version and keeps the previous one; accounts store the snapshot they were
-- booked with, so product changes never alter live loans (US-043).
CREATE TABLE lending.loan_product_history (
    product_code text NOT NULL REFERENCES lending.loan_product(code),
    version      int NOT NULL,
    snapshot     jsonb NOT NULL,
    changed_by   text NOT NULL,
    changed_at   timestamptz NOT NULL DEFAULT now(),
    approval_id  uuid REFERENCES platform.approval_request(id),
    PRIMARY KEY (product_code, version)
);

-- Fee rules (US-040): fixed / percent / slab, caps, GST inclusive/exclusive, raised on an event.
CREATE TABLE lending.fee_rule (
    product_code  text NOT NULL REFERENCES lending.loan_product(code),
    code          text NOT NULL CHECK (code ~ '^[A-Z0-9_]{2,20}$'),
    name          text NOT NULL,
    event         text NOT NULL CHECK (event IN ('DISBURSEMENT','PRECLOSURE','PART_PREPAYMENT','BOUNCE','LATE_PAYMENT','CANCELLATION','ADHOC')),
    calc_type     text NOT NULL CHECK (calc_type IN ('FIXED','PERCENT','SLAB')),
    amount        platform.money,
    percent       numeric(9,4),
    slabs         jsonb,                      -- [{"from":"0","to":"10000","fee":"300"}, …]
    min_amount    platform.money,
    max_amount    platform.money,
    gst_rate      numeric(7,4) NOT NULL DEFAULT 18,
    tax_treatment text NOT NULL DEFAULT 'EXCLUSIVE' CHECK (tax_treatment IN ('EXCLUSIVE','INCLUSIVE')),
    deduct_from_disbursal boolean NOT NULL DEFAULT false,
    PRIMARY KEY (product_code, code),
    CHECK (calc_type <> 'FIXED' OR amount IS NOT NULL),
    CHECK (calc_type <> 'PERCENT' OR percent IS NOT NULL),
    CHECK (calc_type <> 'SLAB' OR jsonb_typeof(slabs) = 'array'),
    CHECK (min_amount IS NULL OR max_amount IS NULL OR min_amount <= max_amount),
    CHECK (deduct_from_disbursal = false OR event = 'DISBURSEMENT')
);

-- Interest tables (US-041): the rate is either base + slab spread (ADDITIVE, as in the reference system)
-- or the slab rate itself (ABSOLUTE). The resolution is shown to users.
CREATE TABLE lending.interest_table (
    code        text PRIMARY KEY,
    name        text NOT NULL,
    base_rate   platform.rate NOT NULL DEFAULT 0,
    mode        text NOT NULL CHECK (mode IN ('ADDITIVE','ABSOLUTE')),
    effective_from date NOT NULL
);
CREATE TABLE lending.interest_slab (
    table_code  text NOT NULL REFERENCES lending.interest_table(code),
    min_amount  platform.money NOT NULL,
    max_amount  platform.money NOT NULL,
    min_tenor_months int NOT NULL,
    max_tenor_months int NOT NULL,
    rate        platform.rate NOT NULL,
    CHECK (min_amount <= max_amount AND min_tenor_months <= max_tenor_months),
    EXCLUDE USING gist (table_code WITH =,
                        numrange(min_amount, max_amount, '[]') WITH &&,
                        int4range(min_tenor_months, max_tenor_months, '[]') WITH &&)
);
ALTER TABLE lending.loan_product ADD CONSTRAINT product_interest_table_fk
  FOREIGN KEY (interest_table_code) REFERENCES lending.interest_table(code);

CREATE FUNCTION lending.resolve_rate(p_table text, p_amount numeric, p_tenor int)
RETURNS TABLE (rate numeric, explanation text) LANGUAGE plpgsql STABLE AS $$
DECLARE t lending.interest_table%ROWTYPE; s lending.interest_slab%ROWTYPE;
BEGIN
  SELECT * INTO t FROM lending.interest_table WHERE code = p_table;
  IF NOT FOUND THEN RAISE EXCEPTION 'interest table % not found', p_table USING ERRCODE = 'P0002'; END IF;
  SELECT * INTO s FROM lending.interest_slab
   WHERE table_code = p_table AND p_amount BETWEEN min_amount AND max_amount AND p_tenor BETWEEN min_tenor_months AND max_tenor_months;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'no slab in % for amount % and tenor % months', p_table, p_amount, p_tenor USING ERRCODE = 'P0002';
  END IF;
  IF t.mode = 'ADDITIVE' THEN
    RETURN QUERY SELECT (t.base_rate + s.rate)::numeric, format('base %s%% + slab %s%% = %s%%', trim_scale(t.base_rate::numeric), trim_scale(s.rate::numeric), trim_scale((t.base_rate + s.rate)::numeric));
  ELSE
    RETURN QUERY SELECT s.rate::numeric, format('slab rate %s%%', trim_scale(s.rate::numeric));
  END IF;
END $$;

-- Provisioning rates (US-042). Starter values follow RBI IRACP minimums for NBFCs; confirm per client (D-08).
CREATE TABLE lending.provisioning_rate (
    asset_class text NOT NULL CHECK (asset_class IN ('STANDARD','SMA0','SMA1','SMA2','SUBSTANDARD','DOUBTFUL1','DOUBTFUL2','DOUBTFUL3','LOSS')),
    secured     boolean NOT NULL,
    rate        numeric(7,4) NOT NULL CHECK (rate BETWEEN 0 AND 100),
    PRIMARY KEY (asset_class, secured)
);
INSERT INTO lending.provisioning_rate VALUES
  ('STANDARD',true,0.40),('SMA0',true,0.40),('SMA1',true,0.40),('SMA2',true,0.40),
  ('SUBSTANDARD',true,10),('DOUBTFUL1',true,20),('DOUBTFUL2',true,30),('DOUBTFUL3',true,50),('LOSS',true,100),
  ('STANDARD',false,0.40),('SMA0',false,0.40),('SMA1',false,0.40),('SMA2',false,0.40),
  ('SUBSTANDARD',false,10),('DOUBTFUL1',false,100),('DOUBTFUL2',false,100),('DOUBTFUL3',false,100),('LOSS',false,100);

-- ---------------------------------------------------------------------------------------------------------
-- Loan accounts
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE lending.loan_account DROP CONSTRAINT loan_account_status_check;
ALTER TABLE lending.loan_account
  ADD CONSTRAINT loan_account_status_check
      CHECK (status IN ('APPLIED','SANCTIONED','ACTIVE','FROZEN','CLOSED','WRITTEN_OFF','CANCELLED')),
  ADD COLUMN product_version     int,
  ADD COLUMN product_snapshot    jsonb,          -- engine parameters frozen from the product version at booking
  ADD COLUMN booked_terms        jsonb,          -- amount, rate, tenor, method, moratorium, balloon as sanctioned
  ADD COLUMN state               jsonb,          -- LoanAccount.Snapshot (engine state)
  ADD COLUMN repayment_method    text,
  ADD COLUMN moratorium_months   int NOT NULL DEFAULT 0,
  ADD COLUMN penal_rate          platform.rate,
  ADD COLUMN apr                 numeric(9,4),
  ADD COLUMN disbursed_amount    platform.money,
  ADD COLUMN net_disbursed       platform.money,
  ADD COLUMN disbursed_on        date,
  ADD COLUMN principal_outstanding platform.money NOT NULL DEFAULT 0,
  ADD COLUMN overdue_amount      platform.money NOT NULL DEFAULT 0,
  ADD COLUMN next_due_date       date,
  ADD COLUMN npa_since           date,
  ADD COLUMN suspense            platform.money NOT NULL DEFAULT 0,
  ADD COLUMN provision_held      platform.money NOT NULL DEFAULT 0,
  ADD COLUMN customer_state      text,           -- GST place of supply
  ADD COLUMN secured_portion     platform.money NOT NULL DEFAULT 0,
  ADD COLUMN source              text NOT NULL DEFAULT 'CONSOLE' CHECK (source IN ('CONSOLE','API')),
  ADD COLUMN external_ref        text,           -- LOS application id
  ADD COLUMN approval_id         uuid REFERENCES platform.approval_request(id),
  ADD COLUMN created_by          text,
  ADD COLUMN closed_on           date,
  ADD CONSTRAINT loan_state_when_active CHECK (status IN ('APPLIED','SANCTIONED') OR state IS NOT NULL);
CREATE UNIQUE INDEX loan_external_ref ON lending.loan_account (external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX loan_status ON lending.loan_account (status) WHERE status IN ('ACTIVE','FROZEN');

-- Key Fact Statement as shown to the borrower, and its acceptance (RBI KFS directions; US-048).
CREATE TABLE lending.loan_kfs (
    loan_id          uuid PRIMARY KEY REFERENCES lending.loan_account(id),
    kfs              jsonb NOT NULL,
    generated_at     timestamptz NOT NULL DEFAULT now(),
    accepted_at      timestamptz,
    accepted_channel text,
    evidence_ref     text                         -- e.g. e-sign or OTP transaction id
);
CREATE TRIGGER loan_kfs_immutable_after_acceptance BEFORE DELETE ON lending.loan_kfs
  FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();

-- Every financial event on a loan, with the state before it: reversal restores that state and posts the mirror
-- lots; reversing an earlier transaction first reverses every later one (US-058).
CREATE TABLE lending.loan_txn (
    id            uuid PRIMARY KEY,
    loan_id       uuid NOT NULL REFERENCES lending.loan_account(id),
    seq           int NOT NULL,
    txn_type      text NOT NULL,
    value_date    date NOT NULL,
    business_date date NOT NULL,
    amount        platform.money,
    lot_ids       uuid[] NOT NULL DEFAULT '{}',
    state_before  jsonb NOT NULL,
    summary       text,
    reverses      uuid REFERENCES lending.loan_txn(id),
    reversed_by   uuid REFERENCES lending.loan_txn(id),
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (loan_id, seq)
);
CREATE INDEX loan_txn_loan ON lending.loan_txn (loan_id, seq DESC);

-- A loan transaction can change only once, to record its reversal.
CREATE FUNCTION lending.guard_loan_txn() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'loan transactions cannot be deleted' USING ERRCODE = '42501'; END IF;
  IF OLD.reversed_by IS NULL AND NEW.reversed_by IS NOT NULL
     AND (to_jsonb(NEW) - 'reversed_by') = (to_jsonb(OLD) - 'reversed_by') THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'loan transaction % is immutable', OLD.id USING ERRCODE = '42501';
END $$;
CREATE TRIGGER loan_txn_guard BEFORE UPDATE OR DELETE ON lending.loan_txn FOR EACH ROW EXECUTE FUNCTION lending.guard_loan_txn();

-- Day-end classification history (bureau reporting, SMA/NPA reports, audits).
CREATE TABLE lending.dpd_history (
    loan_id        uuid NOT NULL REFERENCES lending.loan_account(id),
    business_date  date NOT NULL,
    dpd            int NOT NULL,
    asset_class    text NOT NULL,
    principal_outstanding platform.money NOT NULL,
    overdue_amount platform.money NOT NULL,
    PRIMARY KEY (loan_id, business_date)
);

-- Borrower-level classification (US-037): worst class across a borrower's live loans.
CREATE VIEW lending.borrower_class AS
  SELECT customer_id,
         (array_agg(asset_class ORDER BY array_position(ARRAY['STANDARD','SMA0','SMA1','SMA2','SUBSTANDARD','DOUBTFUL1','DOUBTFUL2','DOUBTFUL3','LOSS'], asset_class) DESC))[1] AS worst_class,
         min(npa_since) AS npa_since
    FROM lending.loan_account WHERE status IN ('ACTIVE','FROZEN')
   GROUP BY customer_id;
