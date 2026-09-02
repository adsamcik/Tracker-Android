package com.adsamcik.tracker.app.steps

import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal const val QUALIFIED_GOAL_PROGRESS_AWAIT_TIMEOUT_MILLIS = 5_000L

/**
 * Waits briefly for the repository's replay sentinel to settle. A source that never advances is
 * converted to a typed nonnumeric result, while cancellation from the caller still propagates.
 */
internal suspend fun StateFlow<GoalProgress>.awaitQualifiedGoalProgress(
    timeoutMillis: Long = QUALIFIED_GOAL_PROGRESS_AWAIT_TIMEOUT_MILLIS,
): GoalProgress {
    require(timeoutMillis > 0L) { "Qualified goal progress timeout must be positive" }

    val settled = withTimeoutOrNull(timeoutMillis) {
        first { progress -> !progress.stepsToday.isMissingReplaySentinel() }
    }
    if (settled != null) {
        return settled
    }

    val current = value
    return if (current.stepsToday.isMissingReplaySentinel()) {
        current.copy(
            stepsToday = QualifiedStepCount.Unavailable(
                QualifiedStepCountUnavailableReason.SOURCE_EVIDENCE_UNAVAILABLE,
            ),
        )
    } else {
        current
    }
}

private fun QualifiedStepCount.isMissingReplaySentinel(): Boolean =
    (this as? QualifiedStepCount.Unavailable)?.reason == QualifiedStepCountUnavailableReason.MISSING
