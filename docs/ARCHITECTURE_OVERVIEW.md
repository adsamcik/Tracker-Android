# Tracker Android architecture overview

> **Last verified:** 2026-07-19

Tracker is a local-first Android application. Location, activity, Wi-Fi, cell, and
step data are collected and persisted on-device. The application has no backend,
remote sync, analytics, or telemetry.

## Module graph

The project currently includes **31 application Gradle modules**. The authoritative
include list is `settings.gradle.kts`; dependency versions are managed in
`gradle/libs.versions.toml`.

```text
:app
├── :feature:tracker
├── :feature:dashboard (+ :feature:dashboard:api)
├── :feature:statistics (+ :feature:statistics:api)
├── :feature:map (+ :feature:map:api)
├── :feature:game (+ :feature:game:api)
├── :feature:activity
├── :feature:import-export
├── :tracker:engine ── :tracker:api
├── :sensor:activity ── :sensor:activity-api
├── :stats:data ── :stats:engine ── :stats:api
├── :domain:points, :domain:osm, :domain:geocoder
├── :data:preferences
└── :core:base, :core:model, :core:common, :core:ui,
    :core:logging, :core:logging-api, :core:network, :core:testing
```

API modules contain contracts and route types. Feature implementations should
depend on contracts rather than another feature's implementation. `:tracker:api`
is located at `tracker/api-module` for compatibility with the existing source
layout.

| Module | Responsibility |
| --- | --- |
| `:app` | Application entry point, root navigation, Hilt composition root, settings, onboarding |
| `:core:base` | Room database, DAOs, entities, converters, and entity/model mappers |
| `:core:model` | Room-free domain models shared by higher layers |
| `:core:common` | Shared concurrency, time, I/O, notifications, services, and foundation types |
| `:core:ui` | Shared Compose UI, formatters, and `AppTheme` |
| `:core:logging` / `:core:logging-api` | Logging implementation and logger-facing contracts |
| `:core:network` | Network helpers used behind the network abstraction |
| `:core:testing` | Test fakes and test utilities |
| `:data:preferences` | Typed preferences, settings repositories, and retention |
| `:stats:api` / `:stats:engine` / `:stats:data` | Statistics contracts, algorithms, and persistence adapters |
| `:domain:points` / `:domain:osm` / `:domain:geocoder` | Points, OSM, and place lookup domain services |
| `:tracker:api` / `:tracker:engine` | Tracking contracts and the foreground-service pipeline |
| `:sensor:activity-api` / `:sensor:activity` | Activity-recognition contracts and Play Services implementation |
| `:feature:tracker` | Tracking screen and controls |
| `:feature:dashboard` | Dashboard, live statistics, and widgets |
| `:feature:statistics` | Session list, trip details, and analytics |
| `:feature:map` | MapLibre map, route, and heatmap presentation |
| `:feature:game` | Challenges, goals, and gamification |
| `:feature:activity` | Activity-type management UI |
| `:feature:import-export` | GPX, KML, JSON, and SQLite import/export |

Historical names still occur in package names and migration fixtures. The Gradle
module paths above are the current paths: for example `sbase` → `core/base`,
`sutils` → `core/ui`, `logger` → `core/logging`, `spreferences` →
`data/preferences`, `stats-*` → `stats/*`, `points` → `domain/points`,
`osm` → `domain/osm`, `impexp` → `feature/import-export`, and the former flat
feature modules → `feature/*`.

## Runtime data flow

```text
Android sensors
    ↓
tracker producers
    ↓
pre/data/post tracking components
    ├── Room persistence (:core:base)
    └── stats signal pipeline (:stats:engine)
            ↓
       domain events and feature consumers
```

`:tracker:engine` owns `TrackerService` and the component pipeline. It collects
sensor data, applies tracking policy, persists records, and emits signals. Stats
processing and feature-specific consumers are separate from the tracking service.

## UI and navigation

The app uses a Compose-first, single-activity entry point:

```text
MainActivityCompose
    → AppTheme
    → MainRoot
    → NavHost
       ├── TrackerRoute
       ├── Dashboard routes
       ├── Statistics routes
       ├── Map routes
       ├── Game routes
       ├── Activity routes
       └── app-owned settings/onboarding routes
```

The feature `:api` modules own cross-feature route contracts. App-internal routes
remain in `:app`. Shared Compose dependencies and theming live in `:core:ui`.

## Persistence

The main Room database is `AppDatabase` in
`core/base/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabase.kt`.
Its current schema version is **34**. Other local databases include the debug,
preferences, logging, and points databases; their versions are independent.
Schema changes require a Room migration and a migration test.

## Build and test references

Prerequisites and maintained commands are documented in
`.github/context/DEVELOPMENT.md`. Typical commands are:

```powershell
./gradlew.bat :app:assembleDebug
./gradlew.bat testDebugUnitTest
./gradlew.bat :core:base:testDebugUnitTest --no-daemon --console=plain
./gradlew.bat :app:connectedDebugAndroidTest
```

For emulator builds, use `-PuseOpenGlMapRenderer=true` when required by the
MapLibre software-rendered emulator environment.

## Related documentation

- [Project README](../README.md)
- [Module rearchitecture status](MODULE_REARCHITECTURE_STATUS.md)
- [Development guide](../.github/context/DEVELOPMENT.md)
- [Architecture reference](../.github/context/ARCHITECTURE.md)
