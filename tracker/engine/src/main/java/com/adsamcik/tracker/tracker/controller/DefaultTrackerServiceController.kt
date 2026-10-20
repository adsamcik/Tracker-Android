package com.adsamcik.tracker.tracker.controller

import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummarySelection
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

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
    private val startingSessionGeneration = MutableStateFlow<Long?>(null)
    override val startingSessionGenerationFlow: StateFlow<Long?> get() = startingSessionGeneration
    private var lastSessionStartFeedbackGeneration = 0L

    @Synchronized
    override fun updateSessionStart(generation: Long, starting: Boolean) {
        require(generation > 0L)
        if (generation < lastSessionStartFeedbackGeneration) {
            return
        }
        if (starting && generation == lastSessionStartFeedbackGeneration &&
            startingSessionGeneration.value != generation) {
            return
        }
        lastSessionStartFeedbackGeneration = generation
        startingSessionGeneration.value = generation.takeIf { starting }
    }

    private val _savedLocationSummary = MutableStateFlow<SavedLocationSummaryRead>(
        SavedLocationSummaryRead.NotFound)
    override val savedLocationSummaryFlow: StateFlow<SavedLocationSummaryRead>
        get() = _savedLocationSummary
    private var savedLocationObservation: Job? = null

    /** AppGraph calls once. Every live product consumes the same published StateFlow. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun bindSavedLocationSummaries(history: TrackingHistoryRepository,
        applicationScope: CoroutineScope): Job {
        check(savedLocationObservation == null) { "Saved Location presentation is already bound" }
        return applicationScope.launch {
            kotlinx.coroutines.flow.combine(isServiceRunningFlow, sessionInfoFlow,
                sessionFlow) { hosted, info, session ->
                session?.id?.takeIf { hosted && info != null && it > 0L }
            }.distinctUntilChanged().flatMapLatest { segmentId ->
                if (segmentId == null) kotlinx.coroutines.flow.flowOf(null to
                    SavedLocationSummaryRead.NotFound)
                else history.observeLiveSavedLocationSummary(segmentId)
                    .onStart { emit(SavedLocationSummaryRead.NotFound) }
                    .catch { failure ->
                        if (failure is CancellationException) throw failure
                        emit(SavedLocationSummaryRead.Unavailable(
                            HistoryProblemReason.STORAGE_UNAVAILABLE))
                    }.map { segmentId to it }
            }.collect { (segmentId, read) -> publishSavedLocation(segmentId, read) }
        }.also { savedLocationObservation = it }
    }

    @Synchronized
    private fun publishSavedLocation(segmentId: Long?, read: SavedLocationSummaryRead) {
        val current = _sessionFlow.value?.id
        if (segmentId == null || current != segmentId ||
            !_isServiceRunning.value || _sessionInfoFlow.value == null) {
            _savedLocationSummary.value = SavedLocationSummaryRead.NotFound
            return
        }
        _savedLocationSummary.value = if (read !is SavedLocationSummaryRead.Snapshot ||
            read.selection == SavedLocationSummarySelection.Session(segmentId)) read
            else SavedLocationSummaryRead.NotFound
    }

    private val _isServiceRunning = MutableStateFlow(false)
    override val isServiceRunningFlow: StateFlow<Boolean> get() = _isServiceRunning
    override val isServiceRunning: Boolean get() = _isServiceRunning.value
    
    private val _sessionInfoFlow = MutableStateFlow<TrackerSessionInfo?>(null)
    override val sessionInfoFlow: StateFlow<TrackerSessionInfo?> get() = _sessionInfoFlow
    private var physicalSessionInfo: TrackerSessionInfo? = null
    private var providerStepsSessionInfo: TrackerSessionInfo? = null
    private var providerStepsObservation: Job? = null

    /** The same published session state also hosts metadata-only sessions, without a service. */
    @Synchronized
    fun bindProviderStepsSessions(information: kotlinx.coroutines.flow.Flow<TrackerSessionInfo?>,
        applicationScope: CoroutineScope): Job {
        check(providerStepsObservation == null) { "Provider Steps sessions are already bound" }
        return applicationScope.launch {
            information.collect { publishProviderStepsSession(it) }
        }.also { providerStepsObservation = it }
    }

    @Synchronized
    private fun publishProviderStepsSession(info: TrackerSessionInfo?) {
        providerStepsSessionInfo = info
        _sessionInfoFlow.value = physicalSessionInfo ?: providerStepsSessionInfo
    }
    
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

    @Synchronized
    override fun updateServiceRunning(isRunning: Boolean) {
        if (!isRunning) _savedLocationSummary.value = SavedLocationSummaryRead.NotFound
        _isServiceRunning.value = isRunning
    }
    
    @Synchronized
    override fun updateSessionInfo(info: TrackerSessionInfo?) {
        if (info == null) _savedLocationSummary.value = SavedLocationSummaryRead.NotFound
        physicalSessionInfo = info
        _sessionInfoFlow.value = physicalSessionInfo ?: providerStepsSessionInfo
    }
    
    @Synchronized
    override fun updateSession(session: TrackerSessionSnapshot?) {
        if (_sessionFlow.value?.id != session?.id) _savedLocationSummary.value =
            SavedLocationSummaryRead.NotFound
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
