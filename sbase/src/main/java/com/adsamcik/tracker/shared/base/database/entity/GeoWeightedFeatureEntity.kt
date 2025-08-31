package com.adsamcik.tracker.shared.base.database.entity

/**
 * Weighted variant of a geo feature (e.g., speed, signal strength, count).
 */
data class GeoWeightedFeatureEntity(
    val lat: Double,
    val lon: Double,
    val time: Long,
    val weight: Double
)
