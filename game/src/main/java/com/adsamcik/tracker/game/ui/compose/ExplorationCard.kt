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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.ExplorationState
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard

/**
 * Displays exploration statistics: total cells discovered, streak info, and seasons explored.
 */
@Composable
fun ExplorationCard(
	state: ExplorationState,
	modifier: Modifier = Modifier,
) {
	GlassCard(
		modifier = modifier
			.padding(horizontal = 16.dp)
			.fillMaxWidth()
	) {
		Column {
			// Header row with icon and title
			Row(verticalAlignment = Alignment.CenterVertically) {
				Box(
					modifier = Modifier
						.size(40.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)),
					contentAlignment = Alignment.Center
				) {
					Icon(
						Icons.Outlined.Explore,
						contentDescription = null,
						modifier = Modifier.size(24.dp),
						tint = MaterialTheme.colorScheme.tertiary
					)
				}
				Text(
					text = stringResource(R.string.exploration_title),
					style = MaterialTheme.typography.titleMedium,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.padding(start = 12.dp)
				)
			}

			Spacer(modifier = Modifier.height(16.dp))

			// Large cell count
			Text(
				text = state.totalCells.toString(),
				style = MaterialTheme.typography.displaySmall,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.primary
			)
			Text(
				text = stringResource(R.string.exploration_cells_discovered),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)

			Spacer(modifier = Modifier.height(16.dp))

			// Streak row
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceEvenly
			) {
				StreakStat(
					label = stringResource(R.string.exploration_current_streak),
					value = state.dailyStreak,
					unit = stringResource(R.string.exploration_days),
				)
				StreakStat(
					label = stringResource(R.string.exploration_best_streak),
					value = state.bestStreak,
					unit = stringResource(R.string.exploration_days),
				)
			}

			Spacer(modifier = Modifier.height(16.dp))

			// Seasons explored
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				Text(
					text = stringResource(R.string.exploration_seasons_explored),
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				SeasonIndicator(seasonsBitmask = state.seasonsBitmask)
				Text(
					text = "${Integer.bitCount(state.seasonsBitmask)} ${stringResource(R.string.exploration_of_four)}",
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.onSurface
				)
			}
		}
	}
}

@Composable
private fun StreakStat(
	label: String,
	value: Int,
	unit: String,
) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		Text(
			text = label.uppercase(),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		Text(
			text = value.toString(),
			style = MaterialTheme.typography.titleLarge,
			fontWeight = FontWeight.Bold,
			color = MaterialTheme.colorScheme.onSurface
		)
		Text(
			text = unit,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
	}
}

/**
 * Displays four small dots representing seasons (spring, summer, autumn, winter).
 * Filled dots indicate explored seasons based on which bits are set in the bitmask.
 * Bit 0 = Spring, bit 1 = Summer, bit 2 = Autumn, bit 3 = Winter.
 */
@Composable
private fun SeasonIndicator(seasonsBitmask: Int) {
	val seasonColors = listOf(
		MaterialTheme.colorScheme.primary,     // Spring (bit 0)
		MaterialTheme.colorScheme.tertiary,    // Summer (bit 1)
		MaterialTheme.colorScheme.secondary,   // Autumn (bit 2)
		MaterialTheme.colorScheme.outline,     // Winter (bit 3)
	)
	Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		for (i in 0 until SEASON_COUNT) {
			val isCovered = (seasonsBitmask and (1 shl i)) != 0
			Box(
				modifier = Modifier
					.size(8.dp)
					.clip(CircleShape)
					.background(
						if (isCovered) {
							seasonColors[i]
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
						}
					)
			)
		}
	}
}

private const val SEASON_COUNT = 4
