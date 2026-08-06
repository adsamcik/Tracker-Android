package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records step counter deltas between consecutive readings.
 * Handles sensor resets and provides context for step-based inference.
 */
@Entity(
	tableName = "step_interval",
	indices = [
		Index(value = ["start_time_ms", "end_time_ms"], name = "idx_step_interval_time_range"),
		// Retention and latest-reading queries are keyed by the interval end. The range index
		// starts with start_time_ms and cannot efficiently serve either access pattern.
		Index(value = ["end_time_ms"], name = "idx_step_interval_end_time"),
		Index(
			value = ["source_signal_id"],
			unique = true,
			name = "idx_step_interval_source_signal",
		),
	]
)
data class StepInterval(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/**
	 * Interval start timestamp in milliseconds (wall clock time).
	 */
	@ColumnInfo(name = "start_time_ms")
	val startTimeMs: Long,

	/**
	 * Interval end timestamp in milliseconds (wall clock time).
	 */
	@ColumnInfo(name = "end_time_ms")
	val endTimeMs: Long,

	/**
	 * Number of steps detected during this interval.
	 */
	@ColumnInfo(name = "step_count")
	val stepCount: Int,

	/**
	 * Raw sensor value at interval start (for detecting resets).
	 */
	@ColumnInfo(name = "sensor_value_start")
	val sensorValueStart: Int,

	/**
	 * Raw sensor value at interval end (for detecting resets).
	 */
	@ColumnInfo(name = "sensor_value_end")
	val sensorValueEnd: Int,

	/**
	 * True if sensor reset occurred during this interval (requires special handling).
	 */
	@ColumnInfo(name = "sensor_reset")
	val sensorReset: Boolean,

	/**
	 * Row creation timestamp (for auditing/debugging).
	 */
	@ColumnInfo(name = "created_at")
	val createdAt: Long,

	/** Stable pending-signal identity used to make replay idempotent. */
	@ColumnInfo(name = "source_signal_id")
	val sourceSignalId: String? = null,

	@Embedded
	val observationStamp: ObservationStampColumns = ObservationStampColumns(),
)
