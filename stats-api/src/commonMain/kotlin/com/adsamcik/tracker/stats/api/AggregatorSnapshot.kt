package com.adsamcik.tracker.stats.api

/**
 * Immutable snapshot of the StreamingAggregator's accumulated state.
 * Used for periodic flush to live_stats table and for DailySummary materialization.
 *
 * @param sessionStartMs When the current tracking session started
 * @param lastUpdateMs Timestamp of the most recent signal processed
 * @param sessionDistanceM Total distance accumulated in current session
 * @param sessionSteps Total steps accumulated in current session
 * @param sessionDurationMs Active tracking duration in current session
 * @param dayTotalDistanceM Running day-level distance (includes prior sessions)
 * @param dayTotalSteps Running day-level steps (includes prior sessions)
 * @param dayTotalDurationMs Running day-level duration (includes prior sessions)
 * @param currentSpeedMps Most recent speed reading (null if no GPS)
 * @param avgSpeedMps Average speed across session (0 if no readings)
 * @param maxSpeedMps Maximum speed recorded in session
 * @param sampleCount Number of GPS samples in session
 * @param dominantActivity Most frequently detected activity type in session
 * @param tripCount Number of trips completed today
 */
data class AggregatorSnapshot(
	val sessionStartMs: Long,
	val lastUpdateMs: Long,
	val sessionDistanceM: Float,
	val sessionSteps: Int,
	val sessionDurationMs: Long,
	val dayTotalDistanceM: Float,
	val dayTotalSteps: Int,
	val dayTotalDurationMs: Long,
	val currentSpeedMps: Float?,
	val avgSpeedMps: Float,
	val maxSpeedMps: Float,
	val sampleCount: Int,
	val dominantActivity: DetectedActivityType?,
	val tripCount: Int,
)
