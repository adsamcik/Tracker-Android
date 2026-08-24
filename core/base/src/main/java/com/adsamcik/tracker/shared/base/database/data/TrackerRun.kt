package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracking run represents a continuous collection period under a specific policy.
 * Allows reconstruction of adaptive tracking behavior for analysis.
 */
@Entity(
	tableName = "tracker_run",
	indices = [
		Index(value = ["start_time_ms", "end_time_ms"], name = "idx_tracker_run_time_range")
	]
)
data class TrackerRun(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/**
	 * Run start timestamp in milliseconds (wall clock time).
	 */
	@ColumnInfo(name = "start_time_ms")
	val startTimeMs: Long,

	/**
	 * Run end timestamp in milliseconds (wall clock time).
	 *
	 * Null means the factual end is not known. Runtime ownership additionally requires
	 * [legacyRuntimeFenced] to be false.
	 */
	@ColumnInfo(name = "end_time_ms")
	val endTimeMs: Long?,

	/**
	 * Tracking policy active during this run (e.g., "PASSIVE_LOW", "ACTIVE_ELEVATED").
	 */
	val policy: String,

	/**
	 * Policy parameters as JSON string (for debugging/analysis).
	 */
	@ColumnInfo(name = "policy_params")
	val policyParams: String?,

	/**
	 * User-initiated (true) vs system-triggered (false).
	 */
	@ColumnInfo(name = "user_initiated")
	val userInitiated: Boolean,

	/**
	 * Row creation timestamp (for auditing/debugging).
	 */
	@ColumnInfo(name = "created_at")
	val createdAt: Long,

	/**
	 * True when an upgrade fenced a legacy runtime whose factual end is unknown.
	 *
	 * [endTimeMs] remains null in that case so history does not invent a completion boundary, while
	 * active-runtime queries must exclude the row.
	 */
	@ColumnInfo(name = "legacy_runtime_fenced", defaultValue = "0")
	val legacyRuntimeFenced: Boolean = false,
)
