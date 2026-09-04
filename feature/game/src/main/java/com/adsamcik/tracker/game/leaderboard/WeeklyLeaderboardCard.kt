package com.adsamcik.tracker.game.leaderboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import java.text.NumberFormat

/**
 * Weekly Ghost Leaderboard card for the Game screen.
 * Shows current week performance compared to historical ghosts.
 * Always renders when leaderboardState is non-null, even with no historical ghosts.
 */
@Composable
internal fun WeeklyLeaderboardCard(
	state: LeaderboardState,
	onMetricSelected: (LeaderboardMetric) -> Unit,
	modifier: Modifier = Modifier,
) {
	GlassCard(modifier = modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(16.dp)) {
			// Header
			Row(verticalAlignment = Alignment.CenterVertically) {
				Icon(
					imageVector = Icons.Outlined.EmojiEvents,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(24.dp),
				)
				Spacer(modifier = Modifier.width(8.dp))
				Text(
					text = stringResource(R.string.leaderboard_title),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
				)
			}

			Spacer(modifier = Modifier.height(12.dp))

			// Metric selector chips
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				modifier = Modifier.fillMaxWidth(),
			) {
				LeaderboardMetric.selectableEntries.forEach { metric ->
					FilterChip(
						selected = state.metric == metric,
						onClick = { onMetricSelected(metric) },
						label = {
							Text(
								text = stringResource(metric.labelRes),
								style = MaterialTheme.typography.labelSmall,
							)
						},
					)
				}
			}

			Spacer(modifier = Modifier.height(12.dp))

			// Empty state: solo user with no ghosts is a hollow leaderboard. Show an onboarding
			// nudge instead of the misleading "#1 of 1" rank text per Material 3 Expressive
			// empty-state guidance.
			val isSoloWithoutHistory = state.ghosts.isEmpty() &&
				state.competitors.count { !it.isCurrentUser } == 0
			if (isSoloWithoutHistory) {
				Text(
					text = stringResource(R.string.leaderboard_empty_title),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Spacer(modifier = Modifier.height(4.dp))
				Text(
					text = stringResource(R.string.leaderboard_empty_body),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(modifier = Modifier.height(12.dp))
				WeekProgressBar(fraction = state.weekProgressFraction)
				return@Column
			}

			// Current rank
			val rankText = stringResource(
				R.string.leaderboard_rank,
				state.currentRank,
				state.competitors.size,
			)
			Text(
				text = rankText,
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.primary,
			)

			Spacer(modifier = Modifier.height(8.dp))

			// Progress bar against top ghost (only when ghosts exist)
			if (state.ghosts.isNotEmpty()) {
				val animatedProgress by animateFloatAsState(
					targetValue = state.progressAgainstTop,
					label = "leaderboard_progress",
				)
				val progressDescription = stringResource(
					R.string.leaderboard_progress_description,
					formatValue(state.currentWeekValue),
					formatValue(state.topGhostValue),
				)
				LinearProgressIndicator(
					progress = { animatedProgress },
					modifier = Modifier
						.fillMaxWidth()
						.height(8.dp)
						.clip(RoundedCornerShape(4.dp))
						.semantics {
							contentDescription = progressDescription
							progressBarRangeInfo = ProgressBarRangeInfo(
								animatedProgress,
								0f..1f,
							)
						},
					trackColor = MaterialTheme.colorScheme.surfaceVariant,
				)
				Text(
					text = stringResource(
						R.string.leaderboard_vs_top,
						formatValue(state.currentWeekValue),
						stringResource(state.metric.unitRes),
						formatValue(state.topGhostValue),
					),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 4.dp),
				)

				Spacer(modifier = Modifier.height(12.dp))
			}

			// Competitor list (ghosts + current user, sorted by value)
			state.competitors.forEachIndexed { index, competitor ->
				CompetitorRow(
					rank = index + 1,
					competitor = competitor,
					metric = state.metric,
				)
			}

			Spacer(modifier = Modifier.height(8.dp))

			// Week progress indicator
			WeekProgressBar(fraction = state.weekProgressFraction)
		}
	}
}

@Composable
internal fun WeeklyLeaderboardErrorCard(
	onRetry: () -> Unit,
	modifier: Modifier = Modifier,
) {
	GlassCard(modifier = modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(16.dp)) {
			Text(
				text = stringResource(R.string.leaderboard_error_title),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Spacer(modifier = Modifier.height(4.dp))
			Text(
				text = stringResource(R.string.leaderboard_error_body),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(modifier = Modifier.height(12.dp))
			Button(onClick = onRetry) {
				Text(text = stringResource(R.string.leaderboard_retry))
			}
		}
	}
}

@Composable
private fun CompetitorRow(
	rank: Int,
	competitor: GhostCompetitor,
	metric: LeaderboardMetric,
) {
	val isUser = competitor.isCurrentUser
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// Rank badge
		Box(
			modifier = Modifier
				.size(28.dp)
				.clip(CircleShape)
				.background(
					if (isUser) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.surfaceVariant
					},
				),
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = "#$rank",
				style = MaterialTheme.typography.labelSmall,
				fontWeight = FontWeight.Bold,
				color = if (isUser) {
					MaterialTheme.colorScheme.onPrimary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
		}
		Spacer(modifier = Modifier.width(12.dp))
		Text(
			text = stringResource(competitor.nameRes),
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = if (isUser) FontWeight.Bold else FontWeight.Normal,
			modifier = Modifier.weight(1f),
		)
		Text(
			text = "${formatValue(competitor.value)} ${stringResource(metric.unitRes)}",
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.SemiBold,
		)
	}
}

@Composable
private fun WeekProgressBar(fraction: Float) {
	Column {
		Text(
			text = stringResource(R.string.leaderboard_week_progress),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Spacer(modifier = Modifier.height(4.dp))
		val animatedFraction by animateFloatAsState(
			targetValue = fraction,
			label = "week_progress",
		)
		LinearProgressIndicator(
			progress = { animatedFraction },
			modifier = Modifier
				.fillMaxWidth()
				.height(6.dp)
				.clip(RoundedCornerShape(3.dp)),
			trackColor = MaterialTheme.colorScheme.surfaceVariant,
			color = MaterialTheme.colorScheme.tertiary,
		)
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 2.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = stringResource(R.string.leaderboard_monday),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Text(
				text = stringResource(R.string.leaderboard_sunday),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

private fun formatValue(value: Double): String {
	return if (value == value.toLong().toDouble() && value < 1_000_000) {
		NumberFormat.getIntegerInstance().format(value.toLong())
	} else {
		NumberFormat.getInstance().apply { maximumFractionDigits = 1 }.format(value)
	}
}
