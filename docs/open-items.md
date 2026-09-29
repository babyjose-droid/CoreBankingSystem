# Open items

| ID | Item | Owner | Needed by |
|----|------|-------|-----------|
| OI-01 | Reference schedule shows ₹606 (Feb-27) and ₹395 (Apr-27) where standard half-up gives ₹607 / ₹396. Tested hypotheses (daily-rate truncation, daily-rate rounding, unrounded balance carry, 366-day basis) do not reproduce all 12 rows. Decide: keep half-up (recommended, transparent) or obtain vendor rule. | Product owner | Phase 2 start |
| OI-02 | ~~APR basis~~ **Resolved**: reference APR 18.58% = nominal IRR on flat-EMI flows with the processing fee taken excluding GST. Product default `apr_basis = IRR_BASIC_FEE_EX_GST`; compliance to confirm whether KFS should include GST (gives 18.68%). | Compliance | Phase 2 |
| OI-03 | ~~Spring Boot 4.1.0 / Modulith 2.1.1 unverified~~ **Resolved** on the first CI runs (27-Sep). The Phase 1 increment is again compiled only in CI because Maven Central is blocked in the build sandbox. | Claude | Each push |
| OI-04 | ~~GitHub repository~~ done (babyjose-droid/CoreBankingSystem). Still needed: AWS accounts (sandbox, prod, DR) and a named PR reviewer. | Product owner | Before sandbox deploy |
| OI-05 | Decisions D-05 … D-12 in the product backlog workbook. | Product owner | Phase 1 week 2 |
| OI-06 | EOD failure alerts are logged with the recipient list. Email or SMS delivery needs a notification provider (for example SES), to be decided. | Product owner | Phase 2 |
| OI-07 | Personal-data keys come from environment variables in dev and standalone installs. Cloud tiers need a start-up hook that unwraps the per-tenant KMS data key into them. | Claude | Sandbox deploy |
| SEC-02 | Audit trail protection: run migrations as a separate owner role, give the runtime role INSERT/SELECT only on `audit.event`, key the hash chain (HMAC with a KMS key, include the id) and anchor the head hash outside the database. | Claude + platform ops | Sandbox deploy |
| SEC-03 | Customer display name and date of birth are stored in clear (also in approval payloads). Store a masked display value and keep the full values encrypted; seal them in approval payloads. | Claude | Phase 2 (with US-028 rest) |
| SEC-04 | Personal-data key rotation: `PiiCipher` accepts only key id 1; add multi-key decrypt, re-encryption job and row-bound AAD. | Claude | Before first production tenant |
| SEC-05 | Rate limits (dedupe check, login-adjacent APIs) and free-text length limits. | Claude | Phase 2 |
| SEC-08 | Console hosting headers (CSP, HSTS, frame-ancestors), forward-headers for HSTS from the backend, internal TLS decision. | Platform ops | Sandbox deploy |
| D-13 | Password policy: backlog US-026 asks for complexity rules and 90-day expiry; ASVS 5.0 L2 advises length + breached-password check without composition or forced expiry. Choose one (regulator expectations may favour the backlog). | Product owner | Before first production tenant |
