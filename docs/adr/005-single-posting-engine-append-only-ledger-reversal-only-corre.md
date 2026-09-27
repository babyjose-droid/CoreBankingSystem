# ADR-005: Single posting engine; append-only ledger; reversal-only corrections

Status: Accepted  
Date: 2026-09-27

## Context
The reference system showed reversals failing and entries editable in places. Books must always balance and be auditable.

## Decision
Only PostingService writes ledger tables. Lots must balance per currency and per branch (inter-branch legs added automatically). The database enforces this with a deferred constraint trigger, blocks UPDATE/DELETE/TRUNCATE, rejects legs added to a committed lot, and allows each lot to be reversed once.

## Consequences
Two layers of the same rule (Java + SQL). Corrections are always visible as reversal lots.
