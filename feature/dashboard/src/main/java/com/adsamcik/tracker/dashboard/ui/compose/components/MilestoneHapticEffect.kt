package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.dashboard.ui.compose.tracking.SegmentMilestoneHighWater
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot

/**
 * Triggers haptic feedback when tracking milestones are reached.
 *
 * Milestones:
 * - Every 1000 meters (1 km)
 * - Every 1000 source-qualified steps
 * - Every 10 minutes of tracking
 */
@Composable
internal fun MilestoneHapticEffect(
	sessionData: TrackerSessionSnapshot?,
	isTracking: Boolean,
	haptics: HapticFeedback,
	milestonesEnabled: Boolean = true,
	/** Complete source-qualified Steps for the exact active segment; null suppresses only Steps. */
	qualifiedSteps: Long? = null,
) {
	val highWater = remember { SegmentMilestoneHighWater() }

	LaunchedEffect(sessionData, isTracking, milestonesEnabled, qualifiedSteps) {
		if (!isTracking || sessionData == null) {
			highWater.clear()
			return@LaunchedEffect
		}
		highWater.activate(sessionData.id)

		val currentDistanceKm = (sessionData.distanceInM / 1000f).toInt()
		val currentStepsThousand = qualifiedSteps?.div(1000L)
		val durationMinutes = ((Time.nowMillis - sessionData.start) / 60000).toInt()
		val currentMinutesTen = durationMinutes / 10
		val crossings = highWater.record(
			currentDistanceKm = currentDistanceKm,
			currentStepsThousand = currentStepsThousand,
			currentMinutesTen = currentMinutesTen,
		)
		if (!milestonesEnabled) {
			return@LaunchedEffect
		}

		if (crossings.distance) {
			haptics.performHapticFeedback(HapticFeedbackType.Confirm)
		}

		if (crossings.steps) {
			haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
		}

		if (crossings.time) {
			haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
		}
	}
}
