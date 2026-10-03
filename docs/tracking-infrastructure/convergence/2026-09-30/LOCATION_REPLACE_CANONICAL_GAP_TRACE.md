# Location Keep/Replace canonical correction trace

Status: **SOURCE_INSPECTED — IMPLEMENTATION_PENDING**, 2026-10-03.
Implementation-only: no gates, provider activation, Git mutation or production edit in this pass.

## Settled requirements

The user approved a warning before importing conflicting saved point IDs; one **Keep existing**
or **Replace with file** choice applies to all conflicts. Identical points skip. Structurally
valid owner-edited files are accepted; imports grant no collection permission. File bytes,
original dataset epoch and reviewed saved state remain bound to the choice.

The parent explicitly rejected forking/re-ID/archive rebinding: replacing a point must not
leave an additional old copy after the user chose Replace. The smallest storage shape is a
canonical edit retaining the existing saved sample and portable point IDs. No new framework
or proof hierarchy is proposed.

## Current exact chain

- `feature/import-export/.../importer/file/LocationFileImport.kt:69-85` compares complete file
  digest, original epoch, point count, conflict count and saved-state hash before writing.
  Lines 83-86 deliberately reject `REPLACE_WITH_FILE` when conflicts exist. Lines 147-203
  write at most 256 points per Room transaction, recheck exact expected old measurements and
  epoch, skip matching/Keep points and otherwise reject replacement.
- `LocationFileImportPreview.kt:75-138` validates every point and builds conflict/matching
  counts plus a saved-measurement state digest. `LocationPointIdIndex` keeps whole-file ID
  uniqueness and per-batch old measurement expectations on disk.
- `DataImporter.kt:155-174` already transports the bulk choice and preview into work input.
  `ImportWorker.kt:160-179` checks the selected Location file job digest and reconstructs the
  reviewed state with the original data epoch. These admission/worker methods are owned by
  the product agent and were not edited here.
- `app/.../settings/data/DataSettingsScreen.kt:377-403` exposes only Keep/Cancel; the approved
  Replace action is absent. Default/Czech `tracking_portable_export_strings.xml` conflict
  message currently asks whether to Keep and has no Replace label.
- `core/base/.../dao/LocationFileIdentityDao.kt` provides exact portable/sample ID lookups,
  immutable archive membership and local ID assignment. `LocationFileIdentityEntity` has
  one unique sample per portable ID and descriptive imported/origin fields. The existing
  `LocationSampleDao` supports update through `BaseDao`; no schema is needed just to update
  measurement values at the same saved point ID.

## Native replay and reader dependencies

`LocationFileImportMapper.toImportedSample` honestly produces file measurements without
native event/signal/clock fields. Directly updating a native row with it would therefore
remove the signal that the terminal writer uses to find the originally accepted sample:

- `tracker/engine/.../source/location/ProtectedLocationCanonicalHandoff.kt:1449-1513` looks
  up an accepted point by its canonical source signal and compares original observation,
  decision and canonical sample content to its committed terminal receipt. Missing signal
  becomes pending/missing; changed content becomes integrity mismatch.
- `ProtectedLocationAmbientCanonicalWriter.kt:187-203` similarly requires an accepted sample
  to exist under its native signal and match its original terminal content digest.
- `core/base/.../data/LocationAcceptedReceiptIntegrity.kt:60-69` binds native proof to exact
  sample identity, signal, event, original clock/time and canonical measurement bytes.
- `stats/data/.../repository/RetainedLocationContextReader.kt:73-83` rejects mismatched
  canonical content. Edited coordinates therefore cannot borrow old native map-enrichment
  authority, provided no new sealed native proof is fabricated.
- `UnifiedHistoryRangeReader` and native compatibility reader preserve ordinary saved
  Location with an honest unqualified/imported presentation. The range invalidation table
  list includes `location_sample`, accepted receipts, purpose rows and file identity/archive
  membership. Optional radio map qualification also rechecks canonical content.

No existing explicit user-edit terminal disposition was found in those capture/ambient
writer paths. A replacement needs a narrow coherent replay disposition so an intentional
edit is neither reconstructed from the original event nor treated as an endless failed
native write. Keeping or removing original signal fields alone does not provide that.

## Derived values and exact remaining gap

Location import currently writes no session metrics or awards. Existing derived data is
stored separately:

- `core/base/.../data/SessionSegment.kt:42-49` persists distance; original session/run and
  source facts remain separate from the file point. There is no point-membership correction
  method in this entity/DAO path.
- `core/base/.../aggregator/DailySummaryAggregator.kt:144-180` repairs daily totals from
  segment/source totals. Replacing coordinates alone does not repair segment distance.
- `stats/data/.../repository/DefaultTripRepository.kt:37-45,78-86` presents saved Trip distance.
- `domain/points/.../work/PointsWorker.kt:48-80` awards from saved Trip distance/duration,
  with no per-point correction/retraction link. Its comment explicitly avoids using
  wall-time overlap as exact Location ownership.

The pass found no reusable Location canonical edit-to-affected-session/derived retraction
operation. Source-level Steps/Activity/Pressure/radio deletion adapters cannot be copied
as a Location edit framework, nor can an arbitrary coincident session be edited by time
overlap alone. Updating the canonical measurement without addressing native replay and
dependent distance/summary/reward behavior would leave the approved all-conflict flow partial.

The coordinator confirmed that correction/deletion retraction and consistent dependent
metrics/effects are already approved. Do not ask whether replacement should update route
distances, summaries or rewards again. The outstanding implementation is the smallest exact
canonical-edit replay disposition and affected-data repair using actual source membership.
This trace does not approve a new storage design or a replacement that leaves an old copy.

No Replace UI or imported-only replacement shortcut was authored. Required future source
cases include bulk mixed conflicts/identical points, saved/file/epoch races, exact archive
membership after edit, native replay after edit, independently retained source siblings,
map/history invalidation, derived correction/retraction and batch rollback/retry. No executed
test, native lifecycle, UI or readiness claim is made.
