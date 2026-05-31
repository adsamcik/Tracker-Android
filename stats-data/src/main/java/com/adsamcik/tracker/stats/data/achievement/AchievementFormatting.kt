package com.adsamcik.tracker.stats.data.achievement

import android.content.Context
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.stats.api.AchievementDefinition
import com.adsamcik.tracker.stats.api.metric.MetricKey
import com.adsamcik.tracker.stats.data.R

/**
 * Programmatic formatter for achievement titles, descriptions, and value labels.
 *
 * The catalog defines 155 achievements across ~30 metric families. Instead of
 * writing 310 individual placeholder strings, titles and descriptions are
 * derived from the metric + threshold using a small set of format strings —
 * one per metric family. Localisable, future-proof when new thresholds are
 * added, and produces user-friendly copy out of the box.
 *
 * Lives in `:stats-data` (not `:game`) so consumers that don't depend on
 * `:game` — notably `:dashboard` — can call it directly instead of falling
 * back to humanizeResourceKey on a per-id resource lookup. See
 * `r8-dashboard-use-achievementformatting` for the migration context.
 *
 * # Compose seams
 *
 * This object is Compose-free on purpose; `:stats-data` is a data layer
 * module and must not pull in `androidx.compose.*`. UI modules should wrap
 * these `Context`-taking entry points behind their own `remember*`
 * composables; see for example `:game/AchievementFormatting.kt`'s
 * `rememberTitle`/`rememberDescription` wrappers.
 */
object AchievementFormatting {

	fun formatTitle(context: Context, def: AchievementDefinition): String {
		val res = context.resources
		val n = def.threshold.toInt()
		return when (def.metric) {
			MetricKey.DISTANCE_TOTAL_M -> context.getString(R.string.ach_title_distance_total, distance(res, def.threshold))
			MetricKey.WEEK_DISTANCE_M -> context.getString(R.string.ach_title_week_distance, distance(res, def.threshold))
			MetricKey.BEST_DAY_DISTANCE_M -> context.getString(R.string.ach_title_best_day_distance, distance(res, def.threshold))
			MetricKey.MAX_SESSION_DISTANCE_M -> context.getString(R.string.ach_title_max_session_distance, distance(res, def.threshold))
			MetricKey.DISTANCE_ON_FOOT_M -> context.getString(R.string.ach_title_on_foot_distance, distance(res, def.threshold))
			MetricKey.CYCLING_DISTANCE_M -> context.getString(R.string.ach_title_cycling_distance, distance(res, def.threshold))
			MetricKey.VEHICLE_DISTANCE_M -> context.getString(R.string.ach_title_vehicle_distance, distance(res, def.threshold))

			MetricKey.STEPS_TOTAL -> context.getString(R.string.ach_title_steps_total, formatCount(def.threshold.toLong()))
			MetricKey.BEST_DAILY_STEPS -> context.getString(R.string.ach_title_best_daily_steps, formatCount(def.threshold.toLong()))

			MetricKey.SESSIONS_TOTAL -> res.getQuantityString(R.plurals.ach_title_sessions_total, n, formatCount(def.threshold.toLong()))
			MetricKey.CELLS_DISTINCT_LIFETIME -> res.getQuantityString(R.plurals.ach_title_distinct_cells, n, formatCount(def.threshold.toLong()))
			MetricKey.COUNTRIES_VISITED -> res.getQuantityString(R.plurals.ach_title_countries_visited, n, n)

			MetricKey.ACTIVE_DAYS_TOTAL -> res.getQuantityString(R.plurals.ach_title_active_days, n, formatCount(def.threshold.toLong()))
			MetricKey.STREAK_DAYS_CURRENT -> res.getQuantityString(R.plurals.ach_title_current_streak, n, n)
			MetricKey.STREAK_DAYS_MAX -> res.getQuantityString(R.plurals.ach_title_best_streak, n, n)
			MetricKey.MONTHS_ACTIVE -> res.getQuantityString(R.plurals.ach_title_months_active, n, n)
			MetricKey.WEEK_ACTIVE_DAYS -> res.getQuantityString(R.plurals.ach_title_week_active_days, n, n)
			MetricKey.APP_AGE_DAYS -> res.getQuantityString(R.plurals.ach_title_app_age_days, n, formatCount(def.threshold.toLong()))
			MetricKey.COMEBACK_GAP_DAYS -> res.getQuantityString(R.plurals.ach_title_comeback_gap_days, n, n)
			MetricKey.HOURS_OF_DAY_TRACKED -> res.getQuantityString(R.plurals.ach_title_hours_of_day, n, n)
			MetricKey.DAYS_OF_WEEK_TRACKED -> context.getString(R.string.ach_title_days_of_week)

			MetricKey.ACTIVITY_TYPES_USED -> res.getQuantityString(R.plurals.ach_title_activity_types, n, n)
			MetricKey.WEEK_ACTIVITY_TYPES -> res.getQuantityString(R.plurals.ach_title_week_activity_types, n, n)
			MetricKey.WEEK_ALL_DAYS_TRACKED -> context.getString(R.string.ach_title_week_all_days)
			MetricKey.LIFETIME_WALK_CYCLE_DRIVE -> context.getString(R.string.ach_title_walk_cycle_drive)

			MetricKey.MAX_SESSION_DURATION_MS -> context.getString(R.string.ach_title_max_session_duration, formatDuration(context, def.threshold.toLong()))
			MetricKey.MAX_SPEED_MPS -> context.getString(R.string.ach_title_max_speed, formatSpeedKmh(def.threshold))
			MetricKey.ACTIVE_MINUTES_TOTAL -> context.getString(R.string.ach_title_active_minutes, formatCount(def.threshold.toLong()))

			MetricKey.EXPORTS_TOTAL -> res.getQuantityString(R.plurals.ach_title_exports_total, n, n)
			MetricKey.SEASONS_EXPLORED -> res.getQuantityString(R.plurals.ach_title_seasons_explored, n, n)

			MetricKey.CALENDAR_NEW_YEAR -> context.getString(R.string.ach_title_calendar_new_year)
			MetricKey.CALENDAR_LEAP_DAY -> context.getString(R.string.ach_title_calendar_leap_day)
			MetricKey.CALENDAR_SUMMER_SOLSTICE -> context.getString(R.string.ach_title_calendar_summer_solstice)
			MetricKey.CALENDAR_WINTER_SOLSTICE -> context.getString(R.string.ach_title_calendar_winter_solstice)
		}
	}

	fun formatDescription(context: Context, def: AchievementDefinition): String {
		val res = context.resources
		return when (def.metric) {
			MetricKey.DISTANCE_TOTAL_M -> context.getString(R.string.ach_desc_distance_total, distance(res, def.threshold))
			MetricKey.WEEK_DISTANCE_M -> context.getString(R.string.ach_desc_week_distance, distance(res, def.threshold))
			MetricKey.BEST_DAY_DISTANCE_M -> context.getString(R.string.ach_desc_best_day_distance, distance(res, def.threshold))
			MetricKey.MAX_SESSION_DISTANCE_M -> context.getString(R.string.ach_desc_max_session_distance, distance(res, def.threshold))
			MetricKey.DISTANCE_ON_FOOT_M -> context.getString(R.string.ach_desc_on_foot_distance, distance(res, def.threshold))
			MetricKey.CYCLING_DISTANCE_M -> context.getString(R.string.ach_desc_cycling_distance, distance(res, def.threshold))
			MetricKey.VEHICLE_DISTANCE_M -> context.getString(R.string.ach_desc_vehicle_distance, distance(res, def.threshold))

			MetricKey.STEPS_TOTAL -> context.getString(R.string.ach_desc_steps_total, formatCount(def.threshold.toLong()))
			MetricKey.BEST_DAILY_STEPS -> context.getString(R.string.ach_desc_best_daily_steps, formatCount(def.threshold.toLong()))

			MetricKey.SESSIONS_TOTAL -> context.getString(R.string.ach_desc_sessions_total, formatCount(def.threshold.toLong()))
			MetricKey.CELLS_DISTINCT_LIFETIME -> context.getString(R.string.ach_desc_distinct_cells, formatCount(def.threshold.toLong()))
			MetricKey.COUNTRIES_VISITED -> context.getString(R.string.ach_desc_countries_visited, def.threshold.toInt())

			MetricKey.ACTIVE_DAYS_TOTAL -> context.getString(R.string.ach_desc_active_days, formatCount(def.threshold.toLong()))
			MetricKey.STREAK_DAYS_CURRENT -> context.getString(R.string.ach_desc_current_streak, def.threshold.toInt())
			MetricKey.STREAK_DAYS_MAX -> context.getString(R.string.ach_desc_best_streak, def.threshold.toInt())
			MetricKey.MONTHS_ACTIVE -> context.getString(R.string.ach_desc_months_active, def.threshold.toInt())
			MetricKey.WEEK_ACTIVE_DAYS -> context.getString(R.string.ach_desc_week_active_days, def.threshold.toInt())
			MetricKey.APP_AGE_DAYS -> context.getString(R.string.ach_desc_app_age_days, formatCount(def.threshold.toLong()))
			MetricKey.COMEBACK_GAP_DAYS -> context.getString(R.string.ach_desc_comeback_gap_days, def.threshold.toInt())
			MetricKey.HOURS_OF_DAY_TRACKED -> context.getString(R.string.ach_desc_hours_of_day, def.threshold.toInt())
			MetricKey.DAYS_OF_WEEK_TRACKED -> context.getString(R.string.ach_desc_days_of_week)

			MetricKey.ACTIVITY_TYPES_USED -> context.getString(R.string.ach_desc_activity_types, def.threshold.toInt())
			MetricKey.WEEK_ACTIVITY_TYPES -> context.getString(R.string.ach_desc_week_activity_types, def.threshold.toInt())
			MetricKey.WEEK_ALL_DAYS_TRACKED -> context.getString(R.string.ach_desc_week_all_days)
			MetricKey.LIFETIME_WALK_CYCLE_DRIVE -> context.getString(R.string.ach_desc_walk_cycle_drive)

			MetricKey.MAX_SESSION_DURATION_MS -> context.getString(R.string.ach_desc_max_session_duration, formatDuration(context, def.threshold.toLong()))
			MetricKey.MAX_SPEED_MPS -> context.getString(R.string.ach_desc_max_speed, formatSpeedKmh(def.threshold))
			MetricKey.ACTIVE_MINUTES_TOTAL -> context.getString(R.string.ach_desc_active_minutes, formatCount(def.threshold.toLong()))

			MetricKey.EXPORTS_TOTAL -> context.getString(R.string.ach_desc_exports_total, def.threshold.toInt())
			MetricKey.SEASONS_EXPLORED -> context.getString(R.string.ach_desc_seasons_explored, def.threshold.toInt())

			MetricKey.CALENDAR_NEW_YEAR -> context.getString(R.string.ach_desc_calendar_new_year)
			MetricKey.CALENDAR_LEAP_DAY -> context.getString(R.string.ach_desc_calendar_leap_day)
			MetricKey.CALENDAR_SUMMER_SOLSTICE -> context.getString(R.string.ach_desc_calendar_summer_solstice)
			MetricKey.CALENDAR_WINTER_SOLSTICE -> context.getString(R.string.ach_desc_calendar_winter_solstice)
		}
	}

	/** Returns "current / target unit" for an in-progress achievement. */
	fun formatValueLabel(context: Context, current: Double, def: AchievementDefinition): String {
		val res = context.resources
		val target = def.threshold
		return when (def.metric) {
			MetricKey.DISTANCE_TOTAL_M,
			MetricKey.WEEK_DISTANCE_M,
			MetricKey.BEST_DAY_DISTANCE_M,
			MetricKey.MAX_SESSION_DISTANCE_M,
			MetricKey.DISTANCE_ON_FOOT_M,
			MetricKey.CYCLING_DISTANCE_M,
			MetricKey.VEHICLE_DISTANCE_M -> "${distance(res, current.coerceAtMost(target))} / ${distance(res, target)}"

			MetricKey.MAX_SESSION_DURATION_MS -> "${formatDuration(context, current.toLong().coerceAtMost(target.toLong()))} / ${formatDuration(context, target.toLong())}"
			MetricKey.MAX_SPEED_MPS -> "${formatSpeedKmh(current.coerceAtMost(target))} / ${formatSpeedKmh(target)}"

			else -> "${formatCount(current.toLong().coerceAtMost(target.toLong()))} / ${formatCount(target.toLong())}"
		}
	}

	// ─── primitive formatters ────────────────────────────────────────────

	private fun distance(res: android.content.res.Resources, meters: Double): String =
		res.formatDistance(meters, digits = 1, unit = LengthSystem.Metric)

	private fun formatCount(value: Long): String = java.text.NumberFormat.getIntegerInstance().format(value)

	private fun formatSpeedKmh(metersPerSecond: Double): String {
		val kmh = (metersPerSecond * SECONDS_PER_HOUR_FACTOR).toInt()
		return "$kmh km/h"
	}

	private fun formatDuration(context: Context, ms: Long): String {
		val totalSeconds = ms / MS_PER_SECOND
		val totalMinutes = totalSeconds / SECONDS_PER_MINUTE
		val totalHours = totalMinutes / MINUTES_PER_HOUR
		val totalDays = totalHours / HOURS_PER_DAY
		return when {
			totalDays >= 1 -> context.resources.getQuantityString(R.plurals.ach_duration_days, totalDays.toInt(), totalDays.toInt())
			totalHours >= 1 -> context.resources.getQuantityString(R.plurals.ach_duration_hours, totalHours.toInt(), totalHours.toInt())
			totalMinutes >= 1 -> context.resources.getQuantityString(R.plurals.ach_duration_minutes, totalMinutes.toInt(), totalMinutes.toInt())
			else -> context.resources.getQuantityString(R.plurals.ach_duration_seconds, totalSeconds.toInt(), totalSeconds.toInt())
		}
	}

	private const val MS_PER_SECOND = 1_000L
	private const val SECONDS_PER_MINUTE = 60L
	private const val MINUTES_PER_HOUR = 60L
	private const val HOURS_PER_DAY = 24L
	private const val SECONDS_PER_HOUR_FACTOR = 3.6
}
