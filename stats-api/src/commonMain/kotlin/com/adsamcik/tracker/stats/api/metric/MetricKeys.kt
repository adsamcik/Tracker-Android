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
}
