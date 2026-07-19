package com.adsamcik.tracker.map.data

import com.adsamcik.tracker.shared.base.database.entity.GeoCellSignalFeatureEntity
import com.adsamcik.tracker.shared.base.database.entity.GeoCellRadioFeatureEntity
import com.adsamcik.tracker.shared.base.database.entity.GeoFeatureEntity
import com.adsamcik.tracker.shared.base.database.entity.GeoWeightedFeatureEntity
import com.adsamcik.tracker.shared.base.database.entity.GeoWifiRadioFeatureEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Domain models / query descriptors for geo repository.
 */

enum class GeoSource { LOCATION, WIFI, CELL }

data class Bounds(
    val north: Double,
    val east: Double,
    val south: Double,
    val west: Double
) {
    init {
        require(north >= south)
        require(north in -90.0..90.0 && south in -90.0..90.0)
        require(east in -180.0..180.0 && west in -180.0..180.0)
    }

    val crossesAntimeridian: Boolean get() = east < west

    val longitudeSpan: Double
        get() = if (crossesAntimeridian) FULL_LONGITUDE_DEGREES - west + east else east - west

    fun containsLongitude(longitude: Double): Boolean =
        if (crossesAntimeridian) longitude >= west || longitude <= east else longitude in west..east

    fun intersectsLongitudeRange(minLongitude: Double, maxLongitude: Double): Boolean =
        longitudeIntervals().any { interval ->
            listOf(-FULL_LONGITUDE_DEGREES, 0.0, FULL_LONGITUDE_DEGREES).any { shift ->
                maxLongitude + shift >= interval.start && minLongitude + shift <= interval.endInclusive
            }
        }

    private fun longitudeIntervals(): List<ClosedFloatingPointRange<Double>> =
        if (crossesAntimeridian) {
            listOf(west..180.0, -180.0..east)
        } else {
            listOf(west..east)
        }
}

/**
 * Compute a [Bounds] bounding box from a camera center and zoom level.
 * Adds 50% padding on each side so data is pre-fetched for smooth panning.
 * Returns `null` when zoom is too low (world-level view) because filtering
 * would not meaningfully reduce the result set.
 */
fun cameraToBounds(lat: Double, lng: Double, zoom: Double): Bounds? {
    if (zoom < 3.0) return null
    val degreesVisible = 360.0 / 2.0.pow(zoom)
    val halfDeg = degreesVisible / 2.0 * 1.5 // 50 % padding
    return Bounds(
        north = (lat + halfDeg).coerceAtMost(90.0),
        south = (lat - halfDeg).coerceAtLeast(-90.0),
        east = normalizeLongitude(lng + halfDeg),
        west = normalizeLongitude(lng - halfDeg),
    )
}

/**
 * Pad an axis-aligned lat/lon box by [paddingFraction] of its span on each side, clamped to valid
 * coordinate ranges. Antimeridian-crossing input is represented by `east < west`.
 *
 * Used to expand the map's real visible viewport (from the projection) for prefetch, replacing the
 * coarse [cameraToBounds] estimate which assumes a single 256px tile is visible and therefore
 * under-covers the screen.
 */
fun paddedBounds(
    north: Double,
    east: Double,
    south: Double,
    west: Double,
    paddingFraction: Double = 0.5,
): Bounds? {
    if (north <= south || paddingFraction < 0.0) return null
    val latPad = (north - south) * paddingFraction
    val lonSpan = if (east >= west) east - west else FULL_LONGITUDE_DEGREES - west + east
    if (lonSpan <= 0.0) return null
    val paddedLonSpan = lonSpan * (1.0 + 2.0 * paddingFraction)
    val longitudeBounds = if (paddedLonSpan >= FULL_LONGITUDE_DEGREES) {
        -180.0 to 180.0
    } else {
        val center = normalizeLongitude(west + lonSpan / 2.0)
        normalizeLongitude(center - paddedLonSpan / 2.0) to
            normalizeLongitude(center + paddedLonSpan / 2.0)
    }
    return Bounds(
        north = (north + latPad).coerceAtMost(90.0),
        south = (south - latPad).coerceAtLeast(-90.0),
        east = longitudeBounds.second,
        west = longitudeBounds.first,
    )
}

/** Approximate metres per degree of latitude (mean Earth radius). */
private const val METERS_PER_DEG_LAT = 111_320.0

/** Mean Earth radius in metres, for haversine distance. */
private const val EARTH_RADIUS_M = 6_371_000.0

/**
 * Axis-aligned [Bounds] box that covers a circle of [radiusMeters] around (`lat`, `lng`). The
 * longitude span is widened by `1 / cos(lat)` so the box still encloses the full circle at higher
 * latitudes. Returns `null` for a non-positive radius. Coordinates are clamped to valid ranges.
 *
 * Used for point queries (e.g. tapping the speed heatmap to read the speed around a location); the
 * caller should still distance-filter results with [haversineMeters] since the box is a superset.
 */
fun boundsAround(lat: Double, lng: Double, radiusMeters: Double): Bounds? {
    if (radiusMeters <= 0.0) return null
    val latDelta = radiusMeters / METERS_PER_DEG_LAT
    val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
    val lngDelta = radiusMeters / (METERS_PER_DEG_LAT * cosLat)
    val north = (lat + latDelta).coerceAtMost(90.0)
    val south = (lat - latDelta).coerceAtLeast(-90.0)
    val (west, east) = if (lngDelta >= HALF_LONGITUDE_DEGREES) {
        -180.0 to 180.0
    } else {
        normalizeLongitude(lng - lngDelta) to normalizeLongitude(lng + lngDelta)
    }
    if (north < south) return null
    return Bounds(north = north, east = east, south = south, west = west)
}

private fun normalizeLongitude(longitude: Double): Double =
    ((longitude + LONGITUDE_WRAP_OFFSET) % FULL_LONGITUDE_DEGREES) - HALF_LONGITUDE_DEGREES

private const val FULL_LONGITUDE_DEGREES = 360.0
private const val HALF_LONGITUDE_DEGREES = 180.0
private const val LONGITUDE_WRAP_OFFSET = 540.0

/** Great-circle (haversine) distance in metres between two lat/lon points. */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
}

data class GeoQuery(
    val source: GeoSource,
    val bounds: Bounds? = null,
    val timeFrom: Long? = null,
    val timeTo: Long? = null,
    val limit: Int? = null,
    val extraColumns: List<String> = emptyList(),
    val weight: String? = null,
    val sampleLimit: Int? = null,
    val newestLimit: Int? = null,
)

sealed interface GeoFeature {
    val lat: Double
    val lon: Double
    val time: Long
}

data class BasicGeoFeature(
    override val lat: Double,
    override val lon: Double,
    override val time: Long,
    val properties: Map<String, Double> = emptyMap()
) : GeoFeature

data class WeightedGeoFeature(
    override val lat: Double,
    override val lon: Double,
    override val time: Long,
    val weight: Double
) : GeoFeature

data class CellSignalGeoFeature(
    override val lat: Double,
    override val lon: Double,
    override val time: Long,
    val asu: Double,
    val networkType: Int
) : GeoFeature

data class WifiRadioGeoFeature(
    override val lat: Double,
    override val lon: Double,
    override val time: Long,
    val bssid: String,
    val levelDbm: Int,
    val frequencyMhz: Int,
) : GeoFeature

data class CellRadioGeoFeature(
    override val lat: Double,
    override val lon: Double,
    override val time: Long,
    val cellId: Long,
    val areaCode: Int,
    val mcc: Int,
    val mnc: Int,
    val networkType: Int,
    val asu: Int?,
) : GeoFeature

/**
 * A single grid tile (axis-aligned lat/lon rectangle) for the legacy grid-tile heatmap.
 * [weight] is the visit count normalized to [0, 1] across the rendered tile set; [count] is the raw
 * number of contributing location fixes.
 */
data class GridTile(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val weight: Double,
    val count: Int,
)

internal fun GeoFeatureEntity.toDomain(): BasicGeoFeature = BasicGeoFeature(lat, lon, time, properties)
internal fun GeoWeightedFeatureEntity.toDomain(): WeightedGeoFeature = WeightedGeoFeature(lat, lon, time, weight)
internal fun GeoCellSignalFeatureEntity.toDomain(): CellSignalGeoFeature =
    CellSignalGeoFeature(lat, lon, time, weight, networkType)
internal fun GeoWifiRadioFeatureEntity.toDomain(): WifiRadioGeoFeature =
    WifiRadioGeoFeature(lat, lon, time, bssid, level, frequency)
internal fun GeoCellRadioFeatureEntity.toDomain(): CellRadioGeoFeature =
    CellRadioGeoFeature(lat, lon, time, cellId, lac, mcc, mnc, networkType, asu)
