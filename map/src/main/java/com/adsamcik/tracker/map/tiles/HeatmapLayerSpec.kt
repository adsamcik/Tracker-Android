package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.data.Aggregation
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.RenderPolicy
import com.adsamcik.tracker.map.heatmap.implementation.AlphaMergeFunction
import com.adsamcik.tracker.map.heatmap.implementation.WeightMergeFunction
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted

/**
 * Specification for heatmap layer tile generation.
 * Layers provide this to the base provider; the base executes the common pipeline.
 */
internal data class HeatmapLayerSpec(
    val source: GeoSource,
    val weightColumn: String,
    val aggregation: Aggregation,
    // Single strategy to map raw source weight into a normalized [0,1] signal
    val weightNormalizer: (Double) -> Float,
    // Optional clamp used for neighborhood normalization (e.g., cap to 0..1 or <1)
    val neighborClamp: Float? = null,

    // Visuals and rendering
    val colorScheme: HeatmapColorScheme,
    val maxHeat: Float,
    val ageThresholdSec: Int,
    val weightMerge: WeightMergeFunction,
    val alphaMerge: AlphaMergeFunction,
    val valueCurve: ((Float) -> Float)? = null,
    val alphaFromNormalized: Boolean = true,
    val opacity: Float = 0.9f,

    // Revisit gating and other shaping should be provided via HeatmapConfig.weightPolicy
    val weightPolicy: ((baseWeight: Float, heatmap: com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap, cx: Int, cy: Int, ageInSeconds: Int) -> Float)? = null,

    // Heatmap internal resolution
    val heatmapBaseSize: Int,
    val scaleWithQuality: Boolean = true,

    // Radius policy yields a base stamp radius and an optional maximum (for dynamic stamps & padding)
    val radiusComputer: (zoom: Int, metersPerPixel: Double, hysteresisZoom: Double?) -> RadiusInfo,

    // Stamp policy
    val buildStamp: (radius: Int) -> HeatmapStamp,
    val dynamicStampProvider: ((loc: TimeLocation2DWeighted, metersPerPixel: Double, baseRadius: Int, maxRadius: Int) -> HeatmapStamp)? = null,
    val ambientStampProvider: ((TimeLocation2DWeighted) -> HeatmapStamp)? = null,
    val ambientWeightScale: Float = 0f,

    // Neighborhood normalization
    val neighborNormSize: Int = 64,

    // Optional per-layer render policy for blur/cutoff tuning
    val renderPolicy: RenderPolicy? = null
)

internal data class RadiusInfo(val baseRadius: Int, val maxRadius: Int = baseRadius)
