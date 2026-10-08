# Saved Location summary V2 independent review

Phase: IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Source inspection only; all tests NOT_RUN. No production/test changes, execution gates or Git mutations. Original full task messages, approved practical saved-data behavior and AGENTS phase restrictions retained.

Reviewed exact packet: FILES 530424520d40afbc84d9fa7c569c93e5d1a41b43c896295292012697d53ba8fd; patch 811d7ec3dff2feda21827bc9b81a0a8c9a2ef2ada0a8cf0b7edf095251e1a8b2; dependencies e66d2a769c31e9dca5c7c38ec81ce2f0067625c035192ac4778ee56edf4bdd84; SOURCE_BOUNDARY 8ea7fb2b8b8e7b0680e2dd2057f722333b7e21ad33987fb4a02ae02f8bc97e7d. All twelve selected source pins match. Source BASE unchanged at observed HEAD f1bef520eed3cb8d26afebd6d6ab74a18bc4020a. One dependency changed: RoomRadioLocationContextRepository now reuses the already qualified native rows instead of issuing two duplicate effective-row DAO queries; qualifier API, authority, result and accuracy contracts remain compatible with this packet.

## Blocker — mutable operation generation invalidates retained original Location

The original retained reader (selected RetainedLocationContextReader.kt around line159; committed BASE line127) requires current SourceServiceRunEntity.leaseGeneration to equal each sealed Location purpose.lifecycleLeaseGeneration. The actual coordinator updates that SAME run to the newest operation generation during quality reconfigure (AuthoritativeSessionCoordinator.kt2365–2372), and Stop also advances it. releaseLease7534–7548 expires the operation; SourceProjectionStateDao475–477 increments a later acquisition. Those values describe later commands, not the past point.

The actual protected capture writer records the original authority generation and manifest into a separately sealed LocationSamplePurposeAuthorityEntity (ProtectedLocationCanonicalHandoff.kt1400–1420). A valid point at generation1 therefore stops qualifying when its run advances to generation2 even though canonical content, original grant, exact manifest, historical consent and data-retention authority are unchanged. The new numeric session/day summary consequently loses it, ordinary History may downgrade it and radio context omits it. This violates approved retained history and practical adaptation behavior. The packet cannot be accepted as usable while that boundary remains.

The new End test manually changes run state/completion but keeps generation1. The many-manifest fixture also leaves the mutable generation1 across129 manifest keys. These are meaningful saved-reader/SQL fixtures but omit the actual command-generation mutation and cannot prove the claimed quality/End retained-data chain.

Smallest proposed correction (not implemented; coordinator requested user approval): historical reads retain exact original sealed grant/fact authority and named manifest, allowing only a positive recorded generation no later than the current run. Keep exact original action/auth/receipt generation comparisons, scope/run/clock/epoch/content/checksum/consent/retention/deletion predicates, and every current command/provider ownership fence. No new store or runtime generation relaxation. A regression must advance the real saved run generation as production reconfigure and Stop do, then assert old authorized values remain readable; actual coordinator mutation and saved-reader fixture boundaries must be explicit.

The same cause is visible in historical ActivityHistoryComposer548, CellHistoryComposer576, WifiHistoryComposer807, captured Activity/Cell maintenance and PressureFactProofAuthenticator448. Ownerless Steps SHADOW WAL maintenance also compares mutable run generation but is a distinct maintenance path. Current session/current run shape checks and original action==WAL/fact generation checks remain correct and must not be relaxed. Portable archive snapshots need separate semantics review because their run snapshots may intentionally store original generation.

## Remaining static observations

- Optional missing/negative accuracy is no longer a condition for ordinary native Location. Canonical matching and purpose checks remain before acceptance; the radio adapter retains its accuracy condition.
- V2 uses finite negative optional values and finite altitude, avoiding the V1 NaN/Infinity SQLite-representation ambiguity.
- Session/window summaries use strict measured-time/id keyset pages of256 with monotonic progress, constant-memory distance anchor and cancellation. No4096 total summary cap is introduced. Exact-key manifest queries use bounded arguments and validate complete named membership; mismatched run keys do not borrow another run's authority.
- Failure after earlier pages preserves a partial fix/distance prefix. Missing coordinates/authority or discontinuous sequence/clock/gaps do not bridge a distance. Real stationary zero stays zero; no measured pair stays null.
- New methods directly exercise Room qualification, summary, ordinary History and all-day status. Sensor delivery, original service acceptance, current corrections, visible Tracker/notifications/widgets and the complete six-source chain remain outside this slice. New summary has no live consumer yet.

Disposition: BLOCKED on the concrete historical-generation boundary. Source/test authoring and optional-value/keyset structure were reviewed; no execution/pass/completion claim.
