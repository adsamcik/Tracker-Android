# Background radio delivery and recovery selection

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** This is a source prerequisite,
not an assembly freeze, provider activation, passing test or integration receipt.

The original author packet and rejected review remain preserved. The repaired
selection combines their eleven paths with four additional production/test paths.
`FILES.json` records the fifteen selected paths and actual/selected SHA-256 pins.
`REPAIR_AUTHOR/PACKET.json` records the separate AR1/AR2 correction delta.

## Implemented source path

- Existing background Wi-Fi and Cell writers are registered through thin existing
  Projection adapters. Ingress hints and startup recovery schedule independent,
  conflated source drains; failure does not spin or make the sibling wait.
- Each retained drain uses the existing nonserialized retained-data operation.
  It does not hold the startup control mutex, grant collection permission, renew
  consent or start a provider. The existing deletion barrier cancels its child and
  waits for physical cleanup. Current data-generation checks remain mandatory.
- Global registration, dispatch, raw quarantine and continuation exclude only the
  exact known background radio consumer IDs and versions. Unknown IDs/versions
  retain conservative global handling. Independent registration and source-aware
  event retention preserve pending background observations.
- End and suspension of an unrelated Steps session do not depend on pending
  background radio projection progress. Ending a session does not stop a separately
  enabled background owner.

## Selected test boundary

The source scenarios include the real startup gate, recovery actors, global
coordinator, session coordinator, Room checkpoints and source-aware maintenance.
Provider and selected dispatcher boundaries remain fixtures. All tests are authored
and unexecuted; they establish no Android delivery, Hilt, device or battery result.

Three new existing-file before snapshots match supplied HEAD modulo checkout line
endings. `AuthoritativeSessionCoordinatorTest.kt` had unrelated held adaptations.
Its complete working file is excluded: only the HEAD-based selected snapshot adds
two isolation cases, their helper/imports and a false-default fixture flag for a
provider with no admitted Steps events. Original test behavior is preserved.
The held working test remains untouched and recoverable.

Historical Cell capture qualification, complete source history/presentation/export,
remaining lifecycle recovery, schema generation, all convergence gates and local
`dev/v10` integration remain open. The repaired review closes AR1/AR2 only within
these selected source paths.
