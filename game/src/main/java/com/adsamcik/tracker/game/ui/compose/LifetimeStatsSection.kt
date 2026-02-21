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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.adsamcik.tracker.game.repository.LifetimeStatsUi
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard

/**
 * Displays lifetime challenge statistics: total, completion rate, medal breakdown bar, total XP.
 */
@Composable
fun LifetimeStatsSection(
	stats: LifetimeStatsUi,
	modifier: Modifier = Modifier,
) {
	GlassCard(
		modifier = modifier
			.padding(horizontal = 16.dp)
			.fillMaxWidth(),
	) {
		Column {
			Text(
				text = stringResource(R.string.game_lifetime_stats_title),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
			)

			Spacer(modifier = Modifier.height(16.dp))

			// Stat rows
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				StatItem(
					label = stringResource(R.string.game_lifetime_total_challenges),
					value = stats.totalChallenges.toString(),
				)
				StatItem(
					label = stringResource(R.string.game_lifetime_completion_rate),
					value = "${(stats.completionRate * 100).toInt()}%",
				)
			}

			Spacer(modifier = Modifier.height(12.dp))

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				StatItem(
					label = stringResource(R.string.game_lifetime_total_xp),
					value = stats.totalXpEarned.toString(),
				)
			}

			Spacer(modifier = Modifier.height(16.dp))

			// Medal breakdown bar
			Text(
				text = stringResource(R.string.game_lifetime_medal_breakdown),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(modifier = Modifier.height(8.dp))
			MedalBreakdownBar(
				gold = stats.goldCount,
				silver = stats.silverCount,
				bronze = stats.bronzeCount,
				total = stats.completedCount,
			)
			Spacer(modifier = Modifier.height(4.dp))
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceEvenly,
			) {
				Text(
					text = "${stats.goldCount} 🥇",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Text(
					text = "${stats.silverCount} 🥈",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Text(
					text = "${stats.bronzeCount} 🥉",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun StatItem(label: String, value: String) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		Text(
			text = value,
			style = MaterialTheme.typography.titleLarge,
			fontWeight = FontWeight.Bold,
			color = MaterialTheme.colorScheme.onSurface,
		)
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun MedalBreakdownBar(
	gold: Int,
	silver: Int,
	bronze: Int,
	total: Int,
	modifier: Modifier = Modifier,
) {
	if (total == 0) return

	val goldFraction = gold.toFloat() / total
	val silverFraction = silver.toFloat() / total
	val bronzeFraction = bronze.toFloat() / total

	val goldColor = androidx.compose.ui.graphics.Color(0xFFFFD700)
	val silverColor = androidx.compose.ui.graphics.Color(0xFFC0C0C0)
	val bronzeColor = androidx.compose.ui.graphics.Color(0xFFCD7F32)

	Row(
		modifier = modifier
			.fillMaxWidth()
			.height(12.dp)
			.clip(RoundedCornerShape(6.dp)),
	) {
		if (goldFraction > 0f) {
			Box(
				modifier = Modifier
					.weight(goldFraction)
					.height(12.dp)
					.background(goldColor),
			)
		}
		if (silverFraction > 0f) {
			Box(
				modifier = Modifier
					.weight(silverFraction)
					.height(12.dp)
					.background(silverColor),
			)
		}
		if (bronzeFraction > 0f) {
			Box(
				modifier = Modifier
					.weight(bronzeFraction)
					.height(12.dp)
					.background(bronzeColor),
			)
		}
	}
}
