package com.adsamcik.tracker.tracker.controller

import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.stats.api.PolicyState
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.data.collection.TrackerCollectionSnapshot
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of TrackerServiceController.
 * 
 * Contract:
 * - Input: TrackerService calls updateXxx methods during lifecycle
 * - Output: Reactive StateFlows for UI observation
 * - Thread-safety: MutableStateFlow is thread-safe
 * - Lifecycle: Application-scoped singleton (wired in AppGraph)
 * 
 * Replaces TrackerService companion object static state.
 */
class DefaultTrackerServiceController : TrackerServiceController {
    private val _isServiceRunning = MutableStateFlow(false)
    override val isServiceRunningFlow: StateFlow<Boolean> get() = _isServiceRunning
    override val isServiceRunning: Boolean get() = _isServiceRunning.value
    
    private val _sessionInfoFlow = MutableStateFlow<TrackerSessionInfo?>(null)
    override val sessionInfoFlow: StateFlow<TrackerSessionInfo?> get() = _sessionInfoFlow
    
    private val _sessionFlow = MutableStateFlow<TrackerSessionSnapshot?>(null)
    override val sessionFlow: StateFlow<TrackerSessionSnapshot?> get() = _sessionFlow
    
    private val _collectionDataFlow = MutableStateFlow<TrackerCollectionSnapshot?>(null)
    override val collectionDataFlow: StateFlow<TrackerCollectionSnapshot?> get() = _collectionDataFlow

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

    override fun updateServiceRunning(isRunning: Boolean) {
        _isServiceRunning.value = isRunning
    }
    
    override fun updateSessionInfo(info: TrackerSessionInfo?) {
        _sessionInfoFlow.value = info
    }
    
    override fun updateSession(session: TrackerSessionSnapshot?) {
        if (session == null) {
            // Service stopping, retain last session data
            val currentSession = _sessionFlow.value
            if (currentSession != null) {
                _lastSessionFlow.value = currentSession
                _lastPathPointsFlow.value = _pathPointsFlow.value
            }
            _pathPointsFlow.value = null
        }
        _sessionFlow.value = session
    }
    
    override fun updateCollectionData(data: TrackerCollectionSnapshot?) {
        _collectionDataFlow.value = data
        val currentSession = _sessionFlow.value
        val location = data?.location ?: return
        if (currentSession == null) return

        // Atomic read-modify-write via StateFlow.update so concurrent collection cycles
        // can't lose path points. Previously: two cycles would both read the same
        // currentPath, both append, then the last assignment would win, silently
        // dropping a point from the live UI route.
        _pathPointsFlow.update { current ->
            if (current == null || current.first != currentSession.id) {
                currentSession.id to persistentListOf(location)
            } else {
                val points = current.second
                val lastLocation = points.last()
                val results = FloatArray(1)
                android.location.Location.distanceBetween(
                    lastLocation.latitude, lastLocation.longitude,
                    location.latitude, location.longitude,
                    results,
                )
                if (results[0] > 10) {
                    val persistentPoints = points as? PersistentList<Location> ?: points.toPersistentList()
                    currentSession.id to persistentPoints.addingBounded(location)
                } else {
                    current
                }
            }
        }
    }

	/** Keep live UI memory bounded while retaining the first point and an increasingly sparse history. */
	private fun PersistentList<Location>.addingBounded(location: Location): PersistentList<Location> {
		if (size < MAX_LIVE_PATH_POINTS) return adding(location)
		return filterIndexed { index, _ -> index == 0 || index % 2 == 0 }
			.toPersistentList()
			.adding(location)
	}

	override fun restorePathPoints(sessionId: Long, points: List<Location>) {
		val bounded = points.takeLast(MAX_LIVE_PATH_POINTS).toPersistentList()
		_pathPointsFlow.value = if (bounded.isEmpty()) null else sessionId to bounded
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

	internal companion object {
		const val MAX_LIVE_PATH_POINTS = 512
	}
}
