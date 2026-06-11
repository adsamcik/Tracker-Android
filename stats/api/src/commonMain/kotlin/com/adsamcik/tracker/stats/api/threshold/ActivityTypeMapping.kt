package com.adsamcik.tracker.stats.api.threshold

import com.adsamcik.tracker.stats.api.DetectedActivityType

/**
 * Canonical mapping between DetectedActivityType and Google Play Services
 * DetectedActivity integer codes. Single source of truth — no duplicates.
 */
object ActivityTypeMapping {
	/** Maps Google Play Services int code to DetectedActivityType. */
	fun fromPlayServicesCode(code: Int): DetectedActivityType = when (code) {
		0 -> DetectedActivityType.IN_VEHICLE
		1 -> DetectedActivityType.ON_BICYCLE
		2 -> DetectedActivityType.ON_FOOT
		3 -> DetectedActivityType.STILL
		5 -> DetectedActivityType.TILTING
		7 -> DetectedActivityType.WALKING
		8 -> DetectedActivityType.RUNNING
		else -> DetectedActivityType.UNKNOWN
	}

	/** Maps DetectedActivityType to Google Play Services int code. */
	fun toPlayServicesCode(type: DetectedActivityType): Int = when (type) {
		DetectedActivityType.IN_VEHICLE -> 0
		DetectedActivityType.ON_BICYCLE -> 1
		DetectedActivityType.ON_FOOT -> 2
		DetectedActivityType.STILL -> 3
		DetectedActivityType.TILTING -> 5
		DetectedActivityType.WALKING -> 7
		DetectedActivityType.RUNNING -> 8
		DetectedActivityType.UNKNOWN -> 4
	}
}
