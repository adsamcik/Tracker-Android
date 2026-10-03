# Native owner lookup boundary for imported radio products

Status: **READ_ONLY_SOURCE_TRACE**. Phase: **IMPLEMENTATION_ONLY**. No gates or Git mutations.
This trace proposes no new storage and does not approve a changed ownership rule.

## Concrete remaining failure

After the dedicated date query selects one legitimate imported radio record, qualification still loads
native owner cohorts across all saved history. Unrelated native history can exhaust that read budget
or contain a malformed owner and make the selected foreign record unavailable. Date isolation only
removes unrelated **imported** candidate payloads before qualification; it cannot remove this dependency.

## Wi-Fi source primitives

`tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/RoomImportedWifiProductEvaluator.kt`:

- `evaluateSafely` obtains `loadLocalOwnerSnapshot` before imported lineage materialization.
- `loadLocalOwnerSnapshot` uses `ImportedWifiDao.localEntryOwnerCount/localEntryOwnerPage`,
  `localRunOwnerCount/localRunOwnerPage`, `localObservationOwnerCount/localObservationOwnerPage`
  and `localDeletionOwnerCount/localDeletionOwnerPage`. It reads global native owner keys under
  `ImportedWifiProductReadBudget` (262144 total authority rows, 64 MiB estimated authority bytes,
  16384 queries by default, plus finite native key pages). These limits cover the whole read,
  not only native owners.
- `authenticateLocalOwnership` hashes raw logical session, service-run and logical-fact IDs through
  `PortableWifiOpaqueIdentity.derive`; all compatible colliding values must belong to one local origin.

`core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/ImportedWifiDao.kt`:

- `authorityOwners(identities, limit)` already filters exact imported entry/run/observation identities,
  source-fence scope digests and selected deletion protected owner/member identities.
- `selectedDeletionProtectedIdentityOwners`, `deletionFenceIdentityOwners` and related entry/run
  deletion lookups already support bounded exact stored opaque values; they cannot recover a live
  native session/run/fact with no retained selected-deletion relation.
- Native owner columns store raw `logical_tracking_id`, `service_run_id` and `logical_fact_id`, not
  their portable opaque hash. The observation union includes relevant retained fact/cursor identities.

## Cell source primitives

`core/base/src/main/java/com/adsamcik/tracker/shared/base/database/ImportedCellProductReader.kt`:

- `evaluateCandidatesWithBudget` calls `loadScanSnapshot` for a relevant batch.
- The snapshot queries `ImportedCellDao.liveLogicalTrackingIds`, `liveServiceRunOwners`,
  `liveCellFactOwners`, `liveCellCompletenessOwners` and `liveCellDeletionGenerations`, each with
  `MAX_LIVE_OWNER_ROWS + 1` (4097). Any relation exceeding 4096 rejects qualification explicitly.
- It hashes raw owner keys in Kotlin to match imported entry/run/fact/deletion-scope opaque identities.
  The logical claims also include zero-observation/gap completeness and deletion owners.
- `authenticateOwnership` additionally uses existing `opaqueOwnerProbe` on exact graph identities,
  chunked by `MAX_IDENTITY_QUERY_CHUNK` (256), with explicit bounded owner lookahead.

`core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/ImportedCellDao.kt`:

- `opaqueOwnerProbe`, `existingEntryIdentities`, `existingRunIdentityOwners`,
  `existingRunScopeOwners`, `existingObservationIdentityOwners`, `sourceFenceOwners` and imported
  deletion receipts already have exact stored opaque predicates.
- Native current session/run/fact/completeness/deletion owner columns are raw IDs. They do not store
  the reverse relation needed to select only the imported graph's opaque values.

## What can be reused and what remains undecided

Imported authority, source fences and selected-deletion lookups can continue using their existing
exact `IN` predicates without changing scope. SourceSession/Fact DAOs can fetch exact **known raw**
native owner IDs, but portable imported headers carry their hashed identities rather than those raw IDs.
Putting opaque values in a raw-ID `IN` query would silently miss real native duplicates/collisions.
The current schema offers no general indexed portable-identity-to-live-native-owner inverse lookup.

A final source design must retain all protected collision/ownership behavior while removing the
global raw-key census. The coordinator is reconciling this with the still-pending overlapping-file
membership lookup decision. No new reverse table/index, SQLite hash function, global hash scan,
date-inferred duplicate lookup or relaxed native collision invariant was implemented in this slice.
Required future scenarios include one selected imported record beside >4096 unrelated native owners,
a genuine selected native duplicate, cross-kind owner conflict, zero-observation run ownership,
selected deletion/fence ownership and correction/deletion retention of exact native membership.
