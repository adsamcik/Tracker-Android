package com.adsamcik.tracker.dashboard.ui.compose.visualization

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.motion.MotionTokens
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.shared.base.extension.formatReadable

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
			modifier = Modifier.size(96.dp),
		) {
			// Outer ring track (weekly)
			CircularProgressIndicator(
				progress = { 1f },
				modifier = Modifier.fillMaxSize(),
				color = trackColor,
				strokeWidth = 5.dp,
				trackColor = Color.Transparent,
			)
			// Outer ring progress (weekly)
			CircularProgressIndicator(
				progress = { animatedWeeklyProgress },
				modifier = Modifier.fillMaxSize(),
				color = tertiaryColor,
				strokeWidth = 5.dp,
				trackColor = Color.Transparent,
				strokeCap = StrokeCap.Round,
			)

			// Inner ring track (daily)
			CircularProgressIndicator(
				progress = { 1f },
				modifier = Modifier
					.size(72.dp),
				color = trackColor,
				strokeWidth = 6.dp,
				trackColor = Color.Transparent,
			)
			// Inner ring progress (daily)
			CircularProgressIndicator(
				progress = { animatedDailyProgress },
				modifier = Modifier
					.size(72.dp),
				color = primaryColor,
				strokeWidth = 6.dp,
				trackColor = Color.Transparent,
				strokeCap = StrokeCap.Round,
			)

			// Center: Steps count
			Column(horizontalAlignment = Alignment.CenterHorizontally) {
				Text(
					text = goalProgress.dailySteps.formatReadable(),
					style = MaterialTheme.typography.labelMedium,
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
		val statusText = when {
			goalProgress.dailyProgress >= 1f -> stringResource(R.string.dashboard_goal_ahead)
			goalProgress.dailyProgress >= 0.7f -> stringResource(R.string.dashboard_goal_on_track)
			else -> stringResource(R.string.dashboard_goal_behind)
		}
		val statusColor = when {
			goalProgress.dailyProgress >= 1f -> MaterialTheme.colorScheme.tertiary
			goalProgress.dailyProgress >= 0.7f -> MaterialTheme.colorScheme.primary
			else -> MaterialTheme.colorScheme.error
		}

		Text(
			text = statusText,
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.Medium,
			color = statusColor,
			modifier = Modifier.padding(top = 4.dp),
		)
	}
}
