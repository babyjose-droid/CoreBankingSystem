/**
 * Deposits (Phase 4): the rule set by institution type, savings, current and term deposit products, account opening
 * and term deposit booking. The calculations and the checks are the pure engine in backend/deposits-core (the classes
 * of this package); this module adds persistence, maker-checker and the HTTP edge. Paths under /api/v1/casa need the
 * CASA module, paths under /api/v1/deposits the TD module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Deposits")
package com.corebanking.deposits;
