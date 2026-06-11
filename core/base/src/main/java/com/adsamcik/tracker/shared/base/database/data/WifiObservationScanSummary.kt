package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo

data class WifiObservationScanSummary(
	@ColumnInfo(name = "total_observations")
	val totalObservations: Long,
	@ColumnInfo(name = "distinct_scan_times")
	val distinctScanTimes: Long,
)
