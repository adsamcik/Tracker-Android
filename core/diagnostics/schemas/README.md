# Tracking diagnostics schema containment

`TrackingDiagnosticDatabase` is an unreleased standalone version 1 database. During the current
implementation-only phase it uses `exportSchema = false`; the exact table and index contract is
authored in `RoomTrackingDiagnosticStoreTest`.

Before the first version increment or migration, the final convergence batch must enable schema
export, generate and review the version 1 JSON snapshot, commit it, and then add versioned
migrations. No schema generator was run for this checkpoint.
