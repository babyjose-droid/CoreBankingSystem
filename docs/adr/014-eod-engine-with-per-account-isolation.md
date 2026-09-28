# ADR-014: EOD engine with per-account failure isolation and checkpointed restart

Status: Accepted  
Date: 2026-09-28

## Context
In the reference system, one CASA account with a missing booking date made every nightly EOD fail, and the business date stayed stuck for weeks. RBI inspection expects the books to close every day.

## Decision
- **Pure engine.** `kernel.EodEngine` is plain Java: an ordered list of task steps and per-item (per-account) steps.
- **Exceptions stay with the account.** An exception on one item is recorded in `platform.eod_exception` and the step carries on. The step then ends COMPLETED_WITH_EXCEPTIONS.
- **Systemic failures stop the run.** When more than `maxFailureRatio` of a step's items fail, the step is FAILED. A FAILED step stops the run, and the business date does not move.
- **Trial balance gate.** Every branch must net to zero before the business date can advance.
- **Idempotent restart.** Completed steps are skipped, and so are items already checkpointed in `platform.eod_checkpoint`. Interest is therefore never booked twice.
- **One run at a time.** The database allows only one RUNNING run per tenant and one successful run per business date.
- **The business date moves only through `platform.advance_business_date()`.** A trigger rejects any direct change.

## Consequences
- **Operations work from the exception report,** fixing accounts the next morning instead of rerunning the whole EOD.
- **Checkpoints are separate writes.** Today a checkpoint is written in its own statement after each item. A Phase 2 lending step must write its item's postings and the checkpoint in one transaction.
