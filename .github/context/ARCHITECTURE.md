<!-- context-init:version:3.0.3 -->
<!-- context-init:generated:2026-02-17 -->

# Architecture Reference

<!-- context-init:managed -->
Comprehensive architecture reference for Tracker Android.

## System Diagram

<!-- context-init:managed -->

```
APPLICATION LAYER: app
  Entry point, Hilt composition, Navigation, Settings, Onboarding
  Application | app/di modules | MainActivityCompose | MainRoot | Routes

FEATURE MODULES:
  feature/tracker       - Tracking Compose UI and notification customization
  feature/map           - MapLibre Compose, heatmap layers
  feature/statistics    - Sessions/Trips, summary/detail, ViewModels
  feature/dashboard     - Tracker dashboard, live stats, milestones, widgets
  feature/activity      - Activity-type management UI
  feature/game          - Challenges, goals, and gamification
  feature/import-export - GPX/KML/JSON/SQLite import/export

SHARED LIBRARIES:
  core/base (Room DB v27, no Compose) | core/ui (AppTheme + screen-local permission UI)
  data/preferences | core/sqlite-runtime

DOMAIN/ANALYTICS:
  stats/api (contracts) | stats/engine (algorithms) | stats/data
  tracker/control (pure tracking decision reducer)
  domain/points | domain/geocoder

SUPPORTING:
  core/diagnostics | core/testing

BOUNDARIES:
  Feature ViewModels -> feature repositories/ports -> Room adapters
  App startup/Data settings -> frozen v26 importer + LegacyDatabaseRepository -> legacy vault
  App diagnostics -> core/diagnostics (payload-free) -> Tracebox
  feature/statistics -> feature/map/api RoutePreviewRenderer
  feature/statistics -> feature/statistics/api TripGpxExporter <- feature/import-export adapter
```

`:core:diagnostics` is the payload-free application boundary for fixed diagnostic
codes. Tracebox is the sole crash and diagnostic backend; Tracker has no
diagnostic Room storage or parallel crash pipeline.

## Module Details

<!-- context-init:managed -->

### App Module
| Component | File | Purpose |
|-----------|------|---------|
| Application | `app/.../Application.kt` | `@HiltAndroidApp`, WorkManager config |
| App composition | `app/.../di/` | `AppGraphModule`, `InfrastructureModule`, `RepositoryModule`, and `StartupModule` compose runtime implementations |
| MainActivityCompose | `app/.../activity/MainActivityCompose.kt` | `@AndroidEntryPoint`, CompositionLocalProvider |
| MainRoot | `app/.../ui/MainRoot.kt` | NavHost, floating bottom bar, Haze effect |
| Routes | `app/.../ui/navigation/Routes.kt` | `@Serializable` route definitions |
| Hilt Modules | `app/.../di/` | AppGraphModule, RepositoryModule, InfrastructureModule |
| Settings | `app/.../settings/` | SettingsRoute, TrackingSettingsViewModel |
| Setup (Onboarding) | `app/.../onboarding/ui/SetupRoute.kt` | 3-step Setup flow (Welcome / HowToTrack / WhatToCollect), permission flow via `SetupViewModel` |

### Tracker Module - Component Pipeline

<!-- context-init:managed -->

```
TrackerService (Foreground Service + WakeLock)
  |
  DataProducerManager (concurrent on Dispatchers.Default)
  |- ActivityDataProducer
  |- StepDataProducer
  |- WifiDataProducer (ACTIVE+ tier only)
  |- CellDataProducer (ACTIVE+ tier only)
  |
  Pre-Components (validation)
  |- PolicyAwareLocationPreTrackerComponent
  |
  Data-Components (enrichment)
  |- ActivityTrackerComponent, CellTrackerComponent
  |- LocationTrackerComponent, WifiTrackerComponent
  |
  SessionTrackerComponent (metrics update)
  |
  Post-Components (persistence & notification)
  |- DatabaseLocationComponent, DatabaseWifiComponent, DatabaseCellComponent
  |- RawLocationWriter
  |- NotificationComponent
  |
  TrackingPolicyManager -> Tier escalation (AMBIENT -> ACTIVE -> PRECISION)
```

### State Management (TrackerServiceController)

<!-- context-init:managed -->

| Stream | Type | Purpose |
|--------|------|---------|
| `isServiceRunningFlow` | `StateFlow<Boolean>` | Service lifecycle |
| `sessionInfoFlow` | `StateFlow<TrackerSessionInfo?>` | Session start and initiation metadata |
| `sessionFlow` | `StateFlow<TrackerSessionSnapshot?>` | Distance, steps, collections, timestamps |
| `collectionDataFlow` | `StateFlow<TrackerCollectionSnapshot?>` | Privacy-minimized live location, activity, Wi-Fi, and cell state |
| `pathPointsFlow` | `StateFlow<Pair<Long, List<Location>>?>` | Current session route preview |
| `policyTierFlow` | `StateFlow<PolicyTier>` | Current tier (OFF/AMBIENT/ACTIVE/PRECISION) |
| `policyStateFlow` | `StateFlow<PolicyState?>` | Escalation engine internals |
| `persistenceErrorFlow` | `SharedFlow<PersistenceError>?` | DB error notifications |
| `lastSessionFlow` | `StateFlow<TrackerSessionSnapshot?>` | Retained after stop |

### Lock Management (LockManager)

| Lock Type | Mechanism | Trigger |
|-----------|-----------|---------|
| Time Lock | AlarmManager auto-unlock | User-set duration |
| Charge Lock | WorkManager `DisableTillRechargeWorker` | User action |

### Map Feature Module (MapLibre)

<!-- context-init:managed -->

| Component | File | Purpose |
|-----------|------|---------|
| MapStore | `feature/map/.../presentation/MapStore.kt` | ViewModel, UDF state management |
| MapLibreLayerEngine | `feature/map/.../presentation/bridge/MapLibreLayerEngine.kt` | Rendering bridge |
| LocationHeatmapLayer | `feature/map/.../layers/impl/` | Location heatmap |
| WifiHeatmapLayer | `feature/map/.../layers/impl/` | WiFi density heatmap |
| CellHeatmapLayer | `feature/map/.../layers/impl/` | Cell tower heatmap |
| SpeedHeatmapLayer | `feature/map/.../layers/impl/` | Speed heatmap |
| LocationPathLayer | `feature/map/.../layers/impl/` | Route path rendering |
| LayerRegistry | `feature/map/.../layers/registry/` | Layer management |

### Database (core/base)

<!-- context-init:managed -->

- **Version:** 27; active file `main_database_v27`, preserved released-v26 vault `main_database`
- **51 application tables:** the authoritative list is the `entities` array on `AppDatabase`
- **Type Converters:** CellType, DetectedActivity, GeoFeatureProperties, Sessionless
- **Key DAOs:** LocationSampleDao, LocationObservationDao, WifiObservationDao, CellSampleDao, SessionSegmentDao, TripDao, ActivitySnapshotDao, StepIntervalDao, ExplorationCellDao, SkiRunSegmentDao, PressureSampleDao, DomainEventDao

### Navigation Graph

<!-- context-init:managed -->

```
NavHost (MainRoot)
|- Tracker (home) -> TrackerRoute -> TrackerDashboard
|- Stats -> StatsRoute -> session list
|  |- TripDetail(tripId) -> trip detail view
|  |- History -> historical trips
|- Map -> MapRoute -> MapScreen (MapLibre)
|- Game -> GameRoute -> challenges & goals
|- Settings -> SettingsRoute
|  |- NotificationManagement -> notification field order/title/content settings
|- Debug -> DebugRoute
```

Routes use `@Serializable` data objects: `Tracker`, `Stats`, `Map`, `Game`, `TripDetail(tripId: Long)`, `History`, `Settings`, `Debug`.

## Data Flow Diagrams

<!-- context-init:managed -->

### Tracking Session Lifecycle
```
User taps Start -> TrackerServiceController.startTracking()
  -> TrackerService.onStartCommand() -> startForeground() + WakeLock
  -> TrackerComponentManager.startComponents()
  -> Timer fires (configurable interval)
    -> DataProducerManager.getData() (4 producers concurrent)
    -> Pre -> Data -> Session -> Post pipeline
    -> Room DB writes + StateFlow updates
    -> TrackingPolicyManager.feedBack() (may escalate tier)
  -> User taps Stop / LockManager locks
  -> TrackerService.onDestroy() -> Release WakeLock, persist final stats
```

### Import/Export Flow
```
General import/export screen
  -> ImportExportViewModel
  -> ImportExportDataRepository -> Room adapter
  -> Exporter/importer -> streaming file I/O

Statistics GPX share
  -> GpxShareHelper
  -> feature/statistics/api TripGpxExporter
  -> feature/import-export ImportExportTripGpxExporter
  -> streaming GpxExporter -> system share sheet
```

<!-- context-init:user-content-below -->
