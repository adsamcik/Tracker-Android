# Agent C: Proto DataStore & Async Refactoring

> **Archived proposal.** This plan is not the current architecture. The proposed
> `tracking_toggles` store never gained a production caller and was removed; tracking source
> settings remain owned by `TrackingParamsRepository`.

**Assigned Plans**: 2, 5  
**Est. Time**: 10 hours  
**Dependencies**: Plan 5 depends on Plan 2

---

## Plan 2: Proto DataStore Unification

### Scope
Migrate all Preferences DataStore and SharedPreferences to Proto DataStore.

### Current State Inventory

| Location | Type | New Proto |
|----------|------|-----------|
| `tracker/.../TrackerDashboard.kt#L605` | Preferences DataStore | `tracking_toggles.proto` |
| `tracker/.../WifiPermissionHintNotifier.kt` | SharedPreferences | `permission_hints.proto` |
| `spreferences/.../OnboardingRepository.kt` | SharedPreferences sync | Remove (already has proto) |
| `spreferences/.../LegacyPreferenceStore.kt` | Preferences bridge | Delete |
| `spreferences/.../Preferences.kt` | Legacy accessor | Delete |

### Task 1: Create tracking_toggles.proto

File: `tracker/src/main/proto/tracking_toggles.proto`
```protobuf
syntax = "proto3";

option java_package = "com.adsamcik.tracker.tracker.proto";
option java_multiple_files = true;

message TrackingTogglesProto {
  map<string, bool> toggles = 1;
}
```

### Task 2: Create permission_hints.proto

File: `tracker/src/main/proto/permission_hints.proto`
```protobuf
syntax = "proto3";

option java_package = "com.adsamcik.tracker.tracker.proto";
option java_multiple_files = true;

message PermissionHintsProto {
  int64 wifi_hint_last_shown = 1;
  int32 wifi_hint_shown_count = 2;
}
```

### Task 3: Configure protobuf in tracker module

File: `tracker/build.gradle.kts`

Add protobuf plugin and configuration (reference `spreferences/build.gradle.kts` for pattern).

### Task 4: Migrate TrackerDashboard.kt

File: `tracker/src/main/java/com/adsamcik/tracker/tracker/ui/compose/TrackerDashboard.kt`

1. Create `TrackingTogglesDataStore.kt`:
```kotlin
val Context.trackingTogglesDataStore: DataStore<TrackingTogglesProto> by dataStore(
    fileName = "tracking_toggles.pb",
    serializer = TrackingTogglesSerializer
)

object TrackingTogglesSerializer : Serializer<TrackingTogglesProto> {
    override val defaultValue = TrackingTogglesProto.getDefaultInstance()
    override suspend fun readFrom(input: InputStream) = TrackingTogglesProto.parseFrom(input)
    override suspend fun writeTo(t: TrackingTogglesProto, output: OutputStream) = t.writeTo(output)
}
```

2. Update `rememberPrefBoolean()` to use proto:
```kotlin
@Composable
private fun rememberPrefBoolean(keyRes: Int, defaultRes: Int): Boolean {
    val context = LocalContext.current
    val keyName = remember(keyRes) { context.getString(keyRes) }
    val default = remember(defaultRes) { context.getString(defaultRes).toBoolean() }
    
    val flow = remember(keyName) {
        context.trackingTogglesDataStore.data.map { proto ->
            proto.togglesMap[keyName] ?: default
        }
    }
    return flow.collectAsState(initial = default).value
}
```

### Task 5: Migrate WifiPermissionHintNotifier

File: `tracker/src/main/java/com/adsamcik/tracker/tracker/notification/WifiPermissionHintNotifier.kt`

1. Convert from `object` to injectable class
2. Inject Proto DataStore
3. Create repository pattern:

```kotlin
class WifiPermissionHintNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.permissionHintsDataStore
    
    suspend fun maybeNotify() {
        val hints = dataStore.data.first()
        val now = Time.nowMillis
        
        if (hints.wifiHintShownCount >= MAX_SHOWS) return
        if (now - hints.wifiHintLastShown < COOLDOWN_MS) return
        
        // Show notification...
        
        dataStore.updateData { current ->
            current.toBuilder()
                .setWifiHintLastShown(now)
                .setWifiHintShownCount(current.wifiHintShownCount + 1)
                .build()
        }
    }
}
```

### Task 6: Remove OnboardingRepository sync fallback

File: `spreferences/src/main/java/com/adsamcik/tracker/shared/preferences/onboarding/OnboardingRepository.kt`

Delete `isCompletedSync()` method and its SharedPreferences usage (lines 81-90).
This will be replaced by async check in Plan 5.

### Task 7: Find all Preferences.kt call sites

```
grep -r "Preferences(" --include="*.kt"
grep -r "import.*Preferences$" --include="*.kt"
```

Migrate each to use typed repository pattern.

### Task 8: Delete legacy files

After all migrations:
- Delete `spreferences/.../store/LegacyPreferenceStore.kt`
- Delete `spreferences/.../Preferences.kt`
- Delete any related imports

### Verification

```
./gradlew clean assembleDebug testDebugUnitTest
```

### Acceptance Criteria
- [ ] Zero SharedPreferences in production code
- [ ] Zero Preferences DataStore usage (all Proto)
- [ ] All preferences via typed repositories
- [ ] User data preserved via one-time migration

---

## Plan 5: Async Launch Refactoring

### Scope
Eliminate `runBlocking` and implement async launch flow.

### Prerequisites
- Plan 2 complete (Proto DataStore)

### runBlocking Locations

| File | Line | Purpose | Strategy |
|------|------|---------|----------|
| `LegacyPreferenceStore.kt#L54` | Sync snapshot | Deleted in Plan 2 |
| `TrackerSettingsAccess.kt#L35` | Sync initial | Suspend + cached |
| `OnboardingRepository.kt#L81` | `isCompletedSync()` | Async splash |
| `CollectionExtensions.kt#L274` | Parallel collection | Convert to suspend |
| `NormalizationNeighborhood.kt#L151` | Tile compute | Review context |

### Task 1: Implement Async Splash Screen

File: `app/src/main/java/com/adsamcik/tracker/app/ui/MainActivityCompose.kt`

Use SplashScreen API for async condition check:

```kotlin
class MainActivityCompose : ComponentActivity() {
    private val onboardingRepository: OnboardingRepository by lazy { /* inject */ }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        
        var isReady = false
        var showOnboarding = true
        
        lifecycleScope.launch {
            showOnboarding = !onboardingRepository.isCompleted.first()
            isReady = true
        }
        
        splashScreen.setKeepOnScreenCondition { !isReady }
        
        super.onCreate(savedInstanceState)
        
        setContent {
            if (isReady) {
                if (showOnboarding) {
                    OnboardingRoute()
                } else {
                    MainRoot()
                }
            }
        }
    }
}
```

### Task 2: Migrate TrackerSettingsAccess

File: `spreferences/src/main/java/com/adsamcik/tracker/shared/preferences/settings/TrackerSettingsAccess.kt`

Replace `runBlocking` with suspend function or cached initial:

```kotlin
class TrackerSettingsAccess(
    private val repo: TrackerSettingsRepository
) {
    // Option A: Suspend function
    suspend fun getSettings(): TrackerSettings = repo.data.first()
    
    // Option B: Cached with coroutine scope
    private val _cached = MutableStateFlow<TrackerSettings?>(null)
    val settings: StateFlow<TrackerSettings> = repo.data
        .onStart { /* emit cached or default */ }
        .stateIn(scope, SharingStarted.Eagerly, defaultSettings)
}
```

### Task 3: Convert parallel collection extension

File: `sbase/src/main/java/com/adsamcik/tracker/shared/base/extension/CollectionExtensions.kt`

Change from:
```kotlin
fun <T, Result> Collection<T>.forEachParallelBlocking(func: suspend (T) -> Result): List<Result> = 
    runBlocking { forEachParallel(func).awaitAll() }
```

To:
```kotlin
suspend fun <T, Result> Collection<T>.forEachParallelAwait(func: suspend (T) -> Result): List<Result> = 
    coroutineScope { forEachParallel(func).awaitAll() }
```

Find and update all call sites to use coroutine context.

### Task 4: Review NormalizationNeighborhood

File: `map/src/main/java/com/adsamcik/tracker/map/tiles/NormalizationNeighborhood.kt`

Check if called from coroutine context:
- If yes: convert to suspend function
- If no: inject dispatcher and use `withContext`

### Task 5: Delete sync accessors

After all call sites migrated:
- Delete `isCompletedSync()` from OnboardingRepository
- Delete any remaining sync wrapper methods

### Verification

```
grep -r "runBlocking" --include="*.kt" src/main
# Should return zero matches in production code (test-only allowed)

./gradlew clean assembleDebug testDebugUnitTest
```

Test app launch:
- Fresh install → Onboarding shown
- Existing user → Main screen shown
- No ANRs or freezes during launch

### Acceptance Criteria
- [ ] Zero `runBlocking` in production code
- [ ] App uses SplashScreen API for async launch
- [ ] No main thread blocking during startup
- [ ] All parallel ops use structured concurrency

---

## Completion Checklist

- [ ] Commit: "Migrate to Proto DataStore"
- [ ] Commit: "Implement async launch with SplashScreen"
- [ ] Full test suite passes
- [ ] Manual test: fresh install + existing user flows
