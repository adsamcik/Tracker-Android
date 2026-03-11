package com.adsamcik.tracker.map.presentation.bridge

import androidx.compose.runtime.Immutable

/**
 * Sealed interface replacing Google Maps TileProvider in the rendering pipeline.
 * Layers produce config data that MapLibre renders via native GPU layers.
 */
@Immutable
sealed interface MapLibreLayerConfig {
    @Immutable
    data class Heatmap(
        val geoJson: String,
        val colorStops: List<Pair<Float, Int>>,
        val radiusPx: Float = 20f,
        val intensity: Float = 1f,
        val opacity: Float = 0.8f,
        val weightProperty: String = "weight"
    ) : MapLibreLayerConfig

    @Immutable
    data class Line(
        val geoJson: String,
        val colorArgb: Int,
        val widthDp: Float = 4f,
        val opacity: Float = 1f
    ) : MapLibreLayerConfig

    @Immutable
    data class Composite(
        val layers: List<MapLibreLayerConfig>
    ) : MapLibreLayerConfig
}
