package com.adsamcik.tracker.stats.api.ski

/**
 * Project-owned representation of a ski lift from an imported infrastructure database.
 */
data class SkiLift(
	val id: Long,
	val liftType: String,
	val name: String?,
	val startLat: Double,
	val startLon: Double,
	val startElev: Double?,
	val endLat: Double,
	val endLon: Double,
	val endElev: Double?,
)
