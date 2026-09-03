package com.adsamcik.tracker.tracker.data.session

/**
 * Immutable session state published to tracker consumers.
 *
 * [steps] is the legacy live accumulator. A positive value represents observed progress, but zero
 * carries no source-qualified coverage guarantee and must not be presented as a verified zero.
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
