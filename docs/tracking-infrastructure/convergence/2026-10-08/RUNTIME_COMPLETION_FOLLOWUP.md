# October 8 practical runtime follow-up

Inspected selected candidate: `ab25a6dbfaab82d838a22ed97099cdd92bf2a2e7`.
IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Source tracing only; tests NOT_RUN.
T25 and all 25 full-chain prerequisites remain open.

## Confirmed implementation gaps

1. **Automatic sessions do not yet capture every supported selected source.**
   The current source bindings, coordinator checks and writers keep Activity,
   Pressure, Wi-Fi and Cell on manual-only capture. Location also lacks a
   catalog entry and its protected capture handoff checks a manual-only mask.
   Steps already has the prospective manual/automatic pattern. Existing Activity
   trigger availability can make Settings look operational even when the chosen
   capture lanes cannot start. These are implementation gaps; do not present them
   as Android hardware limits or permission repairs. Final automatic sessions must
   use the same selected-source flow. Original recorded masks, modes and products
   remain immutable. The smallest exact final binding/owner transition proposal is
   still under review; do not add a general upgrade engine or require users to erase
   their saved data to use automatic sessions.

   Additional root caller search found only declarations/wrappers of the native
   `installInertCandidate` / `activateCandidate` operations in production. The
   Steps full-clear rearm is called, but initial activation has no located caller.
   `RoomTrackingRolloutStateStore.load` creates an all-contained state with zero
   capture masks for a fresh database. The actual startup/Start chain is being
   independently traced before treating this as a final missing initializer.
   No provider/writer activation or production initialization decision is authorized
   by this source observation alone.

2. **Live Location rejects points without an accuracy estimate.**
   `LocationEvidenceNormalizer.isValidLocationEvidence` is shared by active and
   passive delivery and temporary UI freshness. Saved summaries already accept
   valid coordinates with unknown accuracy under the explicit human approval.
   Extending practical nullable optional metrics to live acquisition has been
   asked through the question tool and remains pending. Coordinate/time,
   permissions, Stop and freshness requirements remain necessary. Games with an
   explicit accuracy ceiling and optional Wi-Fi/Cell matching retain their actual
   accuracy requirements.

3. **Passive Location silently discards delivery items after 64.**
   `PassiveLocationDeliveryAdmission` uses `locations.take(64)` and the receiver
   ignores its overflow result. Remove the arbitrary total cap using cancellable
   work chunks within the existing five-second receiver deadline. Preserve original
   one-fix identities so retries cannot duplicate existing points. This routine
   delivered-data repair is being authored independently of the unresolved durable
   coverage-gap storage decision. The WAL recovers admitted events; it cannot recover
   coordinates that were never admitted. No raw tail inbox is currently approved.

4. **A scoped session erase can mislabel a surviving all-day Location copy.**
   Full History V2 chooses raw purpose-grant presence before retained qualification.
   An exact session deletion fence may reject that capture grant while its independent
   background grant remains valid. The accepted rendering repair must select the
   actual requested surviving purpose, keep capture-only subsets from borrowing
   background, and retain ordinary older/imported points with honest Unknown labels.
   Explicit deletion must never become unverifiable-but-visible native data. The
   fresh reviewer holds the full History selection until this narrow fix is composed.

## Necessary behavior already found in the shared owner

My location/Follow request their own two-second cadence and ten-second freshness;
games retain their own request cadence. Shared Location merges those temporary
leases after ordinary battery-plan resolution, choosing the relevant stricter
cadence/displacement/accuracy. No confirmed user-request battery ceiling defect was
found. Do not introduce a second Location owner or optimizer to solve that rejected
hypothesis.

## Completed narrow cleanup authorship

The committed released-data preservation already includes actual full-clear and
reopen test source for preserved raw tables and labels; those methods are NOT_RUN.
The direct trip-delete path still left a segment annotation after its parent was
deleted. `TRIP_ANNOTATION_CLEANUP_SELECTED` repairs the existing DAO transaction and
adds three actual Room fixture methods. Its independent exact source acceptance is
in `TRIP_ANNOTATION_CLEANUP_REVIEW/ACCEPTANCE.md`. Selection is held behind the current
History base composition. This does not close universal erase, source actions or
logical organization requirements.

## Pending product decisions

The existing four presentation questions (installed-widget cadence, Tracker moving
average, overlapping session names, Calendar month presence) remain unanswered.
The additional live Location optional-metrics question also remains unanswered.
Continue independent approved tracking work; do not interpret elapsed time as approval.
