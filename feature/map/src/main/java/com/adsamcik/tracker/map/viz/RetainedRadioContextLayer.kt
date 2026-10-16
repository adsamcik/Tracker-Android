package com.adsamcik.tracker.map.viz

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.stats.api.repository.OptionalRetainedLocationContext
import com.adsamcik.tracker.stats.api.repository.RadioContextObservation
import com.adsamcik.tracker.stats.api.repository.RadioContextReadGap
import com.adsamcik.tracker.stats.api.repository.RadioContextSource
import com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult
import com.adsamcik.tracker.stats.api.repository.RadioLocationContextRepository
import com.adsamcik.tracker.stats.api.repository.RadioLocationContextRequest
import com.adsamcik.tracker.stats.api.repository.RetainedLocationContext
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.CancellationException

/** Existing radio view, adding actual observed device points without feeding AP/tower estimators. */
internal class RetainedRadioContextLayer<Feature, F : SpatialData>(
    private val pipeline: VizPipeline<Feature, F>,
    private val repository: RadioLocationContextRepository,
    private val source: RadioContextSource,
    dispatchers: DispatchersProvider = DefaultDispatchersProvider,
) : BaseMapLayer<RetainedRadioLayerInput<Feature>, RetainedRadioLayerOutput<F>>(dispatchers = dispatchers),
    SupportsDateRange {
    override var dateRange: LongRange = 0L..Long.MAX_VALUE

    override suspend fun loadData(context: Context, bounds: Bounds?): RetainedRadioLayerInput<Feature> {
        val end = if (dateRange.last == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            dateRange.last + 1L
        }
        val read = try {
            repository.read(RadioLocationContextRequest(source, maxOf(0L, dateRange.first), end))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RadioLocationContextReadResult.StorageUnavailable
        }
        val snapshot = read as? RadioLocationContextReadResult.Ready
        val legacy = loadLegacy(bounds, snapshot)
        val visible = snapshot?.observations.orEmpty().filter { observation ->
            val point = (observation.location as? OptionalRetainedLocationContext.Located)?.value ?: return@filter false
            val latitude = point.latitudeE7 / E7_COORDINATE_SCALE
            val longitude = point.longitudeE7 / E7_COORDINATE_SCALE
            bounds == null || latitude in bounds.south..bounds.north && bounds.containsLongitude(longitude)
        }
        return RetainedRadioLayerInput(legacy, visible, snapshot?.gaps.orEmpty(), read)
    }

    private suspend fun loadLegacy(
        bounds: Bounds?,
        snapshot: RadioLocationContextReadResult.Ready?,
    ): List<Feature> = try {
        pipeline.source.load(VizRequest(dateRange, bounds, currentPerformanceBudgets().maxPoints))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        // Keep qualified retained points when the legacy layer fails; cancellation still escapes above.
        if (snapshot == null) {
            throw error
        }
        emptyList()
    }

    override fun processData(input: RetainedRadioLayerInput<Feature>, budgets: PerformanceManager.PerformanceBudgets) =
        RetainedRadioLayerOutput(pipeline.aggregator.aggregate(input.legacy,
            AggContext(zoom, quality, budgets.maxPoints)), input.observations.take(budgets.maxPoints),
                input.gaps, input.contextRead)

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
    val gaps: Set<RadioContextReadGap>, val contextRead: RadioLocationContextReadResult)
internal data class RetainedRadioLayerOutput<F>(val legacy: F, val observations: List<RadioContextObservation>,
    val gaps: Set<RadioContextReadGap>, val contextRead: RadioLocationContextReadResult)

/** Coordinates and accuracy belong to the device fix, never to a Wi-Fi network or Cell tower. */
internal object RetainedRadioContextEncoder {
    private const val COLOR = 0xFF64748B.toInt()
    private val colorStops = listOf(0f to COLOR, 1f to COLOR)

    fun encode(observations: List<RadioContextObservation>): MapLibreLayerConfig? {
        val points = observations.mapNotNull { (it.location as? OptionalRetainedLocationContext.Located)?.value }
            .distinctBy { it.sourceEventId }
        if (points.isEmpty()) {
            return null
        }
        val markers = points.joinToString(",") { point ->
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[""" +
                """${point.longitudeE7 / E7_COORDINATE_SCALE},""" +
                """${point.latitudeE7 / E7_COORDINATE_SCALE}]},"properties":""" +
                """{"weight":1.0,"accuracyMeters":${point.horizontalAccuracyMeters},"observedTimeMs":""" +
                """${point.observedWallTimeMs},"timeUncertaintyMs":${point.wallTimeUncertaintyMs}}}"""
        }
        val polygons = points.flatMap(::accuracyRings).joinToString(",") { ring ->
            val coordinates = ring.joinToString(",") { (longitude, latitude) -> "[$longitude,$latitude]" }
            """{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$coordinates]]},"properties":{"weight":1.0}}"""
        }
        return MapLibreLayerConfig.Composite(listOfNotNull(
            polygons.takeIf(String::isNotEmpty)?.let { MapLibreLayerConfig.Fill(collection(it),
                colorStops, opacity = ACCURACY_OPACITY) },
            MapLibreLayerConfig.Circle(
                collection(markers), colorStops, MARKER_RADIUS, MARKER_RADIUS, opacity = MARKER_OPACITY,
            ),
        ))
    }

    private fun collection(features: String) = """{"type":"FeatureCollection","features":[$features]}"""

    /** Geodesic accuracy footprint; split at the date line instead of drawing across the world. */
    internal fun accuracyRings(point: RetainedLocationContext): List<List<Pair<Double, Double>>> {
        if (point.horizontalAccuracyMeters == 0f) {
            return emptyList()
        }
        val angular = point.horizontalAccuracyMeters.toDouble() / EARTH_RADIUS_METERS
        if (angular >= Math.PI) {
            return listOf(listOf(
                -MAX_LONGITUDE_DEGREES to -MAX_LATITUDE_DEGREES,
                MAX_LONGITUDE_DEGREES to -MAX_LATITUDE_DEGREES,
                MAX_LONGITUDE_DEGREES to MAX_LATITUDE_DEGREES,
                -MAX_LONGITUDE_DEGREES to MAX_LATITUDE_DEGREES,
                -MAX_LONGITUDE_DEGREES to -MAX_LATITUDE_DEGREES,
            ))
        }
        val latitude = Math.toRadians(point.latitudeE7 / E7_COORDINATE_SCALE)
        val longitude = point.longitudeE7 / E7_COORDINATE_SCALE
        val ring = mutableListOf<Pair<Double, Double>>()
        for (index in 0..CIRCLE_SEGMENTS) {
            val bearing = index * 2.0 * Math.PI / CIRCLE_SEGMENTS
            val targetLatitude =
                asin((sin(latitude) * cos(angular) + cos(latitude) * sin(angular) * cos(bearing)).coerceIn(-1.0, 1.0))
            var targetLongitude = longitude + Math.toDegrees(atan2(sin(bearing) * sin(angular) * cos(latitude),
                cos(angular) - sin(latitude) * sin(targetLatitude)))
            val reference = ring.lastOrNull()?.first ?: longitude
            while (targetLongitude - reference > MAX_LONGITUDE_DEGREES) targetLongitude -= FULL_TURN_DEGREES
            while (targetLongitude - reference < -MAX_LONGITUDE_DEGREES) targetLongitude += FULL_TURN_DEGREES
            ring += targetLongitude to Math.toDegrees(targetLatitude)
        }
        if (abs(ring.last().first - ring.first().first) > MAX_LONGITUDE_DEGREES) {
            val pole = if (latitude >= 0.0) {
                MAX_LATITUDE_DEGREES
            } else {
                -MAX_LATITUDE_DEGREES
            }
            ring += ring.last().first to pole
            ring += ring.first().first to pole
        }
        return splitAtDateLine(ring)
    }

    private fun splitAtDateLine(ring: List<Pair<Double, Double>>): List<List<Pair<Double, Double>>> {
        val firstWorld = floor((ring.minOf { it.first } + MAX_LONGITUDE_DEGREES) / FULL_TURN_DEGREES).toInt()
        val lastWorld = floor((ring.maxOf { it.first } + MAX_LONGITUDE_DEGREES) / FULL_TURN_DEGREES).toInt()
        return (firstWorld..lastWorld).mapNotNull { world ->
            val clipped = clipLongitude(
                clipLongitude(ring, -MAX_LONGITUDE_DEGREES + world * FULL_TURN_DEGREES, true),
                MAX_LONGITUDE_DEGREES + world * FULL_TURN_DEGREES,
                false,
            )
                .map { (lng, lat) ->
                    (lng - world * FULL_TURN_DEGREES)
                        .coerceIn(-MAX_LONGITUDE_DEGREES, MAX_LONGITUDE_DEGREES) to lat
                }
            if (clipped.size < MIN_POLYGON_VERTICES) {
                null
            } else {
                clipped + clipped.first()
            }
        }
    }

    private fun clipLongitude(ring: List<Pair<Double, Double>>, boundary: Double,
        keepGreater: Boolean): List<Pair<Double, Double>> {
        if (ring.isEmpty()) {
            return emptyList()
        }
        fun inside(point: Pair<Double, Double>) = if (keepGreater) {
            point.first >= boundary
        } else {
            point.first <= boundary
        }
        return buildList {
            var previous = ring.last()
            for (point in ring) {
                if (inside(point) != inside(previous)) {
                    val fraction = (boundary - previous.first) / (point.first - previous.first)
                    add(boundary to previous.second + fraction * (point.second - previous.second))
                }
                if (inside(point)) {
                    add(point)
                }
                previous = point
            }
        }
    }

    private const val MAX_LONGITUDE_DEGREES = 180.0
    private const val MAX_LATITUDE_DEGREES = 90.0
    private const val FULL_TURN_DEGREES = 360.0
    private const val ACCURACY_OPACITY = 0.12f
    private const val MARKER_OPACITY = 0.95f
    private const val MARKER_RADIUS = 3.5f
    private const val MIN_POLYGON_VERTICES = 3
    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val CIRCLE_SEGMENTS = 32
}

private const val E7_COORDINATE_SCALE = 10_000_000.0
