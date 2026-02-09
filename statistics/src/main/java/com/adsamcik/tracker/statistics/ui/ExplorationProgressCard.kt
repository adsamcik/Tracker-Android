package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.viewmodel.ExplorationStats

/**
 * Card displaying exploration progress stats: total cells, current streak, best streak.
 */
@Composable
fun ExplorationProgressCard(
	stats: ExplorationStats,
	modifier: Modifier = Modifier,
) {
	Card(modifier = modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(16.dp)) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Icon(
					imageVector = Icons.Default.Explore,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.tertiary,
				)
				Text(
					text = stringResource(R.string.history_exploration_title),
					style = MaterialTheme.typography.titleSmall,
				)
			}

			Spacer(Modifier.height(12.dp))

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceEvenly,
			) {
				ExplorationStatItem(
					value = stats.totalCells.toString(),
					label = stringResource(R.string.history_exploration_cells),
				)
				ExplorationStatItem(
					value = "${stats.currentStreak}d",
					label = stringResource(R.string.history_exploration_streak),
				)
				ExplorationStatItem(
					value = "${stats.bestStreak}d",
					label = stringResource(R.string.history_exploration_best),
				)
			}
		}
	}
}

@Composable
private fun ExplorationStatItem(
	value: String,
	label: String,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text(
			text = value,
			style = MaterialTheme.typography.headlineSmall,
			color = MaterialTheme.colorScheme.tertiary,
		)
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
