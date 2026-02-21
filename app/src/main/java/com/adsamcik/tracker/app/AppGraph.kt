package com.adsamcik.tracker.app

import android.app.Application
import android.content.Context
import com.adsamcik.tracker.game.di.DefaultDailyPointsProvider
import com.adsamcik.tracker.game.di.DefaultGoalProgressProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.tracker.controller.DefaultTrackerServiceController
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.controller.DefaultLockManager
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.di.DefaultDailySummaryProvider
import com.adsamcik.tracker.impexp.exporter.automation.ExportAutomationController
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import kotlinx.coroutines.CoroutineScope

/**
 * Application composition root for dependency injection.
 * 
 * Contract (per copilot-instructions Section 16A):
 * - Single source of truth for all application-scoped dependencies
 * - Explicit constructor injection (no hidden service locators)
 * - Separate interface from implementation across module boundaries
 * - Provide stable abstractions (Clock, DispatchersProvider)
 * - Support test variants via constructor parameters
 * 
 * Lifecycle Scopes:
 * - Application scope: repositories, formatters, time providers (long-lived)
 * - Foreground tracking scope: created when tracking starts (via TrackerService internally)
 * - ViewModel scope: UI logic only, never owns lower-level resources
 * 
 * Testing:
 * - Inject TestDispatchersProvider for deterministic coroutine execution
 * - Inject FixedClock for controlled time progression
 * - Use in-memory Room database variant via AppDatabase.buildInMemory()
 * 
 * Note: ViewModels are now managed by Hilt (@HiltViewModel) and injected via hiltViewModel().
 * Repositories are bound in RepositoryModule and injected into ViewModels automatically.
 */
class AppGraph(
    val dispatchers: DispatchersProvider,
    val clock: Clock,
    val appScope: CoroutineScope,
) {
    
    // Late-initialized application instance (set during Application.onCreate)
    lateinit var application: Application
        private set
    
    fun initialize(app: Application) {
        application = app
    }
    
    // Core infrastructure (application-scoped)
    val database: AppDatabase by lazy {
        AppDatabase.database(application)
    }
    
    // GameRepository via Hilt EntryPoint (avoids manual construction of deep dependency chain)
    private val gameRepository: com.adsamcik.tracker.game.repository.GameRepository by lazy {
        val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
            application,
            GameRepositoryEntryPoint::class.java,
        )
        entryPoint.gameRepository()
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface GameRepositoryEntryPoint {
        fun gameRepository(): com.adsamcik.tracker.game.repository.GameRepository
    }

    private val exportPlanStore by lazy {
        ExportPlanStore(application, dispatchers.io, clock)
    }

    val exportAutomationController: ExportAutomationController by lazy {
        ExportAutomationController(application, exportPlanStore, dispatchers, clock, appScope)
    }
    
    // Services (application-scoped)
    val trackerServiceController: TrackerServiceController by lazy {
        DefaultTrackerServiceController()
    }
    
    val lockManager: LockManager by lazy {
        DefaultLockManager(trackerServiceController)
    }
    
    // Dashboard providers (application-scoped)
    val dailySummaryProvider: DailySummaryProvider by lazy {
        DefaultDailySummaryProvider(
            database.sessionDao(),
            database.dailySummaryDao(),
            database.liveStatsDao(),
            dispatchers.io
        )
    }
    
    val dailyPointsProvider: DailyPointsProvider by lazy {
        DefaultDailyPointsProvider(gameRepository, appScope)
    }
    
    val goalProgressProvider: GoalProgressProvider by lazy {
        DefaultGoalProgressProvider(application, gameRepository, appScope)
    }
    
    companion object {
        /**
         * Create production AppGraph with real system dependencies.
         */
        fun create(
            dispatchers: DispatchersProvider,
            clock: Clock,
            appScope: CoroutineScope
        ): AppGraph = AppGraph(dispatchers, clock, appScope)
    }
}

/**
 * Test graph builder for deterministic testing.
 * 
 * Usage:
 * ```
 * val testGraph = TestAppGraphBuilder()
 *     .withTestDispatchers(testDispatcher)
 *     .withFixedClock(startTimeMs = 1000L)
 *     .build(context)
 * ```
 */
class TestAppGraphBuilder {
    private var dispatchers: DispatchersProvider? = null
    private var clock: Clock? = null
    private var scope: CoroutineScope? = null
    
    fun withTestDispatchers(dispatcher: kotlinx.coroutines.CoroutineDispatcher): TestAppGraphBuilder {
        this.dispatchers = com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider(dispatcher)
        return this
    }
    
    fun withFixedClock(startTimeMs: Long = 0L): TestAppGraphBuilder {
        this.clock = com.adsamcik.tracker.shared.base.time.FixedClock(startTimeMs)
        return this
    }
    
    fun withScope(scope: CoroutineScope): TestAppGraphBuilder {
        this.scope = scope
        return this
    }
    
    fun build(context: Context): AppGraph {
        val finalDispatchers = dispatchers 
            ?: com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
        val finalClock = clock 
            ?: com.adsamcik.tracker.shared.base.time.SystemClock
        val finalScope = scope 
            ?: CoroutineScope(kotlinx.coroutines.SupervisorJob() + finalDispatchers.default)
        
        return AppGraph(finalDispatchers, finalClock, finalScope).apply {
            initialize(context as Application)
        }
    }
}
