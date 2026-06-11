package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.adsamcik.tracker.shared.model.Trip
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
 * Displays a paged list of trips with swipe-to-delete support.
 */
@Composable
internal fun TripsContent(
	tripsItems: LazyPagingItems<Trip>,
	onTripClick: (Long) -> Unit,
	onDeleteTrip: (Long) -> Unit,
	pendingDeletes: Set<Long>,
	onViewTripOnMap: (Trip) -> Unit = {},
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
			if (trip.id in pendingDeletes) return@items
			SwipeToDeleteTripCard(
				trip = trip,
				onClick = { onTripClick(trip.id) },
				onViewOnMap = { onViewTripOnMap(trip) },
				onDelete = { onDeleteTrip(trip.id) },
			)
		}
	}
}

/**
 * Wraps [TripCard] in a [SwipeToDismissBox] for swipe-to-delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeToDeleteTripCard(
	trip: Trip,
	onClick: () -> Unit,
	onDelete: () -> Unit,
	onViewOnMap: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	val dismissState = rememberSwipeToDismissBoxState(
		confirmValueChange = { dismissValue ->
			if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
				onDelete()
				true
			} else {
				false
			}
		},
	)

	SwipeToDismissBox(
		state = dismissState,
		backgroundContent = { SwipeDeleteBackground() },
		modifier = modifier,
		content = {
			TripCard(
				trip = trip,
				onClick = onClick,
				onViewOnMap = onViewOnMap,
			)
		},
	)
}

@Composable
private fun SwipeDeleteBackground() {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 16.dp)
			.background(
				MaterialTheme.colorScheme.errorContainer,
				shape = MaterialTheme.shapes.medium,
			),
		contentAlignment = Alignment.CenterEnd,
	) {
		Icon(
			imageVector = Icons.Default.Delete,
			contentDescription = stringResource(
				com.adsamcik.tracker.shared.base.R.string.generic_delete,
			),
			tint = MaterialTheme.colorScheme.onErrorContainer,
			modifier = Modifier
				.padding(end = 16.dp)
				.size(24.dp),
		)
	}
}

/**
 * Card displaying a single trip with activity icon, label, distance, and duration.
 * Shows a warning indicator for trips with physically implausible GPS distances.
 */
@Composable
internal fun TripCard(
	trip: Trip,
	onClick: () -> Unit,
	onViewOnMap: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	val plausible = !trip.hasDistanceAnomaly
	val containerColor = if (plausible) {
		CardDefaults.cardColors()
	} else {
		CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
		)
	}

	Card(
		onClick = onClick,
		modifier = modifier.fillMaxWidth(),
		colors = containerColor,
	) {
		Row(
			modifier = Modifier.padding(16.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Icon(
				imageVector = activityIcon(trip.primaryActivity),
				contentDescription = null,
				tint = if (plausible) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.error
				},
			)
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = activityLabel(trip.primaryActivity),
					style = MaterialTheme.typography.titleSmall,
				)
				Text(
					text = formatTripTimeRange(trip.startTimeMs, trip.endTimeMs),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}
			Column(horizontalAlignment = Alignment.End) {
				if (!plausible) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Icon(
							imageVector = Icons.Filled.Warning,
							contentDescription = stringResource(R.string.trip_gps_anomaly),
							tint = MaterialTheme.colorScheme.error,
							modifier = Modifier.size(14.dp),
						)
						Spacer(modifier = Modifier.width(4.dp))
						Text(
							text = formatDistanceLabel(trip.distanceM),
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.error,
						)
					}
				} else {
					Text(
						text = formatDistanceLabel(trip.distanceM),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.primary,
					)
				}
				Text(
					text = formatDuration(trip.durationMs),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}
			if (onViewOnMap != null) {
				IconButton(onClick = onViewOnMap) {
					Icon(
						imageVector = Icons.Filled.Map,
						contentDescription = stringResource(R.string.history_view_trip_on_map),
						tint = MaterialTheme.colorScheme.primary,
					)
				}
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
