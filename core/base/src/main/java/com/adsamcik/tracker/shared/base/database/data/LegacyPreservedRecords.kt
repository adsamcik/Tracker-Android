package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Original sessions that could not become a complete session segment. No bounds are invented. */
@Entity(tableName = "legacy_rejected_tracker_session")
data class LegacyRejectedTrackerSession(
	@PrimaryKey val id: Long,
	val start: Long,
	val end: Long,
	@ColumnInfo(name = "user_initiated") val userInitiated: Boolean,
	val collections: Int,
	val distance: Double,
	@ColumnInfo(name = "distance_on_foot") val distanceOnFoot: Double,
	@ColumnInfo(name = "distance_in_vehicle") val distanceInVehicle: Double,
	val steps: Int,
	@ColumnInfo(name = "session_activity_id") val sessionActivityId: Long?,
)

/** An original count at a measured time and position, without invented access-point records. */
@Entity(tableName = "legacy_location_wifi_count", indices = [
	Index(value = ["time"], name = "idx_legacy_location_wifi_count_time"),
	Index(value = ["lat", "lon"], name = "idx_legacy_location_wifi_count_coords"),
])
data class LegacyLocationWifiCount(
	@PrimaryKey val id: Long,
	val time: Long,
	val count: Int,
	val lat: Double,
	val lon: Double,
	val alt: Double?,
)
