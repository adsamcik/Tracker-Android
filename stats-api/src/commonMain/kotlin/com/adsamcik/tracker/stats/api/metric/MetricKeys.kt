package com.adsamcik.tracker.stats.api.metric

/**
 * Shared metric key vocabulary used across cumulative achievements and windowed challenges.
 *
 * Keys are stable string identifiers persisted in definitions and evaluators. Additions should
 * be append-only; existing key values must remain unchanged for backward compatibility.
 */
object MetricKeys {
	const val STEPS = "steps"
	const val DISTANCE_M = "distance_m"
	const val DISTANCE_ON_FOOT_M = "distance_on_foot_m"
	const val ACTIVE_MINUTES = "active_minutes"
	const val CELLS_DISCOVERED = "cells_discovered"
	const val UNIQUE_AREAS = "unique_areas"
	const val TOTAL_DISTANCE_KM = "total_distance_km"
	const val TOTAL_STEPS = "total_steps"
	const val DAILY_STREAK = "daily_streak"
	const val WEEKLY_STREAK = "weekly_streak"
	const val WALKING_TRIPS = "walking_trips"
	const val CYCLING_TRIPS = "cycling_trips"
	const val BEST_DAILY_STEPS = "best_daily_steps"
	const val LONGEST_TRIP_KM = "longest_trip_km"
	const val TOTAL_TRIPS = "total_trips"
	const val TRANSPORT_MODE_COUNT = "transport_mode_count"
	const val SEASONS_EXPLORED = "seasons_explored"
	const val TOTAL_EXPORTS = "total_exports"

	/**
	 * Number of distinct calendar days (epoch-day, local TZ) with at least [MIN_DAILY_TRIPS]
	 * trips recorded in `daily_summary` during the window.
	 * Used by the Consistency challenge.
	 *
	 * Note: [MIN_DAILY_TRIPS] = 1 — any day with at least one tracked trip counts.
	 * The legacy [ConsistencyChallengeProcessor] used a per-session `MIN_COLLECTIONS = 2`
	 * filter (≥2 GPS fix points = not an accidental tap); that was a session-level noise
	 * gate, not a "how many trips per day" gate. A single trip in `daily_summary` already
	 * represents a real tracking event, so `trip_count >= 1` is the correct mapping.
	 */
	const val ACTIVE_DAYS = "active_days"

	/** Minimum trips in a day for it to count toward [ACTIVE_DAYS]. */
	const val MIN_DAILY_TRIPS = 1

	// ── Source-table mapping (p6-2) ─────────────────────────────────────────────
	// Maps each metric to the pre-aggregated tables that back it. The
	// MetricDirtyTracker uses this in reverse: when a writer marks a table dirty,
	// the registry resolves "which rules depend on this table" and only those are
	// re-evaluated. Battery win: idle flushes are a single set-difference, not 15+
	// SUM aggregates.

	/** Canonical pre-aggregated table names. Plain strings to keep stats-api KMP-friendly. */
	const val TABLE_DAILY_SUMMARY = "daily_summary"
	const val TABLE_SESSION_SEGMENT = "session_segment"
	const val TABLE_EXPLORATION_CELL = "exploration_cell"
	const val TABLE_EXPLORATION_STREAK = "exploration_streak"
	const val TABLE_EXPORT_LOG = "export_log"

	private val SOURCE_TABLES: Map<String, Set<String>> = mapOf(
		STEPS to setOf(TABLE_DAILY_SUMMARY),
		TOTAL_STEPS to setOf(TABLE_DAILY_SUMMARY),
		BEST_DAILY_STEPS to setOf(TABLE_DAILY_SUMMARY),
		DISTANCE_M to setOf(TABLE_DAILY_SUMMARY),
		TOTAL_DISTANCE_KM to setOf(TABLE_DAILY_SUMMARY),
		ACTIVE_MINUTES to setOf(TABLE_DAILY_SUMMARY),
		TOTAL_TRIPS to setOf(TABLE_DAILY_SUMMARY),
		ACTIVE_DAYS to setOf(TABLE_DAILY_SUMMARY),

		DISTANCE_ON_FOOT_M to setOf(TABLE_SESSION_SEGMENT),
		WALKING_TRIPS to setOf(TABLE_SESSION_SEGMENT),
		CYCLING_TRIPS to setOf(TABLE_SESSION_SEGMENT),
		LONGEST_TRIP_KM to setOf(TABLE_SESSION_SEGMENT),
		TRANSPORT_MODE_COUNT to setOf(TABLE_SESSION_SEGMENT),

		CELLS_DISCOVERED to setOf(TABLE_EXPLORATION_CELL),
		UNIQUE_AREAS to setOf(TABLE_EXPLORATION_CELL),
		SEASONS_EXPLORED to setOf(TABLE_EXPLORATION_CELL),

		DAILY_STREAK to setOf(TABLE_EXPLORATION_STREAK),
		WEEKLY_STREAK to setOf(TABLE_EXPLORATION_STREAK),

		TOTAL_EXPORTS to setOf(TABLE_EXPORT_LOG),
	)

	/**
	 * Tables whose mutation invalidates the value of [metric]. The dirty tracker uses this to
	 * answer "is rule X affected by a write?" in O(1) per metric.
	 *
	 * Returns the empty set for unknown metrics. Empty set means the metric never changes
	 * (rare — e.g. constant) OR the metric is unmapped and should be conservatively
	 * re-evaluated on every flush. Callers should treat empty as "always dirty".
	 */
	fun sourceTables(metric: String): Set<String> = SOURCE_TABLES[metric] ?: emptySet()

	/**
	 * All known metric keys, in declaration order. Useful for tests + audit logging.
	 * NOT call this on the flush hot path — it allocates.
	 */
	fun all(): Set<String> = SOURCE_TABLES.keys
}
