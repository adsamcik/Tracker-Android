# Tracker Android architecture overview

> **Last verified:** 2026-08-11

Tracker is a local-first Android application. Location, activity, Wi-Fi, cell, and
step data are collected and persisted on-device. The application has no backend,
remote sync, analytics, or telemetry.

## Module graph

The project currently includes **31 application Gradle modules**, plus the
`:tools:ski-data-generator` tooling module. The authoritative include list is
`settings.gradle.kts`; dependency versions are managed in `gradle/libs.versions.toml`.

```text
:app
├── :feature:tracker
├── :feature:dashboard (+ :feature:dashboard:api)
├── :feature:statistics (+ :feature:statistics:api)
├── :feature:map (+ :feature:map:api)
├── :feature:game (+ :feature:game:api)
├── :feature:activity
├── :feature:import-export
├── :tracker:engine ── :tracker:api, :tracker:control
├── :sensor:activity ── :sensor:activity-api
├── :stats:data ── :stats:engine ── :stats:api
├── :domain:points, :domain:geocoder
├── :data:preferences
└── :core:base, :core:model, :core:common, :core:ui,
    :core:diagnostics, :core:network,
    :core:sqlite-runtime, :core:testing
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
| `:core:common` | Shared concurrency, time, startup, result, WorkManager data, I/O, notifications, services, and foundation types |
| `:core:ui` | Dependency-free shared Compose UI, `AppTheme`, and screen-local permission presentation |
| `:core:diagnostics` | Payload-free diagnostic contracts and Tracebox boundary |
| `:core:network` | Network helpers used behind the network abstraction |
| `:core:sqlite-runtime` | Bundled SQLiteX runtime and AndroidX `SupportSQLite` adapter |
| `:core:testing` | Test fakes and test utilities |
| `:data:preferences` | Typed preferences, settings repositories, retention, and unit-aware measurement formatting |
| `:stats:api` / `:stats:engine` / `:stats:data` | Statistics contracts, algorithms, and persistence adapters |
| `:domain:points` / `:domain:geocoder` | Points and bundled offline place lookup domain services |
| `:tracker:api` / `:tracker:engine` | Immutable tracking and notification-settings contracts; Android foreground-service and notification runtime |
| `:tracker:control` | Android-free tracking decision model and reducer, currently evaluated from the engine |
| `:sensor:activity-api` / `:sensor:activity` | Activity-recognition contracts and Play Services implementation |
| `:feature:tracker` | Tracking screen, controls, and notification customization UI |
| `:feature:dashboard` | Dashboard, live statistics, and widgets |
| `:feature:statistics` | Session list, trip details, and analytics |
| `:feature:map` | MapLibre map, route, and heatmap presentation |
| `:feature:game` | Challenges, goals, and gamification |
| `:feature:activity` | Activity-type management UI |
| `:feature:import-export` | GPX, KML, JSON, and SQLite import/export |

Historical names still occur in package names and migration fixtures. The Gradle
module paths above are the current paths: for example `sbase` → `core/base`,
`sutils` → `core/ui`, `spreferences` → `data/preferences`,
`stats-*` → `stats/*`, `points` → `domain/points`,
`impexp` → `feature/import-export`, and the former flat
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
sensor data, applies tracking policy, persists records, and emits signals.
`:tracker:control` is the pure decision boundary; Android acquisition and service
effects remain in `:tracker:engine`. Stats processing and feature-specific
consumers are separate from the tracking service.

The engine maps its Room-backed session and mutable collection state to immutable
`:tracker:api` snapshots before publishing them. The collection snapshot is
feature-oriented and privacy-minimized: Wi-Fi access points expose SSID and signal
level, but not BSSID. Tracker contracts therefore do not expose `:core:base`.
Game goals consume the same `TrackerStateReader.sessionFlow` snapshot stream; the
former shared-UI session channel and reflective listener broadcasts no longer
exist.

Notification customization follows the same boundary. `:tracker:api` exposes an
immutable ordered settings catalog and repository port. `:tracker:engine` maps that
contract to its runtime notification components and the legacy Room preference
table; `:feature:tracker` never sees either implementation type.

Feature presentation follows feature-owned data ports. ViewModels consume
repositories for activity management (including built-in activity localization),
dashboard history and layout, game progress, scores, and leaderboard reads,
tracker recent trips, and import/export metadata/streaming. The unreleased offline
OSM importer/matcher and its settings surface were removed for v27; Git history
retains that work if a user-facing offline-map feature is deliberately resumed.
Room adapters remain behind those boundaries, so ViewModels do not inject DAOs or
persistence entities and Compose/UI code does not open `AppDatabase`.

App diagnostics follow the same rule. `:core:diagnostics` is the single,
payload-free boundary for fixed diagnostic codes. Tracebox is the sole active
diagnostic and crash backend. Tracker has no diagnostic Room storage,
migration-only adapter, compatibility reporting facade, or parallel crash
pipeline.

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
remain in `:app`. Shared Compose dependencies, theming, and composition-local
permission rationale/Activity Result presentation live in `:core:ui`;
`:core:base` and `:core:common` contain no Compose code or dependencies.

Statistics embeds a map preview through the `:feature:map:api`
`RoutePreviewRenderer` contract, supplied by `:app`, and exports GPX through a
context-free `:feature:statistics:api` port implemented by
`:feature:import-export`, whose adapter owns the Android `Context`. No feature
implementation depends on another feature implementation.

## Persistence

The main Room database is `AppDatabase` in
`core/base/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabase.kt`.
Its current schema version is **27** and its long-lived active filename is
`main_database_v27`. A released pre-v27 `main_database` is treated as a preserved
legacy vault: a frozen raw-SQL importer reads relevant v26 data while Room creates
the fresh v27 database, and older public versions are normalized only on a
disposable copy through public migrations ending at v26. Normal active migrations
are currently empty. Other local databases include the preferences and points
databases; their versions are independent. Future v27+ schema changes require a
Room migration and migration test.

## Dependency direction

The intended dependency direction for ongoing boundary work is:

```text
feature UI → feature/domain contracts → repositories/ports → data adapters
tracker engine → tracker contracts + pure tracker control
app → all implementations (composition root)
```

Contract modules must not expose Room entities or Android implementation types.
Feature implementations should not depend on another feature implementation or an
`:*:engine` module, and
`:core:ui` is a presentation leaf with no project-module dependencies and no
manifest permissions. Runtime startup, workers, process-wide permission state,
tracker state, and persistence responsibilities belong to their respective
foundation, API, or feature owners.

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
