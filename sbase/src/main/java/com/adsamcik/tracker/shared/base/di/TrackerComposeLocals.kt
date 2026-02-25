package com.adsamcik.tracker.shared.base.di

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * CompositionLocal providers for dependency injection in Compose UI.
 * 
 * Per copilot-instructions Section 16A: Dependency Injection & Composition Root
 * - Provides AppGraph dependencies to Compose tree without static access
 * - Set at root composition (MainActivity.AppContent())
 * - Enables test injection via CompositionLocalProvider
 * 
 * Interfaces defined in tracker module, implementations in app module.
 * CompositionLocals in sbase to avoid circular dependency (tracker UI → app).
 * 
 * Usage in composables:
 * ```
 * val controller = LocalTrackerController.current
 * val lockManager = LocalLockManager.current
 * val dailySummary = LocalDailySummaryProvider.current
 * val dailyPoints = LocalDailyPointsProvider.current
 * ```
 * 
 * Wiring in MainActivity:
 * ```
 * CompositionLocalProvider(
 *     LocalTrackerController provides appGraph.trackerServiceController,
 *     LocalLockManager provides appGraph.lockManager,
 *     LocalDailySummaryProvider provides appGraph.dailySummaryProvider,
 *     LocalDailyPointsProvider provides appGraph.dailyPointsProvider
 * ) {
 *     AppContent()
 * }
 * ```
 */
// Note: These use compositionLocalOf<Any> intentionally because the concrete interfaces
// (TrackerServiceController, LockManager) live in the tracker module, which sbase cannot
// depend on. Consumers must cast: `LocalTrackerController.current as TrackerServiceController`.
val LocalTrackerController = compositionLocalOf<Any> {
    error("TrackerServiceController not provided. Ensure CompositionLocalProvider wraps root composition.")
}

val LocalLockManager = compositionLocalOf<Any> {
    error("LockManager not provided. Ensure CompositionLocalProvider wraps root composition.")
}

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

val LocalDailySummaryProvider = compositionLocalOf<DailySummaryProvider> {
    error("DailySummaryProvider not provided. Ensure CompositionLocalProvider wraps root composition.")
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

val LocalDailyPointsProvider = compositionLocalOf<DailyPointsProvider> {
    error("DailyPointsProvider not provided. Ensure CompositionLocalProvider wraps root composition.")
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

val LocalGoalProgressProvider = compositionLocalOf<GoalProgressProvider> {
    error("GoalProgressProvider not provided. Ensure CompositionLocalProvider wraps root composition.")
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

val LocalActiveChallengesProvider = compositionLocalOf<ActiveChallengesProvider> {
    error("ActiveChallengesProvider not provided. Ensure CompositionLocalProvider wraps root composition.")
}
