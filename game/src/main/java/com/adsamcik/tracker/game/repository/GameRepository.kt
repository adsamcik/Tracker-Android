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

    /** Observe player profile (level, XP, etc.) */
    fun getPlayerProfile(): Flow<PlayerProfileUi?>

    /** Observe challenge streak data */
    fun getStreak(): Flow<StreakUi?>

    /** Observe trophy summary (completed count + medal counts) */
    fun getTrophySummary(): Flow<TrophySummaryUi>

    /** Observe full challenge history for trophy case */
    fun getChallengeHistory(): Flow<List<TrophyItemUi>>

    /** Observe personal records */
    fun getPersonalRecords(): Flow<List<PersonalRecordUi>>

    /** Get lifetime stats */
    fun getLifetimeStats(): Flow<LifetimeStatsUi>
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
 *
 * [endTimeMs] is the absolute wall-clock end of the challenge window — UI layers should
 * prefer this with a local ticker (`produceState { while(true) { delay(60_000); value = Time.nowMillis } }`)
 * over [timeRemainingMs], which is a snapshot captured at flow emission and goes stale
 * until the next active-challenges emission.
 */
data class ChallengeData(
    val id: Long,
    val title: String,
    val description: String,
    val progress: Float,
    val difficulty: String = "",
    val timeRemainingMs: Long = 0L,
    val endTimeMs: Long = 0L,
)

/**
 * UI data class for player profile information.
 */
data class PlayerProfileUi(
    val level: Int,
    val totalXp: Long,
    val xpIntoCurrentLevel: Long,
    val xpForNextLevel: Long,
)

/**
 * UI data class for streak information.
 */
data class StreakUi(
    val currentCount: Int,
    val bestCount: Int,
    val freezeCount: Int,
)

/**
 * UI data class for trophy summary.
 */
data class TrophySummaryUi(
    val totalCompleted: Int,
    val goldCount: Int,
    val silverCount: Int,
    val bronzeCount: Int,
)

/**
 * UI data class for a single trophy (completed challenge).
 */
data class TrophyItemUi(
    val id: Long,
    val challengeType: String,
    val difficulty: String,
    val medal: String?,
    val completedAt: Long?,
    val xpAwarded: Int,
    val progressValue: Double,
    val targetValue: Double,
)

/**
 * UI data class for a personal record.
 */
data class PersonalRecordUi(
    val challengeType: String,
    val metric: String,
    val value: Double,
    val achievedAt: Long,
)

/**
 * UI data class for lifetime statistics.
 */
data class LifetimeStatsUi(
    val totalChallenges: Int,
    val completedCount: Int,
    val completionRate: Float,
    val goldCount: Int,
    val silverCount: Int,
    val bronzeCount: Int,
    val totalXpEarned: Long,
)
