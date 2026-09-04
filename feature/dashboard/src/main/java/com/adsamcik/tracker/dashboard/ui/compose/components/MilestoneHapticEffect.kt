package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.adsamcik.tracker.shared.base.Time
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
	var lastDistanceKm by remember { mutableStateOf(0) }
	var lastStepsThousand by remember { mutableLongStateOf(0L) }
	var lastMinutesTen by remember { mutableStateOf(0) }

	LaunchedEffect(sessionData, isTracking, milestonesEnabled, qualifiedSteps) {
		if (!isTracking || !milestonesEnabled || sessionData == null) {
			lastDistanceKm = 0
			lastStepsThousand = 0L
			lastMinutesTen = 0
			return@LaunchedEffect
		}

		val currentDistanceKm = (sessionData.distanceInM / 1000f).toInt()
		val currentStepsThousand = qualifiedSteps?.div(1000L)
		val durationMinutes = ((Time.nowMillis - sessionData.start) / 60000).toInt()
		val currentMinutesTen = durationMinutes / 10

		if (currentDistanceKm > lastDistanceKm && lastDistanceKm > 0) {
			haptics.performHapticFeedback(HapticFeedbackType.Confirm)
		}
		lastDistanceKm = currentDistanceKm

		if (crossedQualifiedStepMilestone(lastStepsThousand, currentStepsThousand)) {
			haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
		}
		lastStepsThousand = currentStepsThousand ?: 0L

		if (currentMinutesTen > lastMinutesTen && lastMinutesTen > 0) {
			haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
		}
		lastMinutesTen = currentMinutesTen
	}
}

private fun crossedQualifiedStepMilestone(
	previous: Long,
	current: Long?,
): Boolean = current != null && current > previous && previous > 0L
