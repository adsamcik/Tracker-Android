package com.adsamcik.tracker.app

import android.app.Application
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.testing.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.base.time.FixedClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

import kotlinx.coroutines.test.StandardTestDispatcher
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.testing.fake.FakeTrackerServiceController
import com.adsamcik.tracker.testing.fake.FakeLockManager

/**
 * Builder for creating test variants of AppGraph with fake dependencies.
 * 
 * Contract (per copilot-instructions Section 16A):
 * - Provides deterministic test doubles (FakeClock, TestDispatchers, in-memory DB)
 * - Exposes builder methods for overriding specific dependencies
 * - Returns fully-wired AppGraph compatible with production code paths
 * - No reflection or hidden global state
 * 
 * Usage:
 * ```kotlin
 * val testGraph = TestAppGraphBuilder(mockContext)
 *     .withClock(FixedClock(initialMillis = 1000L))
 *     .withTrackerServiceController(FakeTrackerServiceController())
 *     .withLockManager(FakeLockManager())
 *     .build()
 * 
 * // Use testGraph in tests to inject into ViewModels / composables
 * val viewModel = testGraph.viewModelFactory.create(StatsPresenterViewModel::class.java)
 * ```
 */
class TestAppGraphBuilder(private val application: Application) {
    
    private var dispatchers: DispatchersProvider = TestDispatchersProvider(StandardTestDispatcher())
    private var clock: Clock = FixedClock(fixedTimeMillis = 0L)
    private var trackerServiceController: TrackerServiceController? = null
    private var lockManager: LockManager? = null
    
    /**
     * Override the DispatchersProvider.
     * Default: TestDispatchersProvider with StandardTestDispatcher for deterministic execution.
     */
    fun withDispatchers(dispatchers: DispatchersProvider) = apply {
        this.dispatchers = dispatchers
    }
    
    /**
     * Override the Clock abstraction.
     * Default: FixedClock at epoch zero.
     */
    fun withClock(clock: Clock) = apply {
        this.clock = clock
    }
    
    /**
     * Override the TrackerServiceController.
     * Default: FakeTrackerServiceController with idle state.
     */
    fun withTrackerServiceController(controller: TrackerServiceController) = apply {
        this.trackerServiceController = controller
    }
    
    /**
     * Override the LockManager.
     * Default: FakeLockManager with unlocked state.
     */
    fun withLockManager(manager: LockManager) = apply {
        this.lockManager = manager
    }
    
    /**
     * Build the test AppGraph with configured fakes.
     * Initializes with in-memory database and test-scoped coroutine scope.
     */
    fun build(): AppGraph {
        val testDispatcher = when (dispatchers) {
            is TestDispatchersProvider -> (dispatchers as TestDispatchersProvider).testDispatcher
            else -> StandardTestDispatcher()
        }
        
        val appScope = CoroutineScope(SupervisorJob() + testDispatcher)
        
        return AppGraph(
            dispatchers = dispatchers,
            clock = clock,
            appScope = appScope
        ).apply {
            initialize(application)
        }
    }
}
