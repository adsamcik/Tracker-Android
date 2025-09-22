package com.adsamcik.tracker.game.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for accessing game data (points, steps, goals, challenges).
 * Provides abstraction over direct DAO and manager access.
 */
interface GameRepository {
    /**
     * Get points earned today.
     * @return Flow emitting current points for today
     */
    fun getPointsToday(): Flow<Int>
    
    /**
     * Get current steps and goal information.
     * @return StateFlow with steps summary or null if not available
     */
    fun getStepsSummary(): StateFlow<StepsSummaryData?>
    
    /**
     * Get active challenges.
     * @return StateFlow with list of active challenges
     */
    fun getActiveChallenges(): StateFlow<List<ChallengeData>>
}

/**
 * Data class for steps summary information.
 */
data class StepsSummaryData(
    val stepsToday: Int,
    val stepsWeek: Int,
    val goalDay: Int,
    val goalWeek: Int
)

/**
 * Data class for challenge information.
 */
data class ChallengeData(
    val id: Long,
    val title: String,
    val description: String,
    val progress: Float
)