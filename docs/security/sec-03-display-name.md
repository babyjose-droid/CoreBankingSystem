# SEC-03: customer name and date of birth in clear

Status: design note for the product owner. Written 02-Oct-2026 with increment P2-5.
Source: ASVS L2 review, defect D10 (`asvs-l2-review.md`), open item SEC-03.

## The finding

`customer.customer.display_name` and `date_of_birth` are stored in clear, while PAN, mobile, e-mail, the name parts
and the address lines are encrypted (ADR-013). The same values were also copied in clear into approval payloads
(`platform.approval_request.payload`), which are kept as history.

## What changed in P2-5

Nothing changed in how the customer row is stored. Two things changed around it:

- **Approval payloads.** Date of birth, city and pincode are now inside the sealed (encrypted) part of a
  customer-create request, next to the name parts, PAN, mobile, e-mail and address lines.
  - The checker sees derived values instead: an age band (for example `36-45`), the state code and a masked pincode
    (`682XXX`).
  - The display name stays readable in the payload, because the checker must know whom they are approving.
  - Requests raised before this change hold the three values in clear. The applier reads both shapes, so approvals
    pending at the upgrade still apply. Their payloads stay as they were written; see "Old payloads" below.
- **This note**, so the remaining decision is explicit.

## What is still in clear

| Value | Where | Why it is still there |
|-------|-------|-----------------------|
| Display name | `customer.customer.display_name`, approval payloads, loan and customer lists | Staff identify customers by name; name search uses `ILIKE` on this column |
| Date of birth | `customer.customer.date_of_birth`, customer summary API | Shown on the customer screen; used for the age check and the name + DOB dedupe key (already a keyed hash) |
| City, state, pincode | `customer.address` | City and pincode are quasi-identifiers; state is needed in clear for GST place of supply |

## Options for the display name

**Option A — keep the name in clear, tighten access (recommended now).**
- Storage stays as it is. The risk accepted is that a database administrator, or a leaked backup, shows customer
  names alongside masked identifiers and loan balances.
- Compensating controls, most of which exist or are already planned:
  - encryption at rest and `sslmode=verify-full` (in place);
  - the runtime database role limited to what the application needs, and migrations run by a separate owner
    (SEC-02);
  - branch scope on every customer read (in place);
  - backups encrypted with a separate key, and production data never copied to lower environments without masking.
- Cost: none. Search and every screen keep working.

**Option B — masked display value in clear, full name encrypted.**
- `display_name` would hold a short form such as `R**** S*****` and the full name would be decrypted on read.
- Not acceptable for a banking console: staff must be able to pick the right customer from a list, and two
  customers with the same initials would look alike. Decrypting every row of a list brings back the full name
  anyway, at a cost in latency and key use per page.
- Not recommended.

**Option C — full name encrypted, searchable through blind indexes.**
- `display_name` is replaced by `name_cipher`. Lists decrypt the names of the rows shown (20 to 100 per page).
- Search by name no longer uses `ILIKE`. It uses keyed hashes of normalised name tokens and of their prefixes
  (for example the first 3, 4 and 5 letters of each token), stored in a side table. Exact-token and prefix search
  keep working; "contains" search does not.
- Costs and risks:
  - a migration that re-writes every customer and builds the token index, with a re-index job for key rotation
    (ties in with SEC-04);
  - prefix hashes leak some information: equal prefixes give equal hashes, so common names are recognisable by
    frequency;
  - every list of loans or approvals that shows a customer name needs a decrypt step, including reports and exports;
  - approval payloads would seal the name too, so the checker's queue shows the customer number and masked values
    until a request is opened.
- This is the option to take if a bank's security review requires names to be unreadable to database
  administrators.

## Date of birth

Independent of the name decision, date of birth can be encrypted with little impact:

- store `dob_cipher`, keep `year_of_birth` (or nothing) in clear, and keep the existing name + DOB dedupe hash;
- the age check runs in the application at creation; reports that need age use the year;
- the customer summary API returns the date only to users with a permission for unmasked personal data, and audits it.

This is small enough to do with the remaining US-028 work.

## Recommendation

1. **Now:** option A. No change to name storage in this increment.
2. **With the rest of US-028:** encrypt the date of birth as described above; treat city and pincode as personal
   data in exports (mask the pincode unless the user holds the unmasked-data permission).
3. **Decide before the first production tenant:** whether the target customers' security reviews accept option A.
   If not, plan option C together with key rotation (SEC-04), since both re-write every customer row.

## Old payloads

Approval payloads written before P2-5 still contain date of birth, city and pincode in clear. Approval history is
append-only by design, so they were not rewritten. If the product owner wants them removed, a one-off, audited
migration can move the three values into the sealed part for CUSTOMER requests; it needs the tenant's data key and
therefore has to run in the application, not as a SQL migration.

## Decision needed

| Question | Options |
|----------|---------|
| Name storage | A (clear, access controls) now; C (encrypted with blind-index search) if a bank requires it |
| Date of birth | Encrypt with the US-028 remainder (recommended) or leave in clear |
| Old approval payloads | Leave as history (default) or run the one-off sealing job |
