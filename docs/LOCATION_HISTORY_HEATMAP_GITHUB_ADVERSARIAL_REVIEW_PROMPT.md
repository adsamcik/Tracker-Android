# GitHub-grounded adversarial review: Tracker location-history heatmap

You are a principal adversarial reviewer specializing in geospatial inference, temporal reconstruction,
Android location evidence, Room/SQLite, Kotlin Coroutines/Flow, MapLibre Native, mobile performance,
privacy, and local-first product design.

Your job is to review the **actual linked implementation**, not merely the design narrative. Try to
disprove its correctness, find unsafe assumptions and contradictory architectures, remove unjustified
complexity, and produce an implementation-ready correction plan for a separate coding agent.

## Repository and immutable review target

Review this exact commit:

- [Commit `00fe627acedb4f9aa3d43ffd0408ec4ae9a02228`](https://github.com/adsamcik/Tracker-Android/commit/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228)
- [Repository tree at the reviewed commit](https://github.com/adsamcik/Tracker-Android/tree/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228)
- [Complete comparison against its parent](https://github.com/adsamcik/Tracker-Android/compare/6c25dab52...00fe627acedb4f9aa3d43ffd0408ec4ae9a02228)

The repository is public for purposes of this review. Browse the code through GitHub. Follow imports,
call sites, entity/DAO relationships, migration paths, feature registration, tests, and configuration.
Do not mark a conclusion “needs repository validation” when the linked repository can answer it.

When reporting a code finding:

1. Link directly to the relevant GitHub file and line range at the immutable commit.
2. Describe the concrete execution path or invariant violation.
3. Assign severity `P0` through `P3`.
4. Give the smallest safe correction.
5. Name the test that would prove the correction.

If GitHub cannot expose required generated/dependency behavior, say precisely what remains unverified.
Do not claim to have run the application or physical-device benchmarks.

## Product objective and agreed direction

Tracker is a single-user, local-first Android application. The intended feature is an attractive,
smooth **Location history heatmap** showing where retained location evidence spatially supports time.

The intended first-release direction is:

- raw observations and tracker-state evidence are the rebuildable source while retained;
- heatmap computation happens only when the user opens the feature;
- no multiresolution analytical grid, contribution ledger, generation, checkpoint, background
  backfill, or render raster is persisted for the new path;
- users who never open the feature pay no heatmap-specific derived-storage or background-CPU cost;
- a small byte-bounded memory-only LRU may be added after correct source-version invalidation exists;
- each usable interval contributes exact duration once through one normalized compact spatial kernel;
- uncertainty changes spatial spread, never canonical duration;
- sparse travel gaps remain unresolved: no endpoint dwell, straight-line interpolation, road snapping,
  or invented route;
- the final view evaluates the analytical kernels into a world-aligned scalar raster and displays it
  through MapLibre `ImageSource`/`RasterLayer`, with no second KDE or viewport normalization;
- deleting raw history means the corresponding heatmap history disappears unless the product later
  offers a separate, explicit, sensitive frozen-summary option.

User-facing language must avoid “observed presence,” “stationary,” “dwell,” coverage percentages,
partitions, generations, checkpoints, backfill, covariance, or cache status. Useful copy should be no
more technical than:

- “Warmer areas indicate more time supported near that area.”
- “Broader areas mean the recorded location was less precise.”
- “Some time isn’t shown because location was unavailable or too imprecise.”

## Important state of this commit

This commit contains **both** the older persisted-grid implementation and the first reversible slice
of the new raw/on-demand design. That coexistence is intentional for migration safety, but it may also
be internally contradictory or too risky to merge.

The new slice adds a pure `SPATIALLY_SUPPORTED_TIME_V1` estimator and disables retention-triggered
legacy compaction by default. It does **not** yet connect the pure estimator to Room or to a raster
renderer. The current visible layer still reads/materializes the older persisted representation and
renders cell polygons.

Determine exactly which code is active, dormant, unreachable, user-triggered, background-triggered,
or merely a future contract. Do not review the pure estimator as though it were already the production
map path.

## Required entry points

Start with these files, then follow their dependencies and call sites throughout the repository.

### Canonical semantics and pure estimator

- [Canonical V1 contract](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/docs/LOCATION_HISTORY_HEATMAP_CANONICAL_CONTRACT.md)
- [`SpatiallySupportedTimeEstimator`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/stats/engine/src/commonMain/kotlin/com/adsamcik/tracker/stats/engine/heatmap/SpatiallySupportedTimeEstimator.kt)
- [Estimator tests](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/stats/engine/src/commonTest/kotlin/com/adsamcik/tracker/stats/engine/heatmap/SpatiallySupportedTimeEstimatorTest.kt)

### Raw evidence and tracking lifecycle

- [`LocationObservation` entity](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/LocationObservation.kt)
- [`LocationObservationDao`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/LocationObservationDao.kt)
- [`LocationObservationAdapter`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/pipeline/LocationObservationAdapter.kt)
- [`LocationCollectionTrigger`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/component/trigger/LocationCollectionTrigger.kt)
- [`FusedLocationCollectionTrigger`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/component/trigger/FusedLocationCollectionTrigger.kt)
- [`TrackerRun` entity](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/TrackerRun.kt)
- [`TrackerRunDao`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/TrackerRunDao.kt)
- [`PersistenceProcessor`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/pipeline/persistence/PersistenceProcessor.kt)
- [`SignalSerializer`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/tracker/engine/src/main/java/com/adsamcik/tracker/tracker/pipeline/persistence/SignalSerializer.kt)

### Database, legacy derivatives, and migration

- [`AppDatabase`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabase.kt)
- [Database migrations](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabaseMigrations.kt)
- [Exported Room schema 36](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/36.json)
- [`PresenceCompactor`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/analysis/PresenceCompactor.kt)
- [`MetricAnalysisGrid`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/analysis/MetricAnalysisGrid.kt)
- [`PresenceInterval`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/PresenceInterval.kt)
- [Grid/generation entities](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/PresenceAnalysisEntities.kt)
- [`PresenceAnalysisDao`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/PresenceAnalysisDao.kt)
- [`LegacyPresencePersistence` rollout gate](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/analysis/LegacyPresencePersistence.kt)
- [Migration tests](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/test/java/com/adsamcik/tracker/shared/base/database/Release2024_1MigrationTest.kt)
- [Compactor tests](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/core/base/src/test/java/com/adsamcik/tracker/shared/base/database/analysis/PresenceCompactorRobolectricTest.kt)

### Current repository and map path

- [`ObservedPresenceRepository` contract](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/stats/api/src/commonMain/kotlin/com/adsamcik/tracker/stats/api/repository/ObservedPresenceRepository.kt)
- [`DefaultObservedPresenceRepository`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/DefaultObservedPresenceRepository.kt)
- [`ObservedPresenceLayer`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/layers/impl/ObservedPresenceLayer.kt)
- [Layer registry](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/layers/registry/DefaultLayerRegistry.kt)
- [`MapStore`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/presentation/MapStore.kt)
- [`MapLibreLayerConfig`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/presentation/bridge/MapLibreLayerConfig.kt)
- [`MapScreen`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/java/com/adsamcik/tracker/map/ui/MapScreen.kt)
- [User-facing strings](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/map/src/main/res/values/strings.xml)

### Retention, privacy, and research export

- [`RetentionPipelineWorker`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/app/src/main/java/com/adsamcik/tracker/app/maintenance/RetentionPipelineWorker.kt)
- [`DataRetentionWorker`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/app/src/main/java/com/adsamcik/tracker/maintenance/DataRetentionWorker.kt)
- [Android manifest](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/app/src/main/AndroidManifest.xml)
- [Privacy policy](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/privacypolicy.md)
- [`ResearchTracePayloadExporter`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/import-export/src/debug/java/com/adsamcik/tracker/impexp/exporter/research/ResearchTracePayloadExporter.kt)
- [`EncryptedResearchTraceExporter`](https://github.com/adsamcik/Tracker-Android/blob/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/feature/import-export/src/debug/java/com/adsamcik/tracker/impexp/exporter/research/EncryptedResearchTraceExporter.kt)
- [Research trace tooling](https://github.com/adsamcik/Tracker-Android/tree/00fe627acedb4f9aa3d43ffd0408ec4ae9a02228/tools/research-trace)

## Non-negotiable invariants to verify in code

1. `supportedMs + unresolvedMs == union(valid tracker-active spans intersecting the query)` exactly.
2. Duplicate or more frequent sampling cannot increase canonical duration.
3. Informative evidence may move duration from unresolved to supported but cannot change the total.
4. Same-time alternatives receive one temporal assignment; mixture weights, if introduced, sum to one.
5. Sparse observations cannot assign an entire journey to endpoints or invent a route.
6. Reboot, wall-clock changes, late batches, overlapping runs, orphan runs, and mutable run rows cannot
   silently duplicate, lose, or bridge time.
7. Mock, stale, invalid, rejected, missing-accuracy, and uncalibrated approximate evidence cannot become
   precise heat.
8. Placeability and canonical coverage are independent of zoom, cell size, viewport, and feature budget.
9. Every analytical kernel is normalized; rasterization/display cannot add another broad kernel.
10. Panning, zooming, viewport clipping, progressive publication, and color mapping cannot renormalize
    the analytical field.
11. Retention cannot publish, cache, or resurrect pre-deletion evidence after deletion wins the race.
12. A user who never opens the heatmap receives no heatmap-derived storage growth or background work.
13. User-facing text does not overstate stationary time, certainty, completeness, or accuracy.

## Specific contradictions and hypotheses to attack

Do not accept these statements; verify or refute them from the linked code:

- The pure estimator requires a canonical timeline and clock-domain identity, but `tracker_run` may
  contain only mutable wall-clock bounds and no boot identity or elapsed-time bounds.
- `location_observation` records elapsed acquisition time, but no durable boot identifier may make it
  ambiguous across reboot.
- The old compactor uses midpoint support for adjacent fixes up to five minutes apart and a fixed
  isolated window; this may still paint travel endpoints.
- The old spatial model uses `1.42 × accuracy`, fixed 50 m/500 m floors, per-resolution eligibility,
  centre-sampled 3×3 Gaussian weights, and six persisted grid levels without calibration evidence.
- The default-off legacy gate stops retention-triggered compaction, but opening the current layer may
  still call `PresenceCompactor` and persist intervals/cells/generations.
- Raw retention now deletes raw observations directly when legacy background compaction is disabled,
  but older derived tables may survive and retain sensitive history.
- The current layer uses cell polygons and fixed cell-resolution selection, not the intended original-
  kernel scalar raster.
- The public/internal `ObservedPresence*` API may keep misleading semantics alive despite corrected UI
  strings.
- Room schema 36 may already make the rejected persisted design a release migration even though the
  replacement path does not use it.
- A global insertion high-watermark is insufficient if tracker-run rows are updated in place.
- Retention and interactive computation do not yet share a publication epoch/gate.
- `allowBackup=false` and `fullBackupContent=false` may not fully specify Android 12+ device-transfer
  exclusions.
- Research export may expose more precise or identifying evidence than its UI, encryption, password,
  temporary-file, logging, and deletion behavior communicates.

Find additional contradictions that this list misses.

## Required end-to-end traces

Trace these paths through actual code and identify every write, transformation, clock, filter, and race:

1. Android `Location` callback → batch metadata → `TrackingSignal` → serialization/WAL → replay → raw
   `location_observation` and accepted `location_sample`.
2. Tracker start/policy transition/process death/restart/stop → `tracker_run` creation and mutation.
3. Map layer selection/date change/camera change → repository → compaction/query → map configuration →
   MapLibre rendering and accessibility text.
4. Retention setting → WorkManager → raw/derived deletion → late observation/replay behavior.
5. Debug research export request → database snapshot → encryption/output → temporary and external files.

For each trace, report whether ordering, transactionality, cancellation, source identity, privacy, and
failure recovery are actually sufficient.

## Edge cases the review must simulate conceptually

- stationary overnight with GNSS drift and both dense and sparse sampling;
- one fix in a long active run;
- walking, cycling, driving, rail, ferry, underground, and urban-canyon gaps;
- duplicate observations, conflicting equal timestamps, late old fixes, and batched callback replay;
- overlapping runs, open/orphan runs, process death, reboot, manual clock change, and timezone/DST change;
- approximate-only permission, abrupt accuracy changes, mock locations, and rejected outliers;
- antimeridian, high latitude, wide uncertainty, and viewport-edge overlap;
- dominant home hotspot plus many short visits;
- rapid pan/date changes and cancellation during a year/all-time request;
- retention, process death, and late WAL replay during computation/publication;
- deletion followed by database reopen, WAL checkpoint, backup restore, or device-to-device transfer;
- a user who tracks for years but never opens the heatmap.

## Required external research

Use current primary sources for claims not settled by the repository:

- Android `Location`, elapsed realtime, batching, mock status, and permission precision;
- Room migrations and SQLite WAL/checkpoint/deletion behavior;
- Android backup and device-to-device transfer rules;
- WorkManager semantics;
- Kotlin Flow cancellation;
- MapLibre Native Android `ImageSource`, `RasterLayer`, raster interpolation/fade, and resource lifecycle;
- peer-reviewed smartphone GNSS calibration and mobility-trace privacy research.

Link sources adjacent to the claims they support. Do not fabricate measurements or quote secondary
articles when official documentation or primary research exists.

## Required output

Return one self-contained Markdown report in this order:

1. **Merge/continue verdict** — safe foundation, conditional, or stop-and-rework.
2. **Repository evidence map** — actual active and dormant paths, with GitHub links.
3. **Ranked findings** — `P0`–`P3`, each with file/lines, execution path, consequence, correction, test.
4. **Invariant audit table** — each non-negotiable invariant, pass/fail/unknown, and evidence.
5. **End-to-end trace audit** — the five required traces.
6. **Pure estimator review** — temporal partitioning, deterministic grouping, conflicting evidence,
   kernel-policy boundary, overflow, antimeridian, clock domains, and property-test gaps.
7. **Raw evidence adequacy** — exact missing fields or lifecycle evidence required before Room wiring.
8. **Database/migration verdict** — keep, quarantine, rewrite, or remove each V36 table and migration.
9. **Retention/privacy verdict** — precise deletion meaning, races, backups, exports, WAL/SHM, and old
   derivatives.
10. **Map rendering replacement** — concrete CPU scalar-raster + MapLibre image/raster design based on
    the repository’s actual APIs and dependency versions, without a second KDE.
11. **Performance/storage model** — formulas and physical-device measurements required; no invented
    benchmark numbers.
12. **Test and falsification matrix** — unit, property, migration, concurrency, rendering, privacy,
    and device benchmark tests.
13. **Ordered patch queue** — small reversible implementation slices with exact files, dependencies,
    migration implications, rollback, and exit criteria.
14. **Delete/defer list** — code and concepts that should not survive into the first release.
15. **Product decisions only** — the smallest set of decisions engineering cannot make safely.
16. **Implementation handoff** — the first three patches the coding agent should make next.

## Review discipline

- Browse and cross-reference the code; do not merely summarize this prompt.
- Be adversarial, specific, and willing to recommend deletion.
- Distinguish defects in active production paths from dormant prototype debt.
- Prefer unresolved output over invented precision.
- Prefer a small pure model and disposable rendering over persistent acceleration without measurements.
- Treat every location derivative and export as sensitive.
- Do not recommend a server, telemetry, cloud processing, online routing, or mandatory OSM dependency.
- Do not recommend ML without a concrete on-device advantage, calibration plan, and deterministic
  fallback.
- Do not expose internal engineering terms to users.
- Do not silently reinterpret old derived records under a new model version.
- Do not propose destructive schema cleanup until raw-source availability and migration history are
  proven.
- End with a direct answer: **What should the implementation agent change next, and what must it not
  build yet?**
