package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard

/**
 * Compact card summarizing the player's trophy collection with medal counts.
 */
@Composable
fun TrophySummaryCard(
	totalCompleted: Int,
	goldCount: Int,
	silverCount: Int,
	bronzeCount: Int,
	modifier: Modifier = Modifier,
	onViewTrophyCase: () -> Unit = {},
) {
	val summaryDescription = if (totalCompleted == 0) {
		stringResource(R.string.game_no_trophies_yet)
	} else {
		stringResource(R.string.game_trophy_summary, totalCompleted) +
				", $goldCount gold, $silverCount silver, $bronzeCount bronze"
	}

	GlassCard(
		modifier = modifier
			.padding(horizontal = 16.dp)
			.fillMaxWidth()
			.clickable(onClick = onViewTrophyCase)
			.semantics { contentDescription = summaryDescription }
	) {
		Column {
			// Header row
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Icon(
					Icons.Outlined.EmojiEvents,
					contentDescription = null,
					modifier = Modifier.size(24.dp),
					tint = MaterialTheme.colorScheme.tertiary,
				)
				Text(
					text = stringResource(R.string.game_trophies_title),
					style = MaterialTheme.typography.titleMedium,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.padding(start = 8.dp),
				)
				Spacer(modifier = Modifier.weight(1f))
				Row(
					modifier = Modifier
						.clip(MaterialTheme.shapes.small)
						.clickable(onClick = onViewTrophyCase)
						.padding(horizontal = 8.dp, vertical = 4.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = stringResource(R.string.game_trophy_view_all),
						style = MaterialTheme.typography.labelLarge,
						fontWeight = FontWeight.Medium,
						color = MaterialTheme.colorScheme.primary,
					)
					Icon(
						Icons.AutoMirrored.Outlined.KeyboardArrowRight,
						contentDescription = null,
						modifier = Modifier.size(16.dp),
						tint = MaterialTheme.colorScheme.primary,
					)
				}
			}

			// Summary row
			Row(
				modifier = Modifier.padding(top = 12.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (totalCompleted == 0) {
					Text(
						text = stringResource(R.string.game_no_trophies_yet),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				} else {
					Text(
						text = stringResource(R.string.game_trophy_summary, totalCompleted),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						text = " · ",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					MedalCount(count = goldCount, medal = MEDAL_GOLD)
					Spacer(modifier = Modifier.width(4.dp))
					MedalCount(count = silverCount, medal = MEDAL_SILVER)
					Spacer(modifier = Modifier.width(4.dp))
					MedalCount(count = bronzeCount, medal = MEDAL_BRONZE)
				}
			}
		}
	}
}

@Composable
private fun MedalCount(count: Int, medal: String) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = count.toString(),
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Bold,
			color = MaterialTheme.colorScheme.onSurface,
		)
		Text(
			text = medal,
			style = MaterialTheme.typography.bodyMedium,
		)
	}
}

private const val MEDAL_GOLD = "🥇"
private const val MEDAL_SILVER = "🥈"
private const val MEDAL_BRONZE = "🥉"
