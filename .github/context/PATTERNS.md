<!-- context-init:version:3.0.3 -->
<!-- context-init:generated:2026-02-17 -->

# Code Patterns Reference

<!-- context-init:managed -->
Coding conventions for Tracker Android. For comprehensive LLM standards, see [.github/copilot-instructions.md](../copilot-instructions.md).

## Naming Conventions

<!-- context-init:managed -->

| Type | Convention | Example | Location |
|------|------------|---------|----------|
| Modules | lowercase, domain-based | `tracker`, `sbase`, `impexp` | `settings.gradle.kts` |
| Packages | reverse domain | `com.adsamcik.tracker.tracker` | All modules |
| Classes | PascalCase, descriptive | `TrackerServiceController` | Throughout |
| Interfaces | Domain name (no I prefix) | `TrackerRepository`, `LockManager` | Module APIs |
| Implementations | `Default*` prefix | `DefaultTrackerServiceController` | Internal |
| Factories | `*Factory` suffix | `SessionScopeFactory` | DI layer |
| ViewModels | `*ViewModel`, `@HiltViewModel` | `TrackingSettingsViewModel` | UI layer |
| Composables | PascalCase, `*Route`/`*Screen`/`*Dashboard` | `TrackerRoute`, `MapScreen` | UI layer |
| DAOs | `*Dao` suffix | `LocationSampleDao` | `sbase/database/dao/` |
| Entities | `*Entity`/`*Sample`/`Database*` | `LocationSample`, `FrequentPlaceEntity` | `sbase/database/data/` |
| Sealed Results | `*Result` suffix | `ExportResult`, `LockResult` | Cross-module APIs |

## Architecture Patterns

<!-- context-init:managed -->

### Repository Pattern
```kotlin
// Interface (module public API)
interface LocationRepository {
    fun getLocations(sessionId: Long): Flow<List<Location>>
}

// Implementation (internal)
class DefaultLocationRepository(
    private val dao: LocationSampleDao,
    private val dispatchers: DispatchersProvider
) : LocationRepository {
    override fun getLocations(sessionId: Long) =
        dao.getAllBetween(start, end).flowOn(dispatchers.io)
}
```

### ViewModel Pattern (Hilt)
```kotlin
// Correct: @HiltViewModel with constructor injection
@HiltViewModel
class FeatureViewModel @Inject constructor(
    private val repository: FeatureRepository
) : ViewModel() {
    val uiState: StateFlow<UiState> = repository.getData()
        .map { data -> UiState.Success(data) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)
}

// Wrong: AndroidViewModel, direct DB access, LiveData
```

### Sealed Result Types (Cross-Module)
```kotlin
sealed class ExportResult {
    data class Success(val file: File) : ExportResult()
    data class Error(val message: String, val cause: Throwable?) : ExportResult()
    data object Cancelled : ExportResult()
}
// Never throw across module boundaries -- return sealed results
```

### Composable Route Pattern
```kotlin
@Composable
fun TrackerRoute(
    viewModel: TrackerViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TrackerScreen(state = state, onSettingsClick = onNavigateToSettings)
}

@Composable
private fun TrackerScreen(
    state: TrackerUiState,
    onSettingsClick: () -> Unit
) { /* pure UI, stateless */ }
```

### Component Pipeline Pattern (Tracker)
```kotlin
interface TrackerComponent {
    fun onEnable(context: Context)
    fun onDisable(context: Context)
}

interface TrackerDataConsumerComponent : TrackerComponent {
    val requiredData: Collection<TrackerComponentRequirement>
    fun requirementsMet(cycle: TrackingCycle): Boolean
}

// Pipeline stages: Pre -> Data -> Post
interface PreTrackerComponent : TrackerDataConsumerComponent
interface DataTrackerComponent : TrackerDataConsumerComponent
interface PostTrackerComponent : TrackerDataConsumerComponent
```

### UDF Store Pattern (Map Module)
```kotlin
class MapStore : ViewModel() {
    val state: StateFlow<MapState>        // State
    fun onEvent(event: MapEvent)          // User actions
    val effects: SharedFlow<MapEffect>    // One-shot side effects
}
```

## Dependency Injection Patterns

<!-- context-init:managed -->

### Hilt Module Pattern
```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun bindSessionRepository(impl: DefaultSessionRepository): SessionRepository
}

@Module
@InstallIn(SingletonComponent::class)
object InfrastructureModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getInstance(context)
    @Provides
    fun provideLocationDao(db: AppDatabase): LocationSampleDao = db.locationSampleDao()
}
```

### CompositionLocal Provider Pattern
```kotlin
CompositionLocalProvider(
    LocalTrackerController provides appGraph.trackerServiceController,
    LocalLockManager provides appGraph.lockManager,
    LocalDailySummaryProvider provides appGraph.dailySummaryProvider,
) { AppTheme { MainRoot() } }
```

## State & Concurrency Patterns

<!-- context-init:managed -->

| Pattern | Correct | Wrong |
|---------|---------|-------|
| Flow collection | `collectAsStateWithLifecycle()` | `collectAsState()` |
| Dispatchers | Inject `DispatchersProvider` | Direct `Dispatchers.IO` |
| Scoping | `viewModelScope`, injected scope | `GlobalScope` |
| State | `StateFlow` / `Flow` | `LiveData` |
| Preferences | DataStore (proto) | SharedPreferences |

## Testing Patterns

<!-- context-init:managed -->

### Flow Testing with Turbine
```kotlin
@Test
fun `emits updated state`() = runTest {
    viewModel.uiState.test {
        awaitItem() shouldBe UiState.Loading
        awaitItem() shouldBe UiState.Success(testData)
    }
}
```

### Fakes over Mocks
```kotlin
// Preferred: Fakes from testing-common
val settings = FakeTrackerSettingsRepository()
val locationSource = FakeLocationSource()
locationSource.emitLocation(lat = 50.0, lon = 14.0)
```

### Compose UI Test Pattern
```kotlin
@RunWith(AndroidJUnit4::class)
class TrackerDashboardTest {
    @get:Rule val composeRule = createComposeRule()
    @Test
    fun testSemantics() {
        composeRule.setContent { TrackerDashboard(state = testState) }
        composeRule.onNodeWithTag("settingsButton").assertIsDisplayed()
    }
}
```

## Database Patterns

<!-- context-init:managed -->

### DAO Query Pattern
```kotlin
@Dao
interface LocationSampleDao {
    @Query("SELECT * FROM location_sample WHERE time_ms BETWEEN :from AND :to ORDER BY time_ms")
    fun getAllBetween(from: Long, to: Long): Flow<List<LocationSample>>

    @Query("SELECT * FROM location_sample ORDER BY time_ms DESC")
    fun getAllPaged(): PagingSource<Int, LocationSample>
}
```

### Entity Pattern
```kotlin
@Entity(tableName = "location_sample", indices = [Index("time_ms"), Index("lat", "lon")])
data class LocationSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "time_ms") val timeMs: Long,
    val lat: Double, val lon: Double,
    val altitude: Double?, val accuracy: Float, val speed: Float?
)
```

## Anti-Patterns (Do NOT Use)

<!-- context-init:managed -->

| Pattern | Why | Instead |
|---------|-----|---------|
| XML layouts / Fragments | Legacy | Compose `@Composable` |
| `AndroidView` interop | Perpetuates legacy | Native Compose |
| LiveData | Legacy reactive | `StateFlow` / `Flow` |
| `GlobalScope` | Unstructured | `viewModelScope`, injected scope |
| SharedPreferences (new) | Not type-safe | DataStore (proto) |
| `Dispatchers.IO` directly | Not testable | `DispatchersProvider` |
| KAPT | Slow builds | KSP |
| Inline versions | Unmanageable | `libs.versions.toml` |
| Static mutable singletons | Untestable | Constructor injection |
| Throwing across modules | Unsafe | Sealed `Result` types |

<!-- context-init:user-content-below -->
