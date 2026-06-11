package com.adsamcik.tracker.stats.api.speed

/**
 * Pluggable source of speed limits for the map's "vehicle speed compliance" layer.
 *
 * Phase 1: implementations return a single user-configured baseline regardless of input.
 * Future phases may route by road class, OSM import lookup, or map-matched geometry.
 *
 * Implementations MUST be offline-only — the tracker has a hard no-network rule.
 *
 * @param epochMs sample timestamp in milliseconds since the Unix epoch
 * @param latE7 sample latitude in E7 (1e-7 degrees); `null` when coordinate is unknown
 * @param lonE7 sample longitude in E7 (1e-7 degrees); `null` when coordinate is unknown
 * @return speed limit at the sample expressed in metres per second
 */
fun interface SpeedLimitSource {
    suspend fun limitMpsAt(epochMs: Long, latE7: Int?, lonE7: Int?): Double
}
