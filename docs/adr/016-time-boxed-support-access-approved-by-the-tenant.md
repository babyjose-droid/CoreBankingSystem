# ADR-016: Time-boxed support access, approved by the tenant

Status: Accepted  
Date: 2026-10-03

## Context
- Support engineers of the platform sometimes need to look at a tenant's data to solve a ticket (US-007).
- The ASVS review fixed two rules (D1): a tenant realm can never reach `/platform/**`, and a platform-realm token can never act as a tenant user. Support access is a deliberate, narrow exception to the second rule and must not weaken the first.

## Decision
- **The engineer asks, the tenant decides.** `POST /platform/v1/support-access` (platform realm, permission `platform:support`) records a request: tenant, reason, ticket, duration of 15 minutes to 8 hours, scope READ_ONLY. The tenant's admin (`support-access:approve`) approves or rejects it in the tenant API and can revoke a grant at any time.
- **The record lives in the tenant's own database** (`platform.support_access`). The control plane keeps only an index of requests, with no status. Approval can therefore only be written through the tenant API by a tenant user.
- **The database sets the limits.** Expiry is the approval time plus the requested duration and is never taken from the caller. More than 8 hours is impossible (a CHECK constraint). A request older than 24 hours cannot be approved. Records cannot be edited or deleted.
- **One pure function decides every request:** `kernel.SupportAccess`. `TenantFilter` calls it and does nothing else on its own.
  - `/platform/**`: only a platform-realm token. Unchanged.
  - `/api/**` with a tenant token: realm equals the `tenant` claim. Unchanged. Such a request may not carry the support headers, and a tenant user name may not start with `support:`.
  - `/api/**` with a platform token: refused, unless all of these hold: the token has `platform:support`; the method is GET; the path is on the support read list; the headers `X-Support-Tenant` and `X-Support-Grant` name a grant; that grant, read from that tenant's database on this request, belongs to this engineer (token subject), is APPROVED, is READ_ONLY, and the time is inside its window.
- **The headers only locate the grant.** The tenant is not trusted from the header: the grant must exist in that tenant's database for this engineer.
- **Expiry and revocation are checked on every request.** Nothing is cached.
- **Inside the tenant the engineer is `support:<username>` with view permissions only.** The token's own authorities are replaced by a fixed read-only set. Branch scope gives this login every branch only while a grant is in force. No staff profile can use the `support:` prefix.
- **Closed by default.** The read list names each path. A new endpoint is not reachable under support access until it is added. Left out on purpose: KYC document content, loan documents and invoices, report files and the bureau export, sessions, and the support-access endpoints.
- **Personal data stays masked.** The API already masks PAN, mobile and e-mail. For support requests the response is also passed through a masker that hides names, dates of birth and address parts by property name. A response that is not JSON is withheld.
- **Everything is audited in the tenant's trail** under `support:<username>` with the grant id: the request, the decision, the revocation and every read (method, path, status; never the query string). The read is audited before the data is returned; if the audit cannot be written, nothing is returned.
- **Calls under support access are not counted as the tenant's API usage.**

## Consequences
- A platform token still cannot act as a tenant user: it can only read, only listed paths, only while the tenant's own grant is in force.
- Refusals give one answer ("no support access is in force for this tenant") whatever the cause; the cause goes to the application log.
- Masking by property name depends on naming. A new property that holds personal data under an unusual name would not be masked; the read list limits where that could happen.
- Each support read costs one extra query and one audit insert in the tenant database.

## Not built
- Any scope other than READ_ONLY.
- Notifying the tenant admin of a new request (needs the notification provider, OI-06).
