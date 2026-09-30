package com.adsamcik.tracker.map.viz

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.stats.api.repository.*
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlin.math.*

/** Existing radio view, adding actual observed device points without feeding AP/tower estimators. */
internal class RetainedRadioContextLayer<Feature, F : SpatialData>(
    private val pipeline: VizPipeline<Feature, F>,
    private val repository: RadioLocationContextRepository,
    private val source: RadioContextSource,
    dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : BaseMapLayer<RetainedRadioLayerInput<Feature>, RetainedRadioLayerOutput<F>>(dispatchers = dispatchers), SupportsDateRange {
    override var dateRange: LongRange = 0L..Long.MAX_VALUE

    override suspend fun loadData(context: Context, bounds: Bounds?): RetainedRadioLayerInput<Feature> {
        val end = if (dateRange.last == Long.MAX_VALUE) Long.MAX_VALUE else dateRange.last + 1L
        val read = repository.read(RadioLocationContextRequest(source, maxOf(0L, dateRange.first), end))
        val snapshot = read as? RadioLocationContextReadResult.Ready
            ?: throw RetainedRadioMapReadUnavailable()
        val legacy = pipeline.source.load(VizRequest(dateRange, bounds, currentPerformanceBudgets().maxPoints))
        val visible = snapshot.observations.filter { observation ->
            val point = (observation.location as? OptionalRetainedLocationContext.Located)?.value ?: return@filter false
            val latitude = point.latitudeE7 / 10_000_000.0
            val longitude = point.longitudeE7 / 10_000_000.0
            bounds == null || latitude in bounds.south..bounds.north && bounds.containsLongitude(longitude)
        }
        return RetainedRadioLayerInput(legacy, visible, snapshot.gaps)
    }

    override fun processData(input: RetainedRadioLayerInput<Feature>, budgets: PerformanceManager.PerformanceBudgets) =
        RetainedRadioLayerOutput(pipeline.aggregator.aggregate(input.legacy,
            AggContext(zoom, quality, budgets.maxPoints)), input.observations.take(budgets.maxPoints), input.gaps)

    override fun produceConfig(processed: RetainedRadioLayerOutput<F>): MapLibreLayerConfig? {
        val configurations = listOfNotNull(pipeline.encoder.encode(processed.legacy, RenderContext(quality)),
            RetainedRadioContextEncoder.encode(processed.observations))
        return when (configurations.size) {
            0 -> null
            1 -> configurations.single()
            else -> MapLibreLayerConfig.Composite(configurations)
        }
    }
}

internal data class RetainedRadioLayerInput<F>(val legacy: List<F>, val observations: List<RadioContextObservation>,
    val gaps: Set<RadioContextReadGap>)
internal data class RetainedRadioLayerOutput<F>(val legacy: F, val observations: List<RadioContextObservation>,
    val gaps: Set<RadioContextReadGap>)
private class RetainedRadioMapReadUnavailable : IllegalStateException("Retained radio map context unavailable")

/** Coordinates and accuracy belong to the device fix, never to a Wi-Fi network or Cell tower. */
internal object RetainedRadioContextEncoder {
    private const val COLOR = 0xFF64748B.toInt()
    private val colorStops = listOf(0f to COLOR, 1f to COLOR)

    fun encode(observations: List<RadioContextObservation>): MapLibreLayerConfig? {
        val points = observations.mapNotNull { (it.location as? OptionalRetainedLocationContext.Located)?.value }
            .distinctBy { it.sourceEventId }
        if (points.isEmpty()) return null
        val markers = points.joinToString(",") { point ->
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[${point.longitudeE7 / 10_000_000.0},${point.latitudeE7 / 10_000_000.0}]},"properties":{"weight":1.0,"accuracyMeters":${point.horizontalAccuracyMeters},"observedTimeMs":${point.observedWallTimeMs},"timeUncertaintyMs":${point.wallTimeUncertaintyMs}}}"""
        }
        val polygons = points.flatMap(::accuracyRings).joinToString(",") { ring ->
            val coordinates = ring.joinToString(",") { (longitude, latitude) -> "[$longitude,$latitude]" }
            """{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$coordinates]]},"properties":{"weight":1.0}}"""
        }
        return MapLibreLayerConfig.Composite(listOfNotNull(
            polygons.takeIf(String::isNotEmpty)?.let { MapLibreLayerConfig.Fill(collection(it), colorStops, opacity = 0.12f) },
            MapLibreLayerConfig.Circle(collection(markers), colorStops, 3.5f, 3.5f, opacity = 0.95f),
        ))
    }

    private fun collection(features: String) = """{"type":"FeatureCollection","features":[$features]}"""

    /** Geodesic accuracy footprint; split at the date line instead of drawing across the world. */
    internal fun accuracyRings(point: RetainedLocationContext): List<List<Pair<Double, Double>>> {
        if (point.horizontalAccuracyMeters == 0f) return emptyList()
        val angular = point.horizontalAccuracyMeters.toDouble() / EARTH_RADIUS_METERS
        if (angular >= Math.PI) return listOf(listOf(-180.0 to -90.0, 180.0 to -90.0,
            180.0 to 90.0, -180.0 to 90.0, -180.0 to -90.0))
        val latitude = Math.toRadians(point.latitudeE7 / 10_000_000.0)
        val longitude = point.longitudeE7 / 10_000_000.0
        val ring = mutableListOf<Pair<Double, Double>>()
        for (index in 0..CIRCLE_SEGMENTS) {
            val bearing = index * 2.0 * Math.PI / CIRCLE_SEGMENTS
            val targetLatitude = asin((sin(latitude) * cos(angular) + cos(latitude) * sin(angular) * cos(bearing)).coerceIn(-1.0, 1.0))
            var targetLongitude = longitude + Math.toDegrees(atan2(sin(bearing) * sin(angular) * cos(latitude),
                cos(angular) - sin(latitude) * sin(targetLatitude)))
            val reference = ring.lastOrNull()?.first ?: longitude
            while (targetLongitude - reference > 180.0) targetLongitude -= 360.0
            while (targetLongitude - reference < -180.0) targetLongitude += 360.0
            ring += targetLongitude to Math.toDegrees(targetLatitude)
        }
        if (abs(ring.last().first - ring.first().first) > 180.0) {
            val pole = if (latitude >= 0.0) 90.0 else -90.0
            ring += ring.last().first to pole
            ring += ring.first().first to pole
        }
        val firstWorld = floor((ring.minOf { it.first } + 180.0) / 360.0).toInt()
        val lastWorld = floor((ring.maxOf { it.first } + 180.0) / 360.0).toInt()
        return (firstWorld..lastWorld).mapNotNull { world ->
            val clipped = clipLongitude(clipLongitude(ring, -180.0 + world * 360.0, true), 180.0 + world * 360.0, false)
                .map { (lng, lat) -> (lng - world * 360.0).coerceIn(-180.0, 180.0) to lat }
            if (clipped.size < 3) null else clipped + clipped.first()
        }
    }

    private fun clipLongitude(ring: List<Pair<Double, Double>>, boundary: Double, keepGreater: Boolean): List<Pair<Double, Double>> {
        if (ring.isEmpty()) return emptyList()
        fun inside(point: Pair<Double, Double>) = if (keepGreater) point.first >= boundary else point.first <= boundary
        return buildList {
            var previous = ring.last()
            for (point in ring) {
                if (inside(point) != inside(previous)) {
                    val fraction = (boundary - previous.first) / (point.first - previous.first)
                    add(boundary to previous.second + fraction * (point.second - previous.second))
                }
                if (inside(point)) add(point)
                previous = point
            }
        }
    }

    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val CIRCLE_SEGMENTS = 32
}
