package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.extension.formatSpeed
import com.adsamcik.tracker.shared.utils.style.compose.ActivityColors
import com.adsamcik.tracker.tracker.R as TrackerR

/**
 * 3-column responsive stats grid showing secondary tracking metrics.
 *
 * Row 1: Distance, Avg Speed, Altitude
 * Row 2: Activity type, Steps, Accuracy
 * Row 3: Technical badges (WiFi count, Cell count)
 */
@Composable
internal fun TrackingStatsGrid(
	sessionData: TrackerSession,
	collectionData: CollectionData?,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val resources = context.resources
	val settings = TrackerSettingsQuick.snapshot(context)

	val sessionEnd = when {
		sessionData.end > sessionData.start -> sessionData.end
		else -> Time.nowMillis
	}
	val durationMillis = (sessionEnd - sessionData.start).coerceAtLeast(0L)

	val distanceText = resources.formatDistance(
		sessionData.distanceInM,
		digits = if (sessionData.distanceInM >= 1000f) 1 else 0,
		unit = settings.lengthSystem,
	)

	val avgSpeed = if (durationMillis > 0) {
		sessionData.distanceInM.toDouble() / (durationMillis / 1000.0)
	} else {
		0.0
	}
	val avgSpeedText = resources.formatSpeed(context, avgSpeed, 1)

	val altitude = collectionData?.location?.altitude
	val accuracy = collectionData?.location?.horizontalAccuracy
	val currentActivity = collectionData?.activity
	val wifiCount = collectionData?.wifi?.inRange?.size
	val cellCount = collectionData?.cell?.totalCount

	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		// Row 1: Distance, Avg Speed, Altitude
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			CompactAnimatedStatItem(
				label = stringResource(TrackerR.string.tracker_distance_title),
				value = distanceText,
				modifier = Modifier.weight(1f),
			)
			CompactAnimatedStatItem(
				label = stringResource(TrackerR.string.tracker_average_label),
				value = avgSpeedText,
				modifier = Modifier.weight(1f),
			)
			if (altitude != null) {
				CompactAnimatedStatItem(
					label = stringResource(TrackerR.string.altitude_title),
					value = resources.formatDistance(altitude.toFloat(), 1, settings.lengthSystem),
					modifier = Modifier.weight(1f),
				)
			} else {
				Spacer(Modifier.weight(1f))
			}
		}

		// Row 2: Activity, Steps, Accuracy
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			val activityText = if (currentActivity != null) {
				currentActivity.getGroupedActivityName(context)
			} else {
				"–"
			}
			val activityColor = if (currentActivity != null) {
				when (currentActivity.groupedActivity) {
					GroupedActivity.ON_FOOT -> ActivityColors.Adaptive.Walk
					GroupedActivity.IN_VEHICLE -> ActivityColors.Adaptive.Ride
					else -> MaterialTheme.colorScheme.onSurface
				}
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			}
			CompactAnimatedStatItem(
				label = stringResource(TrackerR.string.tracker_activity_title),
				value = activityText,
				valueColor = activityColor,
				modifier = Modifier.weight(1f),
			)

			CompactAnimatedStatItem(
				label = stringResource(TrackerR.string.tracker_steps_title),
				value = if (sessionData.steps > 0) sessionData.steps.formatReadable() else "–",
				modifier = Modifier.weight(1f),
			)

			CompactAnimatedStatItem(
				label = stringResource(TrackerR.string.tracker_accuracy_label),
				value = if (accuracy != null) {
					"±${resources.formatDistance(accuracy, 0, settings.lengthSystem)}"
				} else {
					"–"
				},
				modifier = Modifier.weight(1f),
			)
		}

		// Row 3: Technical badges
		if ((wifiCount != null && wifiCount > 0) || (cellCount != null && cellCount > 0)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (wifiCount != null && wifiCount > 0) {
					TechBadge(icon = Icons.Filled.Wifi, text = "$wifiCount")
				}
				if (cellCount != null && cellCount > 0) {
					TechBadge(icon = Icons.Filled.SignalCellularAlt, text = "$cellCount")
				}
			}
		}
	}
}

/**
 * Compact stat item with vertical slide animation on value changes.
 */
@Composable
private fun CompactAnimatedStatItem(
	label: String,
	value: String,
	modifier: Modifier = Modifier,
	valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
	Column(modifier) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		AnimatedContent(
			targetState = value,
			label = "stat_$label",
			transitionSpec = {
				(slideInVertically { height -> height / 4 } + fadeIn(animationSpec = tween(300)))
					.togetherWith(
						slideOutVertically { height -> -height / 4 } + fadeOut(animationSpec = tween(150)),
					)
			},
		) { targetValue ->
			Text(
				text = targetValue,
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.SemiBold,
				color = valueColor,
			)
		}
	}
}

/**
 * Pulse scale effect on value change — wraps content and adds a subtle scale pulse.
 */
@Composable
internal fun PulseOnChange(
	key: Any,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	val scale = remember { Animatable(1f) }

	LaunchedEffect(key) {
		scale.animateTo(
			targetValue = 1.08f,
			animationSpec = tween(100, easing = FastOutSlowInEasing),
		)
		scale.animateTo(
			targetValue = 1f,
			animationSpec = spring(
				dampingRatio = Spring.DampingRatioMediumBouncy,
				stiffness = Spring.StiffnessMedium,
			),
		)
	}

	Box(
		modifier = modifier.graphicsLayer {
			scaleX = scale.value
			scaleY = scale.value
		},
	) {
		content()
	}
}

/**
 * Compact technical badge showing icon + count.
 */
@Composable
private fun TechBadge(
	icon: ImageVector,
	text: String,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.background(
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				shape = MaterialTheme.shapes.small,
			)
			.padding(horizontal = 8.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Icon(
			imageVector = icon,
			contentDescription = null,
			modifier = Modifier.size(14.dp),
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = text,
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
