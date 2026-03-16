package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.shared.utils.style.compose.LocalReducedMotion

/**
 * Empty state content shown when the user has no tracking history.
 *
 * Includes: floating animated icon, animated route background,
 * feature highlights section, and a hint arrow pointing to the FAB.
 */
@Composable
internal fun EmptyStateCard(modifier: Modifier = Modifier) {
	val reducedMotion = LocalReducedMotion.current
	val floatOffset = if (reducedMotion) {
		0f
	} else {
		val infiniteTransition = rememberInfiniteTransition(label = "empty_state")
		val animatedFloatOffset by infiniteTransition.animateFloat(
			initialValue = 0f,
			targetValue = 8f,
			animationSpec = infiniteRepeatable(
				animation = tween(2000, easing = FastOutSlowInEasing),
				repeatMode = RepeatMode.Reverse,
			),
			label = "float",
		)
		animatedFloatOffset
	}
	val iconScale = if (reducedMotion) {
		1f
	} else {
		val infiniteTransition = rememberInfiniteTransition(label = "empty_state_icon")
		val animatedIconScale by infiniteTransition.animateFloat(
			initialValue = 1f,
			targetValue = 1.05f,
			animationSpec = infiniteRepeatable(
				animation = tween(3000, easing = FastOutSlowInEasing),
				repeatMode = RepeatMode.Reverse,
			),
			label = "icon_pulse",
		)
		animatedIconScale
	}
	val pathOffset = if (reducedMotion) {
		0f
	} else {
		val infiniteTransition = rememberInfiniteTransition(label = "empty_state_path")
		val animatedPathOffset by infiniteTransition.animateFloat(
			initialValue = 0f,
			targetValue = 50f,
			animationSpec = infiniteRepeatable(
				animation = tween(8000, easing = LinearEasing),
				repeatMode = RepeatMode.Restart,
			),
			label = "path_offset",
		)
		animatedPathOffset
	}

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
				FeatureHighlights()

				Spacer(Modifier.height(16.dp))

				// Hint arrow pointing to FAB
				Icon(
					imageVector = Icons.Filled.KeyboardArrowDown,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
					modifier = Modifier
						.size(28.dp)
						.graphicsLayer { translationY = floatOffset * 0.5f },
				)
			}
		}
	}
}

@Composable
internal fun GettingStartedCard(modifier: Modifier = Modifier) {
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
			)
			QuickStartStep(
				icon = Icons.Default.PlayArrow,
				title = stringResource(R.string.dashboard_empty_step_start_title),
				description = stringResource(R.string.dashboard_empty_step_start_desc),
			)
			QuickStartStep(
				icon = Icons.Default.Map,
				title = stringResource(R.string.dashboard_empty_step_review_title),
				description = stringResource(R.string.dashboard_empty_step_review_desc),
			)
		}
	}
}

@Composable
internal fun EmptyStateStartHintCard(modifier: Modifier = Modifier) {
	Card(
		modifier = modifier.fillMaxWidth(),
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
				imageVector = Icons.Default.LocationSearching,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSecondaryContainer,
				modifier = Modifier.size(28.dp),
			)
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
		}
	}
}

/**
 * Feature highlights grid for the empty state.
 */
@Composable
private fun FeatureHighlights(modifier: Modifier = Modifier) {
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
			)
			Spacer(Modifier.width(8.dp))
			FeatureChip(
				icon = Icons.Default.Star,
				title = stringResource(R.string.dashboard_empty_feature_achieve),
				description = stringResource(R.string.dashboard_empty_feature_achieve_desc),
				modifier = Modifier.weight(1f),
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
			)
			Spacer(Modifier.width(8.dp))
			FeatureChip(
				icon = Icons.Default.Lock,
				title = stringResource(R.string.dashboard_empty_feature_privacy),
				description = stringResource(R.string.dashboard_empty_feature_privacy_desc),
				modifier = Modifier.weight(1f),
			)
		}
	}
}

@Composable
private fun QuickStartStep(
	icon: ImageVector,
	title: String,
	description: String,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier.fillMaxWidth(),
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
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.clip(MaterialTheme.shapes.small)
			.background(MaterialTheme.colorScheme.surfaceContainerLow)
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
