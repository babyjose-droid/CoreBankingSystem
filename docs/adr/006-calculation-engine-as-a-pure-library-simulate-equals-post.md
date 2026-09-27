# ADR-006: Calculation engine as a pure library (simulate = post)

Status: Accepted  
Date: 2026-09-27

## Context
Previews (KFS, EMI calculator) disagreeing with booked schedules is a common source of complaints and regulatory findings.

## Decision
backend/calc is pure Java: no I/O, no clock, BigDecimal only. The same functions produce previews, booked schedules and EOD accruals. Golden tests pin verified values; any change to a golden value needs a signed-off reason.

## Consequences
Rounding and day-count rules are product parameters, not code branches.
