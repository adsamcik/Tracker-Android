package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
 * - Every 1000 steps
 * - Every 10 minutes of tracking
 */
@Composable
internal fun MilestoneHapticEffect(
	sessionData: TrackerSessionSnapshot?,
	isTracking: Boolean,
	haptics: HapticFeedback,
	milestonesEnabled: Boolean = true,
) {
	var lastDistanceKm by remember { mutableStateOf(0) }
	var lastStepsThousand by remember { mutableStateOf(0) }
	var lastMinutesTen by remember { mutableStateOf(0) }

	LaunchedEffect(sessionData, isTracking, milestonesEnabled) {
		if (!isTracking || !milestonesEnabled || sessionData == null) {
			lastDistanceKm = 0
			lastStepsThousand = 0
			lastMinutesTen = 0
			return@LaunchedEffect
		}

		val currentDistanceKm = (sessionData.distanceInM / 1000f).toInt()
		val currentStepsThousand = sessionData.steps / 1000
		val durationMinutes = ((Time.nowMillis - sessionData.start) / 60000).toInt()
		val currentMinutesTen = durationMinutes / 10

		if (currentDistanceKm > lastDistanceKm && lastDistanceKm > 0) {
			haptics.performHapticFeedback(HapticFeedbackType.Confirm)
		}
		lastDistanceKm = currentDistanceKm

		if (currentStepsThousand > lastStepsThousand && lastStepsThousand > 0) {
			haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
		}
		lastStepsThousand = currentStepsThousand

		if (currentMinutesTen > lastMinutesTen && lastMinutesTen > 0) {
			haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
		}
		lastMinutesTen = currentMinutesTen
	}
}
