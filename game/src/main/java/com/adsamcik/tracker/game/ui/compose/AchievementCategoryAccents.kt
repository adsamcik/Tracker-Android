package com.adsamcik.tracker.game.ui.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.AchievementTier

/** Percent multiplier used when rendering progress fractions in the UI. */
internal const val PERCENT_MULTIPLIER = 100

/**
 * Progress fraction (0f-1f) at which a locked achievement is considered
 * "near complete" — surfaced with a softer tier-colored border to telegraph
 * imminent unlock.
 */
internal const val NEAR_COMPLETE_THRESHOLD = 0.5f

/** Material color matching the given tier's display accent. */
@Composable
internal fun tierColor(tier: AchievementTier): Color = when (tier) {
	AchievementTier.BRONZE -> MaterialTheme.colorScheme.tertiary
	AchievementTier.SILVER -> MaterialTheme.colorScheme.outline
	AchievementTier.GOLD -> MaterialTheme.colorScheme.primary
	AchievementTier.DIAMOND -> MaterialTheme.colorScheme.inversePrimary
	AchievementTier.MYTHIC -> MaterialTheme.colorScheme.error
}

/** Localized label for the given tier (e.g. "Bronze", "Silver"). */
@Composable
internal fun tierLabel(tier: AchievementTier): String = stringResource(
	when (tier) {
		AchievementTier.BRONZE -> R.string.achievements_bronze
		AchievementTier.SILVER -> R.string.achievements_silver
		AchievementTier.GOLD -> R.string.achievements_gold
		AchievementTier.DIAMOND -> R.string.achievements_diamond
		AchievementTier.MYTHIC -> R.string.achievements_mythic
	},
)

/** Material color identifying the given achievement category in UI accents. */
@Composable
internal fun categoryColor(category: AchievementCategory): Color = when (category) {
	AchievementCategory.EXPLORATION -> MaterialTheme.colorScheme.primary
	AchievementCategory.DISTANCE -> MaterialTheme.colorScheme.tertiary
	AchievementCategory.STEPS -> MaterialTheme.colorScheme.secondary
	AchievementCategory.STREAKS -> MaterialTheme.colorScheme.error
	AchievementCategory.MILESTONES -> MaterialTheme.colorScheme.primary
	AchievementCategory.MODES -> MaterialTheme.colorScheme.secondary
	AchievementCategory.TIME -> MaterialTheme.colorScheme.tertiary
	AchievementCategory.CALENDAR -> MaterialTheme.colorScheme.inversePrimary
}

/** Vector icon representing the given achievement category. */
internal fun categoryIcon(category: AchievementCategory): ImageVector = when (category) {
	AchievementCategory.EXPLORATION -> Icons.Outlined.Explore
	AchievementCategory.DISTANCE -> Icons.Outlined.Route
	AchievementCategory.STEPS -> Icons.AutoMirrored.Outlined.DirectionsWalk
	AchievementCategory.STREAKS -> Icons.Outlined.LocalFireDepartment
	AchievementCategory.MILESTONES -> Icons.Outlined.EmojiEvents
	AchievementCategory.MODES -> Icons.AutoMirrored.Outlined.DirectionsBike
	AchievementCategory.TIME -> Icons.Outlined.Schedule
	AchievementCategory.CALENDAR -> Icons.Outlined.CalendarMonth
}

/** Localized display label for the given achievement category. */
@Composable
internal fun categoryLabel(category: AchievementCategory): String = stringResource(
	when (category) {
		AchievementCategory.EXPLORATION -> R.string.achievement_category_exploration
		AchievementCategory.DISTANCE -> R.string.achievement_category_distance
		AchievementCategory.STEPS -> R.string.achievement_category_steps
		AchievementCategory.STREAKS -> R.string.achievement_category_streaks
		AchievementCategory.MILESTONES -> R.string.achievement_category_milestones
		AchievementCategory.MODES -> R.string.achievement_category_modes
		AchievementCategory.TIME -> R.string.achievement_category_time
		AchievementCategory.CALENDAR -> R.string.achievement_category_calendar
	},
)
