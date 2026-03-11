package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R as DashboardR
import com.adsamcik.tracker.tracker.R

/**
 * Expressive Tracking FAB with Material 3 styling.
 *
 * - Morphing shape: Rounded square (idle) → Circle (tracking)
 * - Animated ring with rotating gradient when active
 * - Soft glow effect during tracking
 * - AnimatedContent icon transitions
 * - Three states: no permission, idle, tracking
 */
@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun TrackingFAB(
	isTracking: Boolean,
	hasPermission: Boolean,
	onToggleTracking: () -> Unit,
	onRequestPermission: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val infiniteTransition = rememberInfiniteTransition(label = "tracking_button")

	val cornerRadius by animateFloatAsState(
		targetValue = if (isTracking) 50f else 28f,
		animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
		label = "corner_radius",
	)

	val pulseScale by infiniteTransition.animateFloat(
		initialValue = 1f,
		targetValue = if (isTracking) 1.04f else 1f,
		animationSpec = infiniteRepeatable(
			animation = tween(1200, easing = FastOutSlowInEasing),
			repeatMode = RepeatMode.Reverse,
		),
		label = "pulse",
	)

	val glowRotation by infiniteTransition.animateFloat(
		initialValue = 0f,
		targetValue = 360f,
		animationSpec = infiniteRepeatable(
			animation = tween(3000, easing = LinearEasing),
			repeatMode = RepeatMode.Restart,
		),
		label = "glow_rotation",
	)

	val glowAlpha by animateFloatAsState(
		targetValue = if (isTracking) 0.6f else 0f,
		animationSpec = tween(durationMillis = 500),
		label = "glow_alpha",
	)

	val iconScale by animateFloatAsState(
		targetValue = if (isTracking) 0.9f else 1f,
		animationSpec = tween(durationMillis = 300),
		label = "icon_scale",
	)

	val buttonSize = 96.dp
	val primaryColor = MaterialTheme.colorScheme.primary
	val errorColor = MaterialTheme.colorScheme.error
	val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

	val containerColor = when {
		!hasPermission -> surfaceVariant
		isTracking -> errorColor
		else -> primaryColor
	}

	val contentColor = when {
		!hasPermission -> MaterialTheme.colorScheme.onSurfaceVariant
		isTracking -> MaterialTheme.colorScheme.onError
		else -> MaterialTheme.colorScheme.onPrimary
	}

	val fabContentDescription = when {
		!hasPermission -> stringResource(DashboardR.string.dashboard_cd_start_tracking_permission)
		isTracking -> stringResource(DashboardR.string.dashboard_cd_stop_tracking)
		else -> stringResource(DashboardR.string.dashboard_cd_start_tracking)
	}
	val fabClickLabel = when {
		!hasPermission -> stringResource(DashboardR.string.dashboard_action_enable_location)
		isTracking -> stringResource(DashboardR.string.dashboard_cd_stop_tracking)
		else -> stringResource(DashboardR.string.dashboard_cd_start_tracking)
	}

	Box(
		contentAlignment = Alignment.Center,
		modifier = modifier
			.size(buttonSize + 16.dp)
	) {
		// Outer glow ring when tracking
		if (isTracking) {
			Canvas(
				modifier = Modifier
					.size(buttonSize + 12.dp)
					.alpha(glowAlpha)
					.graphicsLayer { rotationZ = glowRotation },
			) {
				val sweepGradient = Brush.sweepGradient(
					0f to errorColor.copy(alpha = 0.8f),
					0.25f to errorColor.copy(alpha = 0.2f),
					0.5f to errorColor.copy(alpha = 0.8f),
					0.75f to errorColor.copy(alpha = 0.2f),
					1f to errorColor.copy(alpha = 0.8f),
				)

				drawCircle(
					brush = sweepGradient,
					radius = size.minDimension / 2,
					style = Stroke(width = 4.dp.toPx()),
				)
			}
		}

		// Main button with morphing shape
		Surface(
			onClick = {
				if (!hasPermission) onRequestPermission() else onToggleTracking()
			},
			shape = RoundedCornerShape(cornerRadius.dp),
			color = containerColor,
			shadowElevation = if (isTracking) 8.dp else 6.dp,
			tonalElevation = if (isTracking) 4.dp else 2.dp,
			modifier = Modifier
				.size(buttonSize)
				.semantics(mergeDescendants = true) {
					contentDescription = fabContentDescription
					onClick(label = fabClickLabel, action = null)
				}
				.graphicsLayer {
					scaleX = pulseScale
					scaleY = pulseScale
				}
				.testTag("tracking_fab"),
		) {
			Box(
				contentAlignment = Alignment.Center,
				modifier = Modifier.fillMaxSize(),
			) {
				val icon: ImageVector = when {
					!hasPermission -> Icons.Default.LocationOff
					isTracking -> Icons.Default.Stop
					else -> Icons.Default.PlayArrow
				}

				AnimatedContent(
					targetState = icon,
					label = "fab_icon",
					transitionSpec = {
						(fadeIn(animationSpec = tween(300)) +
							scaleIn(
								initialScale = 0.8f,
								animationSpec = tween(300),
							))
							.togetherWith(
								fadeOut(animationSpec = tween(200)) +
									scaleOut(
										targetScale = 0.8f,
										animationSpec = tween(200),
									),
							)
					},
				) { targetIcon ->
					Icon(
						imageVector = targetIcon,
						contentDescription = null,
						tint = contentColor,
						modifier = Modifier
							.size(40.dp)
							.graphicsLayer {
								scaleX = iconScale
								scaleY = iconScale
							},
					)
				}
			}
		}
	}
}
