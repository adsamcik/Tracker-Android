package com.adsamcik.tracker.stats.engine.place

/**
 * Configuration for place matching and cluster creation.
 *
 * @property matchRadiusM Maximum distance in meters to match a location to an existing cluster
 * @property initialRadiusM Default radius for newly created place clusters
 * @property minVisitsForStable Minimum visit count before a cluster is considered stable
 */
data class PlaceClusterConfig(
	val matchRadiusM: Float = 150f,
	val initialRadiusM: Float = 100f,
	val minVisitsForStable: Int = 3
)
