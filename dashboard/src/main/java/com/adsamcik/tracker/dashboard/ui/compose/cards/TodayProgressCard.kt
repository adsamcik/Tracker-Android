package com.adsamcik.tracker.dashboard.ui.compose.cards

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.components.TrackingActionRing
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineCardDefaults

/**
 * Today's progress card with contextual greeting, daily summary metrics,
 * and dual goal progress rings.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TodayProgressCard(
	state: DashboardUiState,
	onToggleTracking: () -> Unit,
	onRequestPermission: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val configuration = LocalConfiguration.current
	val resources = context.resources
	val settings = TrackerSettingsQuick.snapshot(context)
	val summary = state.todaySummary
	val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
	// Tighter in landscape: phone landscape viewport is short, so the hero must
	// not crowd out the streak/challenges/last-session widgets below the fold.
	val contentPadding = if (isLandscape) 12.dp else 20.dp
	val metricSpacing = if (isLandscape) 10.dp else 16.dp
	val metricRowSpacing = if (isLandscape) 6.dp else 12.dp
	val primaryMetricStyle = if (isLandscape) {
		MaterialTheme.typography.titleLarge
	} else {
		MaterialTheme.typography.displaySmall
	}

	// Hero card — primaryContainer gives it visual weight vs the rest of the dashboard widgets.
	// Use the larger shape bucket so it feels like the anchor widget.
	Card(
		modifier = modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.primaryContainer,
			contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
		),
		shape = MaterialTheme.shapes.extraLarge,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(contentPadding),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.Top,
		) {
			// Left side: header + stats
			Column(
				verticalArrangement = Arrangement.spacedBy(if (isLandscape) 2.dp else 4.dp),
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
						color = MaterialTheme.colorScheme.onSurface,
					)
				} else {
					// Primary metric: distance
					val distanceText = resources.formatDistance(
						summary.totalDistanceM,
						digits = if (summary.totalDistanceM >= 1000f) 1 else 0,
						unit = settings.lengthSystem,
					)

					Text(
						text = stringResource(R.string.dashboard_today_distance),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						text = distanceText,
						style = primaryMetricStyle,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurface,
					)

					Spacer(Modifier.height(if (isLandscape) 4.dp else 8.dp))

					// Secondary metrics
					FlowRow(
						horizontalArrangement = Arrangement.spacedBy(metricSpacing),
						verticalArrangement = Arrangement.spacedBy(metricRowSpacing),
					) {
						// Duration
						Column {
							Text(
								text = stringResource(R.string.dashboard_today_duration),
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.onSurface,
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
									color = MaterialTheme.colorScheme.onSurface,
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
									color = MaterialTheme.colorScheme.onSurface,
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

			// Right side: Tracking action (play/stop) integrated with goal rings
			Box(
				contentAlignment = Alignment.Center,
				modifier = Modifier.padding(start = if (isLandscape) 8.dp else 16.dp),
			) {
				TrackingActionRing(
					isTracking = state.isTracking,
					hasPermission = state.hasLocationPermission,
					goalProgress = state.goalProgress,
					onToggleTracking = onToggleTracking,
					onRequestPermission = onRequestPermission,
					compact = isLandscape,
				)
			}
		}
	}
}
