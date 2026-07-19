package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Exact archive of legacy sessions that violate modern segment invariants.
 */
@Entity(tableName = "legacy_rejected_tracker_session")
data class LegacyRejectedTrackerSession(
	@PrimaryKey
	val id: Long,
	val start: Long,
	@ColumnInfo(name = "end")
	val end: Long,
	@ColumnInfo(name = "user_initiated")
	val userInitiated: Boolean,
	val collections: Int,
	val distance: Double,
	@ColumnInfo(name = "distance_on_foot")
	val distanceOnFoot: Double,
	@ColumnInfo(name = "distance_in_vehicle")
	val distanceInVehicle: Double,
	val steps: Int,
	@ColumnInfo(name = "session_activity_id")
	val sessionActivityId: Long?
)
