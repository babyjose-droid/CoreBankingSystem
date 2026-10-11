# ADR-017: Deposit rules are dated data, chosen by institution type

Status: Accepted  
Date: 2026-10-10

## Context
- Phase 4 serves two kinds of tenant: banks (savings, current and term deposits) and deposit-taking NBFCs (term deposits only). Their rules differ: tenure limits, a rate ceiling, a lock-in, what is paid on premature repayment, how many nominees, the threshold for tax deducted at source.
- These rules change by regulator notification. RBI re-issued its Directions on 28-Nov-2025 and the Income-tax Act, 2025 replaced the 1961 Act on 1-Apr-2026.
- Part of the bank rule set could not be read in the regulator's own text when Phase 4 started (open item P4-01).

## Decision
- **A rule is a row, not a constant.** `deposits.rule` holds `kind` (BANK or NBFC_DEPOSIT), a code, a numeric value, the dates it applies from and to, its source, and whether it was verified against the regulator's text. A changed rule is a new row from a later date; a row's value never changes.
- **The tenant's legal entity decides the rule set.** BANK, SFB and COOP_BANK use BANK. An NBFC uses NBFC_DEPOSIT only when its registration to accept public deposits is recorded. HFC and MFI have none, and so cannot open deposits.
- **A rule that is absent does not apply.** A bank has no rate ceiling row, so its products decide the rate. The engine never assumes a default for a missing regulatory value; where it cannot work without one (the tax rate) it stops and names the rule.
- **One pure class reads the rules:** `deposits-core` `DepositRules`. The preview, the posting and the day-end use it, so they cannot apply different rules.
- **The database holds the same line.** `deposits.assert_can_offer` refuses a deposit family the institution may not offer, and the control plane refuses the CASA and TD modules for it (`control.module_allowed`), whatever the application does.
- **Unverified rules are visible as such.** Staff screens and the status document show `verified = false`; such a rule is still applied.

## Consequences
- A regulatory change is meant to be made by a compliance user through maker-checker, without a release. That API is not built yet (open item P4-05); until it is, a changed rule is a migration.
- Two tenants of different types run the same code and get different refusals and figures.
- The product does not decide whether a tenant may take deposits: it records the tenant's registration and enforces the rules that follow from it.

## Amendment (11-Oct-2026, P4-1): the rate card and the three places a rule is held
- **The rate card holds the full rate** for an amount step and a tenure step, plus named add-ons (decision D-16, option A). It is never a base rate plus a slab rate. A step is given by where it starts and runs to the next one, so a card has no gaps between steps; what it can lack is a cell, and a product with a missing cell is not approved.
- **A rule is checked in three places, with the same answer.** The engine reports every reason at once, so the API can answer 422 with all of them when a product or an account is proposed. The same check runs again when the request is approved, on the rule set of that day. The database holds the rule whatever the application does (tenant V29).
- **What a deposit was promised is frozen on it.** A term deposit carries its rate with its parts and the product settings that decide its interest, tax and premature repayment, as they were on the day it was booked. A later product version changes new deposits only.
- **Recording an NBFC's registration** is one operator call (`PUT /platform/v1/tenants/{code}/deposit-registration`) that writes the control plane and then the tenant's database.
