# NACH files: the GENERIC layout

NPCI defines the ACH debit transaction; the file a lender exchanges with its **sponsor bank** follows that bank's
own layout. No sponsor bank has been chosen (decision D-09), so the product ships one layout of its own, `GENERIC`,
so that presentation and response processing work and can be tested end to end.

**GENERIC is not any bank's or NPCI's layout and no bank will accept it.** The sponsor bank's layout has to be added
as another implementation of `NachFileFormat` (`backend/integration-core`, package `…integration.core.nach`) and
selected with the tenant property `nach.format`. What the bank must provide: the presentation and response file
specifications (record layouts, character set, file naming, encryption or signing, delivery channel), sample
files, the return reason code list, and the cut-off times.

Tenant properties: `nach.format` (GENERIC), `nach.encoding` (FIXED or CSV).

## Records

Every file has one header (H), one detail (D) per debit and one trailer (T) with the control totals. A file whose
trailer does not agree with its details is refused as a whole.

### FIXED encoding

ASCII, every line 200 characters, CRLF. Text is left-aligned and space-padded; numbers are right-aligned and
zero-padded; amounts are in paise; dates are YYYYMMDD.

| Record | Positions | Field |
|--------|-----------|-------|
| H | 1 | `H` |
| H | 2-10 | layout id `CBSNACH01` |
| H | 11 | `P` presentation, `R` response |
| H | 12-29 | utility code |
| H | 30-40 | sponsor bank code |
| H | 41-70 | file reference (a response carries the reference of the presentation file it answers) |
| H | 71-78 | settlement date |
| D | 1 | `D` |
| D | 2-7 | sequence number |
| D | 8-37 | item reference |
| D | 38-57 | UMRN |
| D | 58-92 | account number (presentation only) |
| D | 93-103 | IFSC (presentation only) |
| D | 104-105 | account type: SB, CA, CC, OT (presentation only) |
| D | 106-145 | account holder name (presentation only) |
| D | 146-158 | amount in paise |
| D | 159-176 | user reference: the loan number (presentation only) |
| D | 177 | response only: `1` debited, `0` returned |
| D | 178-180 | response only: return reason code, blank when debited |
| D | 181-200 | response only: bank reference |
| T | 1 | `T` |
| T | 2-7 | number of detail records |
| T | 8-22 | sum of amounts in paise |
| T | 23-28 | response only: number of debited records |
| T | 29-43 | response only: sum of debited amounts in paise |

### CSV encoding

RFC 4180 with a header row and the same three record types in the first column:

`record_type,file_ref,utility_code,sponsor_bank_code,settlement_date,direction,seq,item_ref,umrn,account_number,ifsc,account_type,holder_name,amount,user_ref,status,return_code,bank_ref,count,total_amount,success_count,success_amount`

Amounts are rupees with two decimals; dates are YYYY-MM-DD; columns that do not apply to a record are empty.

## Idempotency

- **File:** the SHA-256 of the bytes is unique per direction; the same file uploaded twice is refused (409).
- **Control totals:** a response for the same presentation file with the same counts and totals as one already
  processed is marked DUPLICATE and not processed (a re-sent file whose bytes differ).
- **Row:** each presentation takes one outcome. A row whose presentation already has one is counted as
  `alreadyRecorded` and changes nothing.

## Return reasons

`integration.nach_return_reason` holds 27 commonly published NPCI debit return reason codes. The list is
**indicative** (`verified = false` on every row) until it has been checked against NPCI's current circular or the
sponsor bank's list. `representable` decides automatic re-presentation: true for 04 (balance insufficient),
05 (not arranged for) and 59 (network failure) as shipped.
