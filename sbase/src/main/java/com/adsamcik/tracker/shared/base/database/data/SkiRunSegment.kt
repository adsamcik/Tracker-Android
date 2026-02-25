package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Type of ski segment detected by the ski state machine.
 */
enum class SkiSegmentType {
    /** Active skiing/snowboarding descent. */
    DOWNHILL_RUN,
    /** Lift ride (chairlift, gondola, drag lift). */
    LIFT_UP,
    /** Stationary (queue, hut, rest). */
    IDLE,
    /** Walking or traversing between areas. */
    WALK
}

/**
 * Individual segment within a skiing session, detected by post-processing.
 * Each segment represents a contiguous period in one ski state.
 */
@Entity(
    tableName = "ski_run_segment",
    indices = [
        Index(value = ["session_id"], name = "idx_ski_run_segment_session"),
        Index(value = ["start_time_ms"], name = "idx_ski_run_segment_start_time")
    ]
)
data class SkiRunSegment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Associated tracker session ID. */
    @ColumnInfo(name = "session_id")
    val sessionId: Long,

    /** Zero-based index of this segment within the session. */
    @ColumnInfo(name = "run_index")
    val runIndex: Int,

    /** Segment type (DOWNHILL_RUN, LIFT_UP, IDLE, WALK). */
    @ColumnInfo(name = "segment_type")
    val segmentType: SkiSegmentType,

    /** Segment start timestamp in milliseconds. */
    @ColumnInfo(name = "start_time_ms")
    val startTimeMs: Long,

    /** Segment end timestamp in milliseconds. */
    @ColumnInfo(name = "end_time_ms")
    val endTimeMs: Long,

    /** Vertical drop/gain in meters (negative for descent, positive for lift). */
    @ColumnInfo(name = "vertical_m")
    val verticalM: Float,

    /** Horizontal distance traveled in meters. */
    @ColumnInfo(name = "distance_m")
    val distanceM: Float,

    /** Maximum speed during segment in m/s. */
    @ColumnInfo(name = "max_speed_mps")
    val maxSpeedMps: Float,

    /** Average speed during segment in m/s. */
    @ColumnInfo(name = "avg_speed_mps")
    val avgSpeedMps: Float,

    /** Row creation timestamp. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
