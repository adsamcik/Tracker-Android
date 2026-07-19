package com.adsamcik.tracker.app.settings

import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import com.adsamcik.tracker.game.goals.settings.RememberedGameSetup
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameSettingsViewModelTest {

	private val testDispatcher = UnconfinedTestDispatcher()
	private lateinit var repository: FakeGoalsSettingsRepository

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		repository = FakeGoalsSettingsRepository()
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `publishes repository settings as content`() = runTest(testDispatcher) {
		val viewModel = GameSettingsViewModel(repository)
		advanceUntilIdle()

		val content = viewModel.uiState.value as GameSettingsUiState.Content
		content.settings shouldBe GoalsSettingsState.defaults()
	}

	@Test
	fun `goal mutations persist through the repository`() = runTest(testDispatcher) {
		val viewModel = GameSettingsViewModel(repository)
		advanceUntilIdle()

		viewModel.setDailyStepGoal(8_000)
		viewModel.setWeeklyStepGoal(56_000)
		viewModel.setWeeklyDailyLimit(0.4f)
		advanceUntilIdle()

		repository.current.dailyStepGoal shouldBe 8_000
		repository.current.weeklyStepGoal shouldBe 56_000
		repository.current.weeklyProgressDailyLimit shouldBe 0.4f
	}

	@Test
	fun `notification and game preference mutations persist through the repository`() = runTest(testDispatcher) {
		val viewModel = GameSettingsViewModel(repository)
		advanceUntilIdle()

		viewModel.setNotificationsEnabled(false)
		viewModel.setGameHapticsEnabled(false)
		viewModel.setQuietCoachingEnabled(false)
		viewModel.setRememberLastSetup(true)
		advanceUntilIdle()

		repository.current.notificationsEnabled shouldBe false
		repository.current.gameHapticsEnabled shouldBe false
		repository.current.quietCoachingEnabled shouldBe false
		repository.current.rememberLastSetup shouldBe true
	}
}

private class FakeGoalsSettingsRepository(
	initial: GoalsSettingsState = GoalsSettingsState.defaults(),
) : GoalsSettingsRepository {
	private val state = MutableStateFlow(initial)
	override val data: Flow<GoalsSettingsState> = state
	val current: GoalsSettingsState get() = state.value

	override suspend fun setNotificationsEnabled(enabled: Boolean) = update { copy(notificationsEnabled = enabled) }
	override suspend fun setDailyStepGoal(steps: Int) = update { copy(dailyStepGoal = steps) }
	override suspend fun setWeeklyStepGoal(steps: Int) = update { copy(weeklyStepGoal = steps) }
	override suspend fun setWeeklyDailyLimit(fraction: Float) = update { copy(weeklyProgressDailyLimit = fraction) }
	override suspend fun setGameHapticsEnabled(enabled: Boolean) = update { copy(gameHapticsEnabled = enabled) }
	override suspend fun setQuietCoachingEnabled(enabled: Boolean) = update { copy(quietCoachingEnabled = enabled) }
	override suspend fun setRememberLastSetup(enabled: Boolean) = update { copy(rememberLastSetup = enabled) }
	override suspend fun setRememberedOutrunSetup(goalMeters: Int, difficulty: String?) =
		update { copy(rememberedOutrunSetup = RememberedGameSetup(goalMeters, difficulty)) }
	override suspend fun setRememberedTerritorySetup(goalCells: Int, difficulty: String?) =
		update { copy(rememberedTerritorySetup = RememberedGameSetup(goalCells, difficulty)) }
	override suspend fun setRememberedZenSetup(goalMinutes: Int, difficulty: String?) =
		update { copy(rememberedZenSetup = RememberedGameSetup(goalMinutes, difficulty)) }
	override suspend fun setRememberedFuseRunSetup(goalCharges: Int, difficulty: String?) =
		update { copy(rememberedFuseRunSetup = RememberedGameSetup(goalCharges, difficulty)) }
	override suspend fun setRememberedSwitchbackSetup(goalTurns: Int, difficulty: String?) =
		update { copy(rememberedSwitchbackSetup = RememberedGameSetup(goalTurns, difficulty)) }
	override suspend fun setDailyGoalReachedPeriod(period: Int?) = update { copy(dailyGoalReachedPeriod = period) }
	override suspend fun setWeeklyGoalReachedPeriod(period: Int?) = update { copy(weeklyGoalReachedPeriod = period) }

	private inline fun update(transform: GoalsSettingsState.() -> GoalsSettingsState) {
		state.value = state.value.transform()
	}
}
