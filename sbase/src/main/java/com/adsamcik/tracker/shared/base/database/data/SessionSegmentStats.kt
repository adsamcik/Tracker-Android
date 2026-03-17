package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo

data class SessionSegmentStats(
	@ColumnInfo(name = "duration_ms")
	val durationMs: Long,
	@ColumnInfo(name = "collection_count")
	val collectionCount: Long,
	@ColumnInfo(name = "distance_m")
	val distanceM: Float,
	@ColumnInfo(name = "step_count")
	val stepCount: Long,
)
