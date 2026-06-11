package com.adsamcik.tracker.shared.base.database.data

/**
 * Read-only projection over [SessionSegment] for UI consumption.
 * Not a Room entity — projected via [com.adsamcik.tracker.shared.base.database.dao.TripDao] queries.
 */
data class Trip(
	val id: Long,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val distanceM: Float,
	val steps: Int?,
	val primaryActivity: Int?,
	val activityConfidence: Int?,
	val sampleCount: Int,
	val source: SegmentSource,
	val createdAt: Long,
	/**
	 * True when the trip's average speed exceeds the plausible threshold,
	 * indicating likely GPS glitch artifacts. Persisted at write time;
	 * for legacy tracker_session rows, computed via SQL fallback.
	 */
	val hasDistanceAnomaly: Boolean = false,
) {
	val durationMs: Long get() = endTimeMs - startTimeMs
	val isUserInitiated: Boolean get() = source == SegmentSource.USER_CREATED
}

/**
 * Aggregated summary for trips within a date range.
 */
data class TripDaySummary(
	val tripCount: Int,
	val totalDistanceM: Float,
	val totalSteps: Int,
	val totalDurationMs: Long
)
