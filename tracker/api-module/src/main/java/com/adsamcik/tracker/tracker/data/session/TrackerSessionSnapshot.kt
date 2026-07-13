package com.adsamcik.tracker.tracker.data.session

/**
 * Immutable session state published to tracker consumers.
 */
data class TrackerSessionSnapshot(
	val id: Long = 0,
	val start: Long = 0,
	val end: Long = 0,
	val isUserInitiated: Boolean = false,
	val collections: Int = 0,
	val distanceInM: Float = 0f,
	val distanceOnFootInM: Float = 0f,
	val distanceInVehicleInM: Float = 0f,
	val steps: Int = 0,
	val sessionActivityId: Long? = null,
)
