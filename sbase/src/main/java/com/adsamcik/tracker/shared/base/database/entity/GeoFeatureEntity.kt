package com.adsamcik.tracker.shared.base.database.entity

/**
 * Unified geo feature projection used by raw spatial queries.
 * Not a Room @Entity; produced via raw queries in UnifiedGeoDao.
 * Phase 3.1 includes only core columns (lat, lon, time). Additional dynamic properties
 * will be introduced in Phase 3.2 alongside a JSON-backed map + TypeConverter.
 */
data class GeoFeatureEntity(
    val lat: Double,
    val lon: Double,
    val time: Long,
    // Stored as JSON when persisted / projected (Phase 3.2). For current raw queries this will be empty.
    val properties: Map<String, Double> = emptyMap()
)
