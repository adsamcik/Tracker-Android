package com.adsamcik.tracker.tracker.controller

import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.stats.api.PolicyState
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.engine.ski.RealTimeSkiState
import com.adsamcik.tracker.tracker.data.PersistenceError
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fake implementation of TrackerServiceController for testing.
 * 
 * Contract:
 * - Input: Test code calls updateXxx methods to simulate tracking events
 * - Output: Reactive StateFlows emit test-controlled values
 * - Thread-safety: MutableStateFlow is thread-safe
 * - Lifecycle: Test-scoped (create new instance per test)
 * 
 * Usage:
 * ```kotlin
 * val fakeController = FakeTrackerServiceController()
 * val testGraph = TestAppGraphBuilder()
 *     .withTrackerServiceController(fakeController)
 *     .build()
 * 
 * // Simulate tracking start
 * fakeController.updateServiceRunning(true)
 * fakeController.updateSessionInfo(TrackerSessionInfo(isUserInitiated = true))
 * 
 * // Assert UI state
 * composeTestRule.onNodeWithText("Tracking Active").assertExists()
 * ```
 */
class FakeTrackerServiceController : TrackerServiceController {
    private val _isServiceRunning = MutableStateFlow(false)
    override val isServiceRunningFlow: StateFlow<Boolean> get() = _isServiceRunning
    override val isServiceRunning: Boolean get() = _isServiceRunning.value
    
    private val _sessionInfoFlow = MutableStateFlow<TrackerSessionInfo?>(null)
    override val sessionInfoFlow: StateFlow<TrackerSessionInfo?> get() = _sessionInfoFlow
    
    private val _sessionFlow = MutableStateFlow<TrackerSession?>(null)
    override val sessionFlow: StateFlow<TrackerSession?> get() = _sessionFlow
    
    private val _collectionDataFlow = MutableStateFlow<CollectionData?>(null)
    override val collectionDataFlow: StateFlow<CollectionData?> get() = _collectionDataFlow

    private val _pathPointsFlow = MutableStateFlow<Pair<Long, List<Location>>?>(null)
    override val pathPointsFlow: StateFlow<Pair<Long, List<Location>>?> get() = _pathPointsFlow

    private val _lastSessionFlow = MutableStateFlow<TrackerSession?>(null)
    override val lastSessionFlow: StateFlow<TrackerSession?> get() = _lastSessionFlow

    private val _lastPathPointsFlow = MutableStateFlow<Pair<Long, List<Location>>?>(null)
    override val lastPathPointsFlow: StateFlow<Pair<Long, List<Location>>?> get() = _lastPathPointsFlow

    private val _policyTierFlow = MutableStateFlow(PolicyTier.OFF)
    override val policyTierFlow: StateFlow<PolicyTier> get() = _policyTierFlow

    private val _policyStateFlow = MutableStateFlow<PolicyState?>(null)
    override val policyStateFlow: StateFlow<PolicyState?> get() = _policyStateFlow

    private val _skiStateFlow = MutableStateFlow<RealTimeSkiState?>(null)
    override val skiStateFlow: StateFlow<RealTimeSkiState?> get() = _skiStateFlow

    private var _persistenceErrorFlow: SharedFlow<PersistenceError>? = null
    override val persistenceErrorFlow: SharedFlow<PersistenceError>? get() = _persistenceErrorFlow
    
    /**
     * Mutable flow for emitting test persistence errors.
     * Call emitPersistenceError() to simulate database failures.
     */
    val testPersistenceErrors = MutableSharedFlow<PersistenceError>(replay = 5)
    
    override fun updateServiceRunning(isRunning: Boolean) {
        _isServiceRunning.value = isRunning
    }
    
    override fun updateSessionInfo(info: TrackerSessionInfo?) {
        _sessionInfoFlow.value = info
    }
    
    override fun updateSession(session: TrackerSession?) {
        _sessionFlow.value = session
    }
    
    override fun updateCollectionData(data: CollectionData?) {
        _collectionDataFlow.value = data
    }
    
    override fun updatePersistenceErrorFlow(errorFlow: SharedFlow<PersistenceError>?) {
        _persistenceErrorFlow = errorFlow
    }

    override fun updatePolicyTier(tier: PolicyTier) {
        _policyTierFlow.value = tier
    }

    override fun updatePolicyState(state: PolicyState?) {
        _policyStateFlow.value = state
    }

    override fun updateSkiState(state: RealTimeSkiState?) {
        _skiStateFlow.value = state
    }
    
    /**
     * Simulates a persistence error for testing.
     * Requires enablePersistenceErrors() to be called first.
     */
    suspend fun emitPersistenceError(error: PersistenceError) {
        testPersistenceErrors.emit(error)
    }
    
    /**
     * Enables persistence error simulation for tests.
     * Call this before checking persistenceErrorFlow in tests.
     */
    fun enablePersistenceErrors() {
        _persistenceErrorFlow = testPersistenceErrors
    }
}
