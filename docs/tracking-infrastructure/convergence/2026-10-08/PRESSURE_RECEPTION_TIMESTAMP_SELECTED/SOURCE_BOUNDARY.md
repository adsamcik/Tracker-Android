# Narrow Pressure reception timestamp wiring

Base: `3a3476e63e5c3bc40e2697f5e503a94cb18592ed`.

The sole production edit copies the already captured `PressureReception.receivedWallTimeMs` into the existing `SourceEvidenceCandidate.receivedWallTimeMs`. It changes no schema, public API, optional pressure metrics, measured wall-time mapping, dedup identity, clock ordering, authorization or lifecycle rule.

Actual production trace: `PressureSourceRuntime.onSensorChanged` captures reception wall/elapsed time at original callback. The existing accumulator/completed-window carries that reception. `prepareWindowAdmission` now retains its wall reception on the candidate. The existing RoomDurableSourceIngress `toEntity` copies that field to SourceEventWalEntity. Pressure projection retains the existing original measured-time mapping; PressureFactProofAuthenticator requires the original receipt wall time. Before this fix, the runtime candidate defaulted that required field to null and the actual retained proof rejected it. Other redundant wall-order restrictions remain the subject of a separate static report.

A full runtime→Room→projection→reader fixture would duplicate substantial original policy/session/provider/lifecycle setup not reusable from this existing runtime suite; this slice uses reached real runtime tests with explicit recording-sink boundaries and pins the unchanged actual persistence/proof path. Tests are authored NOT_RUN. No validation, provider activation, Git mutation or mixed working-source changes occurred. All 25 prerequisites/T25 remain OPEN.
