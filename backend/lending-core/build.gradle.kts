// Pure Java lending engine. Same code for preview, posting and EOD (ADR-006). No Spring, no I/O.
dependencies {
    "api"(project(":backend:calc"))
    "api"(project(":backend:ledger-core"))
}
