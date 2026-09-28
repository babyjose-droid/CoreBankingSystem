-- General ledger: starter chart of accounts, manual vouchers, daily balances and financial statements.
-- Stories US-100, US-102, US-103, US-104.

-- ---------------------------------------------------------------------------------------------------------
-- Starter kit (US-100). NBFC chart; BANK adds deposits, CRR/SLR. Tenants edit it afterwards via maker-checker.
-- ---------------------------------------------------------------------------------------------------------
CREATE FUNCTION ledger.load_starter_kit(kind text) RETURNS int LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  IF kind NOT IN ('NBFC','BANK') THEN RAISE EXCEPTION 'unknown starter kit %', kind; END IF;
  INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting) VALUES
    ('1000','Assets','ASSET',NULL,false),
    ('1100','Loans and advances','ASSET','1000',false),
    ('1101','Loans - principal outstanding','ASSET','1100',true),
    ('1102','Loans - interest receivable','ASSET','1100',true),
    ('1103','Loans - fees receivable','ASSET','1100',true),
    ('1104','Loans - penal charges receivable','ASSET','1100',true),
    ('1109','Provision for loan losses (contra)','ASSET','1100',true),
    ('1200','Cash and bank','ASSET','1000',false),
    ('1201','Cash in hand','ASSET','1200',true),
    ('1202','Bank - disbursement account','ASSET','1200',true),
    ('1203','Bank - collection account','ASSET','1200',true),
    ('1204','Bank - NACH settlement','ASSET','1200',true),
    ('1300','Other assets','ASSET','1000',false),
    ('1301','TDS receivable','ASSET','1300',true),
    ('1302','GST input credit','ASSET','1300',true),
    ('1303','Prepaid expenses','ASSET','1300',true),
    ('1900','Inter-branch account','ASSET','1000',true),
    ('1950','Suspense account','ASSET','1000',true),
    ('2000','Liabilities','LIABILITY',NULL,false),
    ('2100','Borrowings','LIABILITY','2000',false),
    ('2101','Term loans from banks','LIABILITY','2100',true),
    ('2102','Non-convertible debentures','LIABILITY','2100',true),
    ('2103','Commercial paper','LIABILITY','2100',true),
    ('2200','Statutory dues','LIABILITY','2000',false),
    ('2201','CGST output','LIABILITY','2200',true),
    ('2202','SGST output','LIABILITY','2200',true),
    ('2203','IGST output','LIABILITY','2200',true),
    ('2204','TDS payable','LIABILITY','2200',true),
    ('2300','Other liabilities','LIABILITY','2000',false),
    ('2301','Customer refunds payable','LIABILITY','2300',true),
    ('2302','Excess receipts and advance EMI','LIABILITY','2300',true),
    ('2303','Co-lending partner payable','LIABILITY','2300',true),
    ('2304','Accrued expenses','LIABILITY','2300',true),
    ('3000','Equity','EQUITY',NULL,false),
    ('3101','Share capital','EQUITY','3000',true),
    ('3102','Reserves and surplus','EQUITY','3000',true),
    ('3103','Statutory reserve','EQUITY','3000',true),
    ('4000','Income','INCOME',NULL,false),
    ('4101','Interest income on loans','INCOME','4000',true),
    ('4102','Processing fee income','INCOME','4000',true),
    ('4103','Penal charges income','INCOME','4000',true),
    ('4104','Foreclosure charges','INCOME','4000',true),
    ('4105','Other fee income','INCOME','4000',true),
    ('4106','Bad debts recovered','INCOME','4000',true),
    ('5000','Expenses','EXPENSE',NULL,false),
    ('5101','Interest on borrowings','EXPENSE','5000',true),
    ('5102','Provision for loan losses','EXPENSE','5000',true),
    ('5103','Bad debts written off','EXPENSE','5000',true),
    ('5104','Employee costs','EXPENSE','5000',true),
    ('5105','Payment gateway charges','EXPENSE','5000',true),
    ('5106','Other operating expenses','EXPENSE','5000',true);
  IF kind = 'BANK' THEN
    INSERT INTO ledger.gl_head (code, name, category, parent_code, is_posting) VALUES
      ('1205','Balance with RBI (CRR)','ASSET','1200',true),
      ('1206','SLR investments','ASSET','1300',true),
      ('2400','Deposits','LIABILITY','2000',false),
      ('2401','Savings deposits','LIABILITY','2400',true),
      ('2402','Current deposits','LIABILITY','2400',true),
      ('2403','Term deposits','LIABILITY','2400',true),
      ('2404','Interest payable on deposits','LIABILITY','2400',true),
      ('5107','Interest on deposits','EXPENSE','5000',true);
  END IF;
  INSERT INTO platform.system_property (key, value, updated_by) VALUES
    ('ledger.inter-branch-gl', '1900', 'starter-kit'),
    ('ledger.suspense-gl', '1950', 'starter-kit')
  ON CONFLICT (key) DO NOTHING;
  SELECT count(*) INTO n FROM ledger.gl_head;
  RETURN n;
END $$;

-- A parent must be a non-posting head of the same category; a head with entries cannot become non-posting.
CREATE FUNCTION ledger.check_gl_head() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p ledger.gl_head%ROWTYPE;
BEGIN
  IF NEW.parent_code IS NOT NULL THEN
    SELECT * INTO p FROM ledger.gl_head WHERE code = NEW.parent_code;
    IF p.is_posting THEN RAISE EXCEPTION 'parent % is a posting head', p.code USING ERRCODE = '23514'; END IF;
    IF p.category <> NEW.category THEN
      RAISE EXCEPTION 'head % (%) cannot sit under % (%)', NEW.code, NEW.category, p.code, p.category USING ERRCODE = '23514';
    END IF;
  END IF;
  IF TG_OP = 'UPDATE' AND OLD.is_posting AND NOT NEW.is_posting
     AND EXISTS (SELECT 1 FROM ledger.account_entry WHERE gl_code = OLD.code) THEN
    RAISE EXCEPTION 'head % has entries and must stay a posting head', OLD.code USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'UPDATE' AND OLD.category <> NEW.category
     AND EXISTS (SELECT 1 FROM ledger.account_entry WHERE gl_code = OLD.code) THEN
    RAISE EXCEPTION 'head % has entries; its category cannot change', OLD.code USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER gl_head_check BEFORE INSERT OR UPDATE ON ledger.gl_head FOR EACH ROW EXECUTE FUNCTION ledger.check_gl_head();

-- ---------------------------------------------------------------------------------------------------------
-- Manual vouchers (US-103). Lines live in the posting lot; the voucher row is the business wrapper.
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE ledger.voucher (
    id              uuid PRIMARY KEY,
    voucher_no      text NOT NULL UNIQUE,
    voucher_type    text NOT NULL CHECK (voucher_type IN ('CONTRA','RECEIPT','PAYMENT','JOURNAL')),
    value_date      date NOT NULL,
    business_date   date NOT NULL,
    reference       text,
    description     text NOT NULL,
    amount          platform.money NOT NULL CHECK (amount > 0),
    status          text NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED','REVERSED')),
    lot_id          uuid NOT NULL UNIQUE REFERENCES ledger.transaction_lot(id),
    reversal_lot_id uuid UNIQUE REFERENCES ledger.transaction_lot(id),
    approval_id     uuid REFERENCES platform.approval_request(id),
    maker           text NOT NULL,
    checker         text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (value_date <= business_date),
    CHECK ((status = 'REVERSED') = (reversal_lot_id IS NOT NULL))
);
CREATE INDEX voucher_business_date ON ledger.voucher (business_date);

-- The only permitted change: POSTED → REVERSED with the reversal lot.
CREATE FUNCTION ledger.guard_voucher() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'vouchers cannot be deleted' USING ERRCODE = '42501'; END IF;
  IF OLD.status = 'POSTED' AND NEW.status = 'REVERSED'
     AND (to_jsonb(NEW) - 'status' - 'reversal_lot_id') = (to_jsonb(OLD) - 'status' - 'reversal_lot_id') THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'voucher % can only change from POSTED to REVERSED', OLD.voucher_no USING ERRCODE = '42501';
END $$;
CREATE TRIGGER voucher_guard BEFORE UPDATE OR DELETE ON ledger.voucher FOR EACH ROW EXECUTE FUNCTION ledger.guard_voucher();

-- ---------------------------------------------------------------------------------------------------------
-- Daily GL balances (snapshot written by the EOD "GL balance snapshot" step) and statements (US-104).
-- ---------------------------------------------------------------------------------------------------------
CREATE TABLE ledger.gl_daily_balance (
    business_date date NOT NULL,
    branch_code   text NOT NULL REFERENCES platform.branch(code),
    gl_code       text NOT NULL REFERENCES ledger.gl_head(code),
    day_debit     platform.money NOT NULL,
    day_credit    platform.money NOT NULL,
    closing_net   platform.money NOT NULL,          -- cumulative debit − credit at close of day
    PRIMARY KEY (business_date, branch_code, gl_code)
);

CREATE FUNCTION ledger.snapshot_balances(d date) RETURNS int LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  DELETE FROM ledger.gl_daily_balance WHERE business_date = d;       -- idempotent rerun
  INSERT INTO ledger.gl_daily_balance
  SELECT d, e.branch_code, e.gl_code,
         coalesce(sum(e.amount) FILTER (WHERE e.side = 'DR' AND e.business_date = d), 0),
         coalesce(sum(e.amount) FILTER (WHERE e.side = 'CR' AND e.business_date = d), 0),
         sum(CASE e.side WHEN 'DR' THEN e.amount ELSE -e.amount END)
    FROM ledger.account_entry e
   WHERE e.business_date <= d
   GROUP BY e.branch_code, e.gl_code;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;

DROP FUNCTION ledger.trial_balance(date);
CREATE FUNCTION ledger.trial_balance(as_of date, branch text DEFAULT NULL)
RETURNS TABLE (gl_code text, gl_name text, category text, debit numeric, credit numeric, net numeric)
LANGUAGE sql STABLE AS $$
  SELECT g.code, g.name, g.category,
         coalesce(sum(e.amount) FILTER (WHERE e.side = 'DR'), 0),
         coalesce(sum(e.amount) FILTER (WHERE e.side = 'CR'), 0),
         coalesce(sum(CASE e.side WHEN 'DR' THEN e.amount ELSE -e.amount END), 0)
    FROM ledger.gl_head g
    LEFT JOIN ledger.account_entry e
           ON e.gl_code = g.code AND e.business_date <= as_of AND (branch IS NULL OR e.branch_code = branch)
   WHERE g.is_posting
   GROUP BY g.code, g.name, g.category
   ORDER BY g.code
$$;

CREATE FUNCTION ledger.gl_entries(gl text, from_date date, to_date date, branch text DEFAULT NULL)
RETURNS TABLE (lot_id uuid, business_date date, branch_code text, gl_code text, account_no text, side text,
               amount numeric, narration text, lot_type text)
LANGUAGE sql STABLE AS $$
  SELECT e.lot_id, e.business_date, e.branch_code, e.gl_code, e.account_no, e.side::text, e.amount, e.narration, l.lot_type
    FROM ledger.account_entry e JOIN ledger.transaction_lot l ON l.id = e.lot_id
   WHERE e.gl_code = gl AND e.business_date BETWEEN from_date AND to_date
     AND (branch IS NULL OR e.branch_code = branch)
   ORDER BY e.business_date, e.id
$$;

-- Income shown as credit − debit, expenses as debit − credit; last row is net profit.
CREATE FUNCTION ledger.profit_and_loss(from_date date, to_date date)
RETURNS TABLE (section text, gl_code text, gl_name text, amount numeric)
LANGUAGE sql STABLE AS $$
  WITH x AS (
    SELECT g.category AS section, g.code, g.name,
           coalesce(sum(CASE e.side WHEN 'CR' THEN e.amount ELSE -e.amount END), 0) *
             CASE g.category WHEN 'INCOME' THEN 1 ELSE -1 END AS amt
      FROM ledger.gl_head g
      LEFT JOIN ledger.account_entry e ON e.gl_code = g.code AND e.business_date BETWEEN from_date AND to_date
     WHERE g.is_posting AND g.category IN ('INCOME','EXPENSE')
     GROUP BY g.category, g.code, g.name)
  SELECT section, code, name, amt FROM (
    SELECT section, code, name, amt, CASE section WHEN 'INCOME' THEN 1 ELSE 2 END AS ord FROM x
    UNION ALL
    SELECT 'NET_PROFIT', '', 'Net profit / (loss)',
           coalesce(sum(CASE section WHEN 'INCOME' THEN amt ELSE -amt END), 0), 3 FROM x) t
  ORDER BY ord, code
$$;

-- Assets as debit balance; liabilities and equity as credit balance; income less expenses not yet closed to
-- reserves is shown as its own equity line so the statement always balances.
CREATE FUNCTION ledger.balance_sheet(as_of date)
RETURNS TABLE (section text, gl_code text, gl_name text, amount numeric)
LANGUAGE sql STABLE AS $$
  WITH b AS (
    SELECT g.category, g.code, g.name,
           coalesce(sum(CASE e.side WHEN 'DR' THEN e.amount ELSE -e.amount END), 0) AS net_dr
      FROM ledger.gl_head g
      LEFT JOIN ledger.account_entry e ON e.gl_code = g.code AND e.business_date <= as_of
     WHERE g.is_posting
     GROUP BY g.category, g.code, g.name)
  SELECT category, code, name, CASE category WHEN 'ASSET' THEN net_dr ELSE -net_dr END
    FROM b WHERE category IN ('ASSET','LIABILITY','EQUITY')
  UNION ALL
  SELECT 'EQUITY', 'P&L', 'Profit and loss account (not yet transferred to reserves)',
         coalesce(-sum(net_dr), 0) FROM b WHERE category IN ('INCOME','EXPENSE')
  ORDER BY 1, 2
$$;
