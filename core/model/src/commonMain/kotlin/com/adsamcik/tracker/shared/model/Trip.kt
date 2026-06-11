package com.adsamcik.tracker.shared.model

/**
 * Room-free trip projection for stats and presentation contracts.
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
val hasDistanceAnomaly: Boolean = false,
) {
val durationMs: Long get() = endTimeMs - startTimeMs
val isUserInitiated: Boolean get() = source == SegmentSource.USER_CREATED
}

/**
 * Source classification for a trip/session segment.
 */
enum class SegmentSource {
USER_CREATED,
INFERRED_HIGH_CONFIDENCE,
INFERRED_MEDIUM_CONFIDENCE,
INFERRED_LOW_CONFIDENCE,
LEGACY_MIGRATION,
}

/**
 * Aggregated summary for trips within a date range.
 */
data class TripDaySummary(
val tripCount: Int,
val totalDistanceM: Float,
val totalSteps: Int,
val totalDurationMs: Long,
)
