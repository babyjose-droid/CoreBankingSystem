// Pure Java lending engine. Same code for preview, posting and EOD (ADR-006). No Spring, no I/O.
dependencies {
    "api"(project(":backend:calc"))
    "api"(project(":backend:ledger-core"))
    "implementation"(project(":backend:kernel"))   // SimplePdf for the document builders (P2-4)
}
