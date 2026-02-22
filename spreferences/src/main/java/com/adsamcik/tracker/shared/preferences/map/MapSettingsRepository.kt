package com.adsamcik.tracker.shared.preferences.map

import kotlinx.coroutines.flow.Flow

/** Repository boundary for map display settings. */
interface MapSettingsRepository {
    /** Continuous stream of current map settings; never completes. */
    val data: Flow<MapSettingsState>

    /** Set heatmap quality multiplier. */
    suspend fun setQuality(quality: Float)

    /** Set maximum number of heat points rendered. */
    suspend fun setMaxHeatPoints(maxHeat: Int)

    /** Set minimum visit duration threshold in seconds. */
    suspend fun setVisitThresholdSeconds(seconds: Int)
}
