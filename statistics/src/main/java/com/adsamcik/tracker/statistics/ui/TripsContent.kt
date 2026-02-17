package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.utils.style.compose.EmptyStateCard
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.viewmodel.activityIcon
import com.adsamcik.tracker.statistics.viewmodel.activityLabel
import com.adsamcik.tracker.statistics.viewmodel.formatDistanceLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Displays a paged list of trips.
 */
@Composable
internal fun TripsContent(
	tripsItems: LazyPagingItems<Trip>,
	onTripClick: (Long) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (tripsItems.itemCount == 0) {
		EmptyStateCard(
			icon = Icons.Filled.Route,
			title = stringResource(R.string.history_trips_empty_title),
			subtitle = stringResource(R.string.history_trips_empty_desc),
			modifier = modifier.padding(16.dp),
		)
		return
	}

	LazyColumn(
		modifier = modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 16.dp,
			bottom = 120.dp,
		),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		items(
			count = tripsItems.itemCount,
			key = { tripsItems[it]?.id ?: it },
		) { index ->
			val trip = tripsItems[index] ?: return@items
			TripCard(
				trip = trip,
				onClick = { onTripClick(trip.id) },
			)
		}
	}
}

/**
 * Card displaying a single trip with activity icon, label, distance, and duration.
 */
@Composable
internal fun TripCard(
	trip: Trip,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Card(
		onClick = onClick,
		modifier = modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier.padding(16.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Icon(
				imageVector = activityIcon(trip.primaryActivity),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
			)
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = activityLabel(trip.primaryActivity),
					style = MaterialTheme.typography.titleSmall,
				)
				Text(
					text = formatTripTimeRange(trip.startTimeMs, trip.endTimeMs),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Column(horizontalAlignment = Alignment.End) {
				Text(
					text = formatDistanceLabel(trip.distanceM),
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.primary,
				)
				Text(
					text = formatDuration(trip.durationMs),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

/**
 * Format a time range as "HH:mm - HH:mm".
 */
internal fun formatTripTimeRange(startMs: Long, endMs: Long): String {
	val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
	val zone = ZoneId.systemDefault()
	val start = Instant.ofEpochMilli(startMs).atZone(zone).toLocalTime().format(formatter)
	val end = Instant.ofEpochMilli(endMs).atZone(zone).toLocalTime().format(formatter)
	return "$start - $end"
}

/**
 * Format duration in milliseconds as a human-readable string.
 */
internal fun formatDuration(millis: Long): String {
	val totalSeconds = millis / 1000
	val hours = totalSeconds / 3600
	val minutes = (totalSeconds % 3600) / 60
	return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
