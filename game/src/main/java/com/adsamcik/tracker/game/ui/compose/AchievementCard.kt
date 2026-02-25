package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.AchievementSummaryState
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard

/**
 * Displays achievement progress summary: unlocked counts per tier,
 * progress toward the next closest achievement, and a "View All" action.
 */
@Composable
fun AchievementCard(
	state: AchievementSummaryState,
	modifier: Modifier = Modifier,
	onViewAll: () -> Unit = {},
) {
	GlassCard(
		modifier = modifier
			.padding(horizontal = 16.dp)
			.fillMaxWidth()
	) {
		Column {
			// Header with icon and title
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Box(
						modifier = Modifier
							.size(40.dp)
							.clip(CircleShape)
							.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
						contentAlignment = Alignment.Center
					) {
						Icon(
							Icons.Outlined.EmojiEvents,
							contentDescription = null,
							modifier = Modifier.size(24.dp),
							tint = MaterialTheme.colorScheme.primary
						)
					}
					Text(
						text = stringResource(R.string.achievements_title),
						style = MaterialTheme.typography.titleMedium,
						color = MaterialTheme.colorScheme.onSurface,
						modifier = Modifier.padding(start = 12.dp)
					)
				}

				// View all action — hidden until a detail screen exists
			}

			Spacer(modifier = Modifier.height(16.dp))

			if (state.totalUnlocked == 0 && state.nextClosest == null) {
				// Empty state
				Text(
					text = stringResource(R.string.achievements_none_yet),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			} else {
				// Tier counts
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceEvenly
				) {
					TierBadge(
						label = stringResource(R.string.achievements_bronze),
						count = state.bronzeCount,
						color = BronzeColor,
					)
					TierBadge(
						label = stringResource(R.string.achievements_silver),
						count = state.silverCount,
						color = SilverColor,
					)
					TierBadge(
						label = stringResource(R.string.achievements_gold),
						count = state.goldCount,
						color = GoldColor,
					)
					TierBadge(
						label = stringResource(R.string.achievements_diamond),
						count = state.diamondCount,
						color = DiamondColor,
					)
				}

				// Progress toward next achievement
				state.nextClosest?.let { next ->
					Spacer(modifier = Modifier.height(16.dp))

					Text(
						text = stringResource(R.string.achievements_unlocked),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)

					Spacer(modifier = Modifier.height(4.dp))

					// Progress bar
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.height(6.dp)
							.clip(RoundedCornerShape(3.dp))
							.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
					) {
						Box(
							modifier = Modifier
								.fillMaxWidth(next.progress)
								.height(6.dp)
								.background(MaterialTheme.colorScheme.primary)
						)
					}

					Spacer(modifier = Modifier.height(4.dp))

					Text(
						text = "${(next.progress * PERCENTAGE_MULTIPLIER).toInt()}%",
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.primary
					)
				}
			}
		}
	}
}

@Composable
private fun TierBadge(
	label: String,
	count: Int,
	color: Color,
) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		Box(
			modifier = Modifier
				.size(32.dp)
				.clip(CircleShape)
				.background(color.copy(alpha = 0.2f)),
			contentAlignment = Alignment.Center
		) {
			Text(
				text = count.toString(),
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.Bold,
				color = color
			)
		}
		Spacer(modifier = Modifier.height(4.dp))
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
	}
}

// Theme-aware tier badge colors
private val BronzeColor: Color
	@Composable get() = MaterialTheme.colorScheme.tertiary

private val SilverColor: Color
	@Composable get() = MaterialTheme.colorScheme.outlineVariant

private val GoldColor: Color
	@Composable get() = MaterialTheme.colorScheme.primary

private val DiamondColor: Color
	@Composable get() = MaterialTheme.colorScheme.inversePrimary

private const val PERCENTAGE_MULTIPLIER = 100
