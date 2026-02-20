package com.adsamcik.tracker.stats.engine.place

/**
 * In-memory representation of a frequent place cluster for matching.
 * Decoupled from Room entity for testability.
 */
data class PlaceCluster(
	val id: Long,
	val centerLatE7: Int,
	val centerLonE7: Int,
	val radiusM: Float,
	val visitCount: Int
)
