package com.adsamcik.tracker.stats.api.metric

const val TABLE_DAILY_SUMMARY_NAME = "daily_summary"
const val TABLE_SESSION_SEGMENT_NAME = "session_segment"
const val TABLE_EXPLORATION_CELL_NAME = "exploration_cell"
const val TABLE_EXPLORATION_STREAK_NAME = "exploration_streak"
const val TABLE_EXPORT_LOG_NAME = "export_log"
const val TABLE_AGGREGATOR_STATE_NAME = "aggregator_state"
const val TABLE_OSM_IMPORT_NAME = "osm_import"
const val TABLE_XP_LEDGER_NAME = "xp_ledger"
const val TABLE_PLAYER_PROFILE_NAME = "player_profile"
const val TABLE_MINI_GAME_SCORE_NAME = "minigame_score"
const val TABLE_ACHIEVEMENT_PROGRESS_NAME = "achievement_progress"

enum class MetricKey(val storageKey: String, private vararg val backingTables: String) {
	DISTANCE_TOTAL_M("distance_total_m", TABLE_DAILY_SUMMARY_NAME, TABLE_AGGREGATOR_STATE_NAME),
	STEPS_TOTAL("steps_total", TABLE_DAILY_SUMMARY_NAME, TABLE_AGGREGATOR_STATE_NAME),
	ACTIVE_DAYS_TOTAL("active_days_total", TABLE_DAILY_SUMMARY_NAME),
	SESSIONS_TOTAL("sessions_total", TABLE_DAILY_SUMMARY_NAME, TABLE_AGGREGATOR_STATE_NAME),
	CELLS_DISTINCT_LIFETIME("cells_distinct_lifetime", TABLE_EXPLORATION_CELL_NAME),
	MAX_SESSION_DISTANCE_M("max_session_distance_m", TABLE_SESSION_SEGMENT_NAME, TABLE_AGGREGATOR_STATE_NAME),
	MAX_SESSION_DURATION_MS("max_session_duration_ms", TABLE_SESSION_SEGMENT_NAME),
	MAX_SPEED_MPS("max_speed_mps", TABLE_SESSION_SEGMENT_NAME),
	STREAK_DAYS_CURRENT("streak_days_current", TABLE_EXPLORATION_STREAK_NAME),
	STREAK_DAYS_MAX("streak_days_max", TABLE_EXPLORATION_STREAK_NAME),
	COUNTRIES_VISITED("countries_visited", TABLE_EXPLORATION_CELL_NAME),
	ACTIVITY_TYPES_USED("activity_types_used", TABLE_SESSION_SEGMENT_NAME),
	MONTHS_ACTIVE("months_active", TABLE_DAILY_SUMMARY_NAME),
	HOURS_OF_DAY_TRACKED("hours_of_day_tracked", TABLE_SESSION_SEGMENT_NAME),
	DAYS_OF_WEEK_TRACKED("days_of_week_tracked", TABLE_DAILY_SUMMARY_NAME),
	APP_AGE_DAYS("app_age_days", TABLE_DAILY_SUMMARY_NAME, TABLE_SESSION_SEGMENT_NAME),
	COMEBACK_GAP_DAYS("comeback_gap_days", TABLE_DAILY_SUMMARY_NAME),
	CALENDAR_NEW_YEAR("calendar_new_year", TABLE_DAILY_SUMMARY_NAME),
	CALENDAR_LEAP_DAY("calendar_leap_day", TABLE_DAILY_SUMMARY_NAME),
	CALENDAR_SUMMER_SOLSTICE("calendar_summer_solstice", TABLE_DAILY_SUMMARY_NAME),
	CALENDAR_WINTER_SOLSTICE("calendar_winter_solstice", TABLE_DAILY_SUMMARY_NAME),
	WEEK_DISTANCE_M("week_distance_m", TABLE_DAILY_SUMMARY_NAME),
	WEEK_ACTIVE_DAYS("week_active_days", TABLE_DAILY_SUMMARY_NAME),
	WEEK_ALL_DAYS_TRACKED("week_all_days_tracked", TABLE_DAILY_SUMMARY_NAME),
	WEEK_ACTIVITY_TYPES("week_activity_types", TABLE_SESSION_SEGMENT_NAME),
	LIFETIME_WALK_CYCLE_DRIVE("lifetime_walk_cycle_drive", TABLE_SESSION_SEGMENT_NAME),
	ACTIVE_MINUTES_TOTAL("active_minutes_total", TABLE_DAILY_SUMMARY_NAME),
	DISTANCE_ON_FOOT_M("distance_on_foot_m", TABLE_SESSION_SEGMENT_NAME),
	CYCLING_DISTANCE_M("cycling_distance_m", TABLE_SESSION_SEGMENT_NAME),
	VEHICLE_DISTANCE_M("vehicle_distance_m", TABLE_SESSION_SEGMENT_NAME),
	BEST_DAILY_STEPS("best_daily_steps", TABLE_DAILY_SUMMARY_NAME, TABLE_AGGREGATOR_STATE_NAME),
	BEST_DAY_DISTANCE_M("best_day_distance_m", TABLE_DAILY_SUMMARY_NAME),
	EXPORTS_TOTAL("exports_total", TABLE_EXPORT_LOG_NAME),
	SEASONS_EXPLORED("seasons_explored", TABLE_EXPLORATION_CELL_NAME),
	NIGHT_SESSIONS_TOTAL("night_sessions_total", TABLE_SESSION_SEGMENT_NAME),
	DAWN_SESSIONS_TOTAL("dawn_sessions_total", TABLE_SESSION_SEGMENT_NAME),
	USER_CREATED_SESSIONS("user_created_sessions", TABLE_SESSION_SEGMENT_NAME),
	MAX_CYCLE_SESSION_M("max_cycle_session_m", TABLE_SESSION_SEGMENT_NAME),
	TRIATHLON_DAYS("triathlon_days", TABLE_SESSION_SEGMENT_NAME),
	CELL_ALL_SEASONS("cell_all_seasons", TABLE_EXPLORATION_CELL_NAME),
	MAX_CELL_VISITS("max_cell_visits", TABLE_EXPLORATION_CELL_NAME),
	CELLS_THOROUGH("cells_thorough", TABLE_EXPLORATION_CELL_NAME),
	MAX_CELL_SPAN_M("max_cell_span_m", TABLE_EXPLORATION_CELL_NAME),
	MAX_CELLS_IN_DAY("max_cells_in_day", TABLE_EXPLORATION_CELL_NAME),
	MAX_CELL_REVISIT_GAP_DAYS("max_cell_revisit_gap_days", TABLE_EXPLORATION_CELL_NAME),
	PERFECT_MONTHS("perfect_months", TABLE_DAILY_SUMMARY_NAME),
	EXPORT_FORMATS("export_formats", TABLE_EXPORT_LOG_NAME),
	PLAYER_LEVEL("player_level", TABLE_PLAYER_PROFILE_NAME),
	BEST_DAY_XP("best_day_xp", TABLE_XP_LEDGER_NAME),
	MINIGAMES_PLAYED("minigames_played", TABLE_MINI_GAME_SCORE_NAME),
	TOTAL_ASCENT_M("total_ascent_m", TABLE_DAILY_SUMMARY_NAME),
	XP_SOURCES_USED("xp_sources_used", TABLE_XP_LEDGER_NAME),
	PERFECT_WEEKS("perfect_weeks", TABLE_XP_LEDGER_NAME),
	GOAL_STREAK_DAYS("goal_streak_days", TABLE_XP_LEDGER_NAME),
	ACHIEVEMENTS_UNLOCKED("achievements_unlocked", TABLE_ACHIEVEMENT_PROGRESS_NAME),
	CATEGORIES_COMPLETED("categories_completed", TABLE_ACHIEVEMENT_PROGRESS_NAME);

	val sourceTables: Set<String> = backingTables.toSet()
	companion object {
		private val byStorageKey: Map<String, MetricKey> = entries.associateBy { it.storageKey }
		fun fromStorageKey(storageKey: String): MetricKey? = byStorageKey[storageKey]
	}
}

object MetricKeys {
	const val TABLE_DAILY_SUMMARY = TABLE_DAILY_SUMMARY_NAME
	const val TABLE_SESSION_SEGMENT = TABLE_SESSION_SEGMENT_NAME
	const val TABLE_EXPLORATION_CELL = TABLE_EXPLORATION_CELL_NAME
	const val TABLE_EXPLORATION_STREAK = TABLE_EXPLORATION_STREAK_NAME
	const val TABLE_EXPORT_LOG = TABLE_EXPORT_LOG_NAME
	const val TABLE_AGGREGATOR_STATE = TABLE_AGGREGATOR_STATE_NAME
	const val TABLE_OSM_IMPORT = TABLE_OSM_IMPORT_NAME
	const val TABLE_XP_LEDGER = TABLE_XP_LEDGER_NAME
	const val TABLE_PLAYER_PROFILE = TABLE_PLAYER_PROFILE_NAME
	const val TABLE_MINI_GAME_SCORE = TABLE_MINI_GAME_SCORE_NAME
	const val TABLE_ACHIEVEMENT_PROGRESS = TABLE_ACHIEVEMENT_PROGRESS_NAME
	const val MIN_DAILY_TRIPS = 1
	const val STEPS = "steps"
	const val DISTANCE_M = "distance_m"
	const val ACTIVE_MINUTES = "active_minutes"
	const val CELLS_DISCOVERED = "cells_discovered"
	const val UNIQUE_AREAS = "unique_areas"
	const val DAILY_STREAK = "daily_streak"
	const val WEEKLY_STREAK = "weekly_streak"
	const val WALKING_TRIPS = "walking_trips"
	const val CYCLING_TRIPS = "cycling_trips"
	const val LONGEST_TRIP_KM = "longest_trip_km"
	const val TOTAL_EXPORTS = "total_exports"
	const val DISTANCE_TOTAL_M = "distance_total_m"
	const val STEPS_TOTAL = "steps_total"
	const val ACTIVE_DAYS_TOTAL = "active_days_total"
	const val SESSIONS_TOTAL = "sessions_total"
	const val CELLS_DISTINCT_LIFETIME = "cells_distinct_lifetime"
	const val MAX_SESSION_DISTANCE_M = "max_session_distance_m"
	const val MAX_SESSION_DURATION_MS = "max_session_duration_ms"
	const val MAX_SPEED_MPS = "max_speed_mps"
	const val STREAK_DAYS_CURRENT = "streak_days_current"
	const val STREAK_DAYS_MAX = "streak_days_max"
	const val COUNTRIES_VISITED = "countries_visited"
	const val ACTIVITY_TYPES_USED = "activity_types_used"
	const val MONTHS_ACTIVE = "months_active"
	const val HOURS_OF_DAY_TRACKED = "hours_of_day_tracked"
	const val DAYS_OF_WEEK_TRACKED = "days_of_week_tracked"
	const val APP_AGE_DAYS = "app_age_days"
	const val COMEBACK_GAP_DAYS = "comeback_gap_days"
	const val CALENDAR_NEW_YEAR = "calendar_new_year"
	const val CALENDAR_LEAP_DAY = "calendar_leap_day"
	const val CALENDAR_SUMMER_SOLSTICE = "calendar_summer_solstice"
	const val CALENDAR_WINTER_SOLSTICE = "calendar_winter_solstice"
	const val WEEK_DISTANCE_M = "week_distance_m"
	const val WEEK_ACTIVE_DAYS = "week_active_days"
	const val WEEK_ALL_DAYS_TRACKED = "week_all_days_tracked"
	const val WEEK_ACTIVITY_TYPES = "week_activity_types"
	const val LIFETIME_WALK_CYCLE_DRIVE = "lifetime_walk_cycle_drive"
	const val ACTIVE_MINUTES_TOTAL = "active_minutes_total"
	const val DISTANCE_ON_FOOT_M = "distance_on_foot_m"
	const val CYCLING_DISTANCE_M = "cycling_distance_m"
	const val VEHICLE_DISTANCE_M = "vehicle_distance_m"
	const val BEST_DAILY_STEPS = "best_daily_steps"
	const val BEST_DAY_DISTANCE_M = "best_day_distance_m"
	const val EXPORTS_TOTAL = "exports_total"
	const val SEASONS_EXPLORED = "seasons_explored"
	@Deprecated("Use MetricKey.STEPS_TOTAL.storageKey") const val TOTAL_STEPS = STEPS_TOTAL
	@Deprecated("Use MetricKey.DISTANCE_TOTAL_M.storageKey") const val TOTAL_DISTANCE_KM = DISTANCE_TOTAL_M
	@Deprecated("Use MetricKey.SESSIONS_TOTAL.storageKey") const val TOTAL_TRIPS = SESSIONS_TOTAL
	@Deprecated("Use MetricKey.ACTIVITY_TYPES_USED.storageKey") const val TRANSPORT_MODE_COUNT = ACTIVITY_TYPES_USED
	@Deprecated("Use MetricKey.ACTIVE_DAYS_TOTAL.storageKey") const val ACTIVE_DAYS = ACTIVE_DAYS_TOTAL
	fun sourceTables(metric: MetricKey): Set<String> = metric.sourceTables
	fun sourceTables(metric: String): Set<String> = MetricKey.fromStorageKey(metric)?.sourceTables ?: emptySet()
	fun isKnown(metric: String): Boolean = MetricKey.fromStorageKey(metric) != null
	fun all(): Set<String> = MetricKey.entries.mapTo(LinkedHashSet()) { it.storageKey }
	fun allKeys(): List<MetricKey> = MetricKey.entries
}
