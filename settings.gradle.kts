rootProject.name = "corebanking"

include("backend:calc")          // pure calculation library, no Spring
include("backend:ledger-core")   // posting-lot model and number series, no Spring
include("backend:app")           // Spring Boot modular monolith
