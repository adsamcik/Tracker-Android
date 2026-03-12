package com.adsamcik.tracker.shared.base.di

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Provider interfaces and data classes for dependency injection.
 *
 * Interfaces defined in tracker/game modules, implementations provided via Hilt.
 * Data classes used cross-module for dashboard/tracker/game UI.
 */

/**
 * Immutable data class representing today's aggregated tracking summary.
 * Used for dashboard at-a-glance display.
 */
data class DailySummary(
    val totalDistanceM: Float,
    val totalSteps: Int,
    val totalDurationMs: Long,
    val sessionCount: Int
) {
    val isEmpty: Boolean
        get() = totalDistanceM == 0f && totalSteps == 0 && totalDurationMs == 0L
}

/**
 * Provider interface for fetching today's tracking summary.
 * Implementation fetches from Room DAO on Dispatchers.IO.
 */
interface DailySummaryProvider {
    /**
     * Fetches today's aggregated session summary on-demand.
     * Called in LaunchedEffect when session data changes.
     * @return DailySummary or null if no sessions today
     */
    suspend fun fetchTodaySummary(): DailySummary?

    /**
     * Observe today's live stats as a Flow.
     * Emits updates every ~30s during active tracking.
     * Returns a flow that emits null when no data is available.
     */
    fun observeTodayLive(): Flow<DailySummary?>
}

/**
 * Provider interface for exposing today's gamification points.
 * Implementation wraps GameRepository.getPointsToday() as StateFlow.
 */
interface DailyPointsProvider {
    /**
     * Flow emitting today's earned points.
     * Updates reactively when points are awarded.
     */
    val pointsTodayFlow: StateFlow<Int>
}

/**
 * Immutable data class for goal progress tracking.
 */
data class GoalProgress(
    val stepsToday: Int,
    val goalSteps: Int,
    val gamificationEnabled: Boolean
) {
    val progress: Float
        get() = if (goalSteps > 0) (stepsToday.toFloat() / goalSteps).coerceIn(0f, 1f) else 0f
}

/**
 * Provider interface for exposing goal tracking progress.
 * Implementation wraps GameRepository.getStepsSummary() as StateFlow.
 */
interface GoalProgressProvider {
    /**
     * Flow emitting current goal progress.
     * Updates reactively when steps are recorded.
     */
    val goalProgressFlow: StateFlow<GoalProgress>
}

/**
 * Simplified challenge data for cross-module UI consumption.
 * Maps from game module's ChallengeInstance without leaking game internals.
 */
data class ActiveChallengeInfo(
    val id: Long,
    val title: String,
    val description: String,
    val progress: Float,
    val difficulty: String,
    val timeRemainingMs: Long,
)

/**
 * Provider interface for active challenge data.
 * Implementation bridges from game module's ChallengeManager.
 */
interface ActiveChallengesProvider {
    val activeChallengesFlow: StateFlow<List<ActiveChallengeInfo>>
}
