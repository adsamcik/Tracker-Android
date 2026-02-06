<!-- context-init:version:3.0.2 -->
<!-- context-init:generated:2026-01-30 -->

# Code Patterns Reference

<!-- context-init:managed -->
Quick reference for coding conventions in Tracker Android. For comprehensive standards, see [.github/copilot-instructions.md](../copilot-instructions.md).

## Naming Conventions

<!-- context-init:managed -->

| Type | Convention | Example |
|------|------------|---------|
| Modules | lowercase, domain-based | `tracker`, `sbase`, `impexp` |
| Packages | reverse domain | `com.adsamcik.tracker.tracker` |
| Classes | PascalCase, descriptive | `TrackerServiceController` |
| Interfaces | Domain name (no I prefix) | `TrackerRepository` |
| Implementations | Default prefix | `DefaultTrackerRepository` |
| Factories | *Factory suffix | `SessionScopeFactory` |
| ViewModels | *ViewModel suffix | `TrackingSettingsViewModel` |
| Composables | PascalCase, Route/Screen suffix | `TrackerRoute`, `StatsScreen` |
| DAOs | *Dao suffix | `LocationDataDao` |
| Entities | Database* or *Sample | `DatabaseLocation`, `LocationSample` |

## Architecture Patterns

<!-- context-init:managed -->

### Repository Pattern
```kotlin
// Interface (module API)
interface LocationRepository {
    fun getLocations(sessionId: Long): Flow<List<Location>>
}

// Implementation (internal)
class DefaultLocationRepository(
    private val dao: LocationDataDao,
    private val dispatchers: DispatchersProvider
) : LocationRepository {
    override fun getLocations(sessionId: Long) = 
        dao.getLocationsBySession(sessionId)
            .flowOn(dispatchers.io)
}
```

### Flow-Based State
```kotlin
// ✅ Correct: StateFlow in ViewModel
class FeatureViewModel(
    private val repository: FeatureRepository
) : ViewModel() {
    val uiState: StateFlow<UiState> = repository.getData()
        .map { data -> UiState.Success(data) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UiState.Loading
        )
}

// ❌ Wrong: Don't use LiveData
// ❌ Wrong: Don't use MutableStateFlow exposed directly
```

### Sealed Result Types
```kotlin
// For cross-module operations
sealed class ExportResult {
    data class Success(val file: File) : ExportResult()
    data class Error(val message: String, val cause: Throwable?) : ExportResult()
    data object Cancelled : ExportResult()
}

// Usage
fun export(data: ExportData): ExportResult {
    return try {
        val file = writeExport(data)
        ExportResult.Success(file)
    } catch (e: IOException) {
        ExportResult.Error("Failed to write export", e)
    }
}
```

## Compose Patterns

<!-- context-init:managed -->

### Route Structure
```kotlin
@Composable
fun FeatureRoute(
    viewModel: FeatureViewModel = hiltViewModel(),
    onNavigate: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    
    FeatureScreen(
        state = state,
        onAction = viewModel::onAction,
        onNavigate = onNavigate
    )
}

@Composable
private fun FeatureScreen(
    state: UiState,
    onAction: (Action) -> Unit,
    onNavigate: (String) -> Unit
) {
    // Stateless UI implementation
}
```

### State Hoisting
```kotlin
// ✅ Correct: Accept state + callbacks
@Composable
fun DataCard(
    data: DisplayData,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier
)

// ❌ Wrong: ViewModel inside composable
@Composable
fun DataCard() {
    val viewModel = viewModel<CardViewModel>() // Don't do this
}
```

### CompositionLocal Access
```kotlin
@Composable
fun TrackingControls() {
    val controller = LocalTrackerController.current
    val lockManager = LocalLockManager.current
    
    val isTracking by controller.isServiceRunningFlow.collectAsState()
    val isLocked by lockManager.isLockedFlow.collectAsState()
    
    // Use controller for actions
    Button(
        onClick = { controller.startTracking() },
        enabled = !isLocked
    ) {
        Text(if (isTracking) "Stop" else "Start")
    }
}
```

### Controller Pattern
```kotlin
// Interface defines contract
interface TrackerServiceController {
    val isServiceRunningFlow: StateFlow<Boolean>
    val sessionInfoFlow: StateFlow<TrackerSessionInfo?>
    fun updateServiceRunning(value: Boolean)
    fun updateSessionInfo(value: TrackerSessionInfo?)
}

// Implementation manages state
class DefaultTrackerServiceController : TrackerServiceController {
    private val _isServiceRunning = MutableStateFlow(false)
    override val isServiceRunningFlow: StateFlow<Boolean> = _isServiceRunning
    
    override fun updateServiceRunning(value: Boolean) {
        _isServiceRunning.value = value
    }
    // ...
}

// UI observes, Service updates
// Composable: controller.isServiceRunningFlow.collectAsState()
// Service: controller.updateServiceRunning(true)
```

## Dependency Injection

<!-- context-init:managed -->

### Constructor Injection
```kotlin
// ✅ Correct: Explicit dependencies
class TrackerRepository(
    private val locationDao: LocationDao,
    private val clock: Clock,
    private val dispatchers: DispatchersProvider
)

// ❌ Wrong: Static singletons
companion object {
    val instance: TrackerRepository // Don't do this
}
```

### Hilt ViewModels
```kotlin
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    @ApplicationContext private val context: Context
) : ViewModel()
```

### AppGraph Registration
```kotlin
// In AppGraph.kt
val newService: NewService by lazy {
    DefaultNewService(database.newDao(), dispatchers)
}
```

## Error Handling

<!-- context-init:managed -->

### Sealed Results (Cross-Module)
```kotlin
// Define result type
sealed class TrackingStartResult {
    data object Success : TrackingStartResult()
    data class PermissionDenied(val permission: String) : TrackingStartResult()
    data class Error(val message: String) : TrackingStartResult()
}

// Return results, don't throw
fun startTracking(): TrackingStartResult {
    if (!hasPermission()) return TrackingStartResult.PermissionDenied("LOCATION")
    // ...
}
```

### Error Surface Guidelines
| Category | User Surface | Notes |
|----------|-------------|-------|
| PermissionDenied | Snackbar + action | Open Settings |
| StorageFull | Dialog | Offer cleanup tips |
| ValidationError | Inline error | In import screen |
| NetworkError | N/A | App is offline-only |

## Testing Patterns

<!-- context-init:managed -->

### Coroutine Tests
```kotlin
@Test
fun `feature works correctly`() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val repository = FakeRepository()
    val viewModel = FeatureViewModel(repository, testDispatcher)
    
    viewModel.load()
    advanceUntilIdle()
    
    assertEquals(expected, viewModel.state.value)
}
```

### MockK Usage
```kotlin
@Test
fun `service calls repository`() {
    val repository = mockk<Repository>(relaxed = true)
    val service = MyService(repository)
    
    service.doAction()
    
    verify { repository.save(any()) }
}
```

### Compose Testing
```kotlin
@Test
fun `button triggers action`() {
    composeTestRule.setContent {
        FeatureScreen(state = testState, onAction = mockAction)
    }
    
    composeTestRule.onNodeWithText("Submit").performClick()
    
    verify { mockAction(Action.Submit) }
}
```

## Async Patterns

<!-- context-init:managed -->

### Dispatcher Usage
| Work Type | Dispatcher | Example |
|-----------|------------|---------|
| Database I/O | `Dispatchers.IO` | Room queries |
| File I/O | `Dispatchers.IO` | Export/import |
| CPU-heavy | `Dispatchers.Default` | Heatmap calculation |
| UI updates | `Dispatchers.Main` | State updates |

### Coroutine Scopes
```kotlin
// ViewModel scope
viewModelScope.launch {
    // Cancelled when ViewModel clears
}

// App scope (from AppGraph)
appScope.launch {
    // Long-running, survives config changes
}
```

### Flow Collection
```kotlin
// In Compose
val state by viewModel.state.collectAsStateWithLifecycle()

// In ViewModel
init {
    repository.data()
        .onEach { updateState(it) }
        .launchIn(viewModelScope)
}
```

## Anti-Patterns (Avoid)

<!-- context-init:managed -->

| ❌ Avoid | ✅ Instead |
|---------|-----------|
| LiveData | Flow / StateFlow |
| Fragments | Compose routes |
| XML layouts | Compose UI |
| AndroidViewModel | Regular ViewModel + Hilt |
| GlobalScope | viewModelScope or injected scope |
| SharedPreferences (new) | DataStore |
| Static singletons | Constructor injection |
| KAPT | KSP |
| Network calls | Keep offline (privacy) |
| Inline dependency versions | Version catalog |

<!-- context-init:user-content-below -->
