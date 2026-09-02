package com.adsamcik.tracker.app.steps

import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class QualifiedGoalProgressAwaitTest {

    @Test
    fun `settled qualified progress returns immediately`() = runTest {
        val progress = goalProgress(QualifiedStepCount.Ready(4_200))

        MutableStateFlow(progress).awaitQualifiedGoalProgress(timeoutMillis = 1_000L) shouldBe progress
    }

    @Test
    fun `missing replay sentinel times out to typed unavailable and preserves settings`() = runTest {
        val result = MutableStateFlow(goalProgress(missingSteps()))
            .awaitQualifiedGoalProgress(timeoutMillis = 1_000L)

        result shouldBe GoalProgress(
            stepsToday = QualifiedStepCount.Unavailable(
                QualifiedStepCountUnavailableReason.SOURCE_EVIDENCE_UNAVAILABLE,
            ),
            goalSteps = 10_000,
            gamificationEnabled = true,
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `caller cancellation propagates while replay sentinel is unresolved`() = runTest {
        val waiter = async {
            MutableStateFlow(goalProgress(missingSteps()))
                .awaitQualifiedGoalProgress(timeoutMillis = 60_000L)
        }
        runCurrent()

        waiter.cancel(CancellationException("caller stopped"))
        val failure = runCatching { waiter.await() }.exceptionOrNull()

        (failure is CancellationException).shouldBeTrue()
    }

    private fun goalProgress(steps: QualifiedStepCount) = GoalProgress(
        stepsToday = steps,
        goalSteps = 10_000,
        gamificationEnabled = true,
    )

    private fun missingSteps() = QualifiedStepCount.Unavailable(
        QualifiedStepCountUnavailableReason.MISSING,
    )
}
