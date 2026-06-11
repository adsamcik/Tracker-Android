package com.adsamcik.tracker.dashboard.ui.compose.visualization

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.motion.MotionTokens
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineSpacing
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Dual concentric progress rings showing daily and weekly step goal progress.
 *
 * Outer ring (96dp, 5dp stroke): Weekly step goal — tertiary color.
 * Inner ring (72dp, 6dp stroke): Daily step goal — primary color.
 * Center: Steps count / target.
 * Status badge below rings.
 */
@Composable
internal fun GoalProgressRings(
	goalProgress: GoalProgressState,
	modifier: Modifier = Modifier,
) {
	if (!goalProgress.gamificationEnabled || goalProgress.dailyGoalSteps <= 0) return
	val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
	val outerSize = if (isLandscape) 80.dp else 96.dp
	val innerSize = if (isLandscape) 60.dp else 72.dp
	val outerStroke = if (isLandscape) 4.dp else 5.dp
	val innerStroke = if (isLandscape) 5.dp else 6.dp

	val animatedDailyProgress by animateFloatAsState(
		targetValue = goalProgress.dailyProgress.coerceIn(0f, 1f),
		animationSpec = MotionTokens.tweenExpressive(),
		label = "daily_goal_progress",
	)

	val animatedWeeklyProgress by animateFloatAsState(
		targetValue = goalProgress.weeklyProgress.coerceIn(0f, 1f),
		animationSpec = MotionTokens.tweenExpressive(),
		label = "weekly_goal_progress",
	)

	val primaryColor = MaterialTheme.colorScheme.primary
	val tertiaryColor = MaterialTheme.colorScheme.tertiary
	val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Box(
			contentAlignment = Alignment.Center,
			modifier = Modifier.size(outerSize),
		) {
			// Outer ring track (weekly)
			CircularProgressIndicator(
				progress = { 1f },
				modifier = Modifier.fillMaxSize(),
				color = trackColor,
				strokeWidth = outerStroke,
				trackColor = Color.Transparent,
			)
			// Outer ring progress (weekly)
			CircularProgressIndicator(
				progress = { animatedWeeklyProgress },
				modifier = Modifier.fillMaxSize(),
				color = tertiaryColor,
				strokeWidth = outerStroke,
				trackColor = Color.Transparent,
				strokeCap = StrokeCap.Round,
			)

			// Inner ring track (daily)
			CircularProgressIndicator(
				progress = { 1f },
				modifier = Modifier
					.size(innerSize),
				color = trackColor,
				strokeWidth = innerStroke,
				trackColor = Color.Transparent,
			)
			// Inner ring progress (daily)
			CircularProgressIndicator(
				progress = { animatedDailyProgress },
				modifier = Modifier
					.size(innerSize),
				color = primaryColor,
				strokeWidth = innerStroke,
				trackColor = Color.Transparent,
				strokeCap = StrokeCap.Round,
			)

			// Center: Steps count
			Column(horizontalAlignment = Alignment.CenterHorizontally) {
				Text(
					text = goalProgress.dailySteps.formatReadable(),
					style = if (isLandscape) {
						MaterialTheme.typography.labelSmall
					} else {
						MaterialTheme.typography.labelMedium
					},
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Text(
					text = "/ ${goalProgress.dailyGoalSteps.formatReadable()}",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}

		// Status badge
		val status = evaluateGoalProgressStatus(goalProgress)
		val statusText = stringResource(
			when (status) {
				GoalProgressStatus.GET_STARTED -> R.string.dashboard_goal_get_started
				GoalProgressStatus.ON_TRACK -> R.string.dashboard_goal_on_track
				GoalProgressStatus.AHEAD -> R.string.dashboard_goal_ahead
				GoalProgressStatus.BEHIND -> R.string.dashboard_goal_behind
			},
		)
		val statusColor = when (status) {
			GoalProgressStatus.GET_STARTED -> MaterialTheme.colorScheme.onSurfaceVariant
			GoalProgressStatus.ON_TRACK -> MaterialTheme.colorScheme.primary
			GoalProgressStatus.AHEAD -> MaterialTheme.colorScheme.tertiary
			GoalProgressStatus.BEHIND -> MaterialTheme.colorScheme.error
		}

		Text(
			text = statusText,
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.Medium,
			color = statusColor,
			modifier = Modifier.padding(top = RidgelineSpacing.Xs),
		)
	}
}

internal enum class GoalProgressStatus {
	GET_STARTED,
	ON_TRACK,
	AHEAD,
	BEHIND,
}

internal fun evaluateGoalProgressStatus(
	goalProgress: GoalProgressState,
	now: LocalTime = LocalTime.now(),
): GoalProgressStatus {
	if (goalProgress.dailyGoalSteps <= 0) return GoalProgressStatus.GET_STARTED
	if (goalProgress.dailyProgress <= 0f) return GoalProgressStatus.GET_STARTED
	if (goalProgress.dailyProgress >= 1f) return GoalProgressStatus.AHEAD

	val expectedProgress = expectedDailyProgress(now)
	if (goalProgress.dailySteps <= 0 && expectedProgress < EARLY_DAY_NEUTRAL_PROGRESS_CUTOFF) {
		return GoalProgressStatus.GET_STARTED
	}

	return when {
		goalProgress.dailyProgress + PROGRESS_GRACE < expectedProgress -> GoalProgressStatus.BEHIND
		goalProgress.dailyProgress >= (expectedProgress + PROGRESS_GRACE).coerceAtMost(0.95f) -> GoalProgressStatus.AHEAD
		else -> GoalProgressStatus.ON_TRACK
	}
}

internal fun expectedDailyProgress(
	now: LocalTime,
	wakeStart: LocalTime = WAKING_DAY_START,
	wakeEnd: LocalTime = WAKING_DAY_END,
): Float {
	if (!wakeStart.isBefore(wakeEnd)) return 0f
	if (now <= wakeStart) return 0f
	if (now >= wakeEnd) return 1f

	val elapsedMinutes = ChronoUnit.MINUTES.between(wakeStart, now).toFloat()
	val wakingMinutes = ChronoUnit.MINUTES.between(wakeStart, wakeEnd).toFloat()
	return (elapsedMinutes / wakingMinutes).coerceIn(0f, 1f)
}

private val WAKING_DAY_START: LocalTime = LocalTime.of(6, 0)
private val WAKING_DAY_END: LocalTime = LocalTime.of(22, 0)
private const val EARLY_DAY_NEUTRAL_PROGRESS_CUTOFF = 0.4f
private const val PROGRESS_GRACE = 0.12f
