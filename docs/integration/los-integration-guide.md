# LOS integration guide (US-124)

How a loan origination system (LOS) takes a loan from application to disbursal through the CoreBanking API, and
how it hears what happens afterwards. The contract is `docs/api/openapi.yaml`; this guide is the order of calls
and the rules around them.

**Status:** the API and the SIMULATOR providers are built. The flow has **not** been run end to end against a
running stack in this increment (no stack was available to the build). Run `tools/integration/los_contract_test.py`
against a local stack before giving the guide to a pilot client.

## 1. Authentication

- The LOS is an **API client** of the tenant: an OAuth2 client-credentials client in the tenant's Keycloak realm,
  created by the tenant's admin through `POST /api/v1/api-clients` (maker-checker; two checkers when the client gets
  `loan:stp`, `loan:repay` or `payout:beneficiary`).
- The admin who proposed the client collects the secret once (`POST /api/v1/api-clients/{clientId}/secret`) and
  hands it to the LOS over a secure channel. CoreBanking does not store it. A lost secret means a rotation.
- Token: `POST {keycloak}/realms/{tenant}/protocol/openid-connect/token` with `grant_type=client_credentials`,
  `client_id`, `client_secret`. Access tokens live 5 minutes; ask for a new one when it expires.
- Every call: `Authorization: Bearer <token>`. The tenant comes from the token, never from the URL or body.
- The client sees only the branches of its branch scope (its home branch, or all branches if it was given that).
- Scopes a pilot LOS normally needs: `customer:view`, `customer:create`, `consent:record`, `product:view`,
  `loan:view`, `loan:create`, `loan:stp`, `payout:view`, `payout:beneficiary`, `mandate:register`, `mandate:view`,
  `collection:create`, `collection:view`.

## 2. Idempotency

| Call | Key | Repeating it |
|------|-----|--------------|
| Create customer | `externalRef` in the body (the LOS's customer id), or the PAN | returns the existing customer (200) |
| Create customer (new) | `Idempotency-Key` header | returns the same approval request |
| Create loan | `externalRef` in the body (the LOS's application id) | returns the existing loan |
| Disburse | the loan itself | 409 once the loan is no longer SANCTIONED |
| Create collection order | `Idempotency-Key` header | returns the same order |
| Register mandate | one live mandate per loan | 409 while one is in registration or in force |

Retry a call that timed out with the same key. Never invent a new `externalRef` for a retry.

## 3. Sequence

1. **Customer** — `GET /api/v1/customers?externalRef={id}`; if the list is empty, `POST /api/v1/customers` with
   `externalRef`.
   - 200: the customer already exists (same `externalRef` or PAN).
   - 202: an approval request. A new customer is created when a staff checker approves it; poll
     `GET /api/v1/approvals/{id}` (needs `approval:view`) or look the customer up by `externalRef` again.
     Straight-through customer creation is not built: see "Open points".
2. **Preview** — `POST /api/v1/loans/preview`: EMI, APR, fees, schedule and KFS figures from the engine that posts.
3. **Create the loan** — `POST /api/v1/loans` with `externalRef`. The loan is SANCTIONED; no money has moved.
4. **KFS acceptance** — show the borrower the KFS (`GET /api/v1/loans/{id}/kfs`), then
   `POST /api/v1/loans/{id}/kfs-acceptance` with the channel and the evidence reference (OTP or e-sign id).
5. **Beneficiary** — `PUT /api/v1/loans/{id}/payout-beneficiary` with holder name, account number and IFSC. The
   gateway validates the account (penny drop); 422 means the bank did not confirm it. Do this before disbursing:
   without a beneficiary the payout waits ON_HOLD.
6. **Mandate** (optional) — `POST /api/v1/loans/{id}/mandates`. The answer may carry `authenticationUrl` for the
   borrower. Status: `GET /api/v1/loans/{id}/mandates`, or the `mandate.status` webhook.
7. **Disburse** — `POST /api/v1/loans/{id}/disbursement`. With `loan:stp` the loan is ACTIVE at once (200) and a
   payout instruction follows within seconds. Without it the answer is 202 and staff approve.
8. **Payout status** — `GET /api/v1/payouts?loanId={id}` or the `payout.status` webhook:
   `INITIATED → SENT → SUCCESS`, or `FAILED` / `RETURNED`.
   - A failed or returned payout of a straight-through loan reverses the disbursement automatically: the loan is
     SANCTIONED again (`action: REVERSED` in the event). Correct the beneficiary and disburse again.
9. **Collections** — `POST /api/v1/loans/{id}/collection-orders` returns `paymentUrl` for the borrower. The
   repayment is posted when the gateway confirms it; the LOS hears `payment.received`.

## 4. Errors

Errors are RFC 9457 `application/problem+json` with `status` and `detail`.

| Status | Meaning | What to do |
|--------|---------|------------|
| 401 | token missing or expired | get a new token, retry |
| 403 | scope missing, branch outside the client's scope, or module not enabled | do not retry; fix the configuration |
| 404 | not found, or outside the client's branch scope | do not retry |
| 409 | the state does not allow it (books closed for end of day, loan not SANCTIONED, duplicate) | read `detail`; "business day is EOD_RUNNING" can be retried after a few minutes |
| 413 | body larger than 6 MB | — |
| 422 | a value is not valid; `errors[]` names fields where available | fix the request |
| 5xx / timeout | unknown outcome | retry with the same idempotency key |

## 5. Webhooks

Register an endpoint with `POST /api/v1/webhooks/endpoints` (maker-checker). After approval, the proposer collects
the signing secret once (`POST /api/v1/webhooks/endpoints/{id}/secret`).

- **Events:** `loan.disbursed`, `payment.received`, `payment.bounced`, `loan.closed`, `loan.npa`,
  `mandate.status`, `payout.status`. Fields per event: `GET /api/v1/webhooks/event-types`.
  A loan disbursed in tranches sends one `loan.disbursed` per tranche (`trancheNo` 1, 2 …), each with its own
  `transactionId`, amount and payout.
- **Body:** `{"id", "type", "apiVersion": "v1", "tenant", "occurredAt", "data": {…}}`. Payloads carry identifiers
  and amounts (decimal strings). They never carry PAN, Aadhaar, mobile numbers or unmasked account numbers; fetch
  personal data through the API.
- **Headers:** `X-CoreBanking-Event-Id`, `X-CoreBanking-Event-Type`, `X-CoreBanking-Delivery`,
  `X-CoreBanking-Signature: t=<unix seconds>,v1=<key id>:<hex>[,v1=<key id>:<hex>]`.
- **Verify:** compute HMAC-SHA256 with your secret over the bytes of `t + "." + <raw body>` and compare, in
  constant time, with the `v1` entry for your key id. Reject when `t` is more than **300 seconds** from your
  clock. Verify before parsing the body. `tools/integration/verify_webhook.py` is a sample receiver check.
- **Rotation:** after a rotation both secrets sign for the overlap period (24 hours by default), one `v1` entry
  each, so the endpoint keeps verifying with the old secret until it is switched.
- **Delivery:** at least once. Answer 2xx within 15 seconds. Anything else is retried after 1, 2, 4 … minutes
  (capped at 6 hours, with jitter) for 12 attempts, about 20 hours; then the delivery is dead-lettered and the
  tenant's admin can replay it. De-duplicate on `X-CoreBanking-Event-Id`. Events may arrive out of order.
- **Endpoint rules:** https, a public DNS name, port 443 or 8443, no redirects.

## 6. Open points for the pilot

- **Straight-through customer creation** is not built: a new customer needs a staff checker. To be decided
  with the product owner (a `customer:stp` scope would need dedupe and KYC rules for unattended creation).
- **Role amount limits (US-021):** an API client has no realm roles; with the tenant property
  `limits.default-deny = true` its disbursements and repayments are refused. Decide how limits apply to clients.
- **Rate limits per client** are not built (SAD: "rate limits per client").
- **mTLS and IP allow-lists** (FSD 14.1) are not built.
