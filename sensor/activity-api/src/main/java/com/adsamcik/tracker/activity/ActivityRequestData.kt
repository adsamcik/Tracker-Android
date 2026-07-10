package com.adsamcik.tracker.activity

import com.adsamcik.tracker.stats.api.DetectedActivityType
import kotlin.reflect.KClass

/**
 * Request data for activity. Supports both activity detection and transition detection.
 */
data class ActivityRequestData(
		val key: KClass<*>,
		val changeData: ActivityChangeRequestData? = null,
		val transitionData: ActivityTransitionRequestData? = null
)

/**
 * Request data for activity change detection.
 */
data class ActivityChangeRequestData(
		val detectionIntervalS: Int
)

/**
 * Request data for activity transition.
 */
data class ActivityTransitionRequestData(
		val transitionList: Collection<ActivityTransitionData>
)

/**
 * Activity transition data describing which transition has occurred.
 */
data class ActivityTransitionData(
	val activity: DetectedActivityType,
	val type: ActivityTransitionType,
)

/**
 * Wrapper for type of activity transition.
 */
enum class ActivityTransitionType(val value: Int) {
	ENTER(0),
	EXIT(1)
}
