# Personal data in logs, audit, errors and exports (US-133)

Review date: 29-Sep-2026. Personal data here means PAN, Aadhaar reference, mobile, e-mail, name, date of birth and
address. Rule (ADR-013): never in logs, error responses or exports; in the database only encrypted, blind-indexed or
masked, except where listed as open below.

| Channel | Finding | Status |
|---------|---------|--------|
| Application logs | 12 log statements in the backend. They log tenant codes, run ids, approval ids, usernames of staff, and exceptions. None logs request bodies or customer fields. | OK |
| Exceptions logged with stack traces | Database exceptions can contain key values from constraint messages. Customer keys are hashes (`pan_hash`) or customer numbers, not personal data. | OK |
| Error responses | Database rule messages are the trigger's own user text; unexpected errors are generic; internal exception text is suppressed (`ProblemHandler.safeMessage`); bulk approve no longer echoes raw messages. | Fixed |
| Audit trail (`audit.event`) | Details hold ids, amounts, product, channel and summaries. Reject notes are staff free text (instruction to staff: no personal data in notes). | OK |
| Approval payloads | Customer personal data is sealed (AES-GCM) in the payload; the checker sees masked values. City, pincode, date of birth and display name are in clear. | Open — SEC-03 |
| API responses | PAN and mobile masked (last 4); the sealed blob is removed from approval views. | OK |
| Console exports | CSV downloads exist only for trial balance, P&L and balance sheet (no personal data); cells are formula-neutralised. | OK |
| Console storage / logs | Tokens in sessionStorage only; no `console.log` of data in application code. | OK |
| Uploads | Holiday, territory and voucher files contain no personal data; they are parsed in memory and stored only as approval payloads. | OK |
| Dedupe | Matches outside the caller's branch scope no longer reveal name or customer number. | Fixed |

Follow-ups: SEC-03 (clear display name and date of birth), SEC-02 (audit table protection).
