# Module Rearchitecture — Status & Roadmap

> Branch: `feature/module-rearchitecture-v10`
> Last updated: 2026-06-11

This document tracks the module rearchitecture begun on `feature/module-rearchitecture-v10`.
It records what was completed and verified, and the precise, evidence-based plan (with the
blockers discovered) for the remaining phases.

## Module layout (current)

```
:app
:tracker
:core:base          (Room DB + Room @Entity models + base extensions/permission/misc/assist)
:core:common        (concurrency, time, constant, exception, di, graph, io, logging, notification, service)
:core:ui            (was sutils)
:core:logging       (was logger)
:core:logging-api   (was logging-api)
:core:network       (was network)
:core:testing       (was testing-common)
:data:preferences   (was spreferences)
:stats:api          (was stats-api)
:stats:engine       (was stats-engine)
:stats:data         (was stats-data)
:domain:points      (was points)
:domain:osm         (was osm)
:sensor:activity    (was activity)
:feature:map        (was map)
:feature:statistics (was statistics)
:feature:dashboard  (was dashboard)
:feature:game       (was game)
:feature:import-export (was impexp)
```

## Completed & verified (full `:app:assembleDebug` green)

- **Phase 0 — cleanup.** Deleted the orphan `:smap` placeholder module (it was never wired into
  `settings.gradle.kts`).
- **Phase 3 — regroup & rename.** Renamed all unclear/flat modules into clear layer-grouped Gradle
  paths (`:core:*`, `:data:*`, `:domain:*`, `:stats:*`, `:sensor:*`, `:feature:*`). Kotlin package
  names were intentionally left unchanged (namespace-preserving move) so the change is confined to
  `settings.gradle.kts` and `project()` references — no source edits, no import churn.
- **Phase 1 (foundation) — extract `:core:common`.** Pulled the Room-free, resource-free utility
  packages out of the `sbase` god-module into a new `:core:common` leaf. `:core:base` now
  `api`-depends on `:core:common`, so every existing consumer keeps compiling unchanged. A fitness
  test (`CoreCommonBoundaryTest`) prevents `:core:common` from regaining Room/DB/entity dependencies.

## Key blocker discovered: domain models are Room entities

The `sbase` (`:core:base`) `data` package (`TrackerSession`, `Location`, `CellData`,
`SessionActivity`, …) is **not** a layer of plain domain models — every class is a Room `@Entity`
(`import androidx.room.*`), and `SessionActivity` even calls `AppDatabase.database(context)` directly.
There is **no separate model layer** to extract. This is why the originally-proposed clean
`:core:model` split (and the `:stats:api` decoupling that depends on it) is a real refactor, not a
move, and was not attempted in this pass.

## Remaining phases (roadmap)

### Phase 1b / Phase 2 — `:core:model` + decouple `:stats:api` from `:core:base`
- **Goal:** introduce plain (Room-free) domain models so `:stats:api` (and other contract modules)
  stop transitively re-exporting the entire Room database.
- **Why blocked:** `:stats:api` androidMain does `api(project(":core:base"))` and consumes
  `shared.base.data.Location` plus `shared.base.database.data.{Trip, SkiRunSegment, LocationSample}`,
  which are Room types. Removing the dependency requires extracting Room-free model/DTO equivalents
  and adding entity↔model mappers in `:core:base`.
- **Plan:** create `:core:model` (pure Kotlin/KMP); define value/data classes mirroring the entities
  the contract surface needs; add mappers in `:core:base`; repoint `:stats:api` to `:core:model`;
  verify `:stats:api` commonMain stays Android-free.

### Phase 4 — split `:sensor:activity`
- **Observation:** `:tracker` (core) depends on `:sensor:activity` for the activity-recognition
  contracts (`ActivityRequestManager`, `Activity*RequestData`, `ActivityTransitionType`, callbacks)
  **and** the module also contains UI (`SessionActivityActivityCompose`) plus the concrete
  `SkiInfrastructureManager` and Android resources used by `ActivityWatcherService`.
- **Plan:** extract `:sensor:activity-api` (pure recognition contracts + `ActivityRequestManager`
  interface) consumed by `:tracker`; keep the Play-Services impl + activity-type UI in
  `:sensor:activity` / a new `:feature:activity` (resource handling for the watcher service must move
  with it). Requires DI rewiring (currently a concrete manager is referenced directly).

### Phase 5 — feature navigation `:api` contracts
- **Observation:** `:app` owns every feature's NavGraph (`StatsNavGraph`, `GameNavGraph`,
  `DashboardNavGraph`, `SettingsNavGraph`, `SetupNavGraph`) and statically links all feature impl
  modules, so any feature change rebuilds `:app`.
- **Plan:** add `:feature:<x>:api` modules exposing route keys + `NavGraphBuilder` entry points;
  move each feature's NavGraph into its module; have `:app` consume only the `:api` surfaces and
  features depend on each other's `:api` (e.g. `:feature:statistics` → `:feature:map:api` instead
  of the whole `:feature:map`).

### Phase 6 — split `:tracker` into `:tracker:engine` + `:feature:tracker`
- **Observation:** `:tracker` (~28k LOC) fuses the foreground service / component pipeline with the
  Compose tracking UI (`TrackerRoute`). `:feature:map` and `:feature:dashboard` depend on `:tracker`.
- **Plan:** separate `:tracker:engine` (service, pipeline, producers — no UI) from `:feature:tracker`
  (Compose UI); repoint `:feature:map`/`:feature:dashboard` to the engine + tracker `:api`.

### Phase 0b — `build-logic` convention plugins (deferred DX)
- Every module duplicates ~100 lines of `android { … }` config (compileSdk, buildTypes incl.
  `release_nominify`, lint, JUnit5 platform, test-dependency bundles). A `build-logic` included build
  with convention plugins (`tracker.android.library`, `.compose`, `.room`, `.hilt`, `.feature`)
  would remove this duplication. Deferred because it is an all-or-nothing change against a
  bleeding-edge AGP 9 + `android.newDsl=false` setup and provides DX value, not graph value.

## Guardrails
- `CoreCommonBoundaryTest` (`:core:common`) fails if `:core:common` imports Room, the database
  package, base data models, or other base-resident packages.
- Root `checkRoomSchemaDrift` task continues to guard Room schema changes after the directory moves.
