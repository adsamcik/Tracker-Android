package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.motion.MotionTokens
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion

/**
 * Tracking action ring that merges goal progress visualization with the
 * primary play/stop tracking action.
 *
 * When gamification is enabled, displays dual concentric progress rings
 * (daily + weekly steps) with a play/stop icon overlay in the center.
 * When gamification is disabled, displays a standalone play/stop button
 * in the same position.
 *
 * States:
 * - **Idle + permission:** Play icon in center, rings show goal progress
 * - **Tracking:** Stop icon, rings pulse, colors shift to error
 * - **No permission:** LocationOff icon, muted ring colors
 */
@Composable
internal fun TrackingActionRing(
	isTracking: Boolean,
	hasPermission: Boolean,
	goalProgress: GoalProgressState,
	onToggleTracking: () -> Unit,
	onRequestPermission: () -> Unit,
	compact: Boolean = false,
	modifier: Modifier = Modifier,
) {
	val showRings = goalProgress.gamificationEnabled &&
		goalProgress.dailyGoalSteps > 0 &&
		goalProgress.dailySteps is QualifiedStepCount.Ready

	val fabContentDescription = when {
		!hasPermission -> stringResource(R.string.dashboard_cd_start_tracking_permission)
		isTracking -> stringResource(R.string.dashboard_cd_stop_tracking)
		else -> stringResource(R.string.dashboard_cd_start_tracking)
	}
	val fabClickLabel = when {
		!hasPermission -> stringResource(R.string.dashboard_action_enable_location)
		isTracking -> stringResource(R.string.dashboard_cd_stop_tracking)
		else -> stringResource(R.string.dashboard_cd_start_tracking)
	}

	Column(
		modifier = modifier.semantics(mergeDescendants = true) {
			contentDescription = fabContentDescription
			onClick(label = fabClickLabel, action = null)
			role = Role.Button
		},
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		if (showRings) {
			RingsWithAction(
				isTracking = isTracking,
				hasPermission = hasPermission,
				goalProgress = goalProgress,
				compact = compact,
				onClick = { if (!hasPermission) onRequestPermission() else onToggleTracking() },
			)
		} else {
			StandaloneActionButton(
				isTracking = isTracking,
				hasPermission = hasPermission,
				compact = compact,
				onClick = { if (!hasPermission) onRequestPermission() else onToggleTracking() },
			)
		}

		// Status badge
		val statusText = when {
			!hasPermission -> stringResource(R.string.dashboard_ring_enable_location)
			isTracking -> stringResource(R.string.dashboard_cd_stop_tracking)
			else -> stringResource(R.string.dashboard_ring_start_tracking)
		}
		val statusColor = when {
			!hasPermission -> MaterialTheme.colorScheme.onSurfaceVariant
			isTracking -> MaterialTheme.colorScheme.error
			else -> MaterialTheme.colorScheme.primary
		}
		Text(
			text = statusText,
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.Medium,
			color = statusColor,
			modifier = Modifier.padding(top = 4.dp),
		)
	}
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "FunctionNaming")
@Composable
private fun RingsWithAction(
	isTracking: Boolean,
	hasPermission: Boolean,
	goalProgress: GoalProgressState,
	compact: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val dailyProgress = requireNotNull(goalProgress.dailyProgress)
	val weeklyProgress = goalProgress.weeklyProgress
	val reducedMotion = LocalReducedMotion.current
	val sizing = if (compact) TrackingActionRingSizing.Compact else TrackingActionRingSizing.Default

	val pulseScale = if (reducedMotion) {
		1f
	} else {
		val infiniteTransition = rememberInfiniteTransition(label = "ring_action")
		val animatedPulseScale by infiniteTransition.animateFloat(
			initialValue = 1f,
			targetValue = if (isTracking) 1.06f else 1f,
			animationSpec = infiniteRepeatable(
				animation = tween(MotionTokens.AMBIENT_MS),
				repeatMode = RepeatMode.Reverse,
			),
			label = "ring_pulse",
		)
		animatedPulseScale
	}

	val animatedDailyProgress by animateFloatAsState(
		targetValue = dailyProgress.coerceIn(0f, 1f),
		animationSpec = MotionTokens.tweenExpressive(),
		label = "daily_progress",
	)
	val animatedWeeklyProgress by animateFloatAsState(
		targetValue = weeklyProgress?.coerceIn(0f, 1f) ?: 0f,
		animationSpec = MotionTokens.tweenExpressive(),
		label = "weekly_progress",
	)

	// Color scheme shifts when tracking
	val primaryRingColor = when {
		!hasPermission -> MaterialTheme.colorScheme.surfaceVariant
		isTracking -> MaterialTheme.colorScheme.error
		else -> MaterialTheme.colorScheme.primary
	}
	val secondaryRingColor = when {
		!hasPermission -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
		isTracking -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
		else -> MaterialTheme.colorScheme.tertiary
	}
	val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

	Surface(
		onClick = onClick,
		shape = MaterialTheme.shapes.extraLarge,
		color = Color.Transparent,
		modifier = modifier
			.graphicsLayer {
				scaleX = pulseScale
				scaleY = pulseScale
			}
			.testTag("tracking_action_ring"),
	) {
		Box(
			contentAlignment = Alignment.Center,
			modifier = Modifier.size(sizing.outerSize),
		) {
			if (weeklyProgress != null) {
				CircularProgressIndicator(
					progress = { 1f },
					modifier = Modifier.fillMaxSize(),
					color = trackColor,
					strokeWidth = sizing.outerStroke,
					trackColor = Color.Transparent,
				)
				CircularProgressIndicator(
					progress = { animatedWeeklyProgress },
					modifier = Modifier.fillMaxSize(),
					color = secondaryRingColor,
					strokeWidth = sizing.outerStroke,
					trackColor = Color.Transparent,
					strokeCap = StrokeCap.Round,
				)
			}
			// Inner ring track (daily)
			CircularProgressIndicator(
				progress = { 1f },
				modifier = Modifier.size(sizing.innerSize),
				color = trackColor,
				strokeWidth = sizing.innerStroke,
				trackColor = Color.Transparent,
			)
			// Inner ring progress (daily)
			CircularProgressIndicator(
				progress = { animatedDailyProgress },
				modifier = Modifier.size(sizing.innerSize),
				color = primaryRingColor,
				strokeWidth = sizing.innerStroke,
				trackColor = Color.Transparent,
				strokeCap = StrokeCap.Round,
			)

			// Center: action icon
			ActionIcon(
				isTracking = isTracking,
				hasPermission = hasPermission,
				iconSize = sizing.iconSize,
			)
		}
	}
}

/**
 * Standalone circular play/stop button shown when gamification is disabled.
 */
@Composable
private fun StandaloneActionButton(
	isTracking: Boolean,
	hasPermission: Boolean,
	compact: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val sizing = if (compact) TrackingActionRingSizing.Compact else TrackingActionRingSizing.Default
	val containerColor = when {
		!hasPermission -> MaterialTheme.colorScheme.surfaceVariant
		isTracking -> MaterialTheme.colorScheme.errorContainer
		else -> MaterialTheme.colorScheme.primaryContainer
	}

	Surface(
		onClick = onClick,
		shape = MaterialTheme.shapes.extraLarge,
		color = containerColor,
		modifier = modifier
			.size(sizing.outerSize)
			.testTag("tracking_action_ring"),
	) {
		Box(
			contentAlignment = Alignment.Center,
			modifier = Modifier.fillMaxSize(),
		) {
			ActionIcon(
				isTracking = isTracking,
				hasPermission = hasPermission,
				iconSize = sizing.iconSize,
			)
		}
	}
}

@Composable
private fun ActionIcon(
	isTracking: Boolean,
	hasPermission: Boolean,
	iconSize: androidx.compose.ui.unit.Dp,
	modifier: Modifier = Modifier,
) {
	val icon: ImageVector = when {
		!hasPermission -> Icons.Default.LocationOff
		isTracking -> Icons.Default.Stop
		else -> Icons.Default.PlayArrow
	}
	val tint = when {
		!hasPermission -> MaterialTheme.colorScheme.onSurfaceVariant
		isTracking -> MaterialTheme.colorScheme.error
		else -> MaterialTheme.colorScheme.primary
	}

	AnimatedContent(
		targetState = icon,
		label = "action_icon",
		transitionSpec = {
			(fadeIn(animationSpec = tween(MotionTokens.STANDARD_MS)) +
				scaleIn(initialScale = 0.8f, animationSpec = tween(MotionTokens.STANDARD_MS)))
				.togetherWith(
					fadeOut(animationSpec = tween(MotionTokens.QUICK_MS)) +
						scaleOut(targetScale = 0.8f, animationSpec = tween(MotionTokens.QUICK_MS)),
				)
		},
		modifier = modifier,
	) { targetIcon ->
		Icon(
			imageVector = targetIcon,
			contentDescription = null, // handled by parent semantics
			tint = tint,
			modifier = Modifier.size(iconSize),
		)
	}
}

private data class TrackingActionRingSizing(
	val outerSize: androidx.compose.ui.unit.Dp,
	val innerSize: androidx.compose.ui.unit.Dp,
	val iconSize: androidx.compose.ui.unit.Dp,
	val outerStroke: androidx.compose.ui.unit.Dp,
	val innerStroke: androidx.compose.ui.unit.Dp,
) {
	companion object {
		val Default = TrackingActionRingSizing(
			outerSize = 96.dp,
			innerSize = 72.dp,
			iconSize = 32.dp,
			outerStroke = 5.dp,
			innerStroke = 6.dp,
		)
		val Compact = TrackingActionRingSizing(
			outerSize = 80.dp,
			innerSize = 60.dp,
			iconSize = 28.dp,
			outerStroke = 4.dp,
			innerStroke = 5.dp,
		)
	}
}
