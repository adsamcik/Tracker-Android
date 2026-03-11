package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.DashboardLayoutDefaults
import com.adsamcik.tracker.dashboard.ui.compose.components.SensorDetailsCard
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.visualization.AltitudeSparkline
import com.adsamcik.tracker.dashboard.ui.compose.visualization.SessionPathPreview
import com.adsamcik.tracker.dashboard.ui.compose.visualization.SpeedSparkline
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.extension.formatSpeed
import com.adsamcik.tracker.tracker.R as TrackerR

/**
 * Main tracking layout displayed when the user is actively tracking.
 *
 * Structure:
 * 1. Map Hero — Canvas path preview with metric overlay (speed, distance, duration)
 * 2. Stats Grid — 3-column secondary metrics
 * 3. Challenge Progress — Active challenge bars (if any)
 * 4. Milestone Celebration — Auto-dismissing overlay on milestones
 */
@Composable
internal fun TrackingContent(
	state: DashboardUiState,
	bottomClearance: Dp = DashboardLayoutDefaults.PillClearance,
	onMapClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Box(modifier = modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.padding(horizontal = 16.dp),
			contentPadding = PaddingValues(bottom = bottomClearance),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			item(key = "map_hero") {
				MapHeroCard(
					pathPoints = state.pathPoints,
					state = state,
					onMapClick = onMapClick,
				)
			}

			item(key = "stats_grid") {
				if (state.sessionData != null) {
					TrackingStatsGrid(
						sessionData = state.sessionData,
						collectionData = state.collectionData,
					)
				}
			}

			// Live Charts — speed and altitude sparklines from path data
			val points = state.pathPoints
			if (!points.isNullOrEmpty()) {
				val speedHistory = points.mapNotNull { it.speed }
				val altitudeHistory = points.mapNotNull { it.altitude?.toFloat() }

				if (speedHistory.size >= 2) {
					item(key = "live_speed") {
						LiveChartCard(
							title = stringResource(R.string.dashboard_live_charts_speed),
						) {
							SpeedSparkline(
								speedHistory = speedHistory,
								currentSpeed = speedHistory.lastOrNull(),
								maxSpeed = speedHistory.maxOrNull(),
								modifier = Modifier
									.fillMaxWidth()
									.height(140.dp),
							)
						}
					}
				}

				if (altitudeHistory.size >= 2) {
					item(key = "live_altitude") {
						LiveChartCard(
							title = stringResource(R.string.dashboard_live_charts_altitude),
						) {
							AltitudeSparkline(
								altitudeHistory = altitudeHistory,
								currentAltitude = altitudeHistory.lastOrNull(),
								modifier = Modifier
									.fillMaxWidth()
									.height(140.dp),
							)
						}
					}
				}
			}

			item(key = "challenges") {
				LiveChallengeProgress(
					challenges = state.activeChallenges,
				)
			}

			item(key = "sensor_details") {
				SensorDetailsCard(
					collectionData = state.collectionData,
					isTracking = state.isTracking,
				)
			}
		}

		// Milestone celebration overlay at the top
		MilestoneCelebrationOverlay(
			sessionData = state.sessionData,
			isTracking = state.isTracking,
			modifier = Modifier.align(Alignment.TopCenter),
		)
	}
}

/**
 * Hero card with Canvas path preview and primary metric overlay.
 */
@Composable
private fun MapHeroCard(
	pathPoints: List<Location>?,
	state: DashboardUiState,
	onMapClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val resources = context.resources
	val settings = TrackerSettingsQuick.snapshot(context)

	val sessionData = state.sessionData
	val collectionData = state.collectionData
	val currentSpeed = collectionData?.location?.speed

	val sessionEnd = when {
		sessionData != null && sessionData.end > sessionData.start -> sessionData.end
		else -> Time.nowMillis
	}
	val durationMillis = if (sessionData != null) {
		(sessionEnd - sessionData.start).coerceAtLeast(0L)
	} else {
		0L
	}
	val durationText = durationMillis.formatAsDuration(context)

	val isMoving = (currentSpeed ?: 0f) > 0.5f

	Card(
		modifier = modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.primaryContainer,
		),
		onClick = onMapClick,
	) {
		Box {
			// Path preview background
			if (!pathPoints.isNullOrEmpty() && pathPoints.size >= 2) {
				SessionPathPreview(
					points = pathPoints,
					modifier = Modifier
						.fillMaxWidth()
						.height(200.dp),
				)
			} else {
				// Placeholder while path is being recorded.
				// Distinguish between "no GPS fix yet" and "GPS acquired but < 2 path points".
				val hasGpsFix = collectionData?.location != null
				val placeholderText = if (hasGpsFix) {
					stringResource(R.string.dashboard_tracking_recording_route)
				} else {
					stringResource(R.string.dashboard_tracking_awaiting_gps)
				}
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(200.dp)
						.background(MaterialTheme.colorScheme.primaryContainer),
					contentAlignment = Alignment.Center,
				) {
					Text(
						text = placeholderText,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
					)
				}
			}

			// Metric overlay at the bottom
			Column(
				modifier = Modifier
					.align(Alignment.BottomStart)
					.fillMaxWidth()
					.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f))
					.padding(16.dp),
			) {
				// Recording indicator
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					RecordingDot()
					Text(
						text = stringResource(TrackerR.string.notification_tracking_active),
						style = MaterialTheme.typography.labelLarge,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onPrimaryContainer,
					)
				}

				Spacer(Modifier.height(8.dp))

				// Primary metric: Speed (if moving) or Duration (if stationary)
				if (isMoving && currentSpeed != null) {
					val speedText = resources.formatSpeed(context, currentSpeed.toDouble(), 1)
					PulseOnChange(key = speedText) {
						PrimaryMetricRow(
							label = stringResource(TrackerR.string.speed_title),
							value = speedText,
						)
					}
				} else {
					PrimaryMetricRow(
						label = stringResource(TrackerR.string.duration_title),
						value = durationText,
					)
				}

				// Secondary inline metrics
				if (sessionData != null) {
					Spacer(Modifier.height(4.dp))
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(16.dp),
					) {
						if (isMoving) {
							// Show duration as secondary when speed is primary
							Text(
								text = durationText,
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
							)
						}
						val distText = resources.formatDistance(
							sessionData.distanceInM,
							digits = if (sessionData.distanceInM >= 1000f) 1 else 0,
							unit = settings.lengthSystem,
						)
						Text(
							text = distText,
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
						)
					}
				}
			}
		}
	}
}

/**
 * Primary metric display row with animated value changes.
 */
@Composable
private fun PrimaryMetricRow(
	label: String,
	value: String,
	modifier: Modifier = Modifier,
) {
	Column(modifier = modifier) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
		)
		AnimatedContent(
			targetState = value,
			label = "primary_metric",
			transitionSpec = {
				(slideInVertically { height -> height / 4 } + fadeIn(animationSpec = tween(300)))
					.togetherWith(
						slideOutVertically { height -> -height / 4 } + fadeOut(animationSpec = tween(150)),
					)
			},
		) { targetValue ->
			Text(
				text = targetValue,
				style = MaterialTheme.typography.displaySmall,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onPrimaryContainer,
			)
		}
	}
}

/**
 * Pulsing recording dot indicator.
 */
@Composable
private fun RecordingDot(modifier: Modifier = Modifier) {
	val infiniteTransition = rememberInfiniteTransition(label = "recording_dot")
	val alpha by infiniteTransition.animateFloat(
		initialValue = 1f,
		targetValue = 0.2f,
		animationSpec = infiniteRepeatable(
			animation = tween(1000),
			repeatMode = RepeatMode.Reverse,
		),
		label = "recording_alpha",
	)

	Box(
		modifier = modifier
			.size(12.dp)
			.alpha(alpha)
			.clip(MaterialTheme.shapes.extraLarge)
			.background(MaterialTheme.colorScheme.error),
	)
}

/**
 * Wrapper card for live chart sections (speed, altitude) during tracking.
 */
@Composable
private fun LiveChartCard(
	title: String,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	Card(
		modifier = modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainer,
		),
	) {
		Column(modifier = Modifier.padding(16.dp)) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Spacer(Modifier.height(8.dp))
			content()
		}
	}
}
