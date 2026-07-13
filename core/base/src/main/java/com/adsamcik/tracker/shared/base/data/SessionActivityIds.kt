package com.adsamcik.tracker.shared.base.data

/**
 * Shared activity-id groups used by persisted session segments and UI summaries.
 *
 * `SessionSegment.primaryActivity` stores Google DetectedActivity ids for activities
 * that have a GMS equivalent. Native-only activity ids remain negative.
 */
object SessionActivityIds {
	val WALKING: Set<Int> = setOf(
		DetectedActivity.WALKING.value,
		DetectedActivity.ON_FOOT.value,
		NativeSessionActivity.WALKING.id.toInt(),
	)
	val RUNNING: Set<Int> = setOf(
		DetectedActivity.RUNNING.value,
		NativeSessionActivity.RUNNING.id.toInt(),
	)
	val CYCLING: Set<Int> = setOf(
		DetectedActivity.ON_BICYCLE.value,
		NativeSessionActivity.BICYCLE.id.toInt(),
	)
	val DRIVING: Set<Int> = setOf(
		DetectedActivity.IN_VEHICLE.value,
		NativeSessionActivity.VEHICLE.id.toInt(),
		NativeSessionActivity.LAND_VEHICLE.id.toInt(),
	)
	val WATER: Set<Int> = setOf(NativeSessionActivity.WATER_VEHICLE.id.toInt())
	val AIR: Set<Int> = setOf(NativeSessionActivity.AIR_VEHICLE.id.toInt())
	val SLOPE_SPORTS: Set<Int> = setOf(NativeSessionActivity.SLOPE_SPORTS.id.toInt())

	val ON_FOOT: Set<Int> = WALKING + RUNNING
	/** Motorised travel. Cycling is deliberately excluded: it is human-powered. */
	val IN_VEHICLE: Set<Int> = DRIVING + WATER + AIR
	val HUMAN_POWERED: Set<Int> = ON_FOOT + CYCLING
}

fun NativeSessionActivity.toSegmentPrimaryActivityId(): Int = when (this) {
	NativeSessionActivity.WALKING -> DetectedActivity.WALKING.value
	NativeSessionActivity.RUNNING -> DetectedActivity.RUNNING.value
	NativeSessionActivity.BICYCLE -> DetectedActivity.ON_BICYCLE.value
	NativeSessionActivity.VEHICLE,
	NativeSessionActivity.LAND_VEHICLE,
	NativeSessionActivity.WATER_VEHICLE,
	NativeSessionActivity.AIR_VEHICLE -> DetectedActivity.IN_VEHICLE.value
	NativeSessionActivity.SLOPE_SPORTS -> id.toInt()
}
