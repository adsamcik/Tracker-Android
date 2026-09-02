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

/** A complete source-qualified Steps total, or a named reason no number may be shown. */
sealed interface QualifiedStepCount {
    /** A complete non-negative Steps total backed by qualified source evidence. */
    data class Ready(val value: Int) : QualifiedStepCount {
        init {
            require(value >= 0) { "Qualified Steps count cannot be negative" }
        }
    }

    /** A typed reason that a trustworthy numeric Steps total is not available. */
    data class Unavailable(val reason: QualifiedStepCountUnavailableReason) : QualifiedStepCount
}

/**
 * Builds a presentation summary without allowing the legacy aggregate Steps column to become
 * numeric authority. A qualified zero is still a present and truthful Steps observation.
 */
fun DailySummary?.withSourceQualifiedSteps(steps: QualifiedStepCount): DailySummary? {
    val readySteps = (steps as? QualifiedStepCount.Ready)?.value
    val qualifiedSummary = when {
        this != null -> copy(totalSteps = readySteps ?: 0)
        readySteps != null -> DailySummary(
            totalDistanceM = 0f,
            totalSteps = readySteps,
            totalDurationMs = 0L,
            sessionCount = 0,
        )
        else -> null
    }

    return qualifiedSummary?.takeIf { readySteps != null || it.hasNonStepData }
}

private val DailySummary.hasNonStepData: Boolean
    get() = totalDistanceM > 0f || totalDurationMs > 0L || sessionCount > 0

/** Stable presentation reasons that must never be collapsed into a numeric zero. */
enum class QualifiedStepCountUnavailableReason {
    MISSING,
    MATERIALIZING,
    NOT_CAPTURED,
    PARTIAL_CAPTURE,
    SOURCE_EVIDENCE_UNAVAILABLE,
    CALENDAR_AUTHORITY_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
}

data class GoalProgress(
    val stepsToday: QualifiedStepCount,
    val goalSteps: Int,
    val gamificationEnabled: Boolean,
) {
    init {
        require(goalSteps >= 0) { "Step goal cannot be negative" }
    }

    /** Present only when the Steps total is complete and source-qualified. */
    val progress: Float?
        get() = (stepsToday as? QualifiedStepCount.Ready)?.let { ready ->
            if (goalSteps > 0) {
                (ready.value.toFloat() / goalSteps).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
}

interface GoalProgressProvider {
    val goalProgressFlow: StateFlow<GoalProgress>
}
