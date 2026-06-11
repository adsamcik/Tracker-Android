package com.adsamcik.tracker.shared.base.di

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class DailySummary(
    val totalDistanceM: Float,
    val totalSteps: Int,
    val totalDurationMs: Long,
    val sessionCount: Int
) {
    val isEmpty: Boolean get() = totalDistanceM == 0f && totalSteps == 0 && totalDurationMs == 0L
}

interface DailySummaryProvider {
    suspend fun fetchTodaySummary(): DailySummary?
    fun observeTodayLive(): Flow<DailySummary?>
}

interface DailyPointsProvider {
    val pointsTodayFlow: StateFlow<Int>
}

data class GoalProgress(
    val stepsToday: Int,
    val goalSteps: Int,
    val gamificationEnabled: Boolean
) {
    val progress: Float get() = if (goalSteps > 0) (stepsToday.toFloat() / goalSteps).coerceIn(0f, 1f) else 0f
}

interface GoalProgressProvider {
    val goalProgressFlow: StateFlow<GoalProgress>
}
