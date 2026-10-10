// Pure Java deposit engine (Phase 4): rule sets by tenant type, rate tables, term and savings interest,
// premature withdrawal, TDS on interest, recurring deposits. Same code for preview, posting and day-end (ADR-006).
// No Spring, no I/O, no clock.
dependencies {
    "api"(project(":backend:calc"))
}
