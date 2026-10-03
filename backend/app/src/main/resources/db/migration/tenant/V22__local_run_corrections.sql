-- V22: corrections found by the first end-to-end run of the local stack.

-- ---------------------------------------------------------------------------------------------------------
-- 1. Foreclosure (pre-closure) charges are income of GL 4104 "Foreclosure charges", not of 4102 "Processing fee
--    income". The engine posts them to the product's foreclosureIncome head (LoanPostings.GlMap; 4104 when a
--    stored map does not name it). Entries already posted stay where they are: a reversal mirrors the lot it
--    reverses, so it follows the head the charge was booked to.
-- ---------------------------------------------------------------------------------------------------------
UPDATE lending.loan_product
   SET gl_map = gl_map || '{"foreclosureIncome":"4104"}'::jsonb
 WHERE gl_map IS NOT NULL AND jsonb_typeof(gl_map) = 'object' AND NOT gl_map ? 'foreclosureIncome';

-- the loan's GL heads (V16), with the new head as the last column
CREATE OR REPLACE VIEW lending.loan_gl AS
  SELECT l.id AS loan_id, l.loan_no,
         coalesce(l.product_snapshot->'gl'->>'principal', '1101')          AS principal_gl,
         coalesce(l.product_snapshot->'gl'->>'interestReceivable', '1102') AS interest_gl,
         coalesce(l.product_snapshot->'gl'->>'feeReceivable', '1103')      AS fee_gl,
         coalesce(l.product_snapshot->'gl'->>'penalReceivable', '1104')    AS penal_gl,
         coalesce(l.product_snapshot->'gl'->>'excessReceipts', '2302')     AS excess_gl,
         coalesce(l.product_snapshot->'gl'->>'feeIncome', '4102')          AS fee_income_gl,
         coalesce(l.product_snapshot->'gl'->>'interestIncome', '4101')     AS interest_income_gl,
         coalesce(l.product_snapshot->'gl'->>'interestSuspense', '2305')   AS suspense_gl,
         coalesce(l.product_snapshot->'gl'->>'cgst', '2201')               AS cgst_gl,
         coalesce(l.product_snapshot->'gl'->>'sgst', '2202')               AS sgst_gl,
         coalesce(l.product_snapshot->'gl'->>'igst', '2203')               AS igst_gl,
         coalesce(nullif(l.product_snapshot->'gl'->>'foreclosureIncome', ''), '4104') AS foreclosure_income_gl
    FROM lending.loan_account l;

-- Tax invoices and credit notes are read from the ledger by the fee income line. Both functions are as in V18,
-- except that a line on either fee income head counts (otherwise a foreclosure charge would get no invoice).
CREATE OR REPLACE FUNCTION lending.issue_fee_credit_notes(p_loan uuid DEFAULT NULL) RETURNS int LANGUAGE plpgsql AS $$
DECLARE r record; n int := 0;
BEGIN
  -- a waiver that was itself reversed: its note is cancelled (kept, with its number)
  UPDATE lending.fee_credit_note c
     SET status = 'CANCELLED', cancelled_on = rev.business_date, cancelled_by_txn = rev.id
    FROM lending.loan_txn t JOIN lending.loan_txn rev ON rev.id = t.reversed_by
   WHERE c.txn_id = t.id AND c.reason = 'WAIVER' AND c.status = 'ISSUED' AND (p_loan IS NULL OR c.loan_id = p_loan);

  -- a fee reversed in a later month than its invoice: credit what is left of the invoice
  FOR r IN
    SELECT i.id AS invoice_id, i.loan_id, rev.id AS rev_txn, rev.business_date,
           i.taxable_value - coalesce(u.taxable, 0) AS taxable, i.cgst - coalesce(u.cgst, 0) AS cgst,
           i.sgst - coalesce(u.sgst, 0) AS sgst, i.igst - coalesce(u.igst, 0) AS igst, i.invoice_date
      FROM lending.fee_invoice i
      JOIN lending.loan_txn t ON t.id = i.txn_id
      JOIN lending.loan_txn rev ON rev.id = t.reversed_by
      LEFT JOIN LATERAL (SELECT sum(taxable_value) AS taxable, sum(cgst) AS cgst, sum(sgst) AS sgst, sum(igst) AS igst
                           FROM lending.fee_credit_note c WHERE c.invoice_id = i.id AND c.status = 'ISSUED') u ON true
     WHERE i.status = 'ISSUED' AND (p_loan IS NULL OR i.loan_id = p_loan)
       AND date_trunc('month', rev.business_date) > date_trunc('month', i.invoice_date)
     ORDER BY rev.business_date, i.invoice_no
  LOOP
    IF r.taxable + r.cgst + r.sgst + r.igst > 0 THEN
      INSERT INTO lending.fee_credit_note (credit_note_no, credit_note_date, invoice_id, loan_id, txn_id, reason, taxable_value, cgst, sgst, igst,
                                           tax_adjustable)
      VALUES (platform.next_number('GST_CREDIT_NOTE'), r.business_date, r.invoice_id, r.loan_id, r.rev_txn, 'REVERSAL', r.taxable, r.cgst,
              r.sgst, r.igst, lending.credit_note_in_time(r.invoice_date, r.business_date));
      n := n + 1;
    END IF;
    UPDATE lending.fee_invoice SET status = 'CREDITED' WHERE id = r.invoice_id;
  END LOOP;

  -- waivers: one note per waiver lot that reduced output tax, against the live invoice of the charge it names
  FOR r IN
    SELECT t.id AS txn_id, t.loan_id, t.seq, u.lot_id, lot.business_date, i.id AS invoice_id, i.invoice_date,
           coalesce(sum(e.amount) FILTER (WHERE e.side = 'DR' AND e.gl_code IN (g.fee_income_gl, g.foreclosure_income_gl)), 0) AS taxable,
           coalesce(sum(e.amount) FILTER (WHERE e.side = 'DR' AND e.gl_code = g.cgst_gl), 0) AS cgst,
           coalesce(sum(e.amount) FILTER (WHERE e.side = 'DR' AND e.gl_code = g.sgst_gl), 0) AS sgst,
           coalesce(sum(e.amount) FILTER (WHERE e.side = 'DR' AND e.gl_code = g.igst_gl), 0) AS igst
      FROM lending.loan_txn t
     CROSS JOIN LATERAL unnest(t.lot_ids) u(lot_id)
      JOIN ledger.transaction_lot lot ON lot.id = u.lot_id AND lot.lot_type = 'WAIVER'
      JOIN lending.loan_gl g ON g.loan_id = t.loan_id
      JOIN ledger.account_entry e ON e.lot_id = u.lot_id
      JOIN LATERAL (SELECT substring(x.narration FROM '^FEE waiver (C[0-9]+)$') AS charge_ref
                      FROM ledger.account_entry x
                     WHERE x.lot_id = u.lot_id AND x.side = 'CR' AND x.narration ~ '^FEE waiver C[0-9]+$' LIMIT 1) w ON true
      JOIN lending.fee_invoice i ON i.loan_id = t.loan_id AND i.charge_ref = w.charge_ref AND i.status = 'ISSUED'
     WHERE t.txn_type = 'WAIVER' AND t.reversed_by IS NULL AND (p_loan IS NULL OR t.loan_id = p_loan)
       AND NOT EXISTS (SELECT 1 FROM lending.fee_credit_note c WHERE c.lot_id = u.lot_id)
     GROUP BY t.id, t.loan_id, t.seq, u.lot_id, lot.business_date, i.id, i.invoice_date
    HAVING coalesce(sum(e.amount) FILTER (WHERE e.side = 'DR' AND e.gl_code IN (g.cgst_gl, g.sgst_gl, g.igst_gl)), 0) > 0
     ORDER BY lot.business_date, t.seq
  LOOP
    INSERT INTO lending.fee_credit_note (credit_note_no, credit_note_date, invoice_id, loan_id, txn_id, lot_id, reason, taxable_value, cgst,
                                         sgst, igst, tax_adjustable)
    VALUES (platform.next_number('GST_CREDIT_NOTE'), r.business_date, r.invoice_id, r.loan_id, r.txn_id, r.lot_id, 'WAIVER', r.taxable, r.cgst,
            r.sgst, r.igst, true);
    n := n + 1;
  END LOOP;
  RETURN n;
END $$;

CREATE OR REPLACE FUNCTION lending.issue_fee_invoices(p_loan uuid DEFAULT NULL) RETURNS int LANGUAGE plpgsql AS $$
DECLARE r record; n int := 0;
BEGIN
  UPDATE lending.fee_invoice i
     SET status = 'CANCELLED', cancelled_on = rev.business_date, cancelled_by_txn = rev.id
    FROM lending.loan_txn t JOIN lending.loan_txn rev ON rev.id = t.reversed_by
   WHERE i.txn_id = t.id AND i.status = 'ISSUED' AND (p_loan IS NULL OR i.loan_id = p_loan)
     AND date_trunc('month', rev.business_date) = date_trunc('month', i.invoice_date)
     AND NOT EXISTS (SELECT 1 FROM lending.fee_credit_note c
                       JOIN lending.loan_txn w ON w.id = c.txn_id
                      WHERE c.invoice_id = i.id AND c.status = 'ISSUED' AND w.reversed_by IS NULL);

  FOR r IN
    WITH lots AS (
      SELECT t.id AS txn_id, t.loan_id, t.seq, u.lot_id, u.ord, lot.lot_type, lot.business_date,
             coalesce((t.state_before->>'chargeSeq')::int, 0) AS seq_before
        FROM lending.loan_txn t
       CROSS JOIN LATERAL unnest(t.lot_ids) WITH ORDINALITY u(lot_id, ord)
        JOIN ledger.transaction_lot lot ON lot.id = u.lot_id
       WHERE t.reversed_by IS NULL AND t.txn_type <> 'REVERSAL' AND lot.lot_type IN ('FEE_CHARGE','DISBURSEMENT')
         AND (p_loan IS NULL OR t.loan_id = p_loan)),
    fees AS (
      SELECT lo.txn_id, lo.loan_id, lo.seq, lo.lot_id, lo.ord, lo.lot_type, lo.business_date, lo.seq_before,
             l.branch_code, l.product_snapshot,
             -- the fee income line is "<fee name> <loan no>"; the tax lines are "CGST <fee name> <loan no>"
             regexp_replace(CASE WHEN e.gl_code IN (g.fee_income_gl, g.foreclosure_income_gl) THEN e.narration
                                 ELSE regexp_replace(e.narration, '^(CGST|SGST|IGST) ', '') END,
                            ' ' || g.loan_no || '$', '') AS fee_name,
             min(e.id) AS first_entry,
             coalesce(sum(e.amount) FILTER (WHERE e.gl_code IN (g.fee_income_gl, g.foreclosure_income_gl)), 0) AS taxable,
             coalesce(sum(e.amount) FILTER (WHERE e.gl_code = g.cgst_gl), 0) AS cgst,
             coalesce(sum(e.amount) FILTER (WHERE e.gl_code = g.sgst_gl), 0) AS sgst,
             coalesce(sum(e.amount) FILTER (WHERE e.gl_code = g.igst_gl), 0) AS igst
        FROM lots lo
        JOIN lending.loan_account l ON l.id = lo.loan_id
        JOIN lending.loan_gl g ON g.loan_id = lo.loan_id
        JOIN ledger.account_entry e ON e.lot_id = lo.lot_id AND e.side = 'CR'
             AND e.gl_code IN (g.fee_income_gl, g.foreclosure_income_gl, g.cgst_gl, g.sgst_gl, g.igst_gl)
       GROUP BY 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
    numbered AS (
      SELECT f.*,
             CASE WHEN f.lot_type = 'FEE_CHARGE'
                  THEN 'C' || (f.seq_before + dense_rank() OVER (PARTITION BY f.txn_id, f.lot_type ORDER BY f.ord))
                  ELSE 'D' || row_number() OVER (PARTITION BY f.loan_id, f.lot_type ORDER BY f.seq, f.first_entry) END AS charge_ref
        FROM fees f WHERE f.taxable > 0)
    SELECT x.* FROM numbered x
     WHERE NOT EXISTS (SELECT 1 FROM lending.fee_invoice i WHERE i.lot_id = x.lot_id AND i.description = x.fee_name)
     ORDER BY x.business_date, x.seq, x.ord, x.first_entry
  LOOP
    INSERT INTO lending.fee_invoice (invoice_no, invoice_date, loan_id, txn_id, lot_id, charge_ref, description, branch_code,
                                     supplier_state, place_of_supply, taxable_value, gst_rate, cgst, sgst, igst)
    SELECT platform.next_number('GST_INVOICE'), r.business_date, r.loan_id, r.txn_id, r.lot_id, r.charge_ref, r.fee_name, r.branch_code,
           s.supplier, s.recipient,
           r.taxable,
           coalesce((SELECT (f->>'gstRatePercent')::numeric
                       FROM jsonb_array_elements(CASE WHEN jsonb_typeof(r.product_snapshot->'fees') = 'array'
                                                      THEN r.product_snapshot->'fees' ELSE '[]'::jsonb END) f
                      WHERE f->>'name' = r.fee_name LIMIT 1),
                    round((r.cgst + r.sgst + r.igst) * 100 / r.taxable, 2)),
           r.cgst, r.sgst, r.igst
      FROM (SELECT platform.gst_state_code(coalesce(r.product_snapshot->>'supplierState', b.state_code)) AS supplier,
                   platform.gst_state_code(coalesce(r.product_snapshot->>'recipientState', r.product_snapshot->>'supplierState', b.state_code)) AS recipient
              FROM platform.branch b WHERE b.code = r.branch_code) s;
    n := n + 1;
  END LOOP;
  PERFORM lending.issue_fee_credit_notes(p_loan);
  RETURN n;
END $$;
