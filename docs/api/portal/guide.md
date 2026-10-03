# Getting started

The API is described by one OpenAPI 3.1 document, served next to this page at `/developer/openapi.yaml`. Generate a client from it; it is the source of truth.

## Authentication

- Every call needs an OAuth2 access token in the header `Authorization: Bearer <token>`.
- An integration uses the **client credentials** grant against the tenant's own realm:

```
POST {identity-provider}/realms/{tenant}/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id={client id}&client_secret={client secret}
```

- The token names its tenant; the tenant is never taken from the URL or the body. A token of one tenant cannot act on another.
- What a client may do is set by its permissions (for example `loan:stp`, `customer:create`). A call without the permission gets 403.
- Client registration (creating a client id and secret, rotating the secret) is done by the tenant's administrator. The registration API is being built separately; until it is published, ask the administrator.
- Tokens are short-lived. Ask for a new one when the old one expires; do not store tokens.

## Conventions

- **Money** is a decimal string such as `"100000.00"`, never a JSON number.
- **Dates** are `YYYY-MM-DD` business dates. Timestamps are RFC 3339 in UTC.
- **Maker-checker:** a change to master data or money by a staff user returns 202 with an approval request; it takes effect when another user approves it. Straight-through clients (`loan:stp`) are applied at once where the contract says so.
- **Pagination:** list calls take `page` (from 0) and `size` (at most 100).
- **Custom fields:** customers and loans accept and return an object `custom` with the tenant's own fields; see `GET /api/v1/custom-fields`.

## Idempotency

- Send an `Idempotency-Key` header (any unique text, for example a UUID) on every POST that creates something.
- Repeating a call with the same key returns the first result instead of creating a second record. Use it whenever a call times out and you retry.
- Loan creation also accepts `externalRef`: the same reference returns the same loan.

## Errors

- Errors are RFC 9457 `application/problem+json`: `status`, `title`, `detail`, and for validation problems `errors` with `field` and `message`.
- 401: no token or an expired one. 403: the permission, the branch scope or the module is missing. 404: no such record, or one outside your branch scope. 409: a business rule refused the change; `detail` says which. 422: the request is not valid.
- 5xx: retry with the same `Idempotency-Key`.

## Repayments around end of day

- While end of day runs, a repayment sent by a straight-through client is not refused: the API answers **202** with a receipt, and the money is booked and valued on the next business date as soon as that date opens.
- Send such a repayment without a `valueDate`. The receipt shows `expectedPostingDate`; follow it with `GET /api/v1/loans/{id}/deferred-receipts`.

## Webhooks

Webhook delivery is part of the integration increment and its contract will be published here. Signatures will be verified like this:

- Each delivery carries a timestamp header and a signature header. The signature is an HMAC-SHA256, with your webhook secret, of the timestamp, a dot and the raw request body.
- Compute the same HMAC over the bytes you received, before parsing them, and compare in constant time.
- Reject a delivery whose timestamp is more than five minutes old, and remember delivery ids so that a repeated delivery is processed once.
- Answer 2xx quickly and do the work afterwards; a delivery that is not acknowledged is retried.

The exact header names and the retry schedule are to be published with the webhook contract.

## Rate limits

To be published. Until then, keep to a few requests per second per client and back off on 429 or 503.

## Support

Report a problem with the request's time, path and the `detail` of the error. Never send a token, a client secret or personal data of a customer.
