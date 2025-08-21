package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.data.Aggregation
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.implementation.AgeWeightedHeatmap
import com.adsamcik.tracker.map.heatmap.ReadOnlyHeatmap
import com.adsamcik.tracker.map.heatmap.implementation.AlphaMergeFunction
import com.adsamcik.tracker.map.heatmap.implementation.WeightMergeFunction
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted

/** DSL-style builder to reduce boilerplate when declaring HeatmapLayerSpec */
internal class HeatmapSpecBuilder {
    // Core
    var source: GeoSource = GeoSource.LOCATION
    var weightColumn: String = ""
    var aggregation: Aggregation = Aggregation.Avg

    // Normalization
    var weightNormalizer: (Double) -> Float = { it.toFloat() }
    var neighborClamp: Float? = 1f

    // Visuals
    var colorScheme: HeatmapColorScheme = HeatmapColorScheme.viridis()
    var maxHeat: Float = 100f
    var ageThresholdSec: Int = 15 * 60

    // Merge policies
    var weightMerge: WeightMergeFunction = { cur, _, sv, v -> cur + sv * v }
    var alphaMerge: AlphaMergeFunction = { cur, sv, w ->
        val a = cur / 255f
        val out = 1f - (1f - a) * (1f - sv * w.coerceIn(0f, 1f))
        (out * 255f).toInt().coerceIn(0, 255)
    }
    var valueCurve: ((Float) -> Float)? = null

    // Alpha grouping
    sealed interface AlphaMode {
        object InternalAlpha : AlphaMode
        data class FromNormalized(val opacity: Float = 0.9f) : AlphaMode
    }
    var alphaMode: AlphaMode = AlphaMode.FromNormalized(0.9f)

    var weightPolicyRo: ((baseWeight: Float, heatmap: ReadOnlyHeatmap, cx: Int, cy: Int, ageInSeconds: Int) -> Float)? = null

    // Resolution and stamps
    var heatmapBaseSize: Int = com.adsamcik.tracker.map.heatmap.HeatmapEngine.BASE_HEATMAP_SIZE
    var scaleWithQuality: Boolean = true
    var radiusComputer: (zoom: Int, metersPerPixel: Double, hysteresisZoom: Double?) -> RadiusInfo = { _, _, _ -> RadiusInfo(4, 4) }
    var buildStamp: (radius: Int) -> HeatmapStamp = { r -> HeatmapStamp.generateGaussian(r) }
    var dynamicStampProvider: ((loc: TimeLocation2DWeighted, metersPerPixel: Double, baseRadius: Int, maxRadius: Int) -> HeatmapStamp)? = null
    var ambientStampProvider: ((TimeLocation2DWeighted) -> HeatmapStamp)? = null
    var ambientWeightScale: Float = 0f

    // Neighborhood normalization
    var neighborNormSize: Int = 64


    fun build(): HeatmapLayerSpec {
        val (alphaFromNormalized, opacity) = when (val m = alphaMode) {
            is AlphaMode.InternalAlpha -> false to 0.9f
            is AlphaMode.FromNormalized -> true to m.opacity
        }
        return HeatmapLayerSpec(
            source = source,
            weightColumn = weightColumn,
            aggregation = aggregation,
            weightNormalizer = weightNormalizer,
            neighborClamp = neighborClamp,
            colorScheme = colorScheme,
            maxHeat = maxHeat,
            ageThresholdSec = ageThresholdSec,
            weightMerge = weightMerge,
            alphaMerge = alphaMerge,
            valueCurve = valueCurve,
            alphaFromNormalized = alphaFromNormalized,
            opacity = opacity,
            weightPolicyRo = weightPolicyRo,
            heatmapBaseSize = heatmapBaseSize,
            scaleWithQuality = scaleWithQuality,
            radiusComputer = radiusComputer,
            buildStamp = buildStamp,
            dynamicStampProvider = dynamicStampProvider,
            ambientStampProvider = ambientStampProvider,
            ambientWeightScale = ambientWeightScale,
            neighborNormSize = neighborNormSize
        )
    }
}

internal inline fun heatmapSpec(build: HeatmapSpecBuilder.() -> Unit): HeatmapLayerSpec =
    HeatmapSpecBuilder().apply(build).build()
