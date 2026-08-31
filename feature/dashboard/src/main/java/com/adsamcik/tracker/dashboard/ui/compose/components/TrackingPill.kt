package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.motion.MotionTokens
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import com.adsamcik.tracker.shared.base.extension.formatAsDuration

/**
 * Compact sticky pill that appears above the navigation bar when the
 * TodayProgressCard (with its TrackingActionRing) scrolls out of view.
 *
 * Provides always-accessible play/stop functionality with minimal footprint.
 *
 * States:
 * - **Idle + permission:** Primary-colored pill with ▶ "Start"
 * - **Tracking:** Error-colored pill with ■ "Recording · 3:45"
 * - **No permission:** Surface-variant pill with 📍 "Enable location"
 */
@Composable
internal fun TrackingPill(
	visible: Boolean,
	isTracking: Boolean,
	hasPermission: Boolean,
	sessionData: TrackerSessionSnapshot?,
	onToggleTracking: () -> Unit,
	onRequestPermission: () -> Unit,
	modifier: Modifier = Modifier,
) {
	AnimatedVisibility(
		visible = visible,
		enter = slideInVertically(
			initialOffsetY = { it },
			animationSpec = tween(MotionTokens.STANDARD_MS),
		) + fadeIn(animationSpec = tween(MotionTokens.QUICK_MS)),
		exit = slideOutVertically(
			targetOffsetY = { it },
			animationSpec = tween(MotionTokens.QUICK_MS),
		) + fadeOut(animationSpec = tween(MotionTokens.QUICK_MS)),
		modifier = modifier,
	) {
		PillContent(
			isTracking = isTracking,
			hasPermission = hasPermission,
			sessionData = sessionData,
			onClick = {
				if (isTracking || hasPermission) onToggleTracking() else onRequestPermission()
			},
		)
	}
}

@Composable
private fun PillContent(
	isTracking: Boolean,
	hasPermission: Boolean,
	sessionData: TrackerSessionSnapshot?,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current

	val containerColor = when {
		isTracking -> MaterialTheme.colorScheme.errorContainer
		!hasPermission -> MaterialTheme.colorScheme.surfaceContainerHigh
		else -> MaterialTheme.colorScheme.primaryContainer
	}
	val contentColor = when {
		isTracking -> MaterialTheme.colorScheme.onErrorContainer
		!hasPermission -> MaterialTheme.colorScheme.onSurface
		else -> MaterialTheme.colorScheme.onPrimaryContainer
	}

	val icon = when {
		isTracking -> Icons.Default.Stop
		!hasPermission -> Icons.Default.LocationOff
		else -> Icons.Default.PlayArrow
	}

	val label = when {
		isTracking -> stringResource(R.string.dashboard_pill_stop)
		!hasPermission -> stringResource(R.string.dashboard_pill_enable_location)
		else -> stringResource(R.string.dashboard_pill_start)
	}

	Surface(
		onClick = onClick,
		shape = MaterialTheme.shapes.extraLarge,
		color = containerColor,
		shadowElevation = 4.dp,
		tonalElevation = 2.dp,
		modifier = modifier
			.semantics { contentDescription = label }
			.testTag("tracking_pill"),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = contentColor,
				modifier = Modifier.size(20.dp),
			)
			Text(
				text = label,
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.SemiBold,
				color = contentColor,
			)
		}
	}
}
