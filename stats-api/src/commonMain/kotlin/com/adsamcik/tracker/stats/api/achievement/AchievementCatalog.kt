package com.adsamcik.tracker.stats.api.achievement

import com.adsamcik.tracker.stats.api.AchievementCategory
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.metric.MetricKey

object AchievementCatalog {
	val definitions: List<AchievementDefinition> = buildList {
		series(MetricKey.DISTANCE_TOTAL_M, AchievementCategory.DISTANCE, "total_distance", listOf(1_000.0, 5_000.0, 10_000.0, 50_000.0, 100_000.0, 500_000.0, 1_000_000.0, 5_000_000.0, 10_000_000.0, 50_000_000.0, 100_000_000.0))
		series(MetricKey.STEPS_TOTAL, AchievementCategory.STEPS, "total_steps", listOf(1_000.0, 10_000.0, 100_000.0, 1_000_000.0, 10_000_000.0, 100_000_000.0, 1_000_000_000.0))
		series(MetricKey.ACTIVE_DAYS_TOTAL, AchievementCategory.STREAKS, "active_days", listOf(1.0, 7.0, 30.0, 90.0, 180.0, 365.0, 730.0, 1825.0, 3650.0))
		series(MetricKey.SESSIONS_TOTAL, AchievementCategory.MILESTONES, "sessions_total", listOf(1.0, 10.0, 100.0, 1_000.0, 10_000.0))
		series(MetricKey.CELLS_DISTINCT_LIFETIME, AchievementCategory.EXPLORATION, "distinct_cells", listOf(10.0, 100.0, 1_000.0, 10_000.0, 100_000.0, 1_000_000.0))
		series(MetricKey.MAX_SESSION_DISTANCE_M, AchievementCategory.DISTANCE, "max_session_distance", listOf(1_000.0, 5_000.0, 10_000.0, 50_000.0, 100_000.0, 500_000.0))
		series(MetricKey.MAX_SESSION_DURATION_MS, AchievementCategory.TIME, "max_session_duration", listOf(1_800_000.0, 3_600_000.0, 14_400_000.0, 43_200_000.0, 86_400_000.0, 604_800_000.0))
		series(MetricKey.MAX_SPEED_MPS, AchievementCategory.DISTANCE, "max_speed", listOf(1.4, 3.0, 5.5, 28.0, 50.0, 100.0))
		series(MetricKey.STREAK_DAYS_CURRENT, AchievementCategory.STREAKS, "current_streak", listOf(3.0, 7.0, 30.0, 100.0, 365.0, 1000.0))
		series(MetricKey.STREAK_DAYS_MAX, AchievementCategory.STREAKS, "best_streak", listOf(7.0, 30.0, 100.0, 365.0, 1000.0, 3650.0))
		series(MetricKey.COUNTRIES_VISITED, AchievementCategory.EXPLORATION, "countries_visited", listOf(1.0, 3.0, 5.0, 10.0, 25.0, 50.0, 100.0))
		series(MetricKey.ACTIVITY_TYPES_USED, AchievementCategory.MODES, "activity_types", listOf(1.0, 2.0, 3.0, 4.0, 5.0))
		series(MetricKey.MONTHS_ACTIVE, AchievementCategory.CALENDAR, "months_active", listOf(1.0, 3.0, 6.0, 12.0, 24.0, 60.0))
		series(MetricKey.HOURS_OF_DAY_TRACKED, AchievementCategory.TIME, "hours_of_day", listOf(6.0, 12.0, 18.0, 24.0))
		series(MetricKey.DAYS_OF_WEEK_TRACKED, AchievementCategory.CALENDAR, "days_of_week", listOf(7.0))
		series(MetricKey.APP_AGE_DAYS, AchievementCategory.MILESTONES, "app_age", listOf(30.0, 100.0, 365.0, 730.0, 1825.0, 3650.0))
		series(MetricKey.COMEBACK_GAP_DAYS, AchievementCategory.MILESTONES, "comeback", listOf(30.0, 90.0, 365.0))
		series(MetricKey.CALENDAR_NEW_YEAR, AchievementCategory.CALENDAR, "calendar_new_year", listOf(1.0))
		series(MetricKey.CALENDAR_LEAP_DAY, AchievementCategory.CALENDAR, "calendar_leap_day", listOf(1.0))
		series(MetricKey.CALENDAR_SUMMER_SOLSTICE, AchievementCategory.CALENDAR, "calendar_summer_solstice", listOf(1.0))
		series(MetricKey.CALENDAR_WINTER_SOLSTICE, AchievementCategory.CALENDAR, "calendar_winter_solstice", listOf(1.0))
		series(MetricKey.WEEK_DISTANCE_M, AchievementCategory.DISTANCE, "week_distance", listOf(10_000.0, 50_000.0, 100_000.0, 500_000.0))
		series(MetricKey.WEEK_ACTIVE_DAYS, AchievementCategory.STREAKS, "week_active_days", listOf(3.0, 5.0, 7.0))
		series(MetricKey.WEEK_ALL_DAYS_TRACKED, AchievementCategory.STREAKS, "week_all_days", listOf(1.0), isCompound = true)
		series(MetricKey.WEEK_ACTIVITY_TYPES, AchievementCategory.MODES, "week_activity_types", listOf(3.0), isCompound = true)
		series(MetricKey.LIFETIME_WALK_CYCLE_DRIVE, AchievementCategory.MODES, "walk_cycle_drive", listOf(1.0), isCompound = true)
		series(MetricKey.ACTIVE_MINUTES_TOTAL, AchievementCategory.TIME, "active_minutes", listOf(10.0, 60.0, 600.0, 6_000.0, 60_000.0, 600_000.0))
		series(MetricKey.DISTANCE_ON_FOOT_M, AchievementCategory.MODES, "on_foot_distance", listOf(1_000.0, 10_000.0, 42_195.0, 100_000.0, 500_000.0, 1_000_000.0, 5_000_000.0))
		series(MetricKey.CYCLING_DISTANCE_M, AchievementCategory.MODES, "cycling_distance", listOf(1_000.0, 10_000.0, 50_000.0, 100_000.0, 500_000.0, 1_000_000.0))
		series(MetricKey.VEHICLE_DISTANCE_M, AchievementCategory.MODES, "vehicle_distance", listOf(10_000.0, 100_000.0, 1_000_000.0, 10_000_000.0))
		series(MetricKey.BEST_DAILY_STEPS, AchievementCategory.STEPS, "best_daily_steps", listOf(5_000.0, 10_000.0, 20_000.0, 40_000.0, 60_000.0))
		series(MetricKey.BEST_DAY_DISTANCE_M, AchievementCategory.DISTANCE, "best_day_distance", listOf(1_000.0, 10_000.0, 42_195.0, 100_000.0, 500_000.0))
		series(MetricKey.EXPORTS_TOTAL, AchievementCategory.MILESTONES, "exports_total", listOf(1.0, 5.0, 25.0, 100.0))
		series(MetricKey.SEASONS_EXPLORED, AchievementCategory.EXPLORATION, "seasons_explored", listOf(1.0, 2.0, 3.0, 4.0))
	}
	private val byIdMap = definitions.associateBy { it.id }
	private val byCategoryMap = definitions.groupBy { it.category }
	val byMetric: Map<MetricKey, List<AchievementDefinition>> = definitions.groupBy { it.metric }.mapValues { (_, v) -> v.sortedBy { it.threshold } }
	val compoundRules: List<AchievementDefinition> = definitions.filter { it.isCompound }
	fun byId(id: String): AchievementDefinition? = byIdMap[id]
	fun byCategory(category: AchievementCategory): List<AchievementDefinition> = byCategoryMap[category].orEmpty()
	fun byMetric(metric: MetricKey): List<AchievementDefinition> = byMetric[metric].orEmpty()
	fun byMetric(metric: String): List<AchievementDefinition> = MetricKey.fromStorageKey(metric)?.let(::byMetric).orEmpty()
	private fun MutableList<AchievementDefinition>.series(metric: MetricKey, category: AchievementCategory, baseId: String, thresholds: List<Double>, isCompound: Boolean = false) {
		thresholds.forEachIndexed { index, threshold ->
			val id = "${baseId}_${threshold.token()}"
			add(AchievementDefinition(id, category, "achievement_${id}_title", "achievement_${id}_desc", metric, threshold, tierFor(index, thresholds.size), index, isCompound))
		}
	}
	private fun tierFor(index: Int, count: Int): AchievementTier = when ((index * 5) / count.coerceAtLeast(1)) {
		0 -> AchievementTier.BRONZE
		1 -> AchievementTier.SILVER
		2 -> AchievementTier.GOLD
		3 -> AchievementTier.DIAMOND
		else -> AchievementTier.MYTHIC
	}
	private fun Double.token(): String = toLong().takeIf { kotlin.math.abs(this - it.toDouble()) < 0.0001 }?.toString() ?: toString().replace('.', '_')
}
