-- V23: corrections from the second end-to-end run of the local stack.

-- ---------------------------------------------------------------------------------------------------------
-- 1. A loan closed or cancelled between two day-ends kept the days past due and the asset class of the last
--    day-end before closure (day-end does not visit a closed loan). The engine now clears them at closure
--    (LoanAccount.close): nothing is outstanding, so nothing is past due, and an SMA class (which only describes
--    days past due) becomes standard; an NPA class is the classification at closure and is kept with its NPA
--    date. The closing day also gets a history row (LoanStore.save). Existing rows are corrected the same way.
--    Written-off loans are not touched.
-- ---------------------------------------------------------------------------------------------------------
UPDATE lending.loan_account
   SET dpd = 0,
       asset_class = CASE WHEN asset_class IN ('SMA0','SMA1','SMA2') THEN 'STANDARD' ELSE asset_class END,
       state = CASE WHEN jsonb_typeof(state) <> 'object' THEN state
                    WHEN asset_class IN ('SMA0','SMA1','SMA2') THEN state || '{"dpd": 0, "assetClass": "STANDARD"}'::jsonb
                    ELSE state || '{"dpd": 0}'::jsonb END
 WHERE status IN ('CLOSED','CANCELLED')
   AND (dpd <> 0 OR asset_class IN ('SMA0','SMA1','SMA2'));

-- The history row of the closing day, which reports for a later date read (bureau file, position as at a date).
INSERT INTO lending.dpd_history (loan_id, business_date, dpd, asset_class, principal_outstanding, overdue_amount)
SELECT l.id, l.closed_on, 0, l.asset_class, l.principal_outstanding, 0
  FROM lending.loan_account l
 WHERE l.status IN ('CLOSED','CANCELLED') AND l.closed_on IS NOT NULL
ON CONFLICT (loan_id, business_date) DO UPDATE
   SET dpd = 0, asset_class = EXCLUDED.asset_class, principal_outstanding = EXCLUDED.principal_outstanding, overdue_amount = 0;
