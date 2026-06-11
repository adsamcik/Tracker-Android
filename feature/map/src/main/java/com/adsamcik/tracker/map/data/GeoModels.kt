package com.adsamcik.tracker.map.data

import com.adsamcik.tracker.shared.base.database.entity.GeoCellSignalFeatureEntity
import com.adsamcik.tracker.shared.base.database.entity.GeoFeatureEntity
import com.adsamcik.tracker.shared.base.database.entity.GeoWeightedFeatureEntity
import kotlin.math.pow

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
    init { require(north >= south && east >= west) }
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
        east = (lng + halfDeg).coerceAtMost(180.0),
        west = (lng - halfDeg).coerceAtLeast(-180.0)
    )
}

data class GeoQuery(
    val source: GeoSource,
    val bounds: Bounds? = null,
    val timeFrom: Long? = null,
    val timeTo: Long? = null,
    val limit: Int? = null,
    val extraColumns: List<String> = emptyList(),
    val weight: String? = null
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

internal fun GeoFeatureEntity.toDomain(): BasicGeoFeature = BasicGeoFeature(lat, lon, time, properties)
internal fun GeoWeightedFeatureEntity.toDomain(): WeightedGeoFeature = WeightedGeoFeature(lat, lon, time, weight)
internal fun GeoCellSignalFeatureEntity.toDomain(): CellSignalGeoFeature =
    CellSignalGeoFeature(lat, lon, time, weight, networkType)
