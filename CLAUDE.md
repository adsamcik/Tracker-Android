<!-- context-init:version:3.0.2 -->
<!-- context-init:generated:2026-01-30 -->

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
| Language | Kotlin 2.2.x |
| UI | Jetpack Compose (Material 3 Expressive) |
| Async | Kotlin Flow, Coroutines |
| DI | AppGraph (composition root) |
| Database | Room (SQLite) |
| Navigation | Compose Navigation (single activity) |
| Maps | Google Maps Compose |
| Build | Gradle Kotlin DSL, Version Catalogs |
| Theming | AppTheme (dynamic color/Expressive) |

## Architecture

<!-- context-init:managed -->
Multi-module Gradle project with 14 modules:

| Module | Purpose |
|--------|---------|
| `app` | Entry point, DI, navigation, settings, onboarding |
| `tracker` | Core tracking: TrackerService, components, producers |
| `map` | Map visualization, heatmaps, layers |
| `statistics` | Session list, detail views, analytics |
| `game` | Challenges, goals, gamification |
| `activity` | Activity recognition (Google Play Services) |
| `impexp` | Import/export (GPX, KML, JSON, SQLite) |
| `sbase` | Shared database, entities, DAOs |
| `sutils` | Shared utilities, formatters, extensions |
| `smap` | Shared map utilities |
| `spreferences` | Typed preferences |
| `logger` | Logging, crash handling |
| `points` | Points calculation |
| `testing-common` | Test utilities, fakes |

See @docs/ARCHITECTURE_OVERVIEW.md for detailed component maps and data flows.

## Key Patterns

<!-- context-init:managed -->

### Enforced Standards

- **Compose-only UI**: No XML layouts, no Fragments, no AndroidView
- **Flow-based reactivity**: StateFlow/Flow only, no LiveData
- **Constructor injection**: Via AppGraph composition root
- **Privacy-first**: All processing local, no network calls
- **Interface-based DI**: TrackerServiceController, LockManager, CompositionLocals

### ViewModel Pattern
```kotlin
// ✅ Correct
class FeatureViewModel(
    private val repository: FeatureRepository
) : ViewModel() {
    val state: StateFlow<UiState> = repository.data()
        .stateIn(viewModelScope, SharingStarted.Lazily, UiState.Loading)
}

// ❌ Wrong - Don't use AndroidViewModel or direct DB access
class BadViewModel(app: Application) : AndroidViewModel(app)
```

### Error Handling
- Use sealed `Result` types for cross-module operations
- Never throw exceptions across module boundaries
- Log errors with structured tags, redact coordinates in release builds

## Entry Points

<!-- context-init:managed -->

| Action | Entry Point |
|--------|-------------|
| App launch | `MainActivityCompose.onCreate()` → `MainRoot` |
| Start tracking | `TrackerServiceController.startTracking()` |
| View map | `MapRoute` via navigation |
| Export data | `ImportExportComposeActivity` |
| Auto-track | `ActivityWatcherService` detects motion |

## Data Flow

<!-- context-init:managed -->
```
Sensors → Producers → TempData → Post-Components → Room Database
```

Tracking cycle:
1. User/auto trigger → `TrackerServiceController.startTracking()`
2. `TrackerService` starts as foreground service
3. Timer-based collection via `TrackerComponentManager`
4. Data producers collect from sensors
5. Post-components write to Room database
6. Session ends → aggregated statistics calculated

## Gotchas

<!-- context-init:managed -->

- **No network code allowed**: All data is local-only. Never introduce sync, analytics, or remote endpoints.
- **Version catalog required**: All dependencies via `gradle/libs.versions.toml`. No inline versions.
- **KSP over KAPT**: Use KSP where supported (Room, Moshi). No new KAPT usage.
- **Database migrations required**: Every schema change needs explicit migration + test.
- **Test output to files**: Terminal output truncates. Capture test output: `./gradlew.bat :module:testDebugUnitTest 2>&1 | Tee-Object -FilePath test-output.log`
- **DataStore for new prefs**: No new SharedPreferences keys. Use DataStore (Proto preferred).
- **Baseline profiles**: Update after adding new critical composables.
- **Use TrackerServiceController**: Not TrackerService static members directly for state observation.
- **Use LockManager**: Not TrackerLocker static object directly for lock state.
- **AppTheme for theming**: StyleManager is deprecated; use `AppTheme` from sutils with dynamic color support.

## Important Files

<!-- context-init:managed -->

| File | Purpose |
|------|---------|
| `app/src/main/java/.../AppGraph.kt` | Composition root for DI |
| `app/src/main/java/.../activity/MainActivityCompose.kt` | App entry point |
| `app/src/main/java/.../ui/MainRoot.kt` | NavHost + bottom bar |
| `tracker/src/main/java/.../controller/TrackerServiceController.kt` | Tracking state interface |
| `tracker/src/main/java/.../controller/LockManager.kt` | Lock state interface |
| `tracker/src/main/java/.../service/TrackerService.kt` | Core tracking service |
| `sutils/src/main/java/.../style/compose/AppTheme.kt` | Material 3 theme with dynamic color |
| `sbase/src/main/java/.../database/AppDatabase.kt` | Room database definition |
| `gradle/libs.versions.toml` | Dependency versions |
| `.github/copilot-instructions.md` | Comprehensive coding standards |
| `docs/ARCHITECTURE_OVERVIEW.md` | Detailed architecture docs |

## Testing

<!-- context-init:managed -->

| Type | Command | Notes |
|------|---------|-------|
| Unit tests | `./gradlew.bat :module:testDebugUnitTest` | JUnit4 + Robolectric + MockK |
| Connected tests | `./gradlew.bat :app:connectedDebugAndroidTest` | Requires device |
| Build verification | `./gradlew.bat :app:assembleDebug` | Quick compile check |

### Test Patterns
- Use `runTest` for coroutine tests
- Use `TestDispatcher` for deterministic execution
- Use `FixedClock` for time-dependent tests
- Assert semantics in Compose tests, not structure

## Agent Instructions

<!-- context-init:managed -->
- **Always read full context first**: Use the comprehensive @.github/copilot-instructions.md
- **Run tests after changes**: `./gradlew.bat :module:testDebugUnitTest`
- **Capture output to files**: Terminal truncates. Use `2>&1 | Tee-Object -FilePath output.log`
- **Privacy is non-negotiable**: Never add network sync, analytics, or remote endpoints
- **Follow existing patterns**: Check similar files before implementing new features
- **Use todo lists for complex tasks**: Plan visibly before implementing

<!-- context-init:user-content-below -->
<!-- Add custom instructions below this line -->
