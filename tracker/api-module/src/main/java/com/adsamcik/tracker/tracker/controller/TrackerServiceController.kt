package com.adsamcik.tracker.tracker.controller

import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.stats.api.PolicyState
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.data.collection.TrackerCollectionSnapshot
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only tracker state observation contract.
 * 
 * Provides reactive access to tracking state without static dependencies.
 * Injected via AppGraph for testability and proper dependency management.
 * 
 * Per copilot-instructions Section 16A:
 * - Replaces TrackerService static companion state
 * - Enables test injection (fake controller for UI tests)
 * - Maintains clean module boundaries
 */
interface TrackerStateReader {
    /**
     * Observable tracking service running state.
     * True when TrackerService is actively running.
     */
    val isServiceRunningFlow: StateFlow<Boolean>
    
    /**
     * Current value of service running state (snapshot).
     */
    val isServiceRunning: Boolean
    
    /**
     * Current session information as Flow.
     * Contains basic session metadata (user-initiated flag, start time).
     * Null when no session is active.
     */
    val sessionInfoFlow: StateFlow<TrackerSessionInfo?>
    
    /**
     * Current full session data as Flow.
     * Contains all runtime metrics: distance, steps, collections, timestamps.
     * Null when no session is active.
     */
    val sessionFlow: StateFlow<TrackerSessionSnapshot?>
    
    /**
     * Current collection data as Flow.
     * Contains live tracking data: location, activity, wifi, cell.
     * Null when no session is active or no data collected yet.
     */
    val collectionDataFlow: StateFlow<TrackerCollectionSnapshot?>

    /**
     * Accumulated path points for the current/last session.
     * Pair of Session ID and List of Locations.
     * Used for drawing the map preview.
     */
    val pathPointsFlow: StateFlow<Pair<Long, List<Location>>?>

    /**
     * The last active session data (retained after service stop).
     * Used to display summary immediately after tracking stops.
     */
    val lastSessionFlow: StateFlow<TrackerSessionSnapshot?>

    /**
     * The path points of the last active session.
     */
    val lastPathPointsFlow: StateFlow<Pair<Long, List<Location>>?>
    
    /**
     * Current policy tier (OFF/AMBIENT/ACTIVE/PRECISION).
     * Defaults to OFF when service is not running.
     */
    val policyTierFlow: StateFlow<PolicyTier>

    /**
     * Detailed policy engine state including accumulator value,
     * detected activity, GPS interval, and minimum tier lock.
     * Null when the escalation engine is not active.
     */
    val policyStateFlow: StateFlow<PolicyState?>

    /**
     * Real-time ski detection state.
     * Null when ski detection is not active or no barometric data available.
     * Emits on every collection cycle that produces a ski state update.
     */
    val skiStateFlow: StateFlow<LiveSkiState?>

    /**
     * Real-time sailing detection state.
     * Null when sailing detection is not active or no GPS speed data available.
     * Emits on every collection cycle that produces a sailing state update.
     */
    val sailingStateFlow: StateFlow<LiveSailingState?>

    /**
     * Real-time plane/flight detection state.
     * Null when plane detection is not active or no barometric data available.
     * Emits on every collection cycle that produces a plane state update.
     */
    val planeStateFlow: StateFlow<LivePlaneState?>

}

/**
 * Mutable controller interface for TrackerService state publication.
 *
 * Engine-owned services/components use these methods to publish state. UI and
 * other non-engine consumers should depend on [TrackerStateReader] unless they
 * intentionally need to repair or mutate tracker lifecycle state.
 */
interface TrackerServiceController : TrackerStateReader {
    /**
     * Internal: Update service running state.
     * Called by TrackerService lifecycle methods.
     */
    fun updateServiceRunning(isRunning: Boolean)
    
    /**
     * Internal: Update session info.
     * Called by TrackerService when session starts/stops.
     */
    fun updateSessionInfo(info: TrackerSessionInfo?)
    
    /**
     * Internal: Update session data.
     * Called by TrackerService on each data collection.
     */
    fun updateSession(session: TrackerSessionSnapshot?)
    
    /**
     * Internal: Update collection data.
     * Called by TrackerService on each data collection.
     */
    fun updateCollectionData(data: TrackerCollectionSnapshot?)
    
    /**
     * Internal: Update policy tier.
     * Called by TrackerService when the escalation engine changes tier.
     */
    fun updatePolicyTier(tier: PolicyTier)

    /**
     * Internal: Update detailed policy state.
     * Called by TrackerService when the escalation engine emits a new state.
     */
    fun updatePolicyState(state: PolicyState?)

    /**
     * Internal: Update ski detection state.
     * Called by TrackerService from SkiTrackingComponent's state flow.
     */
    fun updateSkiState(state: LiveSkiState?)

    /**
     * Internal: Update sailing detection state.
     * Called by TrackerService from SailingTrackingComponent's state flow.
     */
    fun updateSailingState(state: LiveSailingState?)

    /**
     * Internal: Update plane/flight detection state.
     * Called by TrackerService from PlaneTrackingComponent's state flow.
     */
    fun updatePlaneState(state: LivePlaneState?)
}
