# Runbook: integrations (P2-2)

Payouts, gateway collections, mandates and NACH, webhooks, API clients, SMS and e-mail. Permissions are named
with each step; none of them is in the realm template yet (see docs/phase2-status.md).

## Deployment settings

| Property (environment variable) | Default | Meaning |
|---|---|---|
| `corebanking.integration.providers-enabled` (`COREBANKING_INTEGRATION_PROVIDERS`) | empty | Providers a tenant may configure: `SIMULATOR`, `EASEBUZZ`, `GENERIC_HTTP`. **Never list SIMULATOR in production**: it reports payouts as paid without moving money. EASEBUZZ and GENERIC_HTTP are unverified against the provider. |
| `corebanking.integration.worker-enabled` (`COREBANKING_INTEGRATION_WORKER`) | true | The background worker (outbox relay, senders, processors). |
| `corebanking.integration.worker-interval-ms` | 5000 | Pause between worker runs. |
| `corebanking.integration.keycloak-admin.*` (`COREBANKING_KEYCLOAK_ADMIN_ENABLED`, `_URL`, `_REALM`, `_CLIENT_ID`, `_CLIENT_SECRET`) | disabled | Admin service account for API clients. Needs the right to manage clients, client scope mappings and user role mappings in the tenant realms. |
| JVM security property `networkaddress.cache.ttl` | JVM default | Keep at 30 or more: the webhook sender checks the resolved address and the HTTP client must connect to the same answer. |

Network: route `POST /hooks/v1/**` from the internet to the app (provider callbacks; no token, signature
checked). Deny egress from the app to private address ranges except the database, Keycloak and the document
store; allow egress to the providers and to tenants' webhook endpoints (ports 443 and 8443).

Tenant properties (system properties, maker-checker) and their defaults are listed at the top of
`V20__integrations.sql`.

## Configure a provider

1. `GET /api/v1/integrations/providers/catalogue` (`integration:view`): the settings and secret names of each
   provider, and whether the deployment enables it.
2. `POST /api/v1/integrations/providers` (`integration:admin`) with `kind`, `provider`, `settings`, `secrets`.
   Example for local use: `{"kind":"PAYOUT","provider":"SIMULATOR","secrets":{"webhookSecret":"<16+ characters>"}}`.
3. A second user approves the request (`approval:approve`). The checker sees the secret names and their last
   four characters, never the values.
4. `GET /api/v1/integrations/providers`: the active configuration per kind.
5. Give the provider the callback URL `https://<host>/hooks/v1/<tenant>/<payout|collection|mandate>/<provider>`.

Checks before a real provider is used: the adapter is marked verified in the catalogue; sandbox credentials were
tested; the callback URL is reachable from the provider; one payout and one collection of a small amount were
reconciled by hand.

## Rotate secrets

- **Provider secret:** propose the configuration again with only the changed secret; the others carry over. The
  new version is active when approved. Change the secret at the provider at the same moment: there is no overlap.
- **Webhook signing secret:** `POST /api/v1/webhooks/endpoints/{id}/actions` `{"action":"ROTATE_SECRET"}`
  (`webhook:admin`), approve, then the proposer calls `POST …/secret` and passes the new secret to the
  subscriber. Both secrets sign for `webhook.secret-overlap-hours` (24). A leaked secret: set the overlap to 0
  before collecting the new one.
- **API client secret:** `POST /api/v1/api-clients/{clientId}/actions` `{"action":"ROTATE_SECRET"}`
  (`apiclient:admin`; two checkers for clients with money-moving scopes), then the proposer calls `POST …/secret`.
  The old secret stops working when the new one is collected: agree the time with the client.
- **Tenant data key** (encrypts all of the above at rest): the key id is fixed at 1 today (SEC-04); rotation
  is not built.

## Replay webhooks

1. `GET /api/v1/webhooks/deliveries?status=DEAD&endpointId=…` (`webhook:view`): what was given up, with the last
   status and error. `GET /api/v1/webhooks/deliveries/{id}` shows every attempt.
2. Fix the cause with the subscriber (endpoint down, certificate, secret not collected, endpoint disabled).
3. `POST /api/v1/webhooks/deliveries/{id}/replay` for one delivery, or
   `POST /api/v1/webhooks/endpoints/{id}/replay-dead` for all of an endpoint (`webhook:admin`). A replay is a new
   delivery of the same event with the same event id: the subscriber de-duplicates on it.

A blocked endpoint ("blocked: the host resolves to an address that is not public") is the SSRF guard: the
subscriber's DNS points at a private address. It is not retried into success; the subscriber has to fix DNS.

## Handle a failed payout

`GET /api/v1/payouts?needsAction=true` (`payout:view`) is the work list. `actionNote` says what is expected.

| Situation | What happened | What to do |
|---|---|---|
| `ON_HOLD` | no validated beneficiary account at disbursement | record it (`PUT /api/v1/loans/{id}/payout-beneficiary`), then `POST /api/v1/payouts/{id}/retry` |
| `INITIATED`, "gave up after n attempts … outcome is unknown" | the provider never answered | check the provider's dashboard for our reference first; then `retry` (same reference: the provider de-duplicates) |
| `SENT` for long | the provider has not reported an outcome | `POST /api/v1/payouts/{id}/refresh`; otherwise ask the provider |
| `FAILED` / `RETURNED`, `failureAction: REVERSED` | straight-through loan: the disbursement was reversed, the loan is SANCTIONED | correct the beneficiary; the LOS disburses again |
| `FAILED` / `RETURNED`, `failureAction: PROPOSED` | a reversal waits in the approvals queue (`LOAN_DISBURSEMENT_REVERSAL`) | approve it, or reject it and decide below |
| `FAILED` / `RETURNED`, `failureAction: PARKED` | tenant setting `payout.failure-action = PARK`, or the reversal was not possible (the loan has later transactions) | correct the beneficiary and `retry` (a new attempt with a new reference), or cancel / reverse the loan by the normal loan rules |
| "the provider reported X while the payout is Y" | contradicting news (for example FAILED after SUCCESS) | nothing was changed automatically; settle it with the provider |

A reversal of a disbursement reverses its ledger lots (principal, fees deducted, GST) and the day-end lots since;
nothing is kept from the borrower. It is refused once the loan has another transaction.

## Process a NACH response file

1. Presentation files are written at end of day for instalments due `nach.presentation.lead-days` working days
   ahead. `GET /api/v1/nach/files?direction=PRESENTATION` (`nach:admin`); download with
   `GET /api/v1/nach/files/{id}/content` (`nach:file`; audited — the file holds account numbers) and send it to the
   sponsor bank by the agreed channel.
2. Upload the bank's response: `POST /api/v1/nach/responses` with the file as the body (`text/plain`).
3. Read the answer: `status` and `summary` (`success`, `bounced`, `alreadyRecorded`, `errors`).
   - `REJECTED`: not in the layout, control totals do not match, or it names an unknown presentation file. Nothing
     was processed. Ask the bank for a correct file.
   - `DUPLICATE`: a response with the same control totals was already processed. Nothing was processed.
   - 409 at upload: the same bytes were already received.
4. `GET /api/v1/nach/presentations/pending`: outcomes whose repayment or bounce charge is not yet posted. `PENDING`
   posts by itself when the books are open; `FAILED` carries the reason (for example the loan is closed) and
   needs a decision.
5. Bounces: the return reason is on the presentation; the product's bounce fee is charged; no receipt is posted,
   so the instalment stays overdue; `representOn` shows when it will be presented again (never above
   `nach.max-presentations-per-demand`).

Not built: automatic pick-up of files the bank drops by SFTP (the document store has no listing), and automatic
submission of presentation files.
