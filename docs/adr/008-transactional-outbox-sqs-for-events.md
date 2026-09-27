# ADR-008: Transactional outbox + SQS for events

Status: Accepted  
Date: 2026-09-27

## Context
Modules and integrations need events (loan disbursed, EMI paid) without dual-write bugs.

## Decision
Events are written to platform.outbox in the same transaction as the change; a relay publishes to SQS. Spring Modulith event publication registry for in-process listeners. No Kafka until volumes need it.

## Consequences
At-least-once delivery: consumers must be idempotent.
