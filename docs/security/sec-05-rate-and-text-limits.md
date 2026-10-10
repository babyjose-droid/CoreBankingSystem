# SEC-05: rate limits and free-text length limits

Built 10-Oct-2026. Closes the open item SEC-05 in `docs/open-items.md` apart from the multi-instance note below.

## Rate limits

A servlet filter (`RateLimitFilter`, in the security chain after authentication and the tenant filter) holds in-memory
token buckets (`kernel RateLimiter`; pure logic with an injected clock, unit-tested).

| Bucket | Default | Key |
|--------|---------|-----|
| Per user | 300 calls a minute (burst 300) | tenant + user name from the token |
| Per client address | 600 calls a minute | remote address (an office behind one address shares it) |
| Strict lookups, per user and per address | 30 calls a minute | same keys, for `strict-paths` |

Strict paths by default: `/api/v1/customers/dedupe-check` and `/api/v1/pincodes` (and below): lookups that could be used
to enumerate customers or places. A call without a token (public paths) is limited by address only.

A refused call gets `429` `application/problem+json` with a `Retry-After` header (seconds). The log line names the kind of
bucket and the path, never the user or the address.

Exempt paths (never limited): `/actuator`, `/api/v1/eod` (day-end and its batch endpoints), `/hooks/v1` (signed provider
callbacks) and `/developer`. Jobs and the EOD scheduler do not use HTTP and are not affected.

Configuration (`corebanking.rate-limit.*`, environment variables in brackets):

| Key | Default |
|-----|---------|
| `enabled` (`COREBANKING_RATE_LIMIT_ENABLED`) | `true` |
| `per-user-per-minute` (`COREBANKING_RATE_LIMIT_PER_USER`) | `300` |
| `per-ip-per-minute` (`COREBANKING_RATE_LIMIT_PER_IP`) | `600` |
| `strict-per-minute` (`COREBANKING_RATE_LIMIT_STRICT`) | `30` |
| `strict-paths` | `/api/v1/customers/dedupe-check,/api/v1/pincodes` |
| `exempt-paths` | `/actuator,/api/v1/eod,/hooks/v1,/developer` |

Limits of this version:

- **Per instance.** Buckets live in memory, so with N instances behind a load balancer the effective limit is up to N times
  the figure. An exact limit needs a shared store (for example Redis or the database); the `RateLimiter` interface is small
  enough to put one behind it.
- **Client address.** The filter uses the servlet's remote address and never reads `X-Forwarded-For` itself (a client could
  forge it). Behind the ingress set `server.forward-headers-strategy=native` (or `framework`) so that the address is the
  real one; otherwise every call comes from the proxy and the per-address bucket is shared by everybody.
- Memory is bounded (100,000 keys; full buckets are dropped first).

## Text length limits

One table (`kernel TextLimits`), looked up by the name of the field, applied to every JSON request body by
`TextLimitAdvice` (records and records nested in lists are walked; free-form maps such as custom fields and job
parameters have their own validation). A too-long value is answered `422` naming the field and the limit, without echoing
the text. Services can be stricter (for example a name of at most 80 characters, a reason of at least ten).

| Field name | Maximum characters |
|------------|--------------------|
| `firstName`, `middleName`, `lastName` | 100 |
| `displayName`, `legalName`, `tradeName`, `holderName`, `name`, `label` | 200 |
| `line1`, `line2`, `addressLine1`, `addressLine2`, `address`, `landmark` | 200 |
| `city`, `district` | 100 |
| `subject` | 200 |
| any field whose name ends in `reason` (`reason`, `overrideReason`, `rejectReason` ...) | 500 |
| `description`, `narration` | 500 |
| `note`, `notes`, `remark(s)`, `comment`, `resolutionNote` | 1000 |

Content fields (CSV text, documents) are bounded by the request size limit (6 MB, `RequestSizeFilter`), not here.

Database backstops (V26, cheap `CHECK`s; `NOT VALID` where rows already exist, so old rows are not rescanned):
`approval_decision.note` at most 1000, `customer.display_name` at most 300 (three name parts), `loan_party.release_reason`
at most 500.

To limit a new free-text field, name it as in the table (or add the name to `TextLimits`); nothing else is needed.
