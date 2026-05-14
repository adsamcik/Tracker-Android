package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R

/**
 * Empty state content shown when the user has no tracking history.
 *
 * Uses a static illustration so the first dashboard screen stays responsive
 * while the rest of startup work settles.
 */
@Composable
internal fun EmptyStateCard(
	onExploreClick: () -> Unit = {},
	onAchieveClick: () -> Unit = {},
	onTrackClick: () -> Unit = {},
	onPrivacyClick: () -> Unit = {},
	modifier: Modifier = Modifier,
) {
	val floatOffset = 0f
	val iconScale = 1f
	val pathOffset = 0f

	Card(
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
		),
		modifier = modifier.fillMaxWidth(),
	) {
		Box(modifier = Modifier.fillMaxWidth()) {
			// Animated background pattern
			Canvas(modifier = Modifier
				.fillMaxWidth()
				.height(220.dp)
				.alpha(0.06f)) {
				val width = size.width
				val height = size.height
				val path = androidx.compose.ui.graphics.Path()

				val waveOffset = pathOffset
				path.moveTo(-50f + waveOffset, height * 0.3f)
				path.cubicTo(
					width * 0.2f + waveOffset * 0.5f, height * 0.5f,
					width * 0.4f + waveOffset * 0.3f, height * 0.2f,
					width * 0.6f + waveOffset * 0.5f, height * 0.4f,
				)
				path.cubicTo(
					width * 0.8f + waveOffset * 0.3f, height * 0.6f,
					width + waveOffset * 0.5f, height * 0.3f,
					width + 50f, height * 0.5f,
				)

				path.moveTo(-30f + waveOffset * 0.7f, height * 0.7f)
				path.cubicTo(
					width * 0.3f + waveOffset * 0.4f, height * 0.8f,
					width * 0.5f + waveOffset * 0.6f, height * 0.6f,
					width * 0.7f + waveOffset * 0.4f, height * 0.85f,
				)
				path.lineTo(width + 30f, height * 0.9f)

				drawPath(
					path = path,
					color = Color.Black,
					style = Stroke(
						width = 3.dp.toPx(),
						cap = StrokeCap.Round,
					),
				)
			}

			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(24.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.Center,
			) {
				// Floating icon with pulse
				Box(
					modifier = Modifier
						.graphicsLayer {
							translationY = -floatOffset
							scaleX = iconScale
							scaleY = iconScale
						}
						.size(72.dp)
						.clip(MaterialTheme.shapes.extraLarge)
						.background(
							Brush.radialGradient(
								colors = listOf(
									MaterialTheme.colorScheme.primaryContainer,
									MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
								),
							),
						)
						.padding(18.dp),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						imageVector = Icons.Default.LocationSearching,
						contentDescription = stringResource(R.string.dashboard_empty_icon_desc),
						modifier = Modifier.size(36.dp),
						tint = MaterialTheme.colorScheme.onPrimaryContainer,
					)
				}

				Spacer(Modifier.height(20.dp))

				Text(
					text = stringResource(R.string.dashboard_empty_title),
					style = MaterialTheme.typography.headlineSmall,
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.onSurface,
					textAlign = TextAlign.Center,
				)

				Spacer(Modifier.height(8.dp))

				Text(
					text = stringResource(R.string.dashboard_empty_subtitle),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
				)

				Spacer(Modifier.height(24.dp))

				// Feature highlights
				FeatureHighlights(
					onExploreClick = onExploreClick,
					onAchieveClick = onAchieveClick,
					onTrackClick = onTrackClick,
					onPrivacyClick = onPrivacyClick,
				)
			}
		}
	}
}

@Composable
internal fun GettingStartedCard(
	onChooseTrackingStyleClick: () -> Unit = {},
	onStartFirstTrackClick: () -> Unit = {},
	onReviewMapsAndStatsClick: () -> Unit = {},
	modifier: Modifier = Modifier,
) {
	Card(
		modifier = modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
		),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(20.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			Text(
				text = stringResource(R.string.dashboard_empty_quick_start_title),
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = stringResource(R.string.dashboard_empty_quick_start_subtitle),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			QuickStartStep(
				icon = Icons.Default.Tune,
				title = stringResource(R.string.dashboard_empty_step_customize_title),
				description = stringResource(R.string.dashboard_empty_step_customize_desc),
				onClick = onChooseTrackingStyleClick,
				testTag = "dashboard_quick_start_choose_tracking_style",
			)
			QuickStartStep(
				icon = Icons.Default.PlayArrow,
				title = stringResource(R.string.dashboard_empty_step_start_title),
				description = stringResource(R.string.dashboard_empty_step_start_desc),
				onClick = onStartFirstTrackClick,
				testTag = "dashboard_quick_start_start_first_track",
			)
			QuickStartStep(
				icon = Icons.Default.Map,
				title = stringResource(R.string.dashboard_empty_step_review_title),
				description = stringResource(R.string.dashboard_empty_step_review_desc),
				onClick = onReviewMapsAndStatsClick,
				testTag = "dashboard_quick_start_review_maps_stats",
			)
		}
	}
}

@Composable
internal fun EmptyStateStartHintCard(
	onStart: () -> Unit,
	hasPermission: Boolean,
	modifier: Modifier = Modifier,
) {
	Card(
		onClick = onStart,
		modifier = modifier
			.fillMaxWidth()
			.testTag("dashboard_ready_when_you_are_card"),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.secondaryContainer,
		),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(20.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				imageVector = if (hasPermission) Icons.Filled.PlayArrow else Icons.Default.LocationSearching,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSecondaryContainer,
				modifier = Modifier.size(28.dp),
			)
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Text(
					text = stringResource(R.string.dashboard_empty_fab_hint_title),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSecondaryContainer,
				)
				Text(
					text = stringResource(R.string.dashboard_empty_fab_hint_desc),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSecondaryContainer,
				)
			}
			Icon(
				imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSecondaryContainer,
				modifier = Modifier.size(24.dp),
			)
		}
	}
}

/**
 * Feature highlights grid for the empty state.
 */
@Composable
private fun FeatureHighlights(
	onExploreClick: () -> Unit,
	onAchieveClick: () -> Unit,
	onTrackClick: () -> Unit,
	onPrivacyClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceEvenly,
		) {
			FeatureChip(
				icon = Icons.Default.Explore,
				title = stringResource(R.string.dashboard_empty_feature_explore),
				description = stringResource(R.string.dashboard_empty_feature_explore_desc),
				modifier = Modifier.weight(1f),
				onClick = onExploreClick,
				testTag = "dashboard_tile_explore",
			)
			Spacer(Modifier.width(8.dp))
			FeatureChip(
				icon = Icons.Default.Star,
				title = stringResource(R.string.dashboard_empty_feature_achieve),
				description = stringResource(R.string.dashboard_empty_feature_achieve_desc),
				modifier = Modifier.weight(1f),
				onClick = onAchieveClick,
				testTag = "dashboard_tile_achieve",
			)
		}
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceEvenly,
		) {
			FeatureChip(
				icon = Icons.Default.Route,
				title = stringResource(R.string.dashboard_empty_feature_track),
				description = stringResource(R.string.dashboard_empty_feature_track_desc),
				modifier = Modifier.weight(1f),
				onClick = onTrackClick,
				testTag = "dashboard_tile_track",
			)
			Spacer(Modifier.width(8.dp))
			FeatureChip(
				icon = Icons.Default.Lock,
				title = stringResource(R.string.dashboard_empty_feature_privacy),
				description = stringResource(R.string.dashboard_empty_feature_privacy_desc),
				modifier = Modifier.weight(1f),
				onClick = onPrivacyClick,
				testTag = "dashboard_tile_privacy",
			)
		}
	}
}

@Composable
private fun QuickStartStep(
	icon: ImageVector,
	title: String,
	description: String,
	onClick: () -> Unit,
	testTag: String,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.testTag(testTag)
			.clip(MaterialTheme.shapes.medium)
			.clickable(role = Role.Button, onClick = onClick),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.Top,
	) {
		Box(
			modifier = Modifier
				.size(40.dp)
				.clip(MaterialTheme.shapes.medium)
				.background(MaterialTheme.colorScheme.primaryContainer),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onPrimaryContainer,
				modifier = Modifier.size(20.dp),
			)
		}
		Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = description,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun FeatureChip(
	icon: ImageVector,
	title: String,
	description: String,
	onClick: () -> Unit,
	testTag: String,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.testTag(testTag)
			.clip(MaterialTheme.shapes.small)
			.background(MaterialTheme.colorScheme.surfaceContainerLow)
			.clickable(role = Role.Button, onClick = onClick)
			.padding(horizontal = 12.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Icon(
			imageVector = icon,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.primary,
			modifier = Modifier.size(20.dp),
		)
		Column {
			Text(
				text = title,
				style = MaterialTheme.typography.labelMedium,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = description,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}
