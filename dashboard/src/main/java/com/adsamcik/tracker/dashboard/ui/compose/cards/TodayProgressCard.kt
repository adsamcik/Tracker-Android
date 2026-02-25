package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.dashboard.ui.compose.visualization.GoalProgressRings
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance

/**
 * Today's progress card with contextual greeting, daily summary metrics,
 * and dual goal progress rings.
 */
@Composable
internal fun TodayProgressCard(
	state: DashboardUiState,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val resources = context.resources
	val settings = TrackerSettingsQuick.snapshot(context)
	val summary = state.todaySummary

	Card(
		modifier = modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainer,
		),
		shape = MaterialTheme.shapes.large,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(20.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			// Left side: header + stats
			Column(
				verticalArrangement = Arrangement.spacedBy(4.dp),
				modifier = Modifier.weight(1f),
			) {
				Text(
					text = stringResource(R.string.dashboard_today_title),
					style = MaterialTheme.typography.titleMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)

				if (summary == null || summary.isEmpty) {
					Text(
						text = stringResource(R.string.dashboard_today_no_activity),
						style = MaterialTheme.typography.bodyLarge,
						color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
					)
				} else {
					// Primary metric: distance
					val distanceText = resources.formatDistance(
						summary.totalDistanceM,
						digits = if (summary.totalDistanceM >= 1000f) 1 else 0,
						unit = settings.lengthSystem,
					)

					Text(
						text = distanceText,
						style = MaterialTheme.typography.displaySmall,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurface,
					)

					Spacer(Modifier.height(8.dp))

					// Secondary metrics
					Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
						// Duration
						Column {
							Text(
								text = stringResource(R.string.dashboard_today_duration),
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
							)
							Text(
								text = summary.totalDurationMs.formatAsDuration(context),
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.SemiBold,
								color = MaterialTheme.colorScheme.onSurface,
							)
						}

						// Steps
						if (summary.totalSteps > 0) {
							Column {
								Text(
									text = stringResource(R.string.dashboard_today_steps),
									style = MaterialTheme.typography.labelMedium,
									color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
								)
								Text(
									text = summary.totalSteps.formatReadable(),
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.SemiBold,
									color = MaterialTheme.colorScheme.onSurface,
								)
							}
						}

						// Trip count
						if (summary.sessionCount > 1) {
							Column {
								Text(
									text = stringResource(R.string.dashboard_today_trips),
									style = MaterialTheme.typography.labelMedium,
									color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
								)
								Text(
									text = summary.sessionCount.toString(),
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.SemiBold,
									color = MaterialTheme.colorScheme.onSurface,
								)
							}
						}
					}
				}
			}

			// Right side: Goal rings
			if (state.goalProgress.gamificationEnabled && state.goalProgress.dailyGoalSteps > 0) {
				Box(
					contentAlignment = Alignment.Center,
					modifier = Modifier.padding(start = 16.dp),
				) {
					GoalProgressRings(goalProgress = state.goalProgress)
				}
			}
		}
	}
}

