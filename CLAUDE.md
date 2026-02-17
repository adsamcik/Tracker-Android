<!-- context-init:version:3.0.3 -->
<!-- context-init:generated:2026-02-17 -->

# Tracker Android (Advention)

<!-- context-init:managed -->
Privacy-first, fully local Android location & activity tracker with analytics, map visualization, and gamification. **No backend, no remote sync, no telemetry.** All data stays on-device.

## Quick Reference

<!-- context-init:managed -->

### Build & Run
```bash
# Build debug APK
./gradlew.bat :app:assembleDebug

# Run all unit tests
./gradlew.bat testDebugUnitTest

# Run specific module tests
./gradlew.bat :tracker:testDebugUnitTest
./gradlew.bat :map:testDebugUnitTest

# Run connected tests (requires device/emulator)
./gradlew.bat :app:connectedDebugAndroidTest

# Check dependencies for updates
./gradlew.bat dependencyUpdates -Drevision=release
```

### Tech Stack
| Category | Technology |
|----------|------------|
| Language | Kotlin 2.2.x (K2 compiler) |
| UI | Jetpack Compose (Material 3 Expressive) |
| Async | Kotlin Flow, Coroutines |
| DI | Hilt + AppGraph (composition root) |
| Database | Room (SQLite), DB version 17 |
| Navigation | Compose Navigation (type-safe, single activity) |
| Maps | MapLibre Compose |
| Build | Gradle Kotlin DSL, Version Catalogs, KSP |
| Theming | AppTheme (dynamic color / MaterialKolor Expressive) |
| Testing | JUnit 5, MockK, Turbine, Kotest assertions, Robolectric |

## Architecture

<!-- context-init:managed -->
Multi-module Gradle project with 17 modules:

| Module | Purpose |
|--------|---------|
| `app` | Entry point, Hilt DI, navigation graph, settings, onboarding |
| `tracker` | Core tracking: TrackerService, component pipeline, producers |
| `map` | MapLibre visualization, heatmaps (location/wifi/cell/speed), layers |
| `statistics` | Session list, trip detail views, summary analytics |
| `game` | Challenges (ActiveTime, Explorer, WalkDistance, Step), goals |
| `activity` | Activity recognition via Google Play Services |
| `impexp` | Import/export (GPX, KML, JSON, SQLite) with streaming writers |
| `sbase` | Room database (27 entities), DAOs, shared data classes |
| `sutils` | Shared utilities, formatters, AppTheme, PermissionManager |
| `smap` | Shared map utilities |
| `spreferences` | Typed preferences, settings repos, retention config |
| `logger` | Structured logging with privacy-aware redaction |
| `points` | Points calculation, session-triggered awards |
| `stats-api` | API contracts: achievements, policies, trip models |
| `stats-engine` | Processing: aggregation, place detection, policy escalation |
| `stats-data` | Stats data layer |
| `testing-common` | Test fakes, utilities (FakeLocationSource, FakeTrackerSettingsRepository) |

See @docs/ARCHITECTURE_OVERVIEW.md for detailed component maps and data flows.

## Key Patterns

<!-- context-init:managed -->

### Enforced Standards
- **Compose-only UI**: No XML layouts, no Fragments, no AndroidView
- **Flow-based reactivity**: StateFlow/Flow only, no LiveData
- **Constructor injection**: Hilt + AppGraph composition root in app module
- **Privacy-first**: All processing local, no network calls
- **Interface-based DI**: TrackerServiceController, LockManager via CompositionLocals
- **KSP only**: No KAPT for new code
- **Version catalog**: All deps via `gradle/libs.versions.toml`

### ViewModel Pattern
```kotlin
// Correct
@HiltViewModel
class FeatureViewModel @Inject constructor(
    private val repository: FeatureRepository
) : ViewModel() {
    val state: StateFlow<UiState> = repository.data()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)
}

// Wrong - Don't use AndroidViewModel or direct DB access
class BadViewModel(app: Application) : AndroidViewModel(app)
```

### Error Handling
- Use sealed `Result` types for cross-module operations (suffix: `*Result`)
- Never throw exceptions across module boundaries
- Log errors with structured tags, redact coordinates in release builds

### Naming Conventions
| Type | Convention | Example |
|------|------------|---------|
| Interfaces | Domain name (no prefix) | `TrackerRepository` |
| Implementations | `Default*` prefix | `DefaultTrackerRepository` |
| Factories | `*Factory` suffix | `SessionScopeFactory` |
| Composables | PascalCase, `*Route`/`*Screen` | `TrackerRoute`, `MapScreen` |
| Entities | `*Entity`/`*Sample`/`Database*` | `LocationSample`, `FrequentPlaceEntity` |
| DAOs | `*Dao` suffix | `LocationSampleDao` |

## Entry Points

<!-- context-init:managed -->

| Action | Entry Point |
|--------|-------------|
| App launch | `MainActivityCompose.onCreate()` -> `MainRoot` (NavHost) |
| Start tracking | `TrackerServiceController.startTracking()` |
| View map | `MapRoute` -> `MapScreen` -> `MapStore` (UDF) |
| View stats | `StatsRoute` -> trip/session views |
| Game | `GameRoute` -> challenges & goals |
| Export data | `impexp` module exporters (GPX, KML, JSON) |
| Auto-track | `ActivityWatcherService` detects motion |
| Navigation | Type-safe routes: Tracker, Stats, Map, Game, TripDetail(id), History, Settings |

## Data Flow

<!-- context-init:managed -->
```
Sensors -> Producers -> TempData -> Pre-Components -> Data-Components -> Post-Components -> Room DB
```

Tracking cycle:
1. User/auto trigger -> `TrackerServiceController.startTracking()`
2. `TrackerService` starts as foreground service with wake lock
3. `DataProducerManager` runs 4 producers concurrently (Activity, Step, WiFi, Cell)
4. Pre-components validate (e.g., GPS accuracy check)
5. Data-components enrich (Activity, Cell, Location, WiFi)
6. `SessionTrackerComponent` updates session metrics
7. Controller emits StateFlows (UI-observable)
8. Post-components persist to Room DB (DatabaseLocationComponent, etc.)
9. `TrackingPolicyManager` feeds back -> adaptive tier escalation (AMBIENT->ACTIVE->PRECISION)

## DI Structure

<!-- context-init:managed -->
- **`@HiltAndroidApp`**: `Application.kt` -- app entry point
- **AppGraph.kt**: Composition root wiring DispatchersProvider, Clock, AppDatabase, repos
- **Hilt Modules**: `AppGraphModule` (singletons from AppGraph), `RepositoryModule` (@Binds repos), `InfrastructureModule` (DAOs, dispatchers)
- **CompositionLocals**: `LocalTrackerController`, `LocalLockManager`, `LocalDailySummaryProvider`, `LocalDailyPointsProvider`, `LocalGoalProgressProvider`
- **Testing**: `TestAppGraphBuilder` for deterministic injection, in-memory Room DB

## Gotchas

<!-- context-init:managed -->
- **No network code allowed**: All data is local-only. Never introduce sync, analytics, or remote endpoints.
- **Version catalog required**: All dependencies via `gradle/libs.versions.toml`. No inline versions.
- **KSP over KAPT**: Use KSP where supported (Room, Moshi). No new KAPT usage.
- **Database migrations required**: Every schema change needs explicit migration + test. Current DB version: 17.
- **Test output to files**: Terminal truncates. Capture: `./gradlew.bat :module:testDebugUnitTest 2>&1 | Tee-Object -FilePath test-output.log`
- **DataStore for new prefs**: No new SharedPreferences keys. Use DataStore (Proto preferred).
- **Baseline profiles**: Update after adding new critical composables.
- **Use TrackerServiceController**: Not TrackerService static members for state observation.
- **Use LockManager**: Not TrackerLocker static object for lock state.
- **AppTheme for theming**: Use `AppTheme` from sutils with dynamic color. MaterialKolor for Expressive palette.
- **MapLibre, not Google Maps**: Map module uses MapLibre Compose (`libs.maplibre.compose`).
- **Hilt + AppGraph coexist**: Hilt manages Android injection; AppGraph provides composition root.
- **JUnit 5 for new tests**: Jupiter API. JUnit 4 via Vintage engine for legacy/instrumented only.
- **Type-safe navigation**: Routes use `@Serializable` data objects with Compose Navigation.
- **27 Room entities**: Check `AppDatabase.kt` entity list before adding new tables.
- **Stats modules split**: `stats-api` (contracts) -> `stats-engine` (algorithms) -> `stats-data` (persistence).
- **Adaptive tracking tiers**: AMBIENT (activity/steps) -> ACTIVE (GPS) -> PRECISION (high-accuracy).

## Important Files

<!-- context-init:managed -->

| File | Purpose |
|------|---------|
| `app/.../AppGraph.kt` | Composition root for DI |
| `app/.../activity/MainActivityCompose.kt` | App entry (`@AndroidEntryPoint`) |
| `app/.../ui/MainRoot.kt` | NavHost + floating bottom bar |
| `app/.../ui/navigation/Routes.kt` | Type-safe route definitions |
| `app/.../di/AppGraphModule.kt` | Hilt module bridging AppGraph |
| `app/.../di/InfrastructureModule.kt` | Core infra: dispatchers, DAOs, clock |
| `tracker/.../service/TrackerService.kt` | Core foreground tracking service |
| `tracker/.../controller/TrackerServiceController.kt` | Tracking state interface (StateFlows) |
| `tracker/.../controller/LockManager.kt` | Lock state interface |
| `tracker/.../component/DataProducerManager.kt` | Concurrent data producer orchestration |
| `tracker/.../policy/TrackingPolicyManager.kt` | Adaptive tier escalation engine |
| `map/.../presentation/MapStore.kt` | Map ViewModel (UDF pattern) |
| `map/.../presentation/bridge/MapLibreLayerEngine.kt` | MapLibre rendering bridge |
| `sutils/.../style/compose/AppTheme.kt` | Material 3 theme with dynamic color |
| `sbase/.../database/AppDatabase.kt` | Room database (v17, 27 entities) |
| `gradle/libs.versions.toml` | Dependency versions |
| `testing-common/` | Test fakes: FakeLocationSource, FakeTrackerSettingsRepository |

## Testing

<!-- context-init:managed -->

| Type | Command | Framework |
|------|---------|-----------|
| Unit tests | `./gradlew.bat :module:testDebugUnitTest` | JUnit 5 + MockK + Turbine + Kotest |
| Connected tests | `./gradlew.bat :app:connectedDebugAndroidTest` | JUnit 4 + Compose UI Test |
| Build verification | `./gradlew.bat :app:assembleDebug` | Gradle |

### Test Patterns
- Use `runTest` for coroutine tests with `TestDispatcher`
- Use `FixedClock` for time-dependent tests
- Use Turbine for Flow testing: `flow.test { awaitItem() shouldBe value }`
- Use Kotest assertions: `shouldBe`, `shouldContain`, `shouldHaveSize`
- Assert semantics in Compose tests (contentDescription, text), not structure
- Fakes over mocks: `FakeLocationSource`, `FakeTrackerSettingsRepository`
- `TestAppGraphBuilder` for test DI with in-memory Room DB

## Agent Instructions

<!-- context-init:managed -->
- **Read full standards**: See @.github/copilot-instructions.md for comprehensive rules
- **Run tests after changes**: `./gradlew.bat :module:testDebugUnitTest`
- **Capture output to files**: `2>&1 | Tee-Object -FilePath output.log`
- **Privacy is non-negotiable**: Never add network sync, analytics, or remote endpoints
- **Follow existing patterns**: Check similar files before implementing new features
- **Use todo lists for complex tasks**: Plan visibly before implementing
- **Migrate legacy on contact**: When touching Views/LiveData/SharedPreferences, refactor to Compose/Flow/DataStore
- **Check @.github/context/ARCHITECTURE.md** for component maps
- **Check @.github/context/PATTERNS.md** for coding conventions
- **Check @.github/context/DEVELOPMENT.md** for environment setup

<!-- context-init:user-content-below -->
<!-- Add custom instructions below this line -->
