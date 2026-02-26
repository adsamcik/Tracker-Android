package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.R as TrackerR

/**
 * Dashboard top bar with adaptive title, points chip, policy tier chip,
 * recording indicator, and settings button.
 *
 * Title adapts: "Dashboard" when idle, "Tracking" when active.
 * Shows a pulsing recording dot + linear progress when tracking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardTopBar(
	isTracking: Boolean,
	isLocked: Boolean,
	policyTier: PolicyTier,
	pointsToday: Int,
	onSettingsClick: () -> Unit,
	onGameClick: (() -> Unit)?,
	onCustomizeClick: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
){
	val context = LocalContext.current
	val settingsContentDescription = stringResource(R.string.dashboard_cd_open_settings)

	Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
		Column {
			TopAppBar(
				title = {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(12.dp),
					) {
						BadgedBox(badge = {
							if (isLocked) {
								Badge { Text(stringResource(R.string.dashboard_cd_locked_badge)) }
							}
						}) {
							Text(
								text = if (isTracking) {
									stringResource(R.string.dashboard_tracking_title)
								} else {
									stringResource(R.string.dashboard_title)
								},
								style = MaterialTheme.typography.titleLarge,
							)
						}

						// Recording indicator when tracking
						AnimatedVisibility(
							visible = isTracking,
							enter = fadeIn() + expandVertically(),
							exit = fadeOut() + shrinkVertically(),
						) {
							RecordingIndicator()
						}
					}
				},
				actions = {
					// Policy tier chip
					PolicyTierChip(tier = policyTier)

					// Points chip
					if (pointsToday > 0 && onGameClick != null) {
						Surface(
							onClick = onGameClick,
							shape = MaterialTheme.shapes.small,
							color = MaterialTheme.colorScheme.secondaryContainer,
							modifier = Modifier
								.padding(end = 8.dp)
								.semantics {
									contentDescription = context.getString(
										TrackerR.string.description_points_today,
										pointsToday.formatReadable(),
									)
								},
						) {
							Row(
								modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(4.dp),
							) {
								Icon(
									Icons.Outlined.Star,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.onSecondaryContainer,
									modifier = Modifier.size(16.dp),
								)
								Text(
									text = pointsToday.formatReadable(),
									style = MaterialTheme.typography.labelMedium,
									fontWeight = FontWeight.SemiBold,
									color = MaterialTheme.colorScheme.onSecondaryContainer,
								)
							}
						}
					}

					// Customize dashboard button (idle mode only)
					if (onCustomizeClick != null) {
						IconButton(
							onClick = onCustomizeClick,
							modifier = Modifier.size(48.dp),
						) {
							Icon(
								Icons.Default.Tune,
								contentDescription = stringResource(R.string.dashboard_customize_title),
							)
						}
					}

					IconButton(
						onClick = onSettingsClick,
						modifier = Modifier.size(48.dp),
					) {
						Icon(
							Icons.Default.Settings,
							contentDescription = settingsContentDescription,
						)
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = Color.Transparent,
				),
			)

			// Linear progress when tracking
			AnimatedVisibility(
				visible = isTracking,
				enter = expandVertically(),
				exit = shrinkVertically(),
			) {
				LinearProgressIndicator(
					modifier = Modifier
						.fillMaxWidth()
						.height(2.dp),
				)
			}
		}
	}
}

/**
 * Pulsing recording dot indicator.
 */
@Composable
private fun RecordingIndicator(modifier: Modifier = Modifier) {
	val reducedMotion = LocalReducedMotion.current
	val alpha = if (reducedMotion) {
		1f
	} else {
		val infiniteTransition = rememberInfiniteTransition(label = "recording")
		val animatedAlpha by infiniteTransition.animateFloat(
			initialValue = 1f,
			targetValue = 0.3f,
			animationSpec = infiniteRepeatable(
				animation = tween(800),
				repeatMode = RepeatMode.Reverse,
			),
			label = "recording_pulse",
		)
		animatedAlpha
	}

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		modifier = modifier,
	) {
		Box(
			modifier = Modifier
				.size(8.dp)
				.alpha(alpha)
				.background(
					color = MaterialTheme.colorScheme.error,
					shape = CircleShape,
				),
		)
		Text(
			text = stringResource(R.string.dashboard_recording),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.error,
		)
	}
}

/**
 * Compact chip displaying the current tracking policy tier.
 * Hidden when tier is [PolicyTier.OFF].
 */
@Composable
private fun PolicyTierChip(tier: PolicyTier, modifier: Modifier = Modifier) {
	if (tier == PolicyTier.OFF) return

	val (label, containerColor) = when (tier) {
		PolicyTier.AMBIENT -> stringResource(TrackerR.string.policy_tier_ambient) to MaterialTheme.colorScheme.tertiaryContainer
		PolicyTier.ACTIVE -> stringResource(TrackerR.string.policy_tier_active) to MaterialTheme.colorScheme.primaryContainer
		PolicyTier.PRECISION -> stringResource(TrackerR.string.policy_tier_precision) to MaterialTheme.colorScheme.secondaryContainer
		PolicyTier.OFF -> return
	}
	val contentColor = when (tier) {
		PolicyTier.AMBIENT -> MaterialTheme.colorScheme.onTertiaryContainer
		PolicyTier.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
		PolicyTier.PRECISION -> MaterialTheme.colorScheme.onSecondaryContainer
		PolicyTier.OFF -> return
	}

	Surface(
		shape = MaterialTheme.shapes.small,
		color = containerColor,
		modifier = modifier.padding(end = 4.dp),
	) {
		Text(
			text = label,
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
			style = MaterialTheme.typography.labelSmall,
			color = contentColor,
		)
	}
}
