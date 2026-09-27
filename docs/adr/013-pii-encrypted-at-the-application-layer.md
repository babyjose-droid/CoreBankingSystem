# ADR-013: PII encrypted at the application layer

Status: Accepted  
Date: 2026-09-27

## Context
DPDP Act and bank security reviews expect PII protected even from database administrators.

## Decision
PAN, mobile, e-mail and address lines are stored as ciphertext (AES-GCM, per-tenant data key from KMS) with a keyed HMAC for exact-match search and dedupe. Erasure sets status ERASED and destroys the ciphertext, keeping ledger history.

## Consequences
No LIKE search on PII; search by exact value or by customer number.
