# ViewModel DI Standards & Architecture

**Date:** 2025-10-08  
**Status:** Enforced  
**North Star:** Pure constructor injection, Flow-based reactivity, no AndroidViewModel, no direct Context/DB access in ViewModels.

---

## ✅ Current Standard (All New Code)

### ViewModel Structure
```kotlin
// Contract: ViewModel for [Feature] screen
// Inputs: [Repository] for data access
// Outputs: StateFlows for UI state
// Errors: None (repository handles failure cases)
class FeatureViewModel(
    private val featureRepository: FeatureRepository
) : ViewModel() {
    
    val data: StateFlow<FeatureData> = featureRepository.getData()
        .stateIn(viewModelScope, SharingStarted.Lazily, FeatureData.Empty)
    
    fun onAction(action: Action) {
        viewModelScope.launch {
            featureRepository.performAction(action)
        }
    }
}
```

### AppGraph Registration
```kotlin
// In app/src/main/java/com/adsamcik/tracker/app/AppGraph.kt
class AppGraph(
    val dispatchers: DispatchersProvider,
    val appScope: CoroutineScope,
) {
    lateinit var application: Application
        private set
    
    fun initialize(app: Application) {
        application = app
    }
    
    // Repositories (application-scoped, lazy-initialized)
    private val featureRepository by lazy { 
        DefaultFeatureRepository(application, dispatchers.io) 
    }
    
    class ViewModelFactory(private val appGraph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return when (modelClass) {
                FeatureViewModel::class.java -> FeatureViewModel(appGraph.featureRepository) as T
                // ... other ViewModels
                else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
    
    val viewModelFactory by lazy { ViewModelFactory(this) }
}
```

### Route Usage
```kotlin
@Composable
fun FeatureRoute() {
    val factory = LocalViewModelFactory.current
    val vm: FeatureViewModel = viewModel(factory = factory)
    val data by vm.data.collectAsStateWithLifecycle()
    
    FeatureScreen(
        data = data,
        onAction = vm::onAction
    )
}
```

---

## ✅ Current Implementation Status

### Compliant ViewModels (Constructor-injected, Flow-based)
| ViewModel | Repository Dependency | State Type | Module | Registration |
|-----------|----------------------|------------|---------|--------------|
| `StatsViewModel` | `SessionRepository` | StateFlow + Paging | statistics | AppGraph ✓ |
| `GameViewModel` | `GameRepository` | StateFlow | game | AppGraph ✓ |
| `SettingsViewModel` | `TrackerSettingsRepository` | StateFlow | app | AppGraph ✓ |
| `MainViewModel` | None (UI state only) | StateFlow | app | AppGraph ✓ |
| `OnboardingViewModel` | None (UI state only) | StateFlow | app | AppGraph ✓ |
| `MapStore` | `LayerManager` (injected) | StateFlow + SharedFlow | map | Factory ✓ |
| `MapViewModel` | None (lightweight test VM) | StateFlow | map | Factory ✓ |

### Repositories (Interface + DefaultImpl Pattern)
| Repository | Interface | Implementation | Data Sources | Scope |
|------------|-----------|----------------|--------------|-------|
| `SessionRepository` | ✓ | `DefaultSessionRepository` | SessionDao, Time | Application |
| `GameRepository` | ✓ | `DefaultGameRepository` | PointsDao, GoalTracker, ChallengeManager | Application |
| `TrackerSettingsRepository` | ✓ | `DefaultTrackerSettingsRepository` | Preferences (DataStore migration TBD) | Application |
| `StatsRepository` | ✓ | `DefaultStatsRepository` | StatsCacheDao, aggregation logic | Application |

---

## 🔶 Legacy (Deprecated, Migration Pending)

### AndroidViewModel Usages
| ViewModel | Module | Reason | Migration Path |
|-----------|--------|--------|----------------|
| `TrackerViewModel` | tracker | Legacy Fragment registration | Migrate to TrackerRoute + repository |

**Migration Plan:**
1. Extract tracker state management into `TrackerRepository`
2. Create `TrackerViewModel(trackerRepository)` with constructor injection
3. Register in AppGraph
4. Wire into TrackerRoute (Compose)
5. Remove legacy Fragment + AndroidViewModel

---

## ⚠️ Anti-Patterns (DO NOT USE)

### ❌ Direct Context/DB Access in ViewModel
```kotlin
// WRONG
class BadViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.database(application)
    val data = db.dao().getAll() // Direct DB access
}

// CORRECT
class GoodViewModel(
    private val repository: Repository
) : ViewModel() {
    val data = repository.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
```

### ❌ LiveData Exposure
```kotlin
// WRONG
class BadViewModel(repo: Repository) : ViewModel() {
    val data: LiveData<Data> = repo.getDataLive()
}

// CORRECT
class GoodViewModel(repo: Repository) : ViewModel() {
    val data: StateFlow<Data> = repo.getData()
        .stateIn(viewModelScope, SharingStarted.Lazily, Data.Empty)
}
```

### ❌ Manual ViewModelProvider.Factory in Routes
```kotlin
// WRONG
@Composable
fun BadRoute() {
    val vm: ViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MyViewModel(/* manual dependencies */) as T
            }
        }
    )
}

// CORRECT
@Composable
fun GoodRoute() {
    val factory = LocalViewModelFactory.current
    val vm: MyViewModel = viewModel(factory = factory)
}
```

---

## Repository Design Principles

### Interface Definition
```kotlin
// In feature module (e.g., statistics/repository/SessionRepository.kt)
interface SessionRepository {
    /**
     * Streams all sessions ordered by start time DESC.
     * Room PagingSource auto-generated; supports efficient large dataset paging.
     */
    fun getAllSessionsPaged(): PagingSource<Int, TrackerSession>
    
    /**
     * Retrieves summary statistics for all sessions.
     * Errors: Returns empty list on failure (logged internally).
     */
    suspend fun getSummaryStats(): List<Stat>
    
    /**
     * Retrieves session by ID.
     * Returns null if not found.
     */
    suspend fun getSession(id: Long): TrackerSession?
}
```

### Implementation
```kotlin
// In same module (e.g., statistics/repository/DefaultSessionRepository.kt)
class DefaultSessionRepository(
    private val context: Context // Minimal Android dep for DB initialization
) : SessionRepository {
    
    private val db by lazy { AppDatabase.database(context) }
    private val sessionDao by lazy { db.sessionDao() }
    
    override fun getAllSessionsPaged(): PagingSource<Int, TrackerSession> {
        return sessionDao.getAllPaged()
    }
    
    override suspend fun getSummaryStats(): List<Stat> = withContext(Dispatchers.IO) {
        try {
            sessionDao.getAll().map { /* aggregation logic */ }
        } catch (e: Exception) {
            Logger.error("Failed to load summary stats", e)
            emptyList()
        }
    }
    
    override suspend fun getSession(id: Long): TrackerSession? = withContext(Dispatchers.IO) {
        sessionDao.get(id)
    }
}
```

### Testing
```kotlin
// Test fake (in test source set or testFixtures)
class FakeSessionRepository : SessionRepository {
    private val sessions = mutableListOf<TrackerSession>()
    
    override fun getAllSessionsPaged(): PagingSource<Int, TrackerSession> {
        // Return TestPagingSource or in-memory variant
    }
    
    override suspend fun getSummaryStats(): List<Stat> {
        return sessions.map { /* test aggregation */ }
    }
    
    override suspend fun getSession(id: Long): TrackerSession? {
        return sessions.find { it.id == id }
    }
    
    // Test helpers
    fun addSession(session: TrackerSession) { sessions.add(session) }
    fun clear() { sessions.clear() }
}
```

---

## Composition Root (AppGraph) Management

### Adding a New ViewModel

1. **Create Repository Interface + Implementation**
   ```kotlin
   // feature/repository/FeatureRepository.kt
   interface FeatureRepository { /* ... */ }
   class DefaultFeatureRepository(context: Context) : FeatureRepository { /* ... */ }
   ```

2. **Create ViewModel with Constructor Injection**
   ```kotlin
   // feature/ui/FeatureViewModel.kt
   class FeatureViewModel(
       private val featureRepository: FeatureRepository
   ) : ViewModel() { /* ... */ }
   ```

3. **Register in AppGraph**
   ```kotlin
   // app/src/main/java/com/adsamcik/tracker/app/AppGraph.kt
   class AppGraph(/* ... */) {
       // Add repository
       private val featureRepository by lazy { 
           DefaultFeatureRepository(application) 
       }
       
       class ViewModelFactory(private val appGraph: AppGraph) : ViewModelProvider.Factory {
           override fun <T : ViewModel> create(modelClass: Class<T>): T {
               return when (modelClass) {
                   // Add ViewModel case
                   FeatureViewModel::class.java -> FeatureViewModel(appGraph.featureRepository) as T
                   // ... existing cases
                   else -> throw IllegalArgumentException(/* ... */)
               }
           }
       }
   }
   ```

4. **Use in Route**
   ```kotlin
   // feature/ui/FeatureRoute.kt
   @Composable
   fun FeatureRoute() {
       val factory = LocalViewModelFactory.current
       val vm: FeatureViewModel = viewModel(factory = factory)
       // ... collect state, render UI
   }
   ```

---

## Testing Strategy

### ViewModel Tests
```kotlin
@Test
fun `loads data from repository`() = runTest {
    // Arrange
    val fakeRepo = FakeFeatureRepository()
    fakeRepo.setData(testData)
    val vm = FeatureViewModel(fakeRepo)
    
    // Act
    val state = vm.data.first()
    
    // Assert
    assertEquals(testData, state)
}
```

### Integration Tests (with AppGraph)
```kotlin
@Test
fun `AppGraph provides FeatureViewModel with real dependencies`() {
    val appGraph = AppGraph(
        dispatchers = TestDispatchers,
        appScope = TestScope()
    )
    appGraph.initialize(ApplicationProvider.getApplicationContext())
    
    val vm = appGraph.viewModelFactory.create(FeatureViewModel::class.java)
    assertNotNull(vm)
}
```

---

## Migration Checklist (Legacy → Modern)

When encountering legacy ViewModel code:

- [ ] Extract direct DB/Context access into Repository interface
- [ ] Create DefaultRepository implementation
- [ ] Refactor ViewModel to accept Repository via constructor
- [ ] Replace LiveData with StateFlow/Flow
- [ ] Register in AppGraph.ViewModelFactory
- [ ] Update Route to use LocalViewModelFactory
- [ ] Write unit tests with fake repository
- [ ] Remove AndroidViewModel base class
- [ ] Delete legacy Fragment if fully migrated to Compose

---

## Related Documents
- `.github/copilot-instructions.md` (§16A: DI & Composition Root)
- `ARCHITECTURE_LIVEDATA_DEPRECATION.md` (LiveData migration plan)
- `AppGraph.kt` (Composition root implementation)
- `COMPOSE_MAIN_APP_ARCHITECTURE.md` (Route-based navigation structure)

---

## LLM Generation Rules (ViewModel DI)

When adding a new ViewModel or modifying existing:

1. **ALWAYS use constructor injection**; NEVER extend AndroidViewModel
2. **ALWAYS inject repository abstractions**; NEVER inject Context/Application/Database directly
3. **ALWAYS expose StateFlow/Flow**; NEVER expose LiveData
4. **ALWAYS register in AppGraph.ViewModelFactory**; NEVER use inline factory in Route
5. **ALWAYS provide interface + default implementation** for repositories
6. **ALWAYS create test fake** for repository when introducing new data source
7. **ALWAYS document contract** (inputs/outputs/errors) in class KDoc header

**Prompt Pattern for New ViewModel:**
```
// Copilot: Create FeatureViewModel with constructor-injected FeatureRepository.
// Expose StateFlow<FeatureState>. Register in AppGraph. Provide FakeFeatureRepository for tests.
```

---

**Enforcement:** Any PR introducing AndroidViewModel or direct DB access in ViewModel will be rejected per copilot-instructions.md §16A.
