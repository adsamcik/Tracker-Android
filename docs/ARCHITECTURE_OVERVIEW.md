# Tracker-Android Architecture Overview

> **Last Updated:** January 2026  
> **Target Audience:** Developers, contributors, and maintainers

---

## 1. What is Tracker?

**Tracker** (also known as **Advention**) is a **free, open-source, offline location and activity tracker** for Android. It collects location, Wi-Fi, cell tower, activity, and step data—all stored locally on the device with no cloud upload.

### Key Capabilities

| Feature | Description |
|---------|-------------|
| **Multi-sensor Tracking** | Location (GPS), Wi-Fi, cell towers, activity recognition, step counter |
| **Auto-start** | Begin tracking based on detected motion or activity |
| **Tracking Lock** | Pause tracking for a duration or until device is charged |
| **Map Visualization** | View routes and heatmaps of tracked data |
| **Session Statistics** | Browse sessions with distance, duration, and activity breakdown |
| **Gamification** | Challenges and exploration-based achievements |
| **Export/Import** | GPX, KML, JSON, SQLite export; GPX and ZIP import |
| **Privacy-First** | All data stays on-device; fully offline operation |

---

## 2. Module Architecture

The application is organized into **14 Gradle modules**:

```
┌─────────────────────────────────────────────────────────────────────┐
│                        APPLICATION LAYER                             │
│                              app                                     │
│         (Entry point, DI, Navigation, Settings, Onboarding)         │
└─────────────────────────────────────────────────────────────────────┘
                                   │
           ┌───────────────────────┼───────────────────────┐
           ▼                       ▼                       ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│    tracker      │    │      map        │    │   statistics    │
│ (Core Tracking) │    │ (Visualization) │    │ (Sessions/Stats)│
└─────────────────┘    └─────────────────┘    └─────────────────┘
           │                       │                       │
           │           ┌───────────┴───────────┐           │
           ▼           ▼                       ▼           ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│    activity     │ │      game       │ │     impexp      │
│ (Recognition)   │ │  (Challenges)   │ │ (Import/Export) │
└─────────────────┘ └─────────────────┘ └─────────────────┘
           │                │                    │
           └────────────────┼────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        SHARED LIBRARIES                              │
│  sbase (Database, Data)  │  sutils  │  smap  │  spreferences        │
└─────────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     SUPPORTING MODULES                               │
│           logger  │  points  │  testing-common                       │
└─────────────────────────────────────────────────────────────────────┘
```

### Module Responsibilities

| Module | Purpose |
|--------|---------|
| **app** | Main application: `Application.kt`, `AppGraph.kt`, navigation, settings, onboarding |
| **tracker** | Core tracking: `TrackerService`, component pipeline, producers/consumers |
| **map** | Map visualization with layers, heatmaps, and Google Maps integration |
| **statistics** | Session list, detail views, summary statistics |
| **game** | Challenges, goals, gamification features |
| **activity** | Activity recognition via Google Play Services |
| **impexp** | Import/export in GPX, KML, JSON, SQLite formats |
| **logger** | Crash handling, logging, error reporting |
| **points** | Points calculation and scoring |
| **sbase** | Shared base: Room database, data classes, entities, DAOs |
| **sutils** | Shared utilities: extensions, formatters, helpers |
| **smap** | Shared map utilities |
| **spreferences** | Shared preferences and settings |
| **testing-common** | Test utilities, fakes, mocks |

---

## 3. UI Architecture

The app uses a **Compose-first, single-activity architecture**:

```
MainActivityCompose
        │
        ▼
    AppTheme (Material 3)
        │
        ▼
CompositionLocalProvider (DI)
        │
        ▼
    MainRoot
        │
        ├── NavHost
        │     ├── TrackerRoute (Home)
        │     ├── StatsRoute (Statistics)
        │     ├── MapRoute (Map)
        │     ├── GameRoute (Challenges)
        │     └── SettingsRoute
        │
        └── BottomNavigationBar
              ├── Home (Tracker)
              ├── Stats
              ├── Map
              └── Game
```

### Key UI Files

| File | Location | Purpose |
|------|----------|---------|
| `MainActivityCompose.kt` | `app/src/main/java/.../activity/` | Entry point with splash screen |
| `MainRoot.kt` | `app/src/main/java/.../ui/` | NavHost + bottom bar |
| `TrackerRoute.kt` | `tracker/src/main/java/.../ui/compose/` | Tracking dashboard |
| `MapRoute.kt` | `map/src/main/java/.../ui/` | Map visualization |
| `StatsRoute.kt` | `statistics/src/main/java/.../fragment/` | Statistics view |
| `GameRoute.kt` | `game/src/main/java/.../ui/compose/` | Challenges screen |

---

## 4. Dependency Injection

The app uses a **hybrid DI approach**: custom `AppGraph` composition root + Hilt for Android components.

### AppGraph (Composition Root)

```kotlin
// app/src/main/java/.../AppGraph.kt
class AppGraph(
    val dispatchers: DispatchersProvider,
    val clock: Clock,
    val appScope: CoroutineScope,
) {
    val database: AppDatabase by lazy { ... }
    val trackerServiceController: TrackerServiceController by lazy { ... }
    val lockManager: LockManager by lazy { ... }
    val dailySummaryProvider: DailySummaryProvider by lazy { ... }
    val exportAutomationController: ExportAutomationController by lazy { ... }
}
```

### CompositionLocal Providers

The app uses CompositionLocal for dependency provision in Compose:

```kotlin
CompositionLocalProvider(
    LocalAppGraph provides appGraph,
    LocalTrackerController provides appGraph.trackerServiceController,
    LocalLockManager provides appGraph.lockManager,
    LocalThemeState provides themeState,
    // ...
) { MainRoot() }
```

Key controllers provided via CompositionLocal:
- **TrackerServiceController**: Tracking state observation and control
- **LockManager**: Lock state management (time lock, recharge lock)
- **ThemeState**: Current theme colors for Material 3

---

## 5. Core Tracking System

### TrackerService Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         TrackerService                               │
│                     (Foreground Service)                             │
├─────────────────────────────────────────────────────────────────────┤
│  TrackerTimerManager  ←──── TrackingPolicyManager                    │
│         │                        │                                   │
│         ▼                        ▼                                   │
│  TrackerComponentManager                                             │
│         │                                                            │
│    ┌────┴────────────────┬─────────────────────┐                    │
│    ▼                     ▼                     ▼                    │
│ Pre-Components     Data Producers      Post-Components              │
│ ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐        │
│ │Location      │  │StepData      │  │DatabaseLocation      │        │
│ │PreTracker    │  │Producer      │  │Component             │        │
│ └──────────────┘  ├──────────────┤  ├──────────────────────┤        │
│                   │ActivityData  │  │DatabaseCellComponent │        │
│                   │Producer      │  ├──────────────────────┤        │
│                   ├──────────────┤  │DatabaseWifiComponent │        │
│                   │CellData      │  ├──────────────────────┤        │
│                   │Producer      │  │NotificationComponent │        │
│                   ├──────────────┤  ├──────────────────────┤        │
│                   │WifiData      │  │RawLocationWriter     │        │
│                   │Producer      │  └──────────────────────┘        │
│                   └──────────────┘                                  │
└─────────────────────────────────────────────────────────────────────┘
```

### Tracking Flow

1. **Start Trigger**: Manual button, shortcut, or auto-detect via `ActivityWatcherService`
2. **Service Startup**: Foreground service with persistent notification
3. **Data Collection**: Timer-based updates through component pipeline
4. **Storage**: Data written to Room database via post-components
5. **Session End**: Session finalized with aggregated statistics

---

## 6. Data Layer

### Database Overview

- **Technology**: Room (SQLite)
- **Version**: 13
- **Location**: `sbase/src/main/java/.../database/AppDatabase.kt`

### Entity Categories

**Legacy Session-Based (Active):**
| Entity | Table | Purpose |
|--------|-------|---------|
| `DatabaseLocation` | `location_data` | Location points |
| `TrackerSession` | `tracker_session` | Session metadata |
| `DatabaseWifiData` | `wifi_data` | Wi-Fi observations |
| `DatabaseCellLocation` | `cell_location` | Cell tower data |
| `SessionActivity` | - | Session activities |
| `NetworkOperator` | - | Cell operators |

**Sessionless Architecture (Implemented):**
| Entity | Table | Purpose |
|--------|-------|---------|
| `LocationSample` | `location_sample` | Raw location samples |
| `StepInterval` | `step_interval` | Step count deltas |
| `ActivitySnapshot` | `activity_snapshot` | Activity changes |
| `CellSample` | `cell_sample` | Cell observations |
| `WifiObservation` | `wifi_observation` | Wi-Fi scans |
| `TrackerRun` | `tracker_run` | Policy state |
| `SessionSegment` | `session_segment` | Inferred sessions |

### Key DAOs

| DAO | Purpose |
|-----|---------|
| `LocationDataDao` | Location CRUD and queries |
| `SessionDataDao` | Session management |
| `WifiDataDao` | Wi-Fi data access |
| `CellLocationDao` | Cell location queries |
| `UnifiedGeoDao` | Cross-entity geo queries |

---

## 7. Background Processing

### Services

| Service | Type | Purpose |
|---------|------|---------|
| `TrackerService` | Foreground | Active tracking |
| `ActivityWatcherService` | Background | Auto-start detection |

### WorkManager Workers

| Worker | Schedule | Purpose |
|--------|----------|---------|
| `DatabaseMaintenanceWorker` | Periodic | Database optimization |
| `DataRetentionWorker` | Weekly | Delete old data |
| `ActivityRecognitionWorker` | On-demand | Activity classification |
| Export workers | User-defined | Scheduled exports |

### Broadcast Receivers

| Receiver | Trigger | Purpose |
|----------|---------|---------|
| `BootReceiver` | BOOT_COMPLETED | Restart after reboot |
| `OnAppUpdateReceiver` | MY_PACKAGE_REPLACED | Re-init after update |
| `PrecisionUpgradeReceiver` | Session end | Prompt for precise location |

---

## 8. Permissions

| Permission | Purpose | Required |
|------------|---------|----------|
| `ACCESS_FINE_LOCATION` | Precise GPS | Yes |
| `ACCESS_COARSE_LOCATION` | Approximate | Yes |
| `ACCESS_BACKGROUND_LOCATION` | Background | Optional |
| `FOREGROUND_SERVICE` | Service | Yes |
| `POST_NOTIFICATIONS` | Notifications | Yes |
| `NEARBY_WIFI_DEVICES` | Wi-Fi (API 33+) | No |
| `READ_PHONE_STATE` | Cell details | No |

### Progressive Disclosure
1. Start with coarse location
2. After 2-3 sessions, suggest precise location
3. Request background only when needed

---

## 9. Architecture Standards

### Enforced Patterns

| Pattern | Description |
|---------|-------------|
| **Constructor Injection** | No `AndroidViewModel`, no direct Context in ViewModels |
| **Flow-based Reactivity** | StateFlow/Flow, no LiveData |
| **Repository Pattern** | Interface + Default implementation |
| **Composition Root** | AppGraph as single dependency source |
| **Compose-first UI** | No new fragments |

### ViewModel Requirements

```kotlin
// ✅ Correct
class FeatureViewModel(
    private val repository: FeatureRepository
) : ViewModel() {
    val data: StateFlow<Data> = repository.getData()
        .stateIn(viewModelScope, SharingStarted.Lazily, Data.Empty)
}

// ❌ Wrong
class BadViewModel(app: Application) : AndroidViewModel(app) {
    val db = AppDatabase.database(app) // Direct DB access
}
```

---

## 10. Build System

### Key Files

| File | Purpose |
|------|---------|
| `build.gradle.kts` | Root build script |
| `settings.gradle.kts` | Module includes |
| `app/build.gradle.kts` | Main app config |
| `gradle/libs.versions.toml` | Version catalog |
| `buildSrc/` | Shared build logic |

### Technologies

- Gradle Kotlin DSL
- Version Catalogs
- Hilt + KSP
- Compose Compiler Plugin
- ProGuard/R8 for release builds

---

## 11. Related Documentation

| Document | Purpose |
|----------|---------|
| `README.md` | Project overview |
| `docs/APP_FUNCTIONALITY.md` | Detailed feature documentation |
| `docs/migration/` | Active migration guides (Agent A–D) |

### Archived Documentation

The following documents are preserved in `docs/archive/` for historical reference:

| Document | Purpose |
|----------|---------|
| `archive/history/ARCHITECTURE_DI_VIEWMODEL_STANDARDS.md` | DI patterns (historical) |
| `archive/history/COMPOSE_MAIN_APP_ARCHITECTURE.md` | UI architecture (historical) |
| `archive/history/SESSIONLESS_TRACKING_STATUS.md` | Sessionless migration status |
| `archive/history/DATABASE_VERSION_TRACKING.md` | DB migrations tracking |
| `archive/compose-migration/` | Compose migration completion reports |

---

## Quick Reference

### Starting the App
1. Entry: `MainActivityCompose` → Splash → Onboarding check
2. Root: `MainRoot` with `NavHost` + bottom navigation
3. Default: `TrackerRoute` (Home screen)

### Starting Tracking
1. User taps "Start" or trigger via shortcut
2. `TrackerServiceController.startTracking()`
3. `TrackerService` starts as foreground service
4. Components initialized via `TrackerComponentManager`
5. Timer begins collecting data

### Data Flow
```
Sensors → Producers → TempData → Post-Components → Room Database
```

### Key Entry Points
| Action | Entry Point |
|--------|-------------|
| App launch | `MainActivityCompose.onCreate()` |
| Start tracking | `TrackerServiceController.startTracking()` |
| View map | `MapRoute` via navigation |
| Export data | `ImportExportComposeActivity` |
