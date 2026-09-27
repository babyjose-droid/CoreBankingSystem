# Open items

| ID | Item | Owner | Needed by |
|----|------|-------|-----------|
| OI-01 | Reference schedule shows ₹606 (Feb-27) and ₹395 (Apr-27) where standard half-up gives ₹607 / ₹396. Tested hypotheses (daily-rate truncation, daily-rate rounding, unrounded balance carry, 366-day basis) do not reproduce all 12 rows. Decide: keep half-up (recommended, transparent) or obtain vendor rule. | Product owner | Phase 2 start |
| OI-02 | ~~APR basis~~ **Resolved**: reference APR 18.58% = nominal IRR on flat-EMI flows with the processing fee taken excluding GST. Product default `apr_basis = IRR_BASIC_FEE_EX_GST`; compliance to confirm whether KFS should include GST (gives 18.68%). | Compliance | Phase 2 |
| OI-03 | Spring Boot 4.1.0 / Modulith 2.1.1 pinned without a first CI run; confirm on first build. | Claude | First CI run |
| OI-04 | GitHub organisation and repository, AWS accounts (sandbox, prod, DR), named PR reviewer. | Product owner | Before first merge |
| OI-05 | Decisions D-05 … D-12 in the product backlog workbook. | Product owner | Phase 1 week 2 |
