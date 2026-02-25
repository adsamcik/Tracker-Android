package com.adsamcik.tracker.shared.preferences.map

/** Immutable snapshot of map display preferences. */
data class MapSettingsState(
    val quality: Float = DEFAULT_QUALITY,
    val maxHeatPoints: Int = DEFAULT_MAX_HEAT,
    val visitThresholdSeconds: Int = DEFAULT_VISIT_THRESHOLD,
) {
    companion object {
        const val DEFAULT_QUALITY = 1.0f
        const val DEFAULT_MAX_HEAT = 10
        const val DEFAULT_VISIT_THRESHOLD = 15
    }
}
