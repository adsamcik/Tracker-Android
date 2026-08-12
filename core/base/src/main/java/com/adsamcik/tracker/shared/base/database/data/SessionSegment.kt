package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adsamcik.tracker.shared.model.SegmentSource

/**
 * Session segment derived from raw timeline data via inference.
 * Represents a meaningful travel episode (start → end) with aggregated metrics.
 */
@Entity(
	tableName = "session_segment",
	indices = [
		Index(value = ["start_time_ms", "end_time_ms"], name = "idx_session_segment_time_range"),
		// Single-column end_time_ms index — needed by the cross-midnight overlap
		// query (`start_time_ms < toMs AND end_time_ms > fromMs`) so SQLite can
		// pick the more selective predicate. Without it the planner only seeks
		// the leading start_time_ms column and scans everything before toMs.
		Index(value = ["end_time_ms"], name = "idx_session_segment_end_time_ms"),
		Index(value = ["source"], name = "idx_session_segment_source"),
		Index(value = ["primary_activity"], name = "idx_session_segment_primary_activity")
	]
)
data class SessionSegment(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/**
	 * Segment start timestamp in milliseconds (wall clock time).
	 */
	@ColumnInfo(name = "start_time_ms")
	val startTimeMs: Long,

	/**
	 * Segment end timestamp in milliseconds (wall clock time).
	 */
	@ColumnInfo(name = "end_time_ms")
	val endTimeMs: Long,

	/**
	 * Total distance traveled in meters.
	 */
	@ColumnInfo(name = "distance_m")
	val distanceM: Float,

	/**
	 * Number of steps during segment (if available).
	 */
	val steps: Int?,

	/**
	 * Primary activity type for segment (DetectedActivity.value).
	 */
	@ColumnInfo(name = "primary_activity")
	val primaryActivity: Int?,

	/**
	 * Average confidence for primary activity (0-100).
	 */
	@ColumnInfo(name = "activity_confidence")
	val activityConfidence: Int?,

	/**
	 * Number of location samples used to derive this segment.
	 */
	@ColumnInfo(name = "sample_count")
	val sampleCount: Int,

	/**
	 * Segment source (USER_CREATED, INFERRED_HIGH_CONFIDENCE, INFERRED_LOW_CONFIDENCE).
	 */
	val source: SegmentSource,

	/**
	 * Inference algorithm version/identifier (for debugging).
	 */
	@ColumnInfo(name = "inference_version")
	val inferenceVersion: String?,

	/**
	 * Row creation timestamp (for auditing/debugging).
	 */
	@ColumnInfo(name = "created_at")
	val createdAt: Long,

	/**
	 * True when the segment's average speed exceeds the plausible threshold
	 * for its activity type, indicating likely GPS glitch artifacts.
	 * Evaluated at persistence time via [com.adsamcik.tracker.stats.api.TripPlausibility].
	 */
	@ColumnInfo(name = "has_distance_anomaly", defaultValue = "0")
	val hasDistanceAnomaly: Boolean = false,
)
