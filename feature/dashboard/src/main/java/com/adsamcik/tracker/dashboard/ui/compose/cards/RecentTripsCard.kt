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
import com.adsamcik.tracker.shared.base.data.SessionActivityIds
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineCardDefaults
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.model.Trip

/**
 * Card displaying the most recent trips with activity icons,
 * distance, duration, and relative time.
 */
@Composable
internal fun RecentTripsCard(
	trips: List<Trip>,
	onTripClick: ((Long) -> Unit)?,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val settings = TrackerSettingsQuick.snapshot(context)

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

			if (trips.isEmpty()) {
				Text(
					text = stringResource(R.string.dashboard_recent_trips_empty),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			} else {
				trips.forEach { trip ->
					key(trip.id) {
						RecentTripRow(
							trip = trip,
							distanceText = context.resources.formatDistance(
								trip.distanceM,
								1,
								settings.lengthSystem,
							),
							durationText = trip.durationMs.formatAsDuration(context),
							timeText = DateUtils.getRelativeTimeSpanString(
								trip.startTimeMs,
								System.currentTimeMillis(),
								DateUtils.MINUTE_IN_MILLIS,
								DateUtils.FORMAT_ABBREV_RELATIVE,
							).toString(),
							onClick = if (onTripClick != null) {
								{ onTripClick(trip.id) }
							} else {
								null
							},
						)
					}
				}
			}
		}
	}
}

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
