# Production trace and bounded semantics

## Generation and immutable measurement clock

- AuthoritativeSessionCoordinator persists later run/session operation generation during quality reconfigure (2360–2373), End (3856–3875) and Stop retry (3924–3941). The original lifecycle actions, manifests, admitted WAL, purpose receipts and saved facts stay under their original generation.
- SessionManifestIntegrity.hasRetainedLeaseGeneration accepts only a positive original generation <= latest run generation. Each caller still authenticates the original named manifest/receipt, exact policy/consent, provider, authorization and original source START action/fact generation. No original generation is reassigned.
- hasValidRetainedServiceRunTimeline derives only the prepared immutable manifest's measurement boot and invokes the unchanged physical timeline validator on a read-only copy. It preserves ownership/start/plan/revision/mode/full-timeline checks. hasValidServiceRunTimeline itself, command/current session-run pairs and physical ingress/runtime checks are unchanged.
- RetainedLocationContextReader authenticates canonical content and sealed original purpose against the exact named manifest; it no longer substitutes the latest operation's boot/generation. Current policy/consent changes do not lend eligibility backward. Ambient siblings, original epoch/highwater/retention/scope fences remain.
- Activity/Wi-Fi/Cell composers, Pressure proof+selector and retained Steps selector use original manifest measurement clock. Exact original source authorization/START action generation still matches the WAL/fact; a future original generation remains rejected. Cell/Activity maintenance retains original authority as well as current nonterminal session-run operation consistency.

## Original Stop vs retries

- SessionLifecycleIntegrity contains the identical pre-existing lifecycle byte encoder: U+001F part separator, U+001E iterable separator, UTF-8 SHA256 and lowercase hex. Engine stableLifecycleChecksum only delegates. No wire bytes, intent construction or current command validators change.
- SourceSessionDao.firstStopIntent is an indexed exact-logical earliest FINALIZED lookup, LIMIT 1. SavedSessionCutoff reads that row, the immediate intent predecessor and its exact named manifest (at most three indexed reads per logical snapshot). Original Stop must have an authenticated ACTIVE predecessor, exact logical/manifest/revision and original cutoff elapsed/wall fields, supported fixed Stop envelope and checksum. The manifest lookup is exact logical+revision. Global logical Stop applies only within its actual boot; individual facts continue to prove their own physical run.
- Retry copies original cutoff elapsed/wall under a later request boot in the current implementation. The earliest-original lookup plus immediate ACTIVE predecessor prevents promoting a retry: its predecessor is FINALIZED, or missing after original removal. Corrupt/missing original or predecessor keeps cutoff clock unknown. Later operation lifecycleBootId never fills that unknown clock.
- Snapshot loaders cache SavedSessionCutoff once per logical session. Unknown original Stop keeps independently authenticated fact bounds and exposes existing acquisition/Stop-incomplete partial coverage. A known cutoff from another boot is never compared to unrelated elapsed counters; the original fact's own bounds remain. An originally open effect end can settle to a known later cutoff only when the actual saved measurement window lies within it; finite original-bound mismatches remain rejected.
- Maintenance remains in its existing Room privacy transaction, using original fact bounds and exact epoch/floor/deletion authority. No new deletion purpose is inferred. One Cell WAL helper shares its single loaded cutoff within the function.
- Shared TrackingHistoryInvalidationTables adds session_lifecycle_intent_version, the newly read original Stop dependency. Existing consumers keep their shared list.

## Native Steps export

PortableStepsRoomReader has two native timeline predicates and one native canonical-fact clock comparison repaired to the immutable original manifest. Wire IDs, counts, times, WAL provenance, count-domain receipts, selected scope and imported acceptance remain unchanged. The test compares exact native entry before/after operation-boot mutation. Imported archive validation and immutable archived snapshots retain their own exact rules.

## Carried Location V2 and companion

All 12 V2 Location paths are included coherently. Only retained qualifier and new historical Location test cases change behavior/source beyond that V2; other carried paths have line-ending normalization only where necessary. Its 256-point finite keyset summary, optional numerical accuracy qualification, bounded named-manifest lookup and source clock/gap/epoch/fence checks remain. The separate LIVE_SAVED_LOCATION_TOTAL_SELECTED overlay owns ongoing shared accumulator, partial retained-suffix marking and consumer wiring; this packet does not overwrite that later companion.
