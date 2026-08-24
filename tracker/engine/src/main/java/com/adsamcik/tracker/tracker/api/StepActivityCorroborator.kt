package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.shared.base.data.GroupedActivity

/** True only when Steps can change the result; full-confidence Activity never consults it. */
internal fun shouldConsultStepCorroboration(
	groupedActivity: GroupedActivity,
	confidence: Int,
	requiredConfidence: Int,
	corroboratedConfidence: Int,
): Boolean = confidence < requiredConfidence &&
	groupedActivity == GroupedActivity.ON_FOOT &&
	confidence >= corroboratedConfidence

/**
 * Pure logic: whether an activity-recognition result should trigger an automatic on-foot start.
 *
 * A confidence at or above [requiredConfidence] always qualifies (unchanged behaviour). Additionally,
 * an ON_FOOT result at or above the lower [corroboratedConfidence] qualifies when the step counter
 * confirms recent walking ([hasRecentSteps]). This only widens when auto-tracking starts; it never
 * blocks a start that the confidence threshold alone would have allowed, and only ON_FOOT benefits
 * (vehicle/bicycle activities do not produce steps).
 */
internal fun isOnFootAutoStartCorroborated(
	groupedActivity: GroupedActivity,
	confidence: Int,
	requiredConfidence: Int,
	corroboratedConfidence: Int,
	hasRecentSteps: Boolean,
): Boolean {
	if (confidence >= requiredConfidence) return true
	return groupedActivity == GroupedActivity.ON_FOOT &&
		confidence >= corroboratedConfidence &&
		hasRecentSteps
}
