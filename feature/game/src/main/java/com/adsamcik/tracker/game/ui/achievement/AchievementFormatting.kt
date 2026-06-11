package com.adsamcik.tracker.game.ui.achievement

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.data.achievement.AchievementFormatting as StatsAchievementFormatting

/**
 * Thin Compose wrappers around [StatsAchievementFormatting] (the canonical
 * formatter in `:stats-data`).
 *
 * Kept here because `:stats-data` is data-layer and must not pull in
 * `androidx.compose.*` (architectural fitness ban). UI modules that need a
 * `remember*`-style API can either depend on this object or inline their own
 * remember + AchievementFormatting.format* call — see `:dashboard` for an
 * example of the inline pattern.
 */
object AchievementFormatting {

	@Composable
	fun rememberTitle(definition: AchievementDefinition): String {
		val context = LocalContext.current
		return remember(definition.id, context) {
			StatsAchievementFormatting.formatTitle(context, definition)
		}
	}

	@Composable
	fun rememberDescription(definition: AchievementDefinition): String {
		val context = LocalContext.current
		return remember(definition.id, context) {
			StatsAchievementFormatting.formatDescription(context, definition)
		}
	}

	/** Returns "current / target unit" for an in-progress achievement. */
	@Composable
	fun rememberValueLabel(current: Double, definition: AchievementDefinition): String {
		val context = LocalContext.current
		return remember(definition.id, current, context) {
			StatsAchievementFormatting.formatValueLabel(context, current, definition)
		}
	}
}
