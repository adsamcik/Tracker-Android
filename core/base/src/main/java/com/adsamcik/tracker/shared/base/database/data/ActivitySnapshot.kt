package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records activity transitions detected by activity recognition APIs.
 * Stores raw activity type and confidence for later analysis.
 */
@Entity(
	tableName = "activity_snapshot",
	indices = [
		Index(value = ["time_ms"], name = "idx_activity_snapshot_time"),
		Index(
			value = ["source_signal_id"],
			unique = true,
			name = "idx_activity_snapshot_source_signal",
		),
	]
)
data class ActivitySnapshot(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/**
	 * Snapshot timestamp in milliseconds (wall clock time).
	 */
	@ColumnInfo(name = "time_ms")
	val timeMs: Long,

	/**
	 * Activity type as integer (matches DetectedActivity.value).
	 * See [com.adsamcik.tracker.shared.base.data.DetectedActivity].
	 */
	@ColumnInfo(name = "activity_type")
	val activityType: Int,

	/**
	 * Confidence level (0-100).
	 */
	val confidence: Int,

	/**
	 * True if this is a transition event (activity changed), false if periodic update.
	 */
	@ColumnInfo(name = "is_transition")
	val isTransition: Boolean,

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
