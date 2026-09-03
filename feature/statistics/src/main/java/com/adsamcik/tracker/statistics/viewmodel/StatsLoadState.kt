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
 * @param steps Legacy derived step projection for chart compatibility; not source qualification
 * @param epochDay The java.time epoch day value
 * @param durationMs Total tracked duration in milliseconds for this day
 */
data class DayBar(
    val dayLabel: String,
    val distanceM: Float,
    val steps: Int,
    val epochDay: Long,
    val sessionCount: Int = 0,
    val durationMs: Long = 0L,
)

/** Structural activity evidence that does not treat the legacy Steps projection as authoritative. */
internal val DayBar.hasNonStepTrackedActivity: Boolean
    get() = distanceM > 0f || sessionCount > 0 || durationMs > 0L
