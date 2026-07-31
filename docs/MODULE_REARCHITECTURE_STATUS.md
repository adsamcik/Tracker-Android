# Module Rearchitecture — Status & Roadmap

> Branch: `dev/v10`
> Last updated: 2026-07-31

This document records the module rearchitecture currently present on `dev/v10`.
The Gradle project contains 32 application modules (including API contract modules), plus
the `:tools:ski-data-generator` tooling module. The `build-logic` included build is
separate from that count.

## Module layout

```
:app                         root NavHost, AppGraph DI, settings, onboarding
:core:base                   Room DB + DAOs + Room @Entity types + entity↔model mappers (was sbase)
:core:model                  pure Room-free domain models (Location, Trip, LocationSample, SkiRunSegment, …)
:core:common                 foundation leaf: concurrency, time, constant, exception, di, graph, io, logging, notification, service
:core:ui                     shared Compose UI + AppTheme (was sutils)
:core:diagnostics             payload-free diagnostic contracts and Tracebox boundary
:core:network                network helpers (was network)
:core:sqlite-runtime         bundled SQLiteX runtime + AndroidX SupportSQLite adapter
:core:testing                test fakes + utilities (was testing-common)
:data:preferences            preferences / settings / retention (was spreferences)
:stats:api                   stats contracts — depends on :core:model, NOT the DB (was stats-api)
:stats:engine /:stats:data   algorithms / data layer (were stats-engine / stats-data)
:domain:points /:domain:osm  points calc / OSM lookup (were points / osm)
:tracker:api                 tracking contracts (TrackerServiceController, LockManager, BackgroundTrackingApi, …; directory `tracker/api-module`)
:tracker:control             pure Kotlin tracking evidence, state, and decision reducer
:tracker:engine              tracking impl (service, pipeline, producers/consumers — no UI)
:feature:tracker             tracking Compose UI and notification customization
:sensor:activity-api         activity-recognition contracts (ActivityRequestManager interface, request/transition types)
:sensor:activity             activity recognition impl (Google Play Services)
:feature:activity            activity-type management Compose UI
:feature:map (+ :api)        MapLibre visualization + route contracts (was map)
:feature:statistics (+ :api) session list / trip detail / analytics + route contracts (was statistics)
:feature:dashboard (+ :api)  dashboard / live stats / widgets + route contracts (was dashboard)
:feature:game (+ :api)       challenges / goals / gamification + route contracts (was game)
:feature:import-export       GPX/KML/JSON/SQLite import-export (was impexp)
:domain:geocoder             offline place/geocoder support
```

## Delivered migration phases

The phases below describe the completed structural migration. Current build and test commands
are maintained in the root `README.md` and `.github/context/DEVELOPMENT.md`.

- **Phase 0 — cleanup.** Deleted the orphan `:smap` placeholder module.
- **Phase 3 — regroup & rename.** Renamed all unclear/flat modules into clear layer-grouped Gradle
  paths (`:core:*`, `:data:*`, `:domain:*`, `:stats:*`, `:sensor:*`, `:feature:*`). Namespace-preserving
  move (no source/import churn).
- **Phase 1 — `:core:common` foundation.** Extracted the Room-free, resource-free utility packages
  out of the `sbase` god-module; `:core:base` `api`-depends on it. Guarded by `CoreCommonBoundaryTest`.
- **Phase 2 — `:core:model` + decouple `:stats:api`.** Added a pure Room-free `:core:model`
  (`Location`, `Trip`, `TripDaySummary`, `LocationSample`, `SampleQuality`, `MotionState`,
  `SkiRunSegment`, `SkiSegmentType`, `SegmentSource`) + entity↔model mappers in `:core:base`, and
  **removed `api(:core:base)` from `:stats:api`** — the original DB‑leak is gone. (Broader migration
  of non-stats consumers off Room-coupled types remains as incremental follow-up.)
- **Phase 4 — split `:sensor:activity`.** `:sensor:activity-api` (recognition contracts +
  `ActivityRequestManager` interface) / `:sensor:activity` (Play-Services impl) / `:feature:activity`
  (UI). `:tracker:*` now depends on `:sensor:activity-api` only; DI rewired through Hilt.
- **Phase 5 — feature navigation `:api`.** Added `:feature:{map,statistics,dashboard,game}:api`
  owning each feature's `@Serializable` route types; moved each NavGraph out of `:app` into its
  feature module; cross-feature navigation now flows through `:api` modules. (App-internal
  settings/setup/about/debug/onboarding routes intentionally remain in `:app`.)
- **Phase 6 — split `:tracker`.** `:tracker:api` (contracts: `TrackerServiceController`, `LockManager`,
  `BackgroundTrackingApi`, session/insight types) / `:tracker:engine` (service, pipeline, producers —
  no UI) / `:feature:tracker` (Compose UI). `:feature:map` and `:feature:dashboard` now depend on
  `:tracker:api` only.
- **Phase 7 — pure tracking control seam.** Added `:tracker:control`, an Android-free evidence ledger
  and decision reducer. The engine can evaluate it while Android service/acquisition effects remain
  in `:tracker:engine`; production authority should move only after shadow comparison is proven.

## Follow-ups — also delivered

### Phase 0b — `build-logic` convention plugins (DONE)
Added a `build-logic` included build with convention plugins (`tracker.android.library`,
`.application`, `.compose`, `.hilt`, `.room`, `.test`). Migrated 27 modules (incl. `:app`) off the
duplicated `android { … }` + test/compose/hilt/room boilerplate (~1.6k net LOC removed). KMP modules
(`:core:model`, `:stats:api`, `:stats:engine`) left as-is. Verified green.

### Incremental `:core:model` migration (COMPLETE FOR CURRENT BOUNDARIES)
Migrated ~38 of 55 Room-type usages in `:feature:*` / `:domain:*` / `:tracker:engine` / `:sensor:activity`
onto `:core:model` (`Location`, `Trip`, `LocationSample`, `SkiRunSegment`) behind the `:core:base`
mappers, committed per-module and verified green. The two previously deferred boundaries are now
closed: `TrackerServiceController` exposes the Room-free `shared.model.Location` contract, and
`SessionSegment`, its DAO/type converter, and all construction paths use the single
`shared.model.SegmentSource` enum while retaining the existing enum-name database representation.

### Tracker API live-state contracts (COMPLETE FOR CURRENT BOUNDARY)

`TrackerServiceController` and `SessionInsightsGenerator` exchange immutable,
API-owned session and collection snapshots. Room-backed sessions and the mutable
legacy collection payload are mapped inside `:tracker:engine`; the public live-state
snapshot exposes only feature-facing primitives and omits Wi-Fi BSSIDs. As a result,
`:tracker:api` no longer exports or depends on `:core:base`.

### Tracker notification settings boundary (COMPLETE)

The notification customization catalog now crosses an immutable,
context-free `TrackerNotificationSettingsRepository` contract in `:tracker:api`.
`:tracker:engine` owns the runtime notification components, stable persisted IDs,
Room adapter, ordering normalization, and Hilt binding. `:feature:tracker` owns only
the editor UI and ViewModel, no longer accesses Room or depends on
`:tracker:engine`. The existing app-owned notification-management destination is
also registered in the settings graph, so the customization row now opens the
editor instead of invoking a no-op callback.

### Shared UI ownership boundary (COMPLETE)

`:core:ui` now contains reusable Compose presentation code only and has no
project-module dependencies. Generic startup, exception-result, and WorkManager
data helpers moved to `:core:common`; measurement-aware distance and speed
formatting moved beside its unit/settings owner in `:data:preferences`.

The duplicate Room-backed `TrackerSessionChannel` was removed. Game goals now
observe the immutable `TrackerStateReader.sessionFlow` contract from
`:tracker:api`, and their update APIs accept `TrackerSessionSnapshot`. The
unused reflective tracker-listener broadcast route and its signature permission
were deleted rather than relocated. The unused Activity Result permission facade
and timer-permission entry point were also removed; active permission flows remain
owned by their screens.

As a result, `:tracker:engine`, `:stats:data`, `:domain:points`, `:domain:osm`,
and `:sensor:activity` no longer depend on `:core:ui`.

### Feature presentation data ports and sibling contracts (COMPLETE)

Feature ViewModels no longer inject `AppDatabase` or Room DAOs. Activity
management (including built-in activity localization), dashboard history and
layout, game exploration/achievement, score, and leaderboard reads, tracker
recent trips, and import/export presentation now consume feature-facing
repositories. App-owned OSM settings consume entity-free `OsmImportSummary`
values and delete imports through `OsmImportController`. Room adapters and Hilt
bindings remain behind those boundaries, and the presentation-facing contracts
expose shared models or immutable feature-owned snapshots.

The two remaining feature implementation edges were also removed.
`:feature:statistics` embeds trip previews through `RoutePreviewRenderer` from
`:feature:map:api`; the application composition root supplies the MapLibre-backed
renderer. GPX sharing crosses the consumer-owned `TripGpxExporter` port in
`:feature:statistics:api`, with `:feature:import-export` supplying the format
adapter and owning the Android `Context`. The API contract remains Android-free,
and statistics depends on neither sibling implementation.

### App diagnostic boundary (COMPLETE)

`:core:diagnostics` is the single application boundary for fixed, payload-free
diagnostic codes. Tracebox is the sole active diagnostic and crash backend.
The former diagnostic Room storage, migration-only read/export/delete path,
compatibility reporting layer, and parallel crash pipeline were deleted; no
transitional diagnostic implementation remains.

### Screen-local permission presentation ownership (COMPLETE)

The active contextual permission rationale, Activity Result launcher, and denial
snackbar moved from database-owning `:core:base` to `:core:ui`. Their state remains
local to each composition: no singleton manager, receiver, service, manifest
permission, or process-wide permission state was introduced.

`:core:base` and `:core:common` no longer apply the Compose convention plugin or
carry Compose and Activity Result dependencies. All existing consumers already
depended on `:core:ui`, so the ownership correction added no new module edge.

## Guardrails
- `CoreCommonBoundaryTest` (`:core:common`) fails if `:core:common` imports Room, the database
  package, base data models, or other base-resident packages.
- `ArchitecturalFitnessTest` (`:app`) enforces project-wide source boundaries, including the
  Android-free `:stats:api` contract. Boundary paths must point at current module locations and
  missing source roots fail closed rather than silently skipping checks.
- `TrackerApiBoundaryTest` rejects dependencies on `:core:base` and imports from its data,
  database, and mapper packages, keeping tracker contracts implementation-free. It also keeps
  the notification settings boundary context-free and hides engine runtime components.
- `CoreUiBoundaryTest` requires a manifest-permission-free,
  project-dependency-free `:core:ui` and rejects tracker state, WorkManager,
  process-wide permission orchestration, logging, base, and preferences ownership
  from returning. Screen-local permission presentation is explicitly allowed.
- `GameTrackerStateBoundaryTest` keeps game goals on `TrackerStateReader` and
  immutable `TrackerSessionSnapshot` rather than the removed legacy channel or
  Room-backed session entity.
- Module dependency fitness rules prevent `:core:network` from regaining a
  database dependency and reject every feature implementation-to-implementation
  edge. They also keep feature ViewModels off Room APIs, DAOs, and persistence
  entities; prevent feature or app ViewModel/Compose code from opening Room
  databases or importing Room entities and DAOs; and keep Compose
  out of `:core:base` and `:core:common`. Feature
  modules may not depend on any `:*:engine` implementation module, and tracker
  notification UI may not regain direct preference-database access.
- `StatisticsFeatureBoundaryTest` keeps statistics on the route-preview and GPX
  export contracts rather than sibling implementation symbols.
- Root `checkRoomSchemaDrift` task continues to guard Room schema changes after the directory moves.

## Evidence-gated follow-up

The planned structural boundary pass is complete. The next tracker-control step is
operational rather than another source move: collect and evaluate shadow-comparison
results before assigning production decision authority to `:tracker:control`.
Additional repository seams should be introduced incrementally when a non-UI
feature service needs independent ownership or testing, not as another path-only
module migration.
