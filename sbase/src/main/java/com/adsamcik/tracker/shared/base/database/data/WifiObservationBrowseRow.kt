package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo

data class WifiObservationBrowseRow(
	val bssid: String,
	val ssid: String,
	val capabilities: String,
	val frequency: Int,
	@ColumnInfo(name = "first_seen_ms")
	val firstSeenMs: Long,
	@ColumnInfo(name = "last_seen_ms")
	val lastSeenMs: Long,
)
