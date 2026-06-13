package com.adsamcik.tracker.shared.preferences.map

/** Immutable snapshot of map display preferences. */
data class MapSettingsState(
    val quality: Float = DEFAULT_QUALITY,
    val maxHeatPoints: Int = DEFAULT_MAX_HEAT,
    val visitThresholdSeconds: Int = DEFAULT_VISIT_THRESHOLD,
    val legacyHeatmapEnabled: Boolean = DEFAULT_LEGACY_HEATMAP,
    val zoomButtonsEnabled: Boolean = DEFAULT_ZOOM_BUTTONS,
) {
    companion object {
        const val DEFAULT_QUALITY = 1.0f
        const val DEFAULT_MAX_HEAT = 10
        const val DEFAULT_VISIT_THRESHOLD = 15
        const val DEFAULT_LEGACY_HEATMAP = false
        const val DEFAULT_ZOOM_BUTTONS = false
    }
}
