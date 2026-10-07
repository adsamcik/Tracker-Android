# Retained Location lifecycle boundary

Status: `IMPLEMENTED_UNVALIDATED`. Source inspection and test authorship only;
all tests are `NOT_RUN`. No compilation, Room generation, validation, provider
activation, Git mutation or integration was performed by this author.

## Required behavior and existing ownership

Saved Location remains user data after End or Turn off. Each independently
authorized capture/background purpose has its own erase and retention boundary.
Optional radio map context must use that same saved qualification and must never
request Location. Unknown older origin remains usable saved data, without being
upgraded to native recording permission or authority.

The existing implementation already distinguishes saved products from an exact
original live acquisition chain. They should remain separate qualifications.
No new reader interface, proof store, lineage field, authority rule, or runtime
behavior is added by this packet.

## Original chain diagnostic

1. `app/.../di/TrackingInfrastructureIntegrationEntryPoint.kt` exposes
   `sourceLifecycleProofCoordinator()` for instrumentation crossing the real
   production Hilt singleton graph. Static caller inventory found no Dashboard,
   Tracker, notification, widget, history or map consumer of the coordinator's
   run-key `observe` or the original `SourceProofKey` contract.
2. `tracker/engine/.../source/lifecycle/SourceLifecycleProofCoordinator.kt`
   reconstructs original Recording, Materialized and Queryable stages. It keeps
   source failures isolated and propagates the exact missing/deleted result.
3. `DefaultSourceLifecycleProofAdapter.recordingLocation` calls the original
   `LocationWalQualificationAdapter`. `materializedLocation` requires the original
   WAL, observation, decision, writer binding/owner and canonical projection
   receipt. `missingRecordingEvidence` handles authoritative deletion/retention
   records; otherwise it reports `Pending(EVIDENCE_NOT_FOUND)`.
4. `stats/data/.../DefaultSourceFactQueryRepository.kt`
   (`RoomSourceFactLookupReader.lookupLocation`) also requires original WAL,
   decision and projection lineage, then delegates presentation membership through
   `DefaultTrackingHistoryRepository.lookupSourceFact`.

These stages intentionally do not borrow a retained point to reconstruct an old
provider registration, projection cursor or writer generation. Their missing
original evidence is not a verdict that independently saved Location was lost or
is unusable. This packet leaves diagnostic state behavior unchanged.

## Saved product chain

1. Already qualified `ProtectedLocationCanonicalHandoff.recordLocationCapturePurposeInCurrentTransaction`
   and `ProtectedLocationAmbientCanonicalWriter.recordLocationAmbientPurpose`
   save the same canonical sample plus a sealed accepted receipt and each exact
   purpose grant in their existing transaction. Replay compatibility preserves
   older blank markers; it never manufactures retained proof for an old row.
2. `LocationAcceptedReceiptIntegrity` verifies saved canonical content and each
   historical purpose independently. The receipt deliberately does not contain
   original writer owner/binding generation or cursor state.
3. `RetainedLocationContextReader.qualifyInTransaction` is the source-owned reader
   shared by saved consumers. It reads bounded candidates and dependencies in its
   caller's Room snapshot. It verifies receipt/sample, dataset epoch/deletion
   high-water, uncertain retention lower bound, historical policy/consent and the
   purpose's exact instant. Capture also verifies run/manifest membership and its
   exact deletion fence; background verifies its retained approval. Later policy
   Off does not replace the old grant. One purpose's failed grant does not become
   another purpose's authority. Read failures/limits remain typed and no read
   repairs, upgrades or deletes data.
4. `UnifiedHistoryNativeReader` uses this reader for native session Location;
   `UnifiedHistoryRangeReader` reaches it through
   `RoomAllDaySourceStatusQuery.qualifyLocationInTransaction` for ordinary
   sessionless day history. Unknown or unsealed saved points remain honestly
   labelled `LEGACY_UNVERIFIABLE`, subject to existing erase/import membership.
5. `RoomAllDaySourceStatusQuery.readLocation` reads bounded receipt candidates,
   uses the same qualifier and returns only native background grants. Its public
   `AllDaySourceStatusQuery` binding is in `StatsDataModule`. The coordinator's
   separate `observeAllDay` uses this saved reader and invents no WAL stages.
6. `RoomRadioLocationContextRepository` qualifies independently saved radio data,
   then calls `RetainedLocationContextReader.readInTransaction` in the same bounded
   Room snapshot. `RadioLocationContextResolver` preserves exact compatible
   purpose/run/dataset/clock rules and the approved temporal windows. It has no
   provider dependency and does not infer AP or tower coordinates.
7. `LocationAmbientCleanup` removes only background purpose associations and
   deletes canonical samples only when no independently retained purpose remains.
   Capture deletion uses its exact scope fence; global dataset clear and retention
   remain independent authority in the evidence guard.

## Added scenarios

`RetainedLocationPurposeLifecycleRoomTest` uses real Room with an actual finalized
segment/run, complete checked manifest, historical capture/background policies and
consents, one canonical sample and both sealed exact-instant grants. No WAL or
provider registration is installed. Nine new source methods cover:

- End retains both saved purposes without the original WAL or live registration.
- A later Off policy/consent leaves the old authorized grants unchanged.
- Capture scope erase preserves independently valid background context.
- Background-purpose removal keeps the same sample under its capture grant.
- A damaged capture grant cannot discard or borrow the valid background sibling.
- Older blank proof remains unchanged and receives no native context authority.
- Dataset deletion high-water fences both purposes; the read is still nonmutating.
- A newer dataset epoch independently rejects both original grants.
- Retention uses the original uncertainty lower bound for both purposes.

## Honest remaining scope

This reconciles the specifically listed run-only-WAL issue without rebuilding
original acquisition proof. It does not close all of T12, prove arbitrary reader/UI
composition, implement the pending Location correction record, or supply executed
tests or device evidence. Count-domain/effect retraction, lifecycle maintenance and
full convergence remain separate planned work. A future UI must use saved product
readers for saved-data availability, not the integration diagnostic stages.
