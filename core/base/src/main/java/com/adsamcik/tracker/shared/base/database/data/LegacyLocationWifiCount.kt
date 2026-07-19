package com.adsamcik.tracker.shared.base.database.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Read-only archive of 2024.1 per-location Wi-Fi counts.
 */
@Entity(
	tableName = "legacy_location_wifi_count",
	indices = [
		Index(value = ["time"], name = "idx_legacy_location_wifi_count_time"),
		Index(value = ["lat", "lon"], name = "idx_legacy_location_wifi_count_coords")
	]
)
data class LegacyLocationWifiCount(
	@PrimaryKey
	val id: Long,
	val time: Long,
	val count: Int,
	val lat: Double,
	val lon: Double,
	val alt: Double?
)
