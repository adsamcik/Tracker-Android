package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.state.StreakState
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineCardDefaults
import com.adsamcik.tracker.dashboard.ui.compose.state.WeeklyTrend

/**
 * Compact full-width banner showing current tracking streak.
 *
 * Left: Flame icon + streak count + "day streak" text.
 * Right: Weekly trend mini-sparkline (7 vertical bars) + trend indicator.
 */
@Composable
internal fun StreakBanner(
	streakState: StreakState,
	onClick: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	if (streakState.currentStreak <= 0) {
		// No active streak — keep the banner in a single encouragement state.
		EncouragementBanner(
			modifier = modifier,
			onClick = onClick,
		)
		return
	}

	val primaryColor = MaterialTheme.colorScheme.primary
	val tertiaryColor = MaterialTheme.colorScheme.tertiary
	val errorColor = MaterialTheme.colorScheme.error
	val streakContentDescription = pluralStringResource(
		R.plurals.dashboard_cd_streak_banner,
		streakState.currentStreak,
		streakState.currentStreak,
	)
	val streakClickLabel = stringResource(R.string.dashboard_action_view_streak_details)

	Card(
		modifier = modifier
			.fillMaxWidth()
			.semantics(mergeDescendants = true) {
				contentDescription = streakContentDescription
			}
			.then(
				if (onClick != null) {
					Modifier.clickable(
						onClickLabel = streakClickLabel,
						onClick = onClick,
					)
				} else {
					Modifier
				},
			),
		colors = CardDefaults.cardColors(
			containerColor = RidgelineCardDefaults.containerColor,
		),
		shape = RidgelineCardDefaults.shape,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			// Left: streak info
			Row(verticalAlignment = Alignment.CenterVertically) {
				Icon(
					imageVector = Icons.Filled.LocalFireDepartment,
					contentDescription = null,
					tint = if (streakState.currentStreak > 0) {
						MaterialTheme.colorScheme.error
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					modifier = Modifier.size(24.dp),
				)
				Spacer(Modifier.width(8.dp))

				if (streakState.currentStreak > 0) {
					Text(
						text = "${streakState.currentStreak}",
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurface,
					)
					Spacer(Modifier.width(4.dp))
					Text(
						text = stringResource(R.string.dashboard_streak_day_streak),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				} else {
					Text(
						text = stringResource(R.string.dashboard_streak_start),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}

			// Right: sparkline + trend
			if (streakState.weeklyDistances.isNotEmpty()) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					WeeklySparkline(
						values = streakState.weeklyDistances,
						barColor = primaryColor,
						modifier = Modifier.size(width = 56.dp, height = 24.dp),
					)
					Spacer(Modifier.width(6.dp))

					val trendPercent = computeTrendPercent(streakState.weeklyDistances)
					val trendText = when (streakState.weeklyTrend) {
						WeeklyTrend.UP -> stringResource(
							R.string.dashboard_streak_trend_up,
							trendPercent,
						)
						WeeklyTrend.DOWN -> stringResource(
							R.string.dashboard_streak_trend_down,
							trendPercent,
						)
						WeeklyTrend.STEADY -> ""
					}

					if (trendText.isNotEmpty()) {
						Text(
							text = trendText,
							style = MaterialTheme.typography.labelSmall,
							fontWeight = FontWeight.Medium,
							color = when (streakState.weeklyTrend) {
								WeeklyTrend.UP -> tertiaryColor
								WeeklyTrend.DOWN -> errorColor
								WeeklyTrend.STEADY -> MaterialTheme.colorScheme.onSurfaceVariant
							},
						)
					}
				}
			}
		}
	}
}

@Composable
private fun EncouragementBanner(
	modifier: Modifier = Modifier,
	onClick: (() -> Unit)? = null,
) {
	val streakContentDescription = stringResource(R.string.dashboard_cd_streak_banner_empty)
	val streakClickLabel = stringResource(R.string.dashboard_action_view_streak_details)

	Card(
		modifier = modifier
			.fillMaxWidth()
			.semantics(mergeDescendants = true) {
				contentDescription = streakContentDescription
			}
			.then(
				if (onClick != null) {
					Modifier.clickable(
						onClickLabel = streakClickLabel,
						onClick = onClick,
					)
				} else {
					Modifier
				},
			),
		colors = CardDefaults.cardColors(
			containerColor = RidgelineCardDefaults.containerColor,
		),
		shape = RidgelineCardDefaults.shape,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				imageVector = Icons.Filled.LocalFireDepartment,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(24.dp),
			)
			Spacer(Modifier.width(8.dp))
			Text(
				text = stringResource(R.string.dashboard_streak_start),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/**
 * Mini sparkline of 7 vertical bars representing weekly distances.
 */
@Composable
private fun WeeklySparkline(
	values: List<Float>,
	barColor: androidx.compose.ui.graphics.Color,
	modifier: Modifier = Modifier,
) {
	val maxVal = values.maxOrNull()?.takeIf { it > 0f } ?: 1f

	Canvas(modifier = modifier) {
		val barCount = values.size.coerceAtMost(7)
		if (barCount == 0) return@Canvas

		val barWidth = size.width / (barCount * 2f - 1f)
		val gap = barWidth

		values.take(7).forEachIndexed { index, value ->
			val normalizedHeight = (value / maxVal).coerceIn(0.05f, 1f) * size.height
			val x = index * (barWidth + gap)
			drawRect(
				color = barColor.copy(alpha = 0.4f + 0.6f * (value / maxVal).coerceIn(0f, 1f)),
				topLeft = Offset(x, size.height - normalizedHeight),
				size = Size(barWidth, normalizedHeight),
			)
		}
	}
}

private fun computeTrendPercent(values: List<Float>): Int {
	if (values.size < 2) return 0
	val recent = values.takeLast(3).average().toFloat()
	val older = values.take((values.size - 3).coerceAtLeast(1)).average().toFloat()
	if (older <= 0f) return 0
	return ((recent - older) / older * 100f).toInt().coerceIn(-999, 999).let {
		kotlin.math.abs(it)
	}
}
