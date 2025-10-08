# LiveData Deprecation & Flow Migration Plan

**Date:** 2025-10-08  
**Status:** In Progress  
**North Star:** Pure Flow-based reactive architecture; eliminate all LiveData usage across ViewModels, Services, and cross-module boundaries.

---

## Current State Assessment

### ✅ Completed Migrations
- **StatsViewModel**: Constructor-injected `SessionRepository`, pure Flow/StateFlow/Paging
- **GameViewModel**: Constructor-injected `GameRepository`, pure Flow/StateFlow
- **SettingsViewModel**: Constructor-injected `TrackerSettingsRepository`, pure StateFlow
- **MainViewModel**: Pure StateFlow
- **OnboardingViewModel**: Pure StateFlow
- **MapStore/MapViewModel**: Pure StateFlow/SharedFlow

### 🔶 Deprecated (Marked with @Deprecated, Flow alternatives exist)
1. **TrackerService.sessionInfo**: `LiveData<TrackerSessionInfo?>` → Use `sessionFlow: StateFlow<TrackerSession?>`
2. **SessionUpdateReceiver**: LiveData broadcasts → Migrate to Flow-based update receiver
3. **GoalTracker LiveData APIs**: `stepsDay`, `goalDay`, `stepsWeek`, `goalWeek` → Already bridged via repository Flow
4. **Room DAO LiveData methods**: `SessionDataDao.getLive()`, `PointsAwardedDao.countBetweenLive()` → Add Flow equivalents

### ⚠️ Legacy (Requires migration or removal)
- **TrackerViewModel (AndroidViewModel)**: Used in legacy Fragment; requires migration to Compose route + DI
- **StatsDetailActivity.ViewModel**: Legacy activity-local ViewModel with direct DB access
- **NonNullLiveData utility**: Custom LiveData abstraction; deprecate post-migration
- **PreferenceListenerType**: Uses MutableLiveData internally; migrate to Flow-based preference observation

---

## Deprecation Strategy

### Phase 1: Mark & Document (This PR) ✅
- Add `@Deprecated` annotations with replacement guidance
- Document Flow alternatives in KDoc
- Create migration examples

### Phase 2: Migrate Active Usages (Next Sprint)
- Convert SessionUpdateReceiver to Flow
- Add Flow-based DAO methods
- Update TrackerService consumers

### Phase 3: Remove (Post-verification)
- Delete deprecated APIs after 1-2 release cycles
- Remove LiveData dependencies from modules

---

## Deprecated APIs & Replacements

### TrackerService
```kotlin
// DEPRECATED
@Deprecated(
    message = "Use sessionFlow instead for Flow-based reactivity",
    replaceWith = ReplaceWith("sessionFlow"),
    level = DeprecationLevel.WARNING
)
val sessionInfo: LiveData<TrackerSessionInfo?>

// REPLACEMENT
val sessionFlow: StateFlow<TrackerSession?>
```

**Migration:**
```kotlin
// Before
TrackerService.sessionInfo.observe(lifecycleOwner) { info -> ... }

// After
lifecycleScope.launch {
    TrackerService.sessionFlow.collectLatest { session -> ... }
}
```

### SessionUpdateReceiver
```kotlin
// DEPRECATED
@Deprecated(
    message = "LiveData-based update receiver. Migrate to Flow-based alternative.",
    level = DeprecationLevel.WARNING
)
val collectionData: LiveData<CollectionData>

// REPLACEMENT (proposed)
interface TrackerUpdateFlow {
    val collectionUpdates: Flow<CollectionData>
    val sessionUpdates: Flow<TrackerSession>
}
```

### GoalTracker
```kotlin
// DEPRECATED (internal bridging; consumers use GameRepository)
@Deprecated(
    message = "Internal LiveData API. Use GameRepository.getStepsSummary() for Flow-based access.",
    level = DeprecationLevel.WARNING
)
val stepsDay: LiveData<Int>
```

**Migration:**
```kotlin
// Before (direct access)
GoalTracker.stepsDay.observe(lifecycleOwner) { steps -> ... }

// After (via repository + ViewModel)
val vm: GameViewModel = viewModel(factory = LocalViewModelFactory.current)
val stepsSummary by vm.stepsSummary.collectAsState()
```

### Room DAOs
```kotlin
// DEPRECATED
@Deprecated(
    message = "Use getFlow() for Flow-based reactivity",
    replaceWith = ReplaceWith("getFlow(id)"),
    level = DeprecationLevel.WARNING
)
@Query("SELECT * FROM session_table WHERE id = :id")
fun getLive(id: Long): LiveData<TrackerSession>

// REPLACEMENT
@Query("SELECT * FROM session_table WHERE id = :id")
fun getFlow(id: Long): Flow<TrackerSession?>
```

---

## Migration Examples

### Example 1: ViewModel with LiveData → Flow
**Before:**
```kotlin
class LegacyViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.database(app).sessionDao()
    val session: LiveData<TrackerSession?> = dao.getLive(sessionId)
}
```

**After:**
```kotlin
class ModernViewModel(
    private val sessionRepository: SessionRepository
) : ViewModel() {
    val session: StateFlow<TrackerSession?> = sessionRepository.getSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
}
```

### Example 2: Fragment observing LiveData → Compose collecting Flow
**Before:**
```kotlin
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    viewModel.session.observe(viewLifecycleOwner) { session ->
        binding.title.text = session?.name
    }
}
```

**After:**
```kotlin
@Composable
fun SessionRoute() {
    val vm: SessionViewModel = viewModel(factory = LocalViewModelFactory.current)
    val session by vm.session.collectAsStateWithLifecycle()
    Text(text = session?.name ?: "")
}
```

---

## Testing Strategy

1. **Dual exposure period**: Keep both LiveData and Flow APIs during transition
2. **Instrumentation tests**: Verify Flow behavior matches LiveData semantics
3. **Baseline profile update**: Ensure Flow collection paths are optimized
4. **Performance regression check**: Compare memory & CPU overhead

---

## Removal Criteria (Phase 3)

Before removing deprecated LiveData APIs:
- [ ] Zero usages across all modules (verified via grep)
- [ ] All instrumentation tests passing with Flow-only paths
- [ ] At least one release cycle with deprecation warnings
- [ ] Documentation updated (API docs, migration guides)

---

## Notes

- **Compose runtime.livedata dependency**: Remove from gradle after migration complete
- **Preference observation**: Consider migrating to DataStore + Flow (separate effort)
- **Third-party LiveData**: Room's `@Query` returning LiveData is acceptable short-term; add Flow overloads
- **Legacy activities**: StatsDetailActivity ViewModel is activity-scoped; defer until activity→route migration

---

## Related Documents
- `.github/copilot-instructions.md` (§5: State & Concurrency, §16A: DI)
- `COMPOSE_MIGRATION_EVALUATION_2025-09-29.md` (Short-term action #6)
- `AppGraph.kt` (Composition root with repository injection)
