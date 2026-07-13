package com.adsamcik.tracker.game.ui.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.metric.MetricKey

@Composable
internal fun AchievementArtworkIcon(
	definition: AchievementDefinition,
	isUnlocked: Boolean,
	modifier: Modifier = Modifier.size(48.dp),
) {
	val spec = remember(definition.id) { achievementPictogramSpec(definition) }
	val tierAccent = tierColor(definition.tier)
	val categoryAccent = categoryColor(definition.category)
	val surface = MaterialTheme.colorScheme.surfaceContainerHighest
	val alpha = if (isUnlocked) 1f else LOCKED_PICTOGRAM_ALPHA

	Box(
		modifier = modifier
			.alpha(alpha)
			.clip(spec.containerShape)
			.background(
				Brush.linearGradient(
					colors = listOf(
						tierAccent.copy(alpha = if (isUnlocked) 0.26f else 0.10f),
						categoryAccent.copy(alpha = if (isUnlocked) 0.18f else 0.08f),
						surface.copy(alpha = 0.88f),
					),
				),
			)
			.border(
				width = 1.5.dp,
				color = tierAccent.copy(alpha = if (isUnlocked) 0.72f else 0.30f),
				shape = spec.containerShape,
			),
		contentAlignment = Alignment.Center,
	) {
		Image(
			painter = painterResource(spec.pictogramRes),
			contentDescription = null,
			modifier = Modifier.matchParentSize().padding(2.dp),
			contentScale = ContentScale.Fit,
		)
		VariantTicks(
			count = spec.variantTickCount,
			color = tierAccent,
			isUnlocked = isUnlocked,
			modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp, start = 8.dp, end = 8.dp),
		)
	}
}

@Composable
private fun VariantTicks(
	count: Int,
	color: Color,
	isUnlocked: Boolean,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.Center,
		verticalAlignment = Alignment.CenterVertically,
	) {
		repeat(count) {
			Box(
				modifier = Modifier
					.padding(horizontal = 1.dp)
					.size(width = 4.dp, height = 2.dp)
					.clip(RoundedCornerShape(1.dp))
					.background(color.copy(alpha = if (isUnlocked) 0.86f else 0.40f)),
			)
		}
	}
}

internal data class AchievementPictogramSpec(
	@DrawableRes val pictogramRes: Int,
	val containerShape: Shape,
	val variantTickCount: Int,
	val signature: String,
)

internal fun achievementPictogramSpec(definition: AchievementDefinition): AchievementPictogramSpec {
	return AchievementPictogramSpec(
		pictogramRes = metricPictogramRes(definition.metric),
		containerShape = expressiveShape(definition.metric, definition.tier),
		variantTickCount = (definition.tierIndex % MAX_VARIANT_TICKS) + 1,
		signature = "${definition.metric.storageKey}:${definition.tier.name}:${definition.tierIndex}",
	)
}

@DrawableRes
private fun metricPictogramRes(metric: MetricKey): Int = when (metric) {
	MetricKey.DISTANCE_TOTAL_M -> R.drawable.achievement_pictogram_distance_total_m
	MetricKey.STEPS_TOTAL -> R.drawable.achievement_pictogram_steps_total
	MetricKey.ACTIVE_DAYS_TOTAL,
	MetricKey.ON_FOOT_ACTIVE_DAYS,
	MetricKey.CYCLING_ACTIVE_DAYS,
	MetricKey.VEHICLE_ACTIVE_DAYS -> R.drawable.achievement_pictogram_active_days_total
	MetricKey.SESSIONS_TOTAL -> R.drawable.achievement_pictogram_sessions_total
	MetricKey.CELLS_DISTINCT_LIFETIME -> R.drawable.achievement_pictogram_cells_distinct_lifetime
	MetricKey.MAX_SESSION_DISTANCE_M -> R.drawable.achievement_pictogram_max_session_distance_m
	MetricKey.MAX_SESSION_DURATION_MS -> R.drawable.achievement_pictogram_max_session_duration_ms
	MetricKey.MAX_SPEED_MPS -> R.drawable.achievement_pictogram_max_speed_mps
	MetricKey.STREAK_DAYS_CURRENT -> R.drawable.achievement_pictogram_streak_days_current
	MetricKey.STREAK_DAYS_MAX -> R.drawable.achievement_pictogram_streak_days_max
	MetricKey.COUNTRIES_VISITED -> R.drawable.achievement_pictogram_countries_visited
	MetricKey.ACTIVITY_TYPES_USED -> R.drawable.achievement_pictogram_activity_types_used
	MetricKey.MONTHS_ACTIVE -> R.drawable.achievement_pictogram_months_active
	MetricKey.HOURS_OF_DAY_TRACKED -> R.drawable.achievement_pictogram_hours_of_day_tracked
	MetricKey.DAYS_OF_WEEK_TRACKED -> R.drawable.achievement_pictogram_days_of_week_tracked
	MetricKey.APP_AGE_DAYS -> R.drawable.achievement_pictogram_app_age_days
	MetricKey.COMEBACK_GAP_DAYS -> R.drawable.achievement_pictogram_comeback_gap_days
	MetricKey.CALENDAR_NEW_YEAR -> R.drawable.achievement_pictogram_calendar_new_year
	MetricKey.CALENDAR_LEAP_DAY -> R.drawable.achievement_pictogram_calendar_leap_day
	MetricKey.CALENDAR_SUMMER_SOLSTICE -> R.drawable.achievement_pictogram_calendar_summer_solstice
	MetricKey.CALENDAR_WINTER_SOLSTICE -> R.drawable.achievement_pictogram_calendar_winter_solstice
	MetricKey.WEEK_DISTANCE_M -> R.drawable.achievement_pictogram_week_distance_m
	MetricKey.WEEK_ACTIVE_DAYS -> R.drawable.achievement_pictogram_week_active_days
	MetricKey.WEEK_ALL_DAYS_TRACKED -> R.drawable.achievement_pictogram_week_all_days_tracked
	MetricKey.WEEK_ACTIVITY_TYPES -> R.drawable.achievement_pictogram_week_activity_types
	MetricKey.LIFETIME_WALK_CYCLE_DRIVE -> R.drawable.achievement_pictogram_lifetime_walk_cycle_drive
	MetricKey.ACTIVE_MINUTES_TOTAL -> R.drawable.achievement_pictogram_active_minutes_total
	MetricKey.DISTANCE_ON_FOOT_M -> R.drawable.achievement_pictogram_distance_on_foot_m
	MetricKey.CYCLING_DISTANCE_M -> R.drawable.achievement_pictogram_cycling_distance_m
	MetricKey.VEHICLE_DISTANCE_M -> R.drawable.achievement_pictogram_vehicle_distance_m
	MetricKey.BEST_DAILY_STEPS -> R.drawable.achievement_pictogram_best_daily_steps
	MetricKey.BEST_DAY_DISTANCE_M -> R.drawable.achievement_pictogram_best_day_distance_m
	MetricKey.EXPORTS_TOTAL -> R.drawable.achievement_pictogram_exports_total
	MetricKey.SEASONS_EXPLORED -> R.drawable.achievement_pictogram_seasons_explored
	MetricKey.NIGHT_SESSIONS_TOTAL -> R.drawable.achievement_pictogram_night_sessions_total
	MetricKey.DAWN_SESSIONS_TOTAL -> R.drawable.achievement_pictogram_dawn_sessions_total
	MetricKey.USER_CREATED_SESSIONS -> R.drawable.achievement_pictogram_user_created_sessions
	MetricKey.MAX_CYCLE_SESSION_M -> R.drawable.achievement_pictogram_max_cycle_session_m
	MetricKey.TRIATHLON_DAYS -> R.drawable.achievement_pictogram_triathlon_days
	MetricKey.CELL_ALL_SEASONS -> R.drawable.achievement_pictogram_cell_all_seasons
	MetricKey.MAX_CELL_VISITS -> R.drawable.achievement_pictogram_max_cell_visits
	MetricKey.CELLS_THOROUGH -> R.drawable.achievement_pictogram_cells_thorough
	MetricKey.MAX_CELL_SPAN_M -> R.drawable.achievement_pictogram_max_cell_span_m
	MetricKey.MAX_CELLS_IN_DAY -> R.drawable.achievement_pictogram_max_cells_in_day
	MetricKey.MAX_CELL_REVISIT_GAP_DAYS -> R.drawable.achievement_pictogram_max_cell_revisit_gap_days
	MetricKey.PERFECT_MONTHS -> R.drawable.achievement_pictogram_perfect_months
	MetricKey.EXPORT_FORMATS -> R.drawable.achievement_pictogram_export_formats
	MetricKey.PLAYER_LEVEL -> R.drawable.achievement_pictogram_player_level
	MetricKey.BEST_DAY_XP -> R.drawable.achievement_pictogram_best_day_xp
	MetricKey.MINIGAMES_PLAYED -> R.drawable.achievement_pictogram_minigames_played
	MetricKey.TOTAL_ASCENT_M -> R.drawable.achievement_pictogram_total_ascent_m
	MetricKey.XP_SOURCES_USED -> R.drawable.achievement_pictogram_xp_sources_used
	MetricKey.PERFECT_WEEKS -> R.drawable.achievement_pictogram_perfect_weeks
	MetricKey.GOAL_STREAK_DAYS -> R.drawable.achievement_pictogram_streak_days_max
	MetricKey.ACHIEVEMENTS_UNLOCKED -> R.drawable.achievement_pictogram_seasons_explored
	MetricKey.CATEGORIES_COMPLETED -> R.drawable.achievement_pictogram_cells_thorough
}

private fun expressiveShape(metric: MetricKey, tier: AchievementTier): Shape {
	val index = (metric.ordinal + tier.ordinal) % EXPRESSIVE_SHAPE_COUNT
	return when (index) {
		0 -> RoundedCornerShape(14.dp, 14.dp, 14.dp, 14.dp)
		1 -> RoundedCornerShape(16.dp, 8.dp, 16.dp, 8.dp)
		2 -> RoundedCornerShape(8.dp, 16.dp, 8.dp, 16.dp)
		3 -> CutCornerShape(topStart = 10.dp, topEnd = 4.dp, bottomEnd = 10.dp, bottomStart = 4.dp)
		4 -> CutCornerShape(topStart = 4.dp, topEnd = 10.dp, bottomEnd = 4.dp, bottomStart = 10.dp)
		else -> CircleShape
	}
}

private const val LOCKED_PICTOGRAM_ALPHA = 0.52f
private const val MAX_VARIANT_TICKS = 5
private const val EXPRESSIVE_SHAPE_COUNT = 6
