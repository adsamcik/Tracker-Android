# Agent D: Hilt DI Migration & Singleton Conversion

**Assigned Plans**: 4, 7  
**Est. Time**: 12 hours  
**Dependencies**: Plan 7 depends on Plan 4

---

## Plan 4: Full Hilt DI Migration

### Scope
Migrate ViewModels from AppGraph.ViewModelFactory to @HiltViewModel.

### Current State
- `AppGraph` provides ViewModels via custom `ViewModelFactory`
- Hilt used for Services, Workers, Receivers
- Hybrid bridge in `app/.../di/AppGraphModule.kt`

### ViewModels to Migrate

| ViewModel | Module | Dependencies |
|-----------|--------|--------------|
| `MainViewModel` | app | None |
| `StatsViewModel` | statistics | `SessionRepository` |
| `GameViewModel` | game | `GameRepository` |
| `SettingsViewModel` | app | `TrackerSettingsRepository` |
| `OnboardingViewModel` | app | None |

### Task 1: Create Repository Hilt Module

File: `app/src/main/java/com/adsamcik/tracker/app/di/RepositoryModule.kt`

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSessionRepository(
        impl: DefaultSessionRepository
    ): SessionRepository

    @Binds
    @Singleton
    abstract fun bindGameRepository(
        impl: DefaultGameRepository
    ): GameRepository

    @Binds
    @Singleton
    abstract fun bindTrackerSettingsRepository(
        impl: DefaultTrackerSettingsRepository
    ): TrackerSettingsRepository
}
```

### Task 2: Add @Inject constructors to repositories

Each repository implementation needs `@Inject constructor`:

```kotlin
// Example for DefaultSessionRepository
class DefaultSessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatchersProvider
) : SessionRepository { ... }
```

### Task 3: Create Infrastructure Module

File: `app/src/main/java/com/adsamcik/tracker/app/di/InfrastructureModule.kt`

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object InfrastructureModule {

    @Provides
    @Singleton
    fun provideDispatchersProvider(): DispatchersProvider = 
        DefaultDispatchersProvider()

    @Provides
    @Singleton
    fun provideClock(): Clock = SystemClock

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = 
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.database(context)
}
```

### Task 4: Convert ViewModels to @HiltViewModel

Example for StatsViewModel:

```kotlin
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {
    // ... existing implementation
}
```

Repeat for: `MainViewModel`, `GameViewModel`, `SettingsViewModel`, `OnboardingViewModel`

### Task 5: Update Compose routes

Replace custom factory with `hiltViewModel()`:

```kotlin
// Before
@Composable
fun StatsRoute() {
    val factory = LocalViewModelFactory.current
    val vm: StatsViewModel = viewModel(factory = factory)
    // ...
}

// After
@Composable
fun StatsRoute() {
    val vm: StatsViewModel = hiltViewModel()
    // ...
}
```

Files to update:
- `statistics/.../ui/compose/StatsRoute.kt`
- `game/.../ui/compose/GameRoute.kt`
- `app/.../settings/SettingsRoute.kt`
- `app/.../ui/MainRoot.kt`
- Any other routes using ViewModels

### Task 6: Delete AppGraph.ViewModelFactory

File: `app/src/main/java/com/adsamcik/tracker/app/AppGraph.kt`

Remove:
- `ViewModelFactory` inner class (lines ~108-125)
- `viewModelFactory` property
- Private repository properties used only for ViewModels

### Task 7: Remove LocalViewModelFactory

Search and remove:
```kotlin
grep -r "LocalViewModelFactory" --include="*.kt"
```

Delete the composition local definition and all usages.

### Task 8: Migrate Dashboard Providers

These need Hilt modules:
- `dailySummaryProvider`
- `dailyPointsProvider`
- `goalProgressProvider`

Create bindings in appropriate modules.

### Task 9: Update AppGraphModule

File: `app/src/main/java/com/adsamcik/tracker/app/di/AppGraphModule.kt`

Update to not reference AppGraph for ViewModel dependencies.
May need to restructure or remove if AppGraph becomes minimal.

### Task 10: Update Tests

Add Hilt test support:

```kotlin
@HiltAndroidTest
class StatsViewModelTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)
    
    @Inject
    lateinit var sessionRepository: SessionRepository
    
    @Before
    fun setup() {
        hiltRule.inject()
    }
}
```

### Verification

```
./gradlew clean assembleDebug testDebugUnitTest
```

### Acceptance Criteria
- [ ] All ViewModels use `@HiltViewModel`
- [ ] Routes use `hiltViewModel()`
- [ ] AppGraph.ViewModelFactory deleted
- [ ] No runtime DI failures
- [ ] Tests pass with Hilt

---

## Plan 7: Static Singleton Migration

### Scope
Convert stateful `object` declarations to injectable services.

### Prerequisites
- Plan 4 complete (Hilt DI infrastructure)

### Singletons to Evaluate

| Object | Stateful? | Action |
|--------|-----------|--------|
| `WifiPermissionHintNotifier` | Yes (prefs) | Convert to @Inject class |
| `TrackerListenerManager` | Yes (scope) | Convert to @Inject class |
| `TrackerTimerManager` | Yes (timers) | Convert to @Inject class |
| `TrackerNotificationProvider` | No | Keep as object |
| `TelephonyUtils` | No | Keep as object |
| `PolicyIntervalMapper` | No | Keep as object |
| `Shortcuts` | Uses EntryPoint | Review if can simplify |

### Task 1: Convert WifiPermissionHintNotifier

File: `tracker/src/main/java/com/adsamcik/tracker/tracker/notification/WifiPermissionHintNotifier.kt`

```kotlin
// Before
internal object WifiPermissionHintNotifier {
    private const val PREFS_NAME = "permission_hints"
    fun maybeNotify(context: Context) { ... }
}

// After
@Singleton
class WifiPermissionHintNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHintsStore: PermissionHintsStore
) {
    suspend fun maybeNotify() { ... }
}
```

Update all call sites from `WifiPermissionHintNotifier.maybeNotify(ctx)` to injected instance.

### Task 2: Convert TrackerListenerManager

File: `tracker/src/main/java/com/adsamcik/tracker/tracker/module/TrackerListenerManager.kt`

Current: `internal object TrackerListenerManager : CoroutineScope`

```kotlin
@Singleton
class TrackerListenerManager @Inject constructor(
    private val appScope: CoroutineScope
) {
    // Use injected scope instead of implementing CoroutineScope
}
```

### Task 3: Convert TrackerTimerManager

File: `tracker/src/main/java/com/adsamcik/tracker/tracker/component/TrackerTimerManager.kt`

Evaluate timer state and convert to injectable if stateful.

### Task 4: Review Shortcuts.kt

File: `tracker/src/main/java/com/adsamcik/tracker/tracker/shortcut/Shortcuts.kt`

Currently uses `@EntryPoint` for Hilt access. Evaluate if can be simplified now that more is in Hilt.

### Task 5: Update call sites

For each converted singleton, find all usages:
```
grep -r "WifiPermissionHintNotifier\." --include="*.kt"
grep -r "TrackerListenerManager\." --include="*.kt"
grep -r "TrackerTimerManager\." --include="*.kt"
```

Update to use injected instances.

### Task 6: Create test fakes

For each converted service, create test doubles:

```kotlin
class FakeWifiPermissionHintNotifier : WifiPermissionHintNotifier {
    var notifyCalled = false
    override suspend fun maybeNotify() { notifyCalled = true }
}
```

### Task 7: Register in Hilt modules

Add bindings for new injectable classes:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class TrackerModule {
    @Binds
    @Singleton
    abstract fun bindWifiPermissionHintNotifier(
        impl: WifiPermissionHintNotifier
    ): WifiPermissionHintNotifier
}
```

### Verification

```
./gradlew clean assembleDebug testDebugUnitTest
```

Verify at runtime:
- Tracking starts/stops correctly
- Notifications work
- No null pointer exceptions from missing injection

### Acceptance Criteria
- [ ] No stateful singletons with mutable state
- [ ] All external dependencies injected
- [ ] Stateless utilities remain as objects
- [ ] Test doubles available for stateful services
- [ ] All tests pass

---

## Completion Checklist

- [ ] Commit: "Migrate ViewModels to Hilt"
- [ ] Commit: "Convert stateful singletons to injectable services"
- [ ] Full test suite passes
- [ ] Manual test: tracking flow end-to-end
- [ ] Document DI patterns for future reference
