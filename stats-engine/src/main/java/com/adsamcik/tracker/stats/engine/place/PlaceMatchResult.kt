package com.adsamcik.tracker.stats.engine.place

/**
 * Result of matching a location against existing place clusters.
 */
sealed class PlaceMatchResult {
	/** Location matched an existing cluster. */
	data class Matched(val cluster: PlaceCluster) : PlaceMatchResult()

	/** No cluster matched; a new place should be created at these coordinates. */
	data class NewPlace(val latE7: Int, val lonE7: Int) : PlaceMatchResult()
}
