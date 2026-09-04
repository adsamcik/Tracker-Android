package com.adsamcik.tracker.statistics.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.EmptyStateCard
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.viewmodel.TimelineEntry
import com.adsamcik.tracker.statistics.viewmodel.TimelineState

/**
 * Displays the timeline tab content with day summaries and trip entries.
 */
@Composable
internal fun TimelineContent(
	state: TimelineState,
	onTripClick: (Long) -> Unit,
	modifier: Modifier = Modifier,
) {
	when (state) {
		is TimelineState.Loading -> {
			Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				CircularProgressIndicator()
			}
		}
		is TimelineState.Empty -> {
			EmptyStateCard(
				icon = Icons.Filled.Route,
				title = stringResource(R.string.history_timeline_empty_title),
				subtitle = stringResource(R.string.history_timeline_empty_desc),
				modifier = modifier.padding(16.dp),
			)
		}
		is TimelineState.Content -> {
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
					items = state.entries,
					key = { it.id },
				) { entry ->
					TimelineEntryCard(
						entry = entry,
						onClick = {
							if (entry is TimelineEntry.TripEntry) {
								onTripClick(entry.tripId)
							}
						},
					)
				}
			}
		}
	}
}

@Composable
private fun TimelineEntryCard(
	entry: TimelineEntry,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	when (entry) {
		is TimelineEntry.TripEntry -> TripTimelineCard(entry, onClick, modifier)
		is TimelineEntry.DiscoveryEntry -> DiscoveryTimelineCard(entry, modifier)
		is TimelineEntry.DaySummaryEntry -> DaySummaryTimelineCard(entry, modifier)
	}
}

@Composable
private fun TripTimelineCard(
	entry: TimelineEntry.TripEntry,
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
				imageVector = entry.modeIcon,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
			)
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = entry.title,
					style = MaterialTheme.typography.titleSmall,
				)
				Text(
					text = entry.subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Text(
				text = entry.timeLabel,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun DiscoveryTimelineCard(
	entry: TimelineEntry.DiscoveryEntry,
	modifier: Modifier = Modifier,
) {
	Card(
		modifier = modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.tertiaryContainer,
		),
	) {
		Row(
			modifier = Modifier.padding(16.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Icon(
				imageVector = Icons.Default.Explore,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onTertiaryContainer,
			)
			Column {
				Text(
					text = entry.title,
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onTertiaryContainer,
				)
				Text(
					text = entry.subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onTertiaryContainer,
				)
			}
		}
	}
}

@Composable
private fun DaySummaryTimelineCard(
	entry: TimelineEntry.DaySummaryEntry,
	modifier: Modifier = Modifier,
) {
	OutlinedCard(modifier = modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(16.dp)) {
			Text(
				text = entry.dateLabel,
				style = MaterialTheme.typography.titleSmall,
			)
			Spacer(Modifier.height(4.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
				Text(
					text = entry.distanceLabel,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Text(
					text = androidx.compose.ui.res.pluralStringResource(
						R.plurals.history_trip_count,
						entry.tripCount,
						entry.tripCount,
					),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}
