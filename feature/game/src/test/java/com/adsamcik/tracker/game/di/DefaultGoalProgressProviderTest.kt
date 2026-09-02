package com.adsamcik.tracker.game.di

import android.content.Context
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.repository.StepsSummaryData
import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultGoalProgressProviderTest {
	@Test
	fun `each subscriber refreshes after the replay resets to typed missing`() = runTest {
		val source = MutableStateFlow<StepsSummaryData?>(readySummary(123))
		val repository = mockk<GameRepository> {
			every { getStepsSummary() } returns source
		}
		val provider = DefaultGoalProgressProvider(
			context = mockk<Context>(relaxed = true),
			gameRepository = repository,
			scope = backgroundScope,
		)

		provider.goalProgressFlow.awaitRepositoryResult().stepsToday shouldBe
			QualifiedStepCount.Ready(123)
		runCurrent()
		provider.goalProgressFlow.value.stepsToday shouldBe QualifiedStepCount.Unavailable(
			QualifiedStepCountUnavailableReason.MISSING,
		)

		source.value = readySummary(456)
		provider.goalProgressFlow.awaitRepositoryResult().stepsToday shouldBe
			QualifiedStepCount.Ready(456)
	}

	@Test
	fun `unavailable repository result remains nonnumeric`() = runTest {
		val source = MutableStateFlow<StepsSummaryData?>(
			StepsSummaryData(
				stepsToday = QualifiedStepCount.Unavailable(
					QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE,
				),
				stepsWeek = QualifiedStepCount.Unavailable(
					QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE,
				),
				goalDay = 10_000,
				goalWeek = 70_000,
			),
		)
		val repository = mockk<GameRepository> {
			every { getStepsSummary() } returns source
		}
		val provider = DefaultGoalProgressProvider(
			context = mockk<Context>(relaxed = true),
			gameRepository = repository,
			scope = backgroundScope,
		)

		val progress = provider.goalProgressFlow.awaitRepositoryResult()

		progress.stepsToday shouldBe QualifiedStepCount.Unavailable(
			QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE,
		)
		progress.progress shouldBe null
	}
}

private suspend fun StateFlow<GoalProgress>.awaitRepositoryResult() = first { progress ->
		progress.stepsToday != QualifiedStepCount.Unavailable(
			QualifiedStepCountUnavailableReason.MISSING,
		)
	}

private fun readySummary(today: Int) = StepsSummaryData(
	stepsToday = QualifiedStepCount.Ready(today),
	stepsWeek = QualifiedStepCount.Ready(today),
	goalDay = 10_000,
	goalWeek = 70_000,
)
