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
- A regulatory change is made by a compliance user through maker-checker (from P4-1), without a release.
- Two tenants of different types run the same code and get different refusals and figures.
- The product does not decide whether a tenant may take deposits: it records the tenant's registration and enforces the rules that follow from it.
