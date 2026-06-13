package com.adsamcik.tracker.map.presentation.bridge

import androidx.compose.runtime.Immutable
import com.adsamcik.tracker.map.shared.CoordinateBounds

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
        val opacity: Float = 1f,
        val bounds: CoordinateBounds? = null,
    ) : MapLibreLayerConfig

    /**
     * Filled polygons coloured by a per-feature weight in [0, 1] via [colorStops]. Used by the
     * legacy grid-tile heatmap, which renders fixed-size square cells instead of a smooth GPU
     * heatmap. [outlineColorArgb] (when non-null) draws a thin cell border.
     */
    @Immutable
    data class Fill(
        val geoJson: String,
        val colorStops: List<Pair<Float, Int>>,
        val opacity: Float = 0.7f,
        val outlineColorArgb: Int? = null,
        val weightProperty: String = "weight",
    ) : MapLibreLayerConfig

    @Immutable
    data class Composite(
        val layers: List<MapLibreLayerConfig>
    ) : MapLibreLayerConfig
}

@Immutable
data class MapLibreLayerRenderKey(
    val index: Int,
    val type: String,
    val style: Any,
)

fun MapLibreLayerConfig.renderKey(index: Int): MapLibreLayerRenderKey = when (this) {
    is MapLibreLayerConfig.Heatmap -> MapLibreLayerRenderKey(
        index = index,
        type = "heatmap",
        style = HeatmapStyleKey(colorStops, radiusPx, intensity, opacity, weightProperty),
    )
    is MapLibreLayerConfig.Line -> MapLibreLayerRenderKey(
        index = index,
        type = "line",
        style = LineStyleKey(colorArgb, widthDp, opacity),
    )
    is MapLibreLayerConfig.Fill -> MapLibreLayerRenderKey(
        index = index,
        type = "fill",
        style = FillStyleKey(colorStops, opacity, outlineColorArgb, weightProperty),
    )
    is MapLibreLayerConfig.Composite -> MapLibreLayerRenderKey(
        index = index,
        type = "composite",
        style = layers.mapIndexed { childIndex, layer -> layer.renderKey(childIndex) },
    )
}

@Immutable
private data class HeatmapStyleKey(
    val colorStops: List<Pair<Float, Int>>,
    val radiusPx: Float,
    val intensity: Float,
    val opacity: Float,
    val weightProperty: String,
)

@Immutable
private data class LineStyleKey(
    val colorArgb: Int,
    val widthDp: Float,
    val opacity: Float,
)

@Immutable
private data class FillStyleKey(
    val colorStops: List<Pair<Float, Int>>,
    val opacity: Float,
    val outlineColorArgb: Int?,
    val weightProperty: String,
)

fun MapLibreLayerConfig.boundsOrNull(): CoordinateBounds? = when (this) {
    is MapLibreLayerConfig.Line -> bounds
    is MapLibreLayerConfig.Heatmap -> null
    is MapLibreLayerConfig.Fill -> null
    is MapLibreLayerConfig.Composite -> layers.asSequence()
        .mapNotNull { it.boundsOrNull() }
        .firstOrNull()
}
