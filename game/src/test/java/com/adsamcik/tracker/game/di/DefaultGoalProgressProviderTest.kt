package com.adsamcik.tracker.game.di

import android.content.Context
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.repository.StepsSummaryData
import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.preferences.Preferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("DefaultGoalProgressProvider")
class DefaultGoalProgressProviderTest {

	private lateinit var context: Context
	private lateinit var gameRepository: GameRepository
	private lateinit var testScope: TestScope
	private lateinit var stepsSummaryFlow: MutableStateFlow<StepsSummaryData?>

	@BeforeEach
	fun setUp() {
		context = mockk(relaxed = true)
		gameRepository = mockk(relaxed = true)

		every {
			context.getString(R.string.settings_game_challenge_enable_key)
		} returns "challenge_enable_key"

		mockkConstructor(Preferences::class)
		every { anyConstructed<Preferences>().getBoolean(any(), any()) } returns true

		stepsSummaryFlow = MutableStateFlow(null)
		every { gameRepository.getStepsSummary() } returns stepsSummaryFlow

		testScope = TestScope(UnconfinedTestDispatcher())
	}

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	private fun createProvider(): DefaultGoalProgressProvider {
		return DefaultGoalProgressProvider(context, gameRepository, testScope)
	}

	@Nested
	@DisplayName("Initial state")
	inner class InitialState {

		@Test
		fun `initial value has zero steps and goal`() {
			val provider = createProvider()
			val initial = provider.goalProgressFlow.value

			initial.stepsToday shouldBe 0
			initial.goalSteps shouldBe 0
		}

		@Test
		fun `initial value reflects gamification enabled preference`() {
			every { anyConstructed<Preferences>().getBoolean(any(), any()) } returns true

			val provider = createProvider()
			val initial = provider.goalProgressFlow.value

			initial.gamificationEnabled shouldBe true
		}

		@Test
		fun `initial value reflects gamification disabled preference`() {
			every { anyConstructed<Preferences>().getBoolean(any(), any()) } returns false

			val provider = createProvider()
			val initial = provider.goalProgressFlow.value

			initial.gamificationEnabled shouldBe false
		}
	}

	@Nested
	@DisplayName("Flow updates")
	inner class FlowUpdates {

		@Test
		fun `emits progress when steps summary updates`() = runTest {
			val provider = createProvider()

			stepsSummaryFlow.value = StepsSummaryData(
				stepsToday = 5000,
				stepsWeek = 20_000,
				goalDay = 10_000,
				goalWeek = 50_000
			)

			val result = provider.goalProgressFlow.first { it.stepsToday > 0 }

			result.stepsToday shouldBe 5000
			result.goalSteps shouldBe 10_000
		}

		@Test
		fun `emits zero steps when summary is null`() = runTest {
			val provider = createProvider()

			stepsSummaryFlow.value = null

			val result = provider.goalProgressFlow.value

			result.stepsToday shouldBe 0
			result.goalSteps shouldBe 0
		}

		@Test
		fun `uses gamification preference from Preferences`() = runTest {
			every { anyConstructed<Preferences>().getBoolean(any(), any()) } returns false

			val provider = createProvider()

			stepsSummaryFlow.value = StepsSummaryData(
				stepsToday = 100,
				stepsWeek = 100,
				goalDay = 1000,
				goalWeek = 5000
			)

			val result = provider.goalProgressFlow.first { it.stepsToday > 0 }

			result.gamificationEnabled shouldBe false
		}
	}

	@Nested
	@DisplayName("Edge cases")
	inner class EdgeCases {

		@Test
		fun `handles zero goal day in summary`() = runTest {
			val provider = createProvider()

			stepsSummaryFlow.value = StepsSummaryData(
				stepsToday = 500,
				stepsWeek = 500,
				goalDay = 0,
				goalWeek = 0
			)

			val result = provider.goalProgressFlow.first { it.stepsToday > 0 }

			result.stepsToday shouldBe 500
			result.goalSteps shouldBe 0
		}

		@Test
		fun `GoalProgress progress calculation with valid goal`() {
			val gp = GoalProgress(stepsToday = 5000, goalSteps = 10_000, gamificationEnabled = true)
			gp.progress shouldBe 0.5f
		}

		@Test
		fun `GoalProgress progress is zero when goal is zero`() {
			val gp = GoalProgress(stepsToday = 5000, goalSteps = 0, gamificationEnabled = true)
			gp.progress shouldBe 0f
		}

		@Test
		fun `GoalProgress progress is clamped at 1`() {
			val gp = GoalProgress(stepsToday = 20_000, goalSteps = 10_000, gamificationEnabled = true)
			gp.progress shouldBe 1f
		}
	}
}
