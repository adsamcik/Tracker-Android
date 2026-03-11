package com.adsamcik.tracker.statistics.viewmodel

import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.stats.api.DetectedActivityType

/**
 * Maps Trip.primaryActivity (DetectedActivity int) to [DetectedActivityType].
 * Values follow Google Play Services DetectedActivity constants.
 */
internal fun Trip.detectedActivityType(): DetectedActivityType? = when (primaryActivity) {
	0 -> DetectedActivityType.IN_VEHICLE
	1 -> DetectedActivityType.ON_BICYCLE
	2 -> DetectedActivityType.ON_FOOT
	3 -> DetectedActivityType.STILL
	7 -> DetectedActivityType.WALKING
	8 -> DetectedActivityType.RUNNING
	else -> null
}
