<!-- context-init:version:3.0.2 -->
<!-- context-init:generated:2026-01-30 -->

# Architecture Reference

<!-- context-init:managed -->
Quick reference for Tracker Android architecture. For complete details, see [docs/ARCHITECTURE_OVERVIEW.md](../../docs/ARCHITECTURE_OVERVIEW.md).

## System Diagram

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
│  sbase (Database)  │  sutils  │  smap  │  spreferences  │ logger   │
└─────────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     SUPPORTING MODULES                               │
│                  points  │  testing-common                           │
└─────────────────────────────────────────────────────────────────────┘
```

## Module Details

<!-- context-init:managed -->

### Feature Modules

| Module | Key Files | Dependencies |
|--------|-----------|--------------|
| **app** | `AppGraph.kt`, `MainActivityCompose.kt`, `MainRoot.kt` | All feature modules |
| **tracker** | `TrackerService.kt`, `TrackerComponentManager.kt` | sbase, activity |
| **map** | `MapRoute.kt`, heatmap layers | sbase, smap |
| **statistics** | `StatsRoute.kt`, session views | sbase |
| **game** | `GameRoute.kt`, challenges | sbase, points |
| **impexp** | Exporters, importers (GPX, KML, JSON) | sbase |
| **activity** | `ActivityRecognitionReceiver`, transitions | sbase |

### Shared Modules

| Module | Purpose | Key Files |
|--------|---------|-----------|
| **sbase** | Database, entities, DAOs | `AppDatabase.kt`, entity classes |
| **sutils** | Extensions, formatters, math | Utility functions |
| **smap** | Map utilities | Shared map helpers |
| **spreferences** | Typed preferences | Preference accessors |
| **logger** | Logging facade | Privacy-redacting loggers |
| **points** | Points calculation | Scoring algorithms |

## Component Map

<!-- context-init:managed -->

### Tracker Service Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         TrackerService                               │
│                     (Foreground Service)                             │
├─────────────────────────────────────────────────────────────────────┤
│  TrackerTimerManager  ←──── TrackingPolicyManager                    │
│         │                                                            │
│         ▼                                                            │
│  TrackerComponentManager                                             │
│         │                                                            │
│    ┌────┴────────────────┬─────────────────────┐                    │
│    ▼                     ▼                     ▼                    │
│ Pre-Components     Data Producers      Post-Components              │
│ ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐        │
│ │LocationPre   │  │StepData      │  │DatabaseLocation      │        │
│ │Tracker       │  │Producer      │  │Component             │        │
│ └──────────────┘  ├──────────────┤  ├──────────────────────┤        │
│                   │ActivityData  │  │DatabaseCellComponent │        │
│                   │Producer      │  ├──────────────────────┤        │
│                   ├──────────────┤  │DatabaseWifiComponent │        │
│                   │CellData      │  ├──────────────────────┤        │
│                   │Producer      │  │NotificationComponent │        │
│                   ├──────────────┤  └──────────────────────┘        │
│                   │WifiData      │                                   │
│                   │Producer      │                                   │
│                   └──────────────┘                                   │
└─────────────────────────────────────────────────────────────────────┘
```

### UI Architecture

```
MainActivityCompose
        │
        ▼
    AppTheme (Material 3)
        │
        ▼
CompositionLocalProvider (AppGraph providers)
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
```

## Data Flow

<!-- context-init:managed -->

### Tracking Flow
```
User/Auto Trigger
        │
        ▼
TrackerServiceController.startTracking()
        │
        ▼
TrackerService (Foreground)
        │
        ▼
TrackerComponentManager.initialize()
        │
        ├── PreComponents (location prep)
        ├── DataProducers (collect sensor data)
        └── PostComponents (write to database)
        │
        ▼
Room Database (persistent storage)
```

### UI State Flow
```
Room Database
        │
        ▼
DAO.getFlow() → Repository → ViewModel.stateIn() → Compose UI
```

## Dependency Injection

<!-- context-init:managed -->

### AppGraph (Composition Root)
- Location: `app/src/main/java/.../AppGraph.kt`
- Provides: database, dispatchers, clock, controllers, providers
- Injected via: `CompositionLocalProvider` in `MainRoot`

### Hilt Integration
- `@HiltAndroidApp` on Application
- `@AndroidEntryPoint` on activities
- `@HiltViewModel` for ViewModels
- `RepositoryModule` binds repository implementations

### Accessing Dependencies

```kotlin
// In Composables
val appGraph = LocalAppGraph.current
val controller = LocalTrackerController.current

// In ViewModels (via Hilt)
@HiltViewModel
class MyViewModel @Inject constructor(
    private val repository: MyRepository
) : ViewModel()
```

## Database

<!-- context-init:managed -->

- Technology: Room (SQLite)
- Current version: 13
- Location: `sbase/src/main/java/.../database/AppDatabase.kt`

### Key Entities

| Entity | Table | Purpose |
|--------|-------|---------|
| `DatabaseLocation` | `location_data` | Location points |
| `TrackerSession` | `tracker_session` | Session metadata |
| `DatabaseWifiData` | `wifi_data` | Wi-Fi observations |
| `DatabaseCellLocation` | `cell_location` | Cell tower data |
| `SessionActivity` | - | Session activities |

### Key DAOs

| DAO | Purpose |
|-----|---------|
| `LocationDataDao` | Location CRUD and queries |
| `SessionDataDao` | Session management |
| `WifiDataDao` | Wi-Fi data access |
| `CellLocationDao` | Cell location queries |

## Background Processing

<!-- context-init:managed -->

### Services
| Service | Type | Purpose |
|---------|------|---------|
| `TrackerService` | Foreground | Active tracking |
| `ActivityWatcherService` | Background | Auto-start detection |

### Workers (WorkManager)
| Worker | Schedule | Purpose |
|--------|----------|---------|
| `DatabaseMaintenanceWorker` | Periodic | DB optimization |
| `DataRetentionWorker` | Weekly | Delete old data |

<!-- context-init:user-content-below -->
