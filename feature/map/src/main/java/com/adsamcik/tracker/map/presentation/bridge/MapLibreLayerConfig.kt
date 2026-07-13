package com.adsamcik.tracker.map.presentation.bridge

import androidx.compose.runtime.Immutable
import com.adsamcik.tracker.map.shared.CoordinateBounds

/**
 * An optional render-time animation applied to a layer property. The pipeline produces a static
 * config; the animation only drives a property per frame (no re-aggregation). See MapDataLayers.
 */
@Immutable
sealed interface LayerAnimation {
    /** Gently pulses the layer (e.g. a marker's radius) between [minScale] and [maxScale]. */
    @Immutable
    data class Pulse(
        val periodMs: Int = 1600,
        val minScale: Float = 1f,
        val maxScale: Float = 1.3f,
    ) : LayerAnimation

    /** A comet trail moving along a line without changing its source geometry. */
    @Immutable
    data class Flow(
        val periodMs: Int = 4_200,
        val colorArgb: Int,
        val trailFraction: Float = 0.2f,
        val phaseOffset: Float = 0f,
    ) : LayerAnimation
}

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
        val weightProperty: String = "weight",
        val animation: LayerAnimation? = null,
    ) : MapLibreLayerConfig

    @Immutable
    data class Line(
        val geoJson: String,
        val colorArgb: Int,
        val widthDp: Float = 4f,
        val opacity: Float = 1f,
        val bounds: CoordinateBounds? = null,
        /**
         * When set, a wider [casingColorArgb] line is drawn *beneath* the main stroke, giving the
         * route depth and keeping it legible over any basemap (the classic map "route outline").
         */
        val casingColorArgb: Int? = null,
        /** Extra width per side for the casing; total casing width is `widthDp + 2 * casingWidthDp`. */
        val casingWidthDp: Float = 2f,
        /** Subtle edge softening (anti-aliased feathering) applied to the main stroke. */
        val blurDp: Float = 0f,
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

    /**
     * 3D extruded polygons (MapLibre fill-extrusion). Each polygon is coloured by its per-feature
     * weight in [0, 1] via [colorStops] and extruded to `weight * maxHeightMeters`. Backs the
     * "life as terrain" family, where dwell density becomes physical height.
     */
    @Immutable
    data class FillExtrusion(
        val geoJson: String,
        val colorStops: List<Pair<Float, Int>>,
        val maxHeightMeters: Float,
        val opacity: Float = 0.85f,
        val weightProperty: String = "weight",
    ) : MapLibreLayerConfig

    /**
     * Point markers drawn as circles. Each feature's per-feature weight in [0, 1] drives both its
     * colour (via [colorStops]) and its radius (interpolated between [minRadiusDp] and [maxRadiusDp]).
     * Backs place-marker visualizations (frequent places sized by visit count).
     */
    @Immutable
    data class Circle(
        val geoJson: String,
        val colorStops: List<Pair<Float, Int>>,
        val minRadiusDp: Float,
        val maxRadiusDp: Float,
        val opacity: Float = 0.9f,
        val strokeColorArgb: Int = 0xFFFFFFFF.toInt(),
        val strokeWidthDp: Float = 1.5f,
        val weightProperty: String = "weight",
        val animation: LayerAnimation? = null,
    ) : MapLibreLayerConfig

    /**
     * A single polyline whose colour flows *along its length* via MapLibre's `line-gradient`
     * (rendered from a source with line-distance metrics). [gradientStops] maps line progress in
     * `[0, 1]` to a colour, each stop's colour pre-resolved from the vertex's weight — so speed,
     * altitude or activity paints the route end-to-end. Round caps/joins + an optional [casingColorArgb]
     * outline are applied at render for a smooth, realistic ribbon.
     */
    @Immutable
    data class GradientLine(
        val geoJson: String,
        val gradientStops: List<Pair<Float, Int>>,
        val widthDp: Float = 6f,
        val opacity: Float = 1f,
        val casingColorArgb: Int? = null,
        val casingWidthDp: Float = 2.5f,
        val bounds: CoordinateBounds? = null,
        val animation: LayerAnimation? = null,
    ) : MapLibreLayerConfig

    /** Collision-aware SDF icon annotations with optional labels from [labelProperty]. */
    @Immutable
    data class Symbol(
        val geoJson: String,
        val iconRes: Int,
        val iconColorArgb: Int = 0xFFFFFFFF.toInt(),
        val iconHaloColorArgb: Int = 0xCC000000.toInt(),
        val iconSizeDp: Float = 24f,
        val textColorArgb: Int = 0xFFFFFFFF.toInt(),
        val textHaloColorArgb: Int = 0xCC000000.toInt(),
        val textSizeSp: Float = 12f,
        val labelProperty: String = "label",
        val allowOverlap: Boolean = false,
        val bounds: CoordinateBounds? = null,
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
        style = HeatmapStyleKey(colorStops, radiusPx, intensity, opacity, weightProperty, animation),
    )
    is MapLibreLayerConfig.Line -> MapLibreLayerRenderKey(
        index = index,
        type = "line",
        style = LineStyleKey(colorArgb, widthDp, opacity, casingColorArgb, casingWidthDp, blurDp),
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
    is MapLibreLayerConfig.FillExtrusion -> MapLibreLayerRenderKey(
        index = index,
        type = "fill-extrusion",
        style = FillExtrusionStyleKey(colorStops, maxHeightMeters, opacity, weightProperty),
    )
    is MapLibreLayerConfig.Circle -> MapLibreLayerRenderKey(
        index = index,
        type = "circle",
        style = CircleStyleKey(colorStops, minRadiusDp, maxRadiusDp, opacity, strokeColorArgb, strokeWidthDp, weightProperty, animation),
    )
    is MapLibreLayerConfig.GradientLine -> MapLibreLayerRenderKey(
        index = index,
        type = "gradient-line",
        style = GradientLineStyleKey(
            gradientStops, widthDp, opacity, casingColorArgb, casingWidthDp, animation,
        ),
    )
    is MapLibreLayerConfig.Symbol -> MapLibreLayerRenderKey(
        index = index,
        type = "symbol",
        style = SymbolStyleKey(
            iconRes, iconColorArgb, iconHaloColorArgb, iconSizeDp, textColorArgb,
            textHaloColorArgb, textSizeSp, labelProperty, allowOverlap,
        ),
    )
}

@Immutable
private data class HeatmapStyleKey(
    val colorStops: List<Pair<Float, Int>>,
    val radiusPx: Float,
    val intensity: Float,
    val opacity: Float,
    val weightProperty: String,
    val animation: LayerAnimation?,
)

@Immutable
private data class LineStyleKey(
    val colorArgb: Int,
    val widthDp: Float,
    val opacity: Float,
    val casingColorArgb: Int?,
    val casingWidthDp: Float,
    val blurDp: Float,
)

@Immutable
private data class FillStyleKey(
    val colorStops: List<Pair<Float, Int>>,
    val opacity: Float,
    val outlineColorArgb: Int?,
    val weightProperty: String,
)

@Immutable
private data class FillExtrusionStyleKey(
    val colorStops: List<Pair<Float, Int>>,
    val maxHeightMeters: Float,
    val opacity: Float,
    val weightProperty: String,
)

@Immutable
private data class CircleStyleKey(
    val colorStops: List<Pair<Float, Int>>,
    val minRadiusDp: Float,
    val maxRadiusDp: Float,
    val opacity: Float,
    val strokeColorArgb: Int,
    val strokeWidthDp: Float,
    val weightProperty: String,
    val animation: LayerAnimation?,
)

@Immutable
private data class GradientLineStyleKey(
    val gradientStops: List<Pair<Float, Int>>,
    val widthDp: Float,
    val opacity: Float,
    val casingColorArgb: Int?,
    val casingWidthDp: Float,
    val animation: LayerAnimation?,
)

@Immutable
private data class SymbolStyleKey(
    val iconRes: Int,
    val iconColorArgb: Int,
    val iconHaloColorArgb: Int,
    val iconSizeDp: Float,
    val textColorArgb: Int,
    val textHaloColorArgb: Int,
    val textSizeSp: Float,
    val labelProperty: String,
    val allowOverlap: Boolean,
)

/** Recursively lowers composites so every renderer receives the same ordered leaf layer sequence. */
fun MapLibreLayerConfig.flattenedLeaves(): List<MapLibreLayerConfig> = when (this) {
    is MapLibreLayerConfig.Composite -> layers.flatMap { it.flattenedLeaves() }
    else -> listOf(this)
}

fun MapLibreLayerConfig.boundsOrNull(): CoordinateBounds? = when (this) {
    is MapLibreLayerConfig.Line -> bounds
    is MapLibreLayerConfig.GradientLine -> bounds
    is MapLibreLayerConfig.Symbol -> bounds
    is MapLibreLayerConfig.Heatmap -> null
    is MapLibreLayerConfig.Fill -> null
    is MapLibreLayerConfig.FillExtrusion -> null
    is MapLibreLayerConfig.Circle -> null
    is MapLibreLayerConfig.Composite -> layers.asSequence()
        .mapNotNull { it.boundsOrNull() }
        .reduceOrNull { accumulated, child ->
            CoordinateBounds(
                topBound = maxOf(accumulated.top, child.top),
                rightBound = maxOf(accumulated.right, child.right),
                bottomBound = minOf(accumulated.bottom, child.bottom),
                leftBound = minOf(accumulated.left, child.left),
            )
        }
}
