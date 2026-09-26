package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.graphics.PolylineOptimizer
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.shared.CoordinateBounds

/** Polyline layer drawing user paths with decimation. Produces MapLibreLayerConfig.Line. */
class LocationPathLayer(
    private val pointsProvider: suspend (LongRange, Bounds?) -> List<LatLngModel> = { _, _ -> emptyList() },
    private val perf: PerformanceManager = PerformanceManager(),
    private val segmentsProvider: (suspend (LongRange, Bounds?) -> List<List<LatLngModel>>)? = null,
) : BaseMapLayer<LocationPathLayer.Input, LocationPathLayer.Prepared>(), SupportsDateRange {

    data class Input(val points: List<LatLngModel>, val segments: List<List<LatLngModel>> = listOf(points))
    data class Prepared(
        val geoJson: String,
        val points: List<LatLngModel>,
        val bounds: CoordinateBounds?,
        val isolatedGeoJson: String? = null,
    )

    override var dateRange: LongRange = LongRange(0, Long.MAX_VALUE)

    override suspend fun loadData(context: Context, bounds: Bounds?): Input {
        val segments = segmentsProvider?.invoke(dateRange, bounds)
        if (segments != null) return Input(segments.flatten(), segments)
        val points = pointsProvider(dateRange, bounds)
        return Input(points)
    }

    override fun processData(input: Input, budgets: PerformanceManager.PerformanceBudgets): Prepared {
        if (input.points.isEmpty()) return Prepared("", emptyList(), null)
        // Use a small, real distance tolerance so Douglas-Peucker preserves the
        // route shape. `budgets.decimationThreshold` is a point-count budget, NOT a
        // metre tolerance — feeding it in as the tolerance (8000m+) collapsed every
        // route to a straight line. The point budget is enforced shape-awarely by
        // the optimizer (adaptive tolerance), and even-distance resampling is off so
        // corners are never rounded away.
        val segments = input.segments.map { segment -> PolylineOptimizer.optimize(
            segment,
            toleranceMeters = OVERVIEW_SIMPLIFY_TOLERANCE_METERS,
            maxPoints = maxOf(2, budgets.maxPolylinePoints * segment.size / input.points.size),
            evenSpacing = false,
            smoothingIterations = PATH_SMOOTHING_ITERATIONS,
        ) }
        val simplified = segments.flatten()
        val geoJson = GeoJsonConverter.segmentsToFeatureCollection(segments)
        val isolated = segments.filter { it.size == 1 }.map { it.single() }
        val isolatedGeoJson = isolated.takeIf { it.isNotEmpty() }?.let { points ->
            GeoJsonConverter.pointsToFeatureCollection(points.map { WeightedGeoFeature(it.lat, it.lng, 0L, 1.0) })
        }
        return Prepared(geoJson, simplified, simplified.coordinateBoundsOrNull(), isolatedGeoJson)
    }

    override fun produceConfig(processed: Prepared): MapLibreLayerConfig? {
        if (processed.geoJson.isEmpty()) return null
        val line = MapLibreLayerConfig.Line(
            geoJson = processed.geoJson,
            colorArgb = DEFAULT_POLYLINE_COLOR,
            widthDp = 4f,
            opacity = 1f,
            bounds = processed.bounds,
            casingColorArgb = DEFAULT_POLYLINE_CASING_COLOR,
            casingWidthDp = 2f,
        )
        val isolated = processed.isolatedGeoJson ?: return line
        return MapLibreLayerConfig.Composite(listOf(line, MapLibreLayerConfig.Circle(
            geoJson = isolated,
            colorStops = listOf(0f to DEFAULT_POLYLINE_COLOR, 1f to DEFAULT_POLYLINE_COLOR),
            minRadiusDp = 4f,
            maxRadiusDp = 4f,
        )))

    }

    private fun List<LatLngModel>.coordinateBoundsOrNull(): CoordinateBounds? {
        if (isEmpty()) return null
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        forEach { point ->
            minLat = minOf(minLat, point.lat)
            maxLat = maxOf(maxLat, point.lat)
            minLng = minOf(minLng, point.lng)
            maxLng = maxOf(maxLng, point.lng)
        }
        return CoordinateBounds(
            topBound = maxLat,
            rightBound = maxLng,
            bottomBound = minLat,
            leftBound = minLng,
        )
    }

    companion object {
        private const val DEFAULT_POLYLINE_COLOR = 0xFF007AFF.toInt()

        /**
         * Casing colour drawn beneath the route: a translucent dark outline that gives the line
         * depth and keeps it legible over both light and dark basemaps.
         */
        private const val DEFAULT_POLYLINE_CASING_COLOR = 0x66002A66

        /**
         * Douglas-Peucker tolerance (metres) for the route overview. Small enough to
         * keep the route's shape (turns, switchbacks) while removing GPS jitter; the
         * optimizer raises it adaptively if the point budget is still exceeded.
         */
        private const val OVERVIEW_SIMPLIFY_TOLERANCE_METERS = 5.0

        /**
         * Chaikin smoothing passes applied after simplification so the recorded track reads as a
         * flowing curve rather than a chain of straight segments. Two passes give a natural,
         * jitter-free curve without the overshoot a spline would add on noisy GPS.
         */
        private const val PATH_SMOOTHING_ITERATIONS = 2
    }
}
