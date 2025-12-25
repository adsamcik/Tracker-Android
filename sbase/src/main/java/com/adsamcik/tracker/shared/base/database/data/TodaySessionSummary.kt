package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo

/**
 * Summary data class for today's aggregated session metrics.
 * Used by dashboard for at-a-glance display.
 */
data class TodaySessionSummary(
    val duration: Long,
    val collections: Int,
    @ColumnInfo(name = "distance")
    val distanceInM: Float,
    @ColumnInfo(name = "distance_on_foot")
    val distanceOnFootInM: Float,
    @ColumnInfo(name = "distance_in_vehicle")
    val distanceInVehicleInM: Float,
    val steps: Int,
    @ColumnInfo(name = "session_count")
    val sessionCount: Int
)
