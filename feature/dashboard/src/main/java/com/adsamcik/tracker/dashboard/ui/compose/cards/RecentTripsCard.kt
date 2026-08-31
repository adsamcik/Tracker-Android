@file:Suppress("FunctionNaming")

package com.adsamcik.tracker.dashboard.ui.compose.cards

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryEntry
import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryState
import com.adsamcik.tracker.shared.base.data.SessionActivityIds
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineCardDefaults
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.extension.formatDistance
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryListState

/**
 * Card displaying the coordinated recent tracking page. Physical rows retain their established
 * actions while opaque Steps-only rows deliberately expose no detail identity or numeric value.
 */
@Composable
internal fun RecentTripsCard(
	recentHistory: DashboardRecentHistoryState,
	onTripClick: ((Long) -> Unit)?,
	modifier: Modifier = Modifier,
) {
	Card(
		modifier = modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = RidgelineCardDefaults.containerColor,
		),
		shape = RidgelineCardDefaults.shape,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = stringResource(R.string.dashboard_recent_trips_title),
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			RecentHistoryRows(recentHistory, onTripClick)
		}
	}
}

@Composable
private fun RecentHistoryRows(
	recentHistory: DashboardRecentHistoryState,
	onTripClick: ((Long) -> Unit)?,
) {
	val context = LocalContext.current
	val settings = TrackerSettingsQuick.snapshot(context)
	when (recentHistory) {
		DashboardRecentHistoryState.Loading -> RecentHistoryMessage(
			text = stringResource(R.string.dashboard_recent_history_loading),
		)
		DashboardRecentHistoryState.Unavailable -> RecentHistoryMessage(
			text = stringResource(R.string.dashboard_recent_history_unavailable),
		)
		is DashboardRecentHistoryState.Content -> if (recentHistory.entries.isEmpty()) {
			RecentHistoryMessage(text = stringResource(R.string.dashboard_recent_trips_empty))
		} else {
			recentHistory.entries.forEach { entry ->
				when (entry) {
					is DashboardRecentHistoryEntry.Physical -> key(entry.trip.id) {
						val trip = entry.trip
						RecentTripRow(
							trip = trip,
							distanceText = context.resources.formatDistance(
								trip.distanceM,
								1,
								settings.lengthSystem,
							),
							durationText = trip.durationMs.formatAsDuration(context),
							timeText = relativeTime(trip.startTimeMs),
							onClick = onTripClick?.let { click -> { click(trip.id) } },
						)
					}
					is DashboardRecentHistoryEntry.StepsOnly -> key(entry.history.key) {
						RecentStepsOnlyRow(entry.history)
					}
				}
			}
		}
	}
}

@Composable
private fun RecentHistoryMessage(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun RecentStepsOnlyRow(
	history: StepsOnlyHistoryEntry,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.heightIn(min = 48.dp)
			.padding(vertical = 8.dp, horizontal = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = stringResource(R.string.dashboard_recent_steps_title),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = stringResource(history.state.labelResource),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Text(
				text = (history.endTime - history.startTime)
					.formatAsDuration(LocalContext.current),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurface,
			)
		}
		Text(
			text = relativeTime(history.startTime.raw),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurface,
		)
	}
}

private val StepsOnlyHistoryListState.labelResource: Int
	get() = when (this) {
		StepsOnlyHistoryListState.AVAILABLE -> R.string.dashboard_recent_steps_available
		StepsOnlyHistoryListState.MATERIALIZING -> R.string.dashboard_recent_steps_materializing
		StepsOnlyHistoryListState.PARTIAL -> R.string.dashboard_recent_steps_partial
	}

private fun relativeTime(startTimeMs: Long): String = DateUtils.getRelativeTimeSpanString(
	startTimeMs,
	System.currentTimeMillis(),
	DateUtils.MINUTE_IN_MILLIS,
	DateUtils.FORMAT_ABBREV_RELATIVE,
).toString()

@Composable
private fun RecentTripRow(
	trip: Trip,
	distanceText: String,
	durationText: String,
	timeText: String,
	onClick: (() -> Unit)?,
	modifier: Modifier = Modifier,
) {
	val icon = getTripIcon(trip.primaryActivity)

	val rowModifier = if (onClick != null) {
		modifier
			.fillMaxWidth()
			.heightIn(min = 48.dp)
			.clip(MaterialTheme.shapes.medium)
			.clickable(onClick = onClick)
			.padding(vertical = 8.dp, horizontal = 4.dp)
	} else {
		modifier
			.fillMaxWidth()
			.heightIn(min = 48.dp)
			.padding(vertical = 8.dp, horizontal = 4.dp)
	}

	Row(
		modifier = rowModifier,
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Icon(
			imageVector = icon,
			contentDescription = stringResource(R.string.dashboard_cd_activity_icon),
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.size(24.dp),
		)
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = distanceText,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = durationText,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurface,
			)
		}
		Text(
			text = timeText,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurface,
		)
		if (onClick != null) {
			Box(
				modifier = Modifier.size(48.dp),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					imageVector = Icons.AutoMirrored.Filled.ArrowForward,
					contentDescription = stringResource(R.string.dashboard_cd_forward_arrow),
					tint = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.size(20.dp),
				)
			}
		}
	}
}

internal fun getTripIcon(primaryActivity: Int?): ImageVector {
	return when (primaryActivity) {
		in SessionActivityIds.WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk
		in SessionActivityIds.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
		in SessionActivityIds.CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
		in SessionActivityIds.DRIVING -> Icons.Filled.DirectionsCar
		in SessionActivityIds.WATER -> Icons.Filled.Sailing
		in SessionActivityIds.AIR -> Icons.Filled.Flight
		in SessionActivityIds.SLOPE_SPORTS -> Icons.Filled.DownhillSkiing
		else -> Icons.Filled.Route
	}
}
