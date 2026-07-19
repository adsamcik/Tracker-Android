# Module Rearchitecture — Status & Roadmap

> Branch: `dev/v10`
> Last updated: 2026-07-19

This document records the module rearchitecture currently present on `dev/v10`.
The Gradle project contains 31 application modules (including API contract modules); the
`build-logic` included build is separate from that count.

## Module layout

```
:app                         root NavHost, AppGraph DI, settings, onboarding
:core:base                   Room DB + DAOs + Room @Entity types + entity↔model mappers (was sbase)
:core:model                  pure Room-free domain models (Location, Trip, LocationSample, SkiRunSegment, …)
:core:common                 foundation leaf: concurrency, time, constant, exception, di, graph, io, logging, notification, service
:core:ui                     shared Compose UI + AppTheme (was sutils)
:core:logging / :core:logging-api  logging impl / contracts (were logger / logging-api)
:core:network                network helpers (was network)
:core:testing                test fakes + utilities (was testing-common)
:data:preferences            preferences / settings / retention (was spreferences)
:stats:api                   stats contracts — depends on :core:model, NOT the DB (was stats-api)
:stats:engine /:stats:data   algorithms / data layer (were stats-engine / stats-data)
:domain:points /:domain:osm  points calc / OSM lookup (were points / osm)
:tracker:api                 tracking contracts (TrackerServiceController, LockManager, BackgroundTrackingApi, …; directory `tracker/api-module`)
:tracker:engine              tracking impl (service, pipeline, producers/consumers — no UI)
:feature:tracker             tracking Compose UI (TrackerRoute)
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

## Follow-ups — also delivered

### Phase 0b — `build-logic` convention plugins (DONE)
Added a `build-logic` included build with convention plugins (`tracker.android.library`,
`.application`, `.compose`, `.hilt`, `.room`, `.test`). Migrated 27 modules (incl. `:app`) off the
duplicated `android { … }` + test/compose/hilt/room boilerplate (~1.6k net LOC removed). KMP modules
(`:core:model`, `:stats:api`, `:stats:engine`) left as-is. Verified green.

### Incremental `:core:model` migration (LARGELY DONE)
Migrated ~38 of 55 Room-type usages in `:feature:*` / `:domain:*` / `:tracker:engine` / `:sensor:activity`
onto `:core:model` (`Location`, `Trip`, `LocationSample`, `SkiRunSegment`) behind the `:core:base`
mappers, committed per-module and verified green. Intentionally left (need a follow-up that touches a
public contract or Room construction, out of scope for a behind-the-mappers pass):
- `DefaultTrackerServiceController` still exposes `base.data.Location` because `:tracker:api` owns that
  public signature (migrating it changes the api surface).
- `SegmentSource` at `SessionSegment` Room-construction boundaries.

## Guardrails
- `CoreCommonBoundaryTest` (`:core:common`) fails if `:core:common` imports Room, the database
  package, base data models, or other base-resident packages.
- Root `checkRoomSchemaDrift` task continues to guard Room schema changes after the directory moves.
