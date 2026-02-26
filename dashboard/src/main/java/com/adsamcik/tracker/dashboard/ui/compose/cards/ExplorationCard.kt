package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.state.ExplorationUiState
import com.adsamcik.tracker.shared.utils.style.compose.RidgelineCardDefaults
import com.adsamcik.tracker.shared.base.extension.formatReadable

/**
 * Exploration progress card showing new cells discovered today,
 * total explored cells, and season indicators.
 */
@Composable
internal fun ExplorationCard(
	explorationState: ExplorationUiState,
	modifier: Modifier = Modifier,
) {
	if (!explorationState.hasExplorationData) return

	// Exploration has a gamification feel — tertiaryContainer aligns with trophy/achievement tone.
	Card(
		modifier = modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.tertiaryContainer,
			contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
		),
		shape = RidgelineCardDefaults.shape,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(16.dp),
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Icon(
					imageVector = Icons.Filled.Explore,
					contentDescription = stringResource(R.string.dashboard_cd_explore_icon),
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(20.dp),
				)
				Spacer(Modifier.padding(start = 8.dp))
				Text(
					text = stringResource(R.string.dashboard_exploration_title),
					style = MaterialTheme.typography.titleMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			Spacer(Modifier.height(12.dp))

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				// New cells today
				Column {
					Text(
						text = explorationState.newCellsToday.formatReadable(),
						style = MaterialTheme.typography.headlineSmall,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurface,
					)
					Text(
						text = stringResource(R.string.dashboard_exploration_new_today),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}

				// Total cells
				Column(horizontalAlignment = Alignment.End) {
					Text(
						text = explorationState.totalCells.formatReadable(),
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface,
					)
					Text(
						text = stringResource(R.string.dashboard_exploration_total_cells),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}

			// Season indicators
			if (explorationState.seasonsCovered > 0) {
				Spacer(Modifier.height(12.dp))
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Text(
						text = stringResource(R.string.dashboard_exploration_seasons),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					SeasonDots(seasonsCovered = explorationState.seasonsCovered)
				}
			}
		}
	}
}

/**
 * Four colored dots representing seasons: spring (green), summer (yellow),
 * autumn (orange), winter (blue). Filled if covered, outlined if not.
 */
@Composable
private fun SeasonDots(
	seasonsCovered: Int,
	modifier: Modifier = Modifier,
) {
	val seasonColors = listOf(
		Color(0xFF4CAF50), // Spring — green
		Color(0xFFFFC107), // Summer — yellow
		Color(0xFFFF9800), // Autumn — orange
		Color(0xFF2196F3), // Winter — blue
	)

	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		seasonColors.forEachIndexed { index, color ->
			val isFilled = index < seasonsCovered
			Canvas(modifier = Modifier.size(10.dp)) {
				if (isFilled) {
					drawCircle(color = color)
				} else {
					drawCircle(
						color = color.copy(alpha = 0.3f),
						style = androidx.compose.ui.graphics.drawscope.Stroke(
							width = 1.5.dp.toPx(),
						),
					)
				}
			}
		}
	}
}
