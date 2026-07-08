package com.adsamcik.tracker.game.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.ui.achievement.AchievementFormatting
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.AchievementListItem
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.AchievementSummaryState
import com.adsamcik.tracker.game.viewmodel.ExplorationViewModel.NextAchievement
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog

@Composable
fun AchievementCard(state: AchievementSummaryState, modifier: Modifier = Modifier, onViewAll: () -> Unit = {}) {
	GlassCard(modifier = modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onViewAll)) {
		Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
			Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
						Icon(Icons.Outlined.EmojiEvents, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
					}
					Text(stringResource(R.string.achievements_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 12.dp))
				}
				Text(stringResource(R.string.game_view_all_achievements), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
			}
			if (state.totalUnlocked == 0 && state.nextUp.isEmpty()) {
				Text(stringResource(R.string.achievements_none_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			} else {
				Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
					TierBadge(stringResource(R.string.achievements_bronze), state.bronzeCount, BronzeColor)
					TierBadge(stringResource(R.string.achievements_silver), state.silverCount, SilverColor)
					TierBadge(stringResource(R.string.achievements_gold), state.goldCount, GoldColor)
					TierBadge(stringResource(R.string.achievements_diamond), state.diamondCount, DiamondColor)
					TierBadge(stringResource(R.string.achievements_mythic), state.mythicCount, MythicColor)
				}
				if (state.recentUnlocks.isNotEmpty()) {
					Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
						Text(stringResource(R.string.game_recent_achievements), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
						state.recentUnlocks.forEach { unlock -> RecentAchievementRow(unlock) }
					}
				}
				if (state.nextUp.isNotEmpty()) {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						Text(stringResource(R.string.game_next_achievements), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
						state.nextUp.forEach { NextAchievementRow(it) }
					}
				}
			}
		}
	}
}

@Composable
private fun RecentAchievementRow(unlock: AchievementListItem) {
	val definition = remember(unlock.id) { AchievementCatalog.byId(unlock.id) }
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		if (definition != null) {
			AchievementArtworkIcon(
				definition = definition,
				isUnlocked = true,
				modifier = Modifier.size(28.dp),
			)
		}
		Text(
			achievementTitleFor(unlock.id, unlock.nameRes),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.weight(1f),
		)
	}
}

@Composable
private fun NextAchievementRow(next: NextAchievement) {
	val definition = remember(next.id) { AchievementCatalog.byId(next.id) }
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		if (definition != null) {
			AchievementArtworkIcon(
				definition = definition,
				isUnlocked = false,
				modifier = Modifier.size(30.dp),
			)
		}
		Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
				Text(
					achievementTitleFor(next.id, next.nameRes),
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.weight(1f),
				)
				Text("${(next.progress * PERCENTAGE_MULTIPLIER).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
			}
			Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))) {
				Box(modifier = Modifier.fillMaxWidth(next.progress).height(6.dp).background(MaterialTheme.colorScheme.primary))
			}
		}
	}
}

@Composable
private fun TierBadge(label: String, count: Int, color: Color) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
			Text(count.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
		}
		Spacer(modifier = Modifier.height(4.dp))
		Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

@Composable
private fun achievementTitleFor(id: String, fallbackNameRes: String): String {
	val definition = remember(id) { AchievementCatalog.byId(id) }
	return if (definition != null) {
		AchievementFormatting.rememberTitle(definition)
	} else {
		// Defensive fallback: try resolving as a legacy string resource so the UI
		// doesn't show a raw id if the catalog ever drifts. Should be unreachable.
		val context = LocalContext.current
		val resId = remember(fallbackNameRes, context) { context.resources.getIdentifier(fallbackNameRes, "string", context.packageName) }
		if (resId != 0) stringResource(resId) else id
	}
}

private val BronzeColor: Color @Composable get() = MaterialTheme.colorScheme.tertiary
private val SilverColor: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
private val GoldColor: Color @Composable get() = MaterialTheme.colorScheme.primary
private val DiamondColor: Color @Composable get() = MaterialTheme.colorScheme.inversePrimary
private val MythicColor: Color @Composable get() = MaterialTheme.colorScheme.error
private const val PERCENTAGE_MULTIPLIER = 100
