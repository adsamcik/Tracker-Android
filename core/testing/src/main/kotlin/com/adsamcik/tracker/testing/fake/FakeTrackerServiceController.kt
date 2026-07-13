package com.adsamcik.tracker.testing.fake

import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.stats.api.PolicyState
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.data.PersistenceError
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import com.adsamcik.tracker.tracker.controller.LivePlaneState
import com.adsamcik.tracker.tracker.controller.LiveSailingState
import com.adsamcik.tracker.tracker.controller.LiveSkiState
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
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

    private val _sessionFlow = MutableStateFlow<TrackerSessionSnapshot?>(null)
    override val sessionFlow: StateFlow<TrackerSessionSnapshot?> get() = _sessionFlow

    private val _collectionDataFlow = MutableStateFlow<CollectionData?>(null)
    override val collectionDataFlow: StateFlow<CollectionData?> get() = _collectionDataFlow

    private val _pathPointsFlow = MutableStateFlow<Pair<Long, List<Location>>?>(null)
    override val pathPointsFlow: StateFlow<Pair<Long, List<Location>>?> get() = _pathPointsFlow

    private val _lastSessionFlow = MutableStateFlow<TrackerSessionSnapshot?>(null)
    override val lastSessionFlow: StateFlow<TrackerSessionSnapshot?> get() = _lastSessionFlow

    private val _lastPathPointsFlow = MutableStateFlow<Pair<Long, List<Location>>?>(null)
    override val lastPathPointsFlow: StateFlow<Pair<Long, List<Location>>?> get() = _lastPathPointsFlow

    private val _policyTierFlow = MutableStateFlow(PolicyTier.OFF)
    override val policyTierFlow: StateFlow<PolicyTier> get() = _policyTierFlow

    private val _policyStateFlow = MutableStateFlow<PolicyState?>(null)
    override val policyStateFlow: StateFlow<PolicyState?> get() = _policyStateFlow

    private val _skiStateFlow = MutableStateFlow<LiveSkiState?>(null)
    override val skiStateFlow: StateFlow<LiveSkiState?> get() = _skiStateFlow

    private val _sailingStateFlow = MutableStateFlow<LiveSailingState?>(null)
    override val sailingStateFlow: StateFlow<LiveSailingState?> get() = _sailingStateFlow

    private val _planeStateFlow = MutableStateFlow<LivePlaneState?>(null)
    override val planeStateFlow: StateFlow<LivePlaneState?> get() = _planeStateFlow

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
        if (session == null && _sessionFlow.value != null) {
            _lastSessionFlow.value = _sessionFlow.value
        }
        _sessionFlow.value = session?.toSnapshot()
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

    override fun updateSkiState(state: LiveSkiState?) {
        _skiStateFlow.value = state
    }

    override fun updateSailingState(state: LiveSailingState?) {
        _sailingStateFlow.value = state
    }

    override fun updatePlaneState(state: LivePlaneState?) {
        _planeStateFlow.value = state
    }

    private fun TrackerSession.toSnapshot() = TrackerSessionSnapshot(
        id = id,
        start = start,
        end = end,
        isUserInitiated = isUserInitiated,
        collections = collections,
        distanceInM = distanceInM,
        distanceOnFootInM = distanceOnFootInM,
        distanceInVehicleInM = distanceInVehicleInM,
        steps = steps,
        sessionActivityId = sessionActivityId,
    )

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
