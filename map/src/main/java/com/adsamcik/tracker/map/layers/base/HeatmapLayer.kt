package com.adsamcik.tracker.map.layers.base

import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig

/**
 * Base class for heatmap layers producing [MapLibreLayerConfig.Heatmap].
 * Subclasses provide color stops and GeoJSON data; this base handles
 * common config construction.
 */
abstract class HeatmapLayer<I, P> : BaseMapLayer<I, P>(), SupportsDateRange {

    override var dateRange: LongRange = 0L..Long.MAX_VALUE

    /** Color stops for the heatmap gradient. Override in subclasses. */
    protected abstract fun colorStops(): List<Pair<Float, Int>>

    /** Extract GeoJSON string from processed data. */
    protected abstract fun geoJsonFrom(processed: P): String

    /** Heatmap radius in pixels. Override to customize. */
    protected open fun radiusPx(): Float = 20f

    /** Heatmap intensity. Override to customize. */
    protected open fun intensity(): Float = 1f

    /** Heatmap opacity. Override to customize. */
    protected open fun opacity(): Float = 0.8f

    override fun produceConfig(processed: P): MapLibreLayerConfig? {
        val geoJson = geoJsonFrom(processed)
        if (geoJson.isEmpty()) return null
        return MapLibreLayerConfig.Heatmap(
            geoJson = geoJson,
            colorStops = colorStops(),
            radiusPx = radiusPx(),
            intensity = intensity(),
            opacity = opacity(),
            weightProperty = "weight"
        )
    }
}
