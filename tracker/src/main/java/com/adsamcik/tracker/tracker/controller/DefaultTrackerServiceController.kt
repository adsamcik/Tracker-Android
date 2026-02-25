package com.adsamcik.tracker.tracker.controller

import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.stats.api.PolicyState
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.engine.ski.RealTimeSkiState
import com.adsamcik.tracker.tracker.data.PersistenceError
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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
    
    private val _sessionFlow = MutableStateFlow<TrackerSession?>(null)
    override val sessionFlow: StateFlow<TrackerSession?> get() = _sessionFlow
    
    private val _collectionDataFlow = MutableStateFlow<CollectionData?>(null)
    override val collectionDataFlow: StateFlow<CollectionData?> get() = _collectionDataFlow

    private val _pathPointsFlow = MutableStateFlow<Pair<Long, List<com.adsamcik.tracker.shared.base.data.Location>>?>(null)
    override val pathPointsFlow: StateFlow<Pair<Long, List<com.adsamcik.tracker.shared.base.data.Location>>?> get() = _pathPointsFlow

    private val _lastSessionFlow = MutableStateFlow<TrackerSession?>(null)
    override val lastSessionFlow: StateFlow<TrackerSession?> get() = _lastSessionFlow

    private val _lastPathPointsFlow = MutableStateFlow<Pair<Long, List<com.adsamcik.tracker.shared.base.data.Location>>?>(null)
    override val lastPathPointsFlow: StateFlow<Pair<Long, List<com.adsamcik.tracker.shared.base.data.Location>>?> get() = _lastPathPointsFlow

    private val _policyTierFlow = MutableStateFlow(PolicyTier.OFF)
    override val policyTierFlow: StateFlow<PolicyTier> get() = _policyTierFlow

    private val _policyStateFlow = MutableStateFlow<PolicyState?>(null)
    override val policyStateFlow: StateFlow<PolicyState?> get() = _policyStateFlow

    private val _skiStateFlow = MutableStateFlow<RealTimeSkiState?>(null)
    override val skiStateFlow: StateFlow<RealTimeSkiState?> get() = _skiStateFlow

    private var _persistenceErrorFlow: SharedFlow<PersistenceError>? = null
    override val persistenceErrorFlow: SharedFlow<PersistenceError>? get() = _persistenceErrorFlow
    
    override fun updateServiceRunning(isRunning: Boolean) {
        _isServiceRunning.value = isRunning
    }
    
    override fun updateSessionInfo(info: TrackerSessionInfo?) {
        _sessionInfoFlow.value = info
    }
    
    override fun updateSession(session: TrackerSession?) {
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
    
    override fun updateCollectionData(data: CollectionData?) {
        _collectionDataFlow.value = data
        val currentSession = _sessionFlow.value
        
        val location = data?.location
        if (currentSession != null && location != null) {
            val newLocation = location
            val currentPath = _pathPointsFlow.value
            
            if (currentPath == null || currentPath.first != currentSession.id) {
                _pathPointsFlow.value = currentSession.id to listOf(newLocation)
            } else {
                val points = currentPath.second
                val lastLocation = points.last()
                val results = FloatArray(1)
                android.location.Location.distanceBetween(
                    lastLocation.latitude, lastLocation.longitude,
                    newLocation.latitude, newLocation.longitude,
                    results
                )
                if (results[0] > 10) {
                    _pathPointsFlow.value = currentSession.id to (points + newLocation)
                }
            }
        }
    }
    
    override fun updatePersistenceErrorFlow(errorFlow: SharedFlow<PersistenceError>?) {
        _persistenceErrorFlow = errorFlow
    }

    override fun updatePolicyTier(tier: PolicyTier) {
        _policyTierFlow.value = tier
    }

    override fun updatePolicyState(state: PolicyState?) {
        _policyStateFlow.value = state
        if (state != null) {
            _policyTierFlow.value = state.tier
        }
    }

    override fun updateSkiState(state: RealTimeSkiState?) {
        _skiStateFlow.value = state
    }
}
