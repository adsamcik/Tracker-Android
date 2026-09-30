# R13 retained radio/Location context — IMPLEMENTED_UNVALIDATED

## Boundary

This receipt records production and focused test-source authorship, plus static self-review.
No compilation, Room schema validation, tests, lint, Hilt, Android/provider activation, device,
battery, CI, integration or release gates were executed. It does not declare assembly complete.

## Approved behavior implemented

- `RadioLocationContextRepository` reads native Wi-Fi/Cell candidates, their independently
  authorized purposes and qualified retained Location endpoints in one bounded Room transaction.
  The API/implementation has no provider, acquisition, network or repair dependency.
- Capture reuses the existing authenticated source history readers. Background observations
  authenticate their complete correction/aggregate-owner lineage, immutable provider and demand
  snapshots, historical consent, retention approvals, deletion footprints, data epoch and floor.
  Both interval authorization snapshots and a later callback snapshot are reconstructed from
  immutable demands. A coverage reference cannot retain a deleted, stale or expired aggregate.
- Captured discovery includes both aggregate and unchanged coverage-only deliveries. Signal
  summaries come from the authenticated history observation, including its resolved aggregate.
  Optional Location matching uses the accepted coverage start/end elapsed times paired with the
  latest accepted child's wall time, rather than the raw batch's wider elapsed envelope.
- Ambient projectors already save the accepted provider-time minimum/maximum and corresponding
  wall time. Legacy unchanged summaries retain the writer's original consent/data/deletion cohort
  reuse contract; both referenced and current lineages qualify independently, without adding a
  new clock or plan restriction to those historical references.
- Future accepted Location points use the small retained receipt/purpose binding committed in
  `ac9f242199`. Canonical values, purpose binding, historical grant and lifecycle/retention fences
  qualify each purpose independently. Coarse accuracy is preserved. Blank old bindings remain
  unverified context; ordinary saved Location history and backups are not filtered by this reader.
- Wi-Fi uses exact or bracketing endpoints within 30 seconds across its full observed interval;
  Cell uses the preceding endpoint within 60 seconds across its full interval. Matching requires
  the same purpose and compatible session ownership, boot, elapsed clock and data epoch. The
  selected endpoint is an actual saved point, never an interpolated AP/tower coordinate.
- Existing five radio map views add neutral observed-device points and metric accuracy footprints.
  Native context never enters the existing AP/tower aggregation pipeline. The shared map lifecycle
  and cache receive Location as well as radio invalidation. Missing Location removes no radio data.
- Explicit API limits/gaps preserve independently qualified siblings. Whole-request storage or
  limit outcomes use existing map Failure. The layer's loaded/processed data retain per-purpose
  gaps; the user-facing partial-map notice remains a separately pending decision.

## Exact production paths

New files:

- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/di/RadioLocationContextDataModule.kt`
- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RetainedLocationContextReader.kt`
- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RetainedAmbientRadioAuthority.kt`
- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RetainedAmbientRadioOwner.kt`
- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RetainedRadioContextGroups.kt`
- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RoomRadioLocationContextRepository.kt`
- `feature/map/src/main/java/com/adsamcik/tracker/map/viz/RetainedRadioContextLayer.kt`

Moved matcher (namespace and source enum adapted, purpose scope retained in its output):

- Delete `feature/map/src/main/java/com/adsamcik/tracker/map/data/RadioLocationContextResolver.kt`
- Add `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RadioLocationContextResolver.kt`

Existing-file changes:

- `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/LocationPurposeDao.kt`:
  four appended `contextReceiptsOverWindow`, `contextReceipts`, `contextPurposes` and
  `contextRetentionApprovals` queries, plus the existing draft `savedFileExportAuthorityStamp`
  query required to fence a pending retention settlement. `savedFileChunk` and
  `latestRegistrationPlan` remain excluded; preserve other draft hunks.
- `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/WifiCapturedFactDao.kt`:
  only appended `contextCandidates` query. Preserve other draft hunks.
- `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/CellCapturedFactDao.kt`:
  only appended `contextCandidates` query. Preserve other draft hunks.
- `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/SourceDeletionFenceDao.kt`:
  only appended `contextFences` query.
- `feature/map/src/main/java/com/adsamcik/tracker/map/layers/registry/MapLayerEntryPoint.kt`:
  shared retained-context repository entry point.
- `feature/map/src/main/java/com/adsamcik/tracker/map/layers/registry/DefaultLayerRegistry.kt`:
  only five existing Wi-Fi/Cell factories now wrap their existing pipelines with retained context.
- `feature/map/src/main/java/com/adsamcik/tracker/map/presentation/MapStore.kt`:
  Location invalidates five existing radio views alongside their original source.
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiCapturedFactClassifier.kt`:
  classify malformed item shape before purpose-index omission, matching the existing retained
  WAL carrier's rejection counts. This fixes writer/history disagreement for partial v6 batches.

## Exact test-source paths

New test files:

- `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RetainedLocationContextReaderTest.kt`:
  real Room retained coarse binding without WAL/session; historical Off; canonical correction;
  old/imported point remains saved but does not gain enrichment authority; retention/full-clear;
  boot/purpose separation; bounded overflow.
- `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RetainedAmbientRadioAuthorityTest.kt`:
  genuine provider/demand/auth rows with mocked query edges; later deny callback; forged later
  callback; provider incarnation/lifetime/consent/demand/retention mismatch.
- `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RetainedRadioContextGroupsTest.kt`:
  dual purposes; disjoint native groups; unchanged owner deduplication and incompatible scope.
- `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RoomRetainedAmbientRadioContextTest.kt`:
  actual Room native ambient Wi-Fi/Cell provider/demand/consent/retention and coarse Location;
  exact unchanged aggregate owner; capture-only authority; incompatible consent; erased aggregate;
  expired owner and full-clear epoch. Legacy summary fixtures intentionally have no invented child
  detail or active session/provider request.
- `feature/map/src/test/java/com/adsamcik/tracker/map/viz/RetainedRadioContextLayerTest.kt`:
  actual map wrapper/pipeline; native/legacy separation; sibling limit preserves renderable points;
  unavailable Location; whole limit failure; endpoint deduplication; coarse, date-line and polar
  accuracy geometry.

Moved pure matcher test:

- Delete `feature/map/src/test/java/com/adsamcik/tracker/map/data/RadioLocationContextResolverTest.kt`
- Add `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RadioLocationContextResolverTest.kt`

Narrow methods in shared existing fixtures:

- `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/WifiHistoryRepositoryRoomTest.kt`:
  four methods starting `paired Wi-Fi map reader`, reusing actual captured source fixture/history
  composition in the same Room transaction; no Location, source limit, stale epoch, unchanged
  delivery and referenced-owner correction/deletion. The accepted-clock regression uses actual
  v6 WAL, children, rotated authorization and Room history; only the retained Location seam is
  mocked to expose its exact inputs and supply a known endpoint to the real matcher. Narrow
  `v6RotatedGroup`/`persistV6Children` fixture additions create a later malformed callback child
  while the same capture purpose stays authorized across its callback revision rotation. A deny-all
  callback cannot create the captured carrier, and is not used as positive test evidence.
- `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/CellHistoryRepositoryRoomTest.kt`:
  three methods starting `paired Cell map reader`, equivalent captured Cell coverage, unchanged
  delivery and referenced-owner correction/deletion. Preserve root's separate rotated-authorization
  fixture/test hunks.
- `feature/map/src/test/java/com/adsamcik/tracker/map/presentation/MapStoreTest.kt`:
  parameterized `optional context refreshes existing radio layers after Location correction or deletion` method for
  all five existing radio view IDs.
- `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/wifi/WifiCapturedFactClassifierTest.kt`:
  `per-reading selection preserves malformed counts and accepted clocks` keeps an omitted malformed
  sibling distinct from an omitted otherwise valid item and retains the accepted wall/elapsed anchor.

## Dependencies and remaining limits

- Already committed minimal retained Location binding and matching migration/containment defaults:
  `ac9f242199`. This read-only slice adds no entity, schema column, dependency or network path.
- Existing API: `stats/api/.../repository/RadioLocationContextRepository.kt`.
- Existing native capture composition, ordinary ambient summary/child readers and their composed
  source fixtures remain dependencies; broader preserved draft changes are not implicitly selected.
- Ordinary ambient native/imported history authority still needs the separately requested reader
  seam repair. R13 currently qualifies native ambient rows after ordinary structural composition;
  it does not make the unqualified ordinary history API authoritative by itself.
- Large unbounded date ranges can exceed the explicit 4096-observation/16384-dependency budget;
  this receipt makes no claim of unlimited map materialization or device performance evidence.
- User-facing partial map notice and the full independent R13 review remain pending. Source-authored
  tests must be compiled/executed only after the complete convergence input has been frozen.

## Retained child invalidation companion

The map observer now also watches native captured/background Wi-Fi and Cell
child tables. Exact child rows are dependencies of the retained reader; an
independent child correction or removal must invalidate existing radio layers
even when the parent revision is unchanged. The existing observer contract test
source covers these four source-specific tables. This companion adds no layer,
request, UI copy or provider demand. It remains IMPLEMENTED_UNVALIDATED.
