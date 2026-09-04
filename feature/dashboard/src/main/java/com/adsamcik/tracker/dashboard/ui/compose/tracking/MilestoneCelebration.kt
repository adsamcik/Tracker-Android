package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.shared.base.Time
import kotlinx.coroutines.delay
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot

private const val MILESTONE_DISPLAY_DURATION_MS = 3000L
private const val METERS_PER_KILOMETER = 1000f
private const val STEPS_PER_MILESTONE = 1000L
private const val MINUTES_PER_MILESTONE = 10

/**
 * Celebration overlay that appears when tracking milestones are hit.
 *
 * Tracks the same thresholds as [MilestoneHapticEffect]:
 * - Every 1 km of distance
 * - Every 1000 source-qualified steps
 * - Every 10 minutes of tracking
 *
 * Shows a banner that auto-dismisses after 3 seconds.
 */
@Composable
internal fun MilestoneCelebrationOverlay(
	sessionData: TrackerSessionSnapshot?,
	isTracking: Boolean,
	/** Complete source-qualified Steps for the exact active segment; null suppresses only Steps. */
	qualifiedSteps: Long? = null,
	modifier: Modifier = Modifier,
) {
	val highWater = remember { SegmentMilestoneHighWater() }

	var celebrationText by remember { mutableStateOf<String?>(null) }
	var showCelebration by remember { mutableStateOf(false) }

	val distanceMilestoneText = stringResource(R.string.dashboard_milestone_distance)
	val stepsMilestoneText = stringResource(R.string.dashboard_milestone_steps)
	val timeMilestoneText = stringResource(R.string.dashboard_milestone_time)

	LaunchedEffect(sessionData, isTracking, qualifiedSteps) {
		if (!isTracking || sessionData == null) {
			highWater.clear()
			return@LaunchedEffect
		}
		highWater.activate(sessionData.id)

		val currentDistanceKm = (sessionData.distanceInM / METERS_PER_KILOMETER).toInt()
		val currentStepsThousand = qualifiedSteps?.div(STEPS_PER_MILESTONE)
		val durationMinutes = ((Time.nowMillis - sessionData.start) / 60000).toInt()
		val currentMinutesTen = durationMinutes / MINUTES_PER_MILESTONE
		val crossings = highWater.record(
			currentDistanceKm = currentDistanceKm,
			currentStepsThousand = currentStepsThousand,
			currentMinutesTen = currentMinutesTen,
		)

		val milestone = milestoneCelebrationText(
			crossings = crossings,
			currentDistanceKm = currentDistanceKm,
			currentStepsThousand = currentStepsThousand,
			currentMinutesTen = currentMinutesTen,
			distanceMilestoneText = distanceMilestoneText,
			stepsMilestoneText = stepsMilestoneText,
			timeMilestoneText = timeMilestoneText,
		)

		if (milestone != null) {
			celebrationText = milestone
			showCelebration = true
		}
	}

	// Auto-dismiss after 3 seconds
	LaunchedEffect(showCelebration) {
		if (showCelebration) {
			delay(MILESTONE_DISPLAY_DURATION_MS)
			showCelebration = false
		}
	}

	AnimatedVisibility(
		visible = showCelebration,
		enter = slideInVertically { -it } + fadeIn(),
		exit = slideOutVertically { -it } + fadeOut(),
		modifier = modifier,
	) {
		Surface(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp),
			color = MaterialTheme.colorScheme.tertiaryContainer,
			contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
			shape = MaterialTheme.shapes.medium,
			tonalElevation = 4.dp,
		) {
			Row(
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Icon(
					imageVector = Icons.Filled.EmojiEvents,
					contentDescription = null,
					modifier = Modifier.size(24.dp),
					tint = MaterialTheme.colorScheme.tertiary,
				)
				Text(
					text = celebrationText.orEmpty(),
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
					modifier = Modifier.weight(1f),
				)
			}
		}
	}
}

internal fun milestoneCelebrationText(
	crossings: MilestoneCrossings,
	currentDistanceKm: Int,
	currentStepsThousand: Long?,
	currentMinutesTen: Int,
	distanceMilestoneText: String,
	stepsMilestoneText: String,
	timeMilestoneText: String,
): String? = when {
	crossings.distance -> "$currentDistanceKm $distanceMilestoneText"
	crossings.steps && currentStepsThousand != null ->
		"${currentStepsThousand * STEPS_PER_MILESTONE} $stepsMilestoneText"
	crossings.time -> "${currentMinutesTen * MINUTES_PER_MILESTONE} $timeMilestoneText"
	else -> null
}
