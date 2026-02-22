package com.adsamcik.tracker.shared.preferences.tracking

import kotlinx.coroutines.flow.Flow

/** Repository boundary for tracking parameter settings. */
interface TrackingParamsRepository {
    /** Continuous stream of current tracking params; never completes. */
    val data: Flow<TrackingParamsState>

    /** Bulk-update all params at once (used by preset application). */
    suspend fun update(block: TrackingParamsState.() -> TrackingParamsState)

    /** Update a single toggle or parameter. */
    suspend fun setLocationEnabled(enabled: Boolean)
    suspend fun setActivityEnabled(enabled: Boolean)
    suspend fun setStepsEnabled(enabled: Boolean)
    suspend fun setWifiEnabled(enabled: Boolean)
    suspend fun setCellEnabled(enabled: Boolean)
    suspend fun setWifiNetworkEnabled(enabled: Boolean)
    suspend fun setWifiLocationCountEnabled(enabled: Boolean)
    suspend fun setTransitionDetectionEnabled(enabled: Boolean)
    suspend fun setNotificationStyled(enabled: Boolean)
    suspend fun setMinDistanceMeters(meters: Int)
    suspend fun setMinTimeSeconds(seconds: Int)
    suspend fun setRequiredAccuracyMeters(meters: Int)
    suspend fun setPresetName(name: String)
}
