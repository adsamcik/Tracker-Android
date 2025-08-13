package com.adsamcik.tracker.map.v2.data

import com.adsamcik.tracker.shared.base.database.entity.GeoFeatureEntity
import com.adsamcik.tracker.shared.base.database.entity.GeoWeightedFeatureEntity

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

internal fun GeoFeatureEntity.toDomain(): BasicGeoFeature = BasicGeoFeature(lat, lon, time, properties)
internal fun GeoWeightedFeatureEntity.toDomain(): WeightedGeoFeature = WeightedGeoFeature(lat, lon, time, weight)
