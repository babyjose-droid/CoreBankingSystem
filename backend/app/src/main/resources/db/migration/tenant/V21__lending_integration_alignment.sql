-- V21: where P2-6 (V18, tranches) and P2-2 (V20, payouts) meet. V18, V19 and V20 were written independently and
-- apply together unchanged; this migration holds the two rules that only exist once both are in the same database.

-- ---------------------------------------------------------------------------------------------------------
-- 1. A disbursement whose payout failed can be taken back (LoanService.reverseDisbursement, P2-2) and the loan
--    disbursed again. The tranche rows of the reversed disbursement stay as history, marked with the reversal;
--    numbering and the sanctioned-amount check then count live tranches only.
-- ---------------------------------------------------------------------------------------------------------
ALTER TABLE lending.loan_tranche ADD COLUMN reversed_by uuid REFERENCES lending.loan_txn(id);
ALTER TABLE lending.loan_tranche DROP CONSTRAINT loan_tranche_pkey;
ALTER TABLE lending.loan_tranche ADD PRIMARY KEY (txn_id);
ALTER TABLE lending.loan_tranche DROP CONSTRAINT loan_tranche_txn_id_key;
CREATE UNIQUE INDEX loan_tranche_live_no ON lending.loan_tranche (loan_id, tranche_no) WHERE reversed_by IS NULL;
CREATE INDEX loan_tranche_loan ON lending.loan_tranche (loan_id, tranche_no);

-- Still append-only, with one exception: a live tranche can be marked reversed, once, by a REVERSAL of its own loan.
DROP TRIGGER loan_tranche_immutable ON lending.loan_tranche;
CREATE FUNCTION lending.guard_loan_tranche() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'DELETE on loan_tranche is not allowed: tranches are append-only' USING ERRCODE = '42501';
  END IF;
  IF OLD.reversed_by IS NOT NULL OR NEW.reversed_by IS NULL
     -- net_disbursed is generated: in a BEFORE trigger NEW does not hold it yet, and it follows the other columns
     OR (to_jsonb(OLD) - 'reversed_by' - 'net_disbursed') IS DISTINCT FROM (to_jsonb(NEW) - 'reversed_by' - 'net_disbursed') THEN
    RAISE EXCEPTION 'UPDATE on loan_tranche is not allowed: a tranche can only be marked reversed, once' USING ERRCODE = '42501';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM lending.loan_txn t WHERE t.id = NEW.reversed_by AND t.loan_id = NEW.loan_id AND t.txn_type = 'REVERSAL') THEN
    RAISE EXCEPTION 'a tranche is reversed only by a REVERSAL transaction of its own loan' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER loan_tranche_guard BEFORE UPDATE OR DELETE ON lending.loan_tranche
  FOR EACH ROW EXECUTE FUNCTION lending.guard_loan_tranche();

CREATE OR REPLACE FUNCTION lending.check_loan_tranche() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sanctioned numeric; total numeric; last_no int;
BEGIN
  SELECT sanctioned_amount INTO sanctioned FROM lending.loan_account WHERE id = NEW.loan_id FOR UPDATE;
  SELECT coalesce(sum(amount), 0), coalesce(max(tranche_no), 0) INTO total, last_no
    FROM lending.loan_tranche WHERE loan_id = NEW.loan_id AND reversed_by IS NULL;
  IF NEW.reversed_by IS NOT NULL THEN
    RAISE EXCEPTION 'a tranche is not created reversed' USING ERRCODE = '23514';
  END IF;
  IF NEW.tranche_no <> last_no + 1 THEN
    RAISE EXCEPTION 'tranche % of loan % is out of sequence (last is %)', NEW.tranche_no, NEW.loan_id, last_no USING ERRCODE = '23514';
  END IF;
  IF total + NEW.amount > sanctioned THEN
    RAISE EXCEPTION 'tranches of % would exceed the sanctioned amount %', total + NEW.amount, sanctioned USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- 2. One payout in flight or paid per disbursement, not per loan: every tranche of a loan disbursed in tranches
--    is paid out. A payout created without a disbursement transaction keeps the per-loan rule.
-- ---------------------------------------------------------------------------------------------------------
DROP INDEX integration.payout_one_live;
CREATE UNIQUE INDEX payout_one_live
    ON integration.payout_instruction (loan_id, coalesce(disbursement_txn, '00000000-0000-0000-0000-000000000000'::uuid))
 WHERE status IN ('INITIATED','ON_HOLD','SENT','SUCCESS');
