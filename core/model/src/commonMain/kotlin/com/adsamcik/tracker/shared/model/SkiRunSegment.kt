package com.adsamcik.tracker.shared.model

/**
 * Type of ski segment detected by the ski state machine.
 */
enum class SkiSegmentType {
DOWNHILL_RUN,
LIFT_UP,
IDLE,
WALK,
}

/**
 * Room-free ski segment within a skiing session.
 */
data class SkiRunSegment(
val id: Long = 0,
val sessionId: Long,
val runIndex: Int,
val segmentType: SkiSegmentType,
val startTimeMs: Long,
val endTimeMs: Long,
val verticalM: Float,
val distanceM: Float,
val maxSpeedMps: Float,
val avgSpeedMps: Float,
val liftType: String? = null,
val createdAt: Long,
)
