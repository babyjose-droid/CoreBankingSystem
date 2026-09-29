# OWASP ASVS 5.0 Level 2 review (US-132)

Review date: 29-Sep-2026. Scope: backend (`backend/**`), Keycloak realm baseline, Helm chart, Dockerfile, console
(`frontend/console/src/**`), CI workflows. Method: code review chapter by chapter against ASVS 5.0 L2;
a penetration test is still required (see "Organisational controls").

## Result by chapter

| Chapter | Status | Notes |
|---------|--------|-------|
| V1 Encoding & sanitisation | Met | Bound SQL parameters everywhere; Jackson output; CSV cells neutralise formulas (server `Csv.cell`, console `csvEscape`). |
| V2 Validation & business logic | Partial | Codes, PAN, mobile, pincode validated; maker ≠ checker in the database. Free-text length limits and anti-automation (rate limits) are open (SEC-05). |
| V3 Web frontend | Partial | No raw HTML sinks; tokens in sessionStorage; PKCE S256. CSP / HSTS / frame-ancestors belong to the console hosting (SEC-08). |
| V4 API | Met | JSON / problem+json, uploads accept `text/csv` only, 6 MB body cap (`RequestSizeFilter`, ingress `proxy-body-size`). |
| V5 File handling | Met | CSV parsed in memory with row caps and strict field counts; never stored or served. |
| V6 Authentication | Partial | Keycloak: 12+ characters, lockout after 5 failures, TOTP, no default credentials in tenant realms. ASVS 5.0 advises against composition rules and forced expiry, which the backlog (US-026) requires — decision D-13. No breached-password check yet. |
| V7 Sessions | Met | 5-minute access tokens, 30-minute idle, refresh-token rotation, stateless API. |
| V8 Authorisation | Met | `@PreAuthorize` on every mutating endpoint; branch scope as a data filter (US-020); tenant = realm = token claim; control plane only for platform-realm tokens. |
| V9 Tokens | Met | RS256 via JWKS, exact issuer, audience `api`, exp/nbf; only ACTIVE tenant realms and the platform realm are resolved. |
| V11 Cryptography | Partial | AES-256-GCM with random 96-bit IVs, HMAC-SHA256 blind indexes. Key rotation (key id fixed at 1) and row-bound AAD are open (SEC-04). |
| V12 Communication | Partial | TLS at ingress, `sslmode=verify-full` for databases. Pod-to-pod TLS is an infrastructure decision (SEC-08). |
| V13 Configuration | Met | Actuator exposes only health/info on the public port; no credentials in values; generic 500s; internal exception text never echoed. |
| V14 Data protection | Partial | PAN, mobile, e-mail, name parts and address lines encrypted and masked; no personal data in logs (see `pii-logs-exports-review.md`). Full name and date of birth are still stored in clear (SEC-03). |
| V15 Secure coding & supply chain | Met | Dependabot (gradle, npm, actions); every action pinned to a commit SHA and enforced by the `actions-pinned` job; CodeQL/dependency review when Code Security is enabled. |
| V16 Logging & errors | Partial | Tenant + trace id in every log line; security events audited in a hash chain; authorisation failures logged. Audit-table protection against the application's own DB role is open (SEC-02). |

## Defects found and what happened to them

| # | Defect | Severity | Status |
|---|--------|----------|--------|
| D1 | Control plane accepted `platform:operator` from any realm (a tenant realm admin could mint it). | High | **Fixed**: `/platform/**` needs a token from the `platform` realm (`TenantFilter`); `platform` can never be a tenant code. |
| D2 | Dedupe check revealed name and customer number of customers in other branches. | Medium | **Fixed**: out-of-scope matches return only rule and strength. Rate limiting: SEC-05. |
| D3 | Runtime DB role owns the audit table (TRUNCATE / trigger disable possible); unkeyed hash chain. | Medium | Open — SEC-02. |
| D4 | `/actuator/prometheus` readable by any authenticated user (lists tenants). | Medium-Low | **Fixed**: not exposed on the public port; enable only on a separate management port. |
| D5 | Unbounded issuer cache driven by unverified tokens. | Low-Medium | **Fixed**: only ACTIVE tenant realms and the platform realm are resolved. |
| D6 | Bulk approve echoed raw exception messages (SQL text). | Low | **Fixed**: generic message, details logged. |
| D7 | Console CSV formula guard bypassable (`-1+…`, tab). | Low | **Fixed** with tests. |
| D8 | Upload size checked after buffering. | Low | **Fixed**: `RequestSizeFilter` (413 before reading; counting stream for chunked bodies) + ingress limit. |
| D9 | Staff proposal: client-set `userId` could disguise an overwrite as a create; no proposer scope check. | Low | **Fixed**: server derives `userId`; makers can grant only branches they see; holiday proposals checked too. |
| D10 | `display_name` and date of birth stored in clear. | Low-Medium | Open — SEC-03 (customer model rework in Phase 2, with US-028). |
| D11 | `IllegalArgumentException` text (internal class names) echoed. | Low | **Fixed**: `ProblemHandler.safeMessage`. |
| D12 | Password policy vs ASVS 5.0 (composition, 90-day expiry, no breached check). | Low | Decision D-13 for the product owner. |
| — | Loan and product permissions were missing from the console's token scope (lending screens would get 403). | Functional | **Fixed** in the realm template; CI now checks scope mappings. |

## Organisational controls (not code)

Penetration test of tenant/branch isolation and maker-checker; KMS key ceremony and rotation runbook; Keycloak
governance (who is realm admin; the `platform` realm with MFA for operators); console hosting headers (CSP, HSTS,
frame-ancestors, Referrer-Policy); internal TLS decision; DB privilege separation in Aurora; SIEM for Keycloak
events and 401/403 spikes; enabling GitHub Code Security (or another SCA + image scanner, SBOM, base images by digest);
retention and erasure policy covering approval payloads and audit rows.
