rootProject.name = "corebanking"

include("backend:calc")          // pure calculation library, no Spring
include("backend:ledger-core")   // posting-lot model and number series, no Spring
include("backend:kernel")        // pure platform rules: maker-checker, EOD engine, PII crypto, calendar, tax, dedupe
include("backend:lending-core")  // pure lending engine: schedules, appropriation, DPD/NPA, accrual, fees+GST, postings
include("backend:app")           // Spring Boot modular monolith
