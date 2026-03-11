package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.graphics.PolylineOptimizer
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.udf.LatLngModel

/** Polyline layer drawing user paths with decimation. Produces MapLibreLayerConfig.Line. */
class LocationPathLayer(
    private val pointsProvider: suspend (LongRange) -> List<LatLngModel>,
    private val perf: PerformanceManager = PerformanceManager()
) : BaseMapLayer<LocationPathLayer.Input, LocationPathLayer.Prepared>(), SupportsDateRange {

    data class Input(val points: List<LatLngModel>)
    data class Prepared(val geoJson: String, val points: List<LatLngModel>)

    override var dateRange: LongRange = LongRange(0, Long.MAX_VALUE)

    override suspend fun loadData(context: Context, bounds: Bounds?): Input {
        // Path layer always loads the full track regardless of viewport
        val points = pointsProvider(dateRange)
        return Input(points)
    }

    override fun processData(input: Input, budgets: PerformanceManager.PerformanceBudgets): Prepared {
        if (input.points.isEmpty()) return Prepared("", emptyList())
        val simplified = PolylineOptimizer.optimize(
            input.points,
            toleranceMeters = budgets.decimationThreshold.toDouble(),
            maxPoints = budgets.maxPolylinePoints
        )
        val geoJson = GeoJsonConverter.lineToFeatureCollection(simplified)
        return Prepared(geoJson, simplified)
    }

    override fun produceConfig(processed: Prepared): MapLibreLayerConfig? {
        if (processed.geoJson.isEmpty()) return null
        return MapLibreLayerConfig.Line(
            geoJson = processed.geoJson,
            colorArgb = DEFAULT_POLYLINE_COLOR,
            widthDp = 4f,
            opacity = 1f
        )
    }

    companion object {
        private const val DEFAULT_POLYLINE_COLOR = 0xFF007AFF.toInt()
    }
}
