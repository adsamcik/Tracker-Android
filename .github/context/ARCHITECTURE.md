<!-- context-init:version:3.0.3 -->
<!-- context-init:generated:2026-02-17 -->

# Architecture Reference

<!-- context-init:managed -->
Comprehensive architecture reference for Tracker Android.

## System Diagram

<!-- context-init:managed -->

```
APPLICATION LAYER: app
  Entry point, Hilt DI, Navigation, Settings, Onboarding
  AppGraph.kt | MainActivityCompose | MainRoot | Routes.kt

FEATURE MODULES:
  tracker       - TrackerService, Components, Producers, PolicyManager
  map           - MapLibre Compose, Heatmap Layers, UDF MapStore
  statistics    - Sessions/Trips, Summary/Detail, ViewModels
  activity      - Recognition, Receivers (OnFoot/Vehicle)
  game          - Challenges, Goals (Steps), ChallengeDB
  impexp        - GPX/KML/JSON, Streaming Exporter interface

SHARED LIBRARIES:
  sbase (Room DB v17) | sutils (AppTheme) | smap | spreferences

DOMAIN/ANALYTICS:
  stats-api (contracts) | stats-engine (algorithms) | stats-data

SUPPORTING:
  logger | points | testing-common
```

## Module Details

<!-- context-init:managed -->

### App Module
| Component | File | Purpose |
|-----------|------|---------|
| Application | `app/.../Application.kt` | `@HiltAndroidApp`, WorkManager config |
| AppGraph | `app/.../AppGraph.kt` | Composition root: DispatchersProvider, Clock, DB, repos |
| MainActivityCompose | `app/.../activity/MainActivityCompose.kt` | `@AndroidEntryPoint`, CompositionLocalProvider |
| MainRoot | `app/.../ui/MainRoot.kt` | NavHost, floating bottom bar, Haze effect |
| Routes | `app/.../ui/navigation/Routes.kt` | `@Serializable` route definitions |
| Hilt Modules | `app/.../di/` | AppGraphModule, RepositoryModule, InfrastructureModule |
| Settings | `app/.../settings/` | SettingsRoute, TrackingSettingsViewModel |
| Onboarding | `app/.../onboarding/` | StreamlinedOnboardingScreen, permission flow |

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

| StateFlow | Type | Purpose |
|-----------|------|---------|
| `isServiceRunningFlow` | `Boolean` | Service lifecycle |
| `sessionFlow` | `TrackerSessionInfo` | Distance, steps, collections, timestamps |
| `collectionDataFlow` | `CollectionDataEcho` | Live location, activity, wifi, cell |
| `policyTierFlow` | `PolicyTier` | Current tier (OFF/AMBIENT/ACTIVE/PRECISION) |
| `policyStateFlow` | `PolicyState` | Escalation engine internals |
| `persistenceErrorFlow` | `PersistenceResult` | DB error notifications |
| `lastSessionFlow` | `TrackerSessionInfo?` | Retained after stop |

### Lock Management (LockManager)

| Lock Type | Mechanism | Trigger |
|-----------|-----------|---------|
| Time Lock | AlarmManager auto-unlock | User-set duration |
| Charge Lock | WorkManager `DisableTillRechargeWorker` | User action |

### Map Module (MapLibre)

<!-- context-init:managed -->

| Component | File | Purpose |
|-----------|------|---------|
| MapStore | `map/.../presentation/MapStore.kt` | ViewModel, UDF state management |
| MapLibreLayerEngine | `map/.../presentation/bridge/MapLibreLayerEngine.kt` | Rendering bridge |
| LocationHeatmapLayer | `map/.../layers/impl/` | Location heatmap |
| WifiHeatmapLayer | `map/.../layers/impl/` | WiFi density heatmap |
| CellHeatmapLayer | `map/.../layers/impl/` | Cell tower heatmap |
| SpeedHeatmapLayer | `map/.../layers/impl/` | Speed heatmap |
| LocationPathLayer | `map/.../layers/impl/` | Route path rendering |
| LayerRegistry | `map/.../layers/registry/` | Layer management |

### Database (sbase)

<!-- context-init:managed -->

- **Version:** 17
- **27 Entities:** LocationSample, StepInterval, ActivitySnapshot, CellSample, WifiObservation, TrackerRun, SessionSegment, DailySummaryEntity, LiveStatsEntity, FrequentPlaceEntity, InferredTripEntity, TripLegEntity, ExplorationCellEntity, ExplorationStreakEntity, AchievementProgressEntity, PersonalRecordEntity, RouteCacheEntity, ExportLogEntity, StorageSizeSnapshotEntity + legacy entities
- **Type Converters:** CellType, DetectedActivity, GeoFeatureProperties, Sessionless
- **Key DAOs:** LocationSampleDao, WifiObservationDao, CellSampleDao, SessionSegmentDao, TripDao, ActivitySnapshotDao, StepIntervalDao, FrequentPlaceDao, ExplorationCellDao

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
User selects format (GPX/KML/JSON/SQLite)
  -> Exporter interface -> streaming writer (O(1) memory)
  -> Query sessions/locations from Room DB (windowed)
  -> Write to file -> user picks save location
```

<!-- context-init:user-content-below -->