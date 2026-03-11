package com.adsamcik.tracker.statistics.viewmodel

import com.adsamcik.tracker.statistics.data.Stat

/**
 * Sealed class representing the loading state of statistics data.
 */
sealed class StatsLoadState {
    data object Idle : StatsLoadState()
    data object Loading : StatsLoadState()
    data class Success(val stats: List<Stat>) : StatsLoadState()
    data class Error(val message: String) : StatsLoadState()
}

/**
 * Per-day bar data for the weekly summary chart.
 * @param dayLabel Short day name (e.g. "Mon")
 * @param distanceM Total distance in meters for this day
 * @param steps Total step count for this day
 * @param epochDay The java.time epoch day value
 */
data class DayBar(
    val dayLabel: String,
    val distanceM: Float,
    val steps: Int,
    val epochDay: Long,
)
