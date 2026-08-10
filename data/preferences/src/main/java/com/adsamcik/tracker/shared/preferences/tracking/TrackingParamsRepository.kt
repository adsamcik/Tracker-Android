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
    suspend fun setBarometerEnabled(enabled: Boolean)
    suspend fun setTransitionDetectionEnabled(enabled: Boolean)
    suspend fun setNotificationStyled(enabled: Boolean)
    suspend fun setMinDistanceMeters(meters: Int)
    suspend fun setMinTimeSeconds(seconds: Int)
    suspend fun setRequiredAccuracyMeters(meters: Int)
    suspend fun setPreset(preset: TrackingPreset)
	suspend fun setSourceFrequency(component: TrackingSourceComponent, frequency: SourceCollectionFrequency) {
		update {
			val current = sourceCollectionSettings
			val next = when (component) {
				TrackingSourceComponent.LOCATION -> current.copy(location = frequency)
				TrackingSourceComponent.ACTIVITY -> current.copy(activity = frequency)
				TrackingSourceComponent.STEPS -> current.copy(steps = frequency)
				TrackingSourceComponent.PRESSURE -> current.copy(pressure = frequency)
				TrackingSourceComponent.WIFI -> current.copy(wifi = frequency)
				TrackingSourceComponent.CELL -> current.copy(cell = frequency)
			}
			copy(sourceCollectionSettings = next)
		}
	}

	suspend fun setAdvancedSourceControlsEnabled(enabled: Boolean) {
		update { copy(advancedSourceControlsEnabled = enabled) }
	}

    /**
     * Set the user-configured baseline speed limit (m/s) used by the
     * "Vehicle speed compliance" map layer to colour driving routes.
     * Values are clamped to [TrackingParamsState.MIN_VEHICLE_SPEED_LIMIT_KMH,
     * TrackingParamsState.MAX_VEHICLE_SPEED_LIMIT_KMH] converted to m/s.
     */
    suspend fun setVehicleSpeedLimitBaselineMps(mps: Double)
}
