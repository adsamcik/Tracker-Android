package com.adsamcik.tracker.app.settings

import com.adsamcik.tracker.game.goals.settings.GoalsSettingsRepository
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("GameSettingsViewModel")
class GameSettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val goalsFlow = MutableStateFlow(
        GoalsSettingsState(
            notificationsEnabled = true,
            dailyStepGoal = 10000,
            weeklyStepGoal = 70000,
            weeklyProgressDailyLimit = 1.5f,
            challengesEnabled = true,
        )
    )
    private val goalsSettingsRepository: GoalsSettingsRepository = mockk()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { goalsSettingsRepository.data } returns goalsFlow
        coEvery { goalsSettingsRepository.setChallengesEnabled(any()) } answers {
            goalsFlow.value = goalsFlow.value.copy(challengesEnabled = firstArg())
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = GameSettingsViewModel(goalsSettingsRepository)

    @Nested
    @DisplayName("Initial state")
    inner class InitialState {

        @Test
        fun `challengeEnabled reflects initial repository state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.challengeEnabled.value shouldBe true
        }

        @Test
        fun `challengeEnabled reflects false initial state`() = runTest(testDispatcher) {
            goalsFlow.value = goalsFlow.value.copy(challengesEnabled = false)
            val vm = createViewModel()
            advanceUntilIdle()
            vm.challengeEnabled.value shouldBe false
        }
    }

    @Nested
    @DisplayName("Challenge toggle")
    inner class ChallengeToggle {

        @Test
        fun `setChallengeEnabled false updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChallengeEnabled(false)
            advanceUntilIdle()
            vm.challengeEnabled.value shouldBe false
        }

        @Test
        fun `setChallengeEnabled true updates state`() = runTest(testDispatcher) {
            goalsFlow.value = goalsFlow.value.copy(challengesEnabled = false)
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChallengeEnabled(true)
            advanceUntilIdle()
            vm.challengeEnabled.value shouldBe true
        }

        @Test
        fun `setChallengeEnabled delegates to repository`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChallengeEnabled(false)
            advanceUntilIdle()

            coVerify { goalsSettingsRepository.setChallengesEnabled(false) }
        }
    }

    @Nested
    @DisplayName("Flow observation")
    inner class FlowObservation {

        @Test
        fun `external flow changes update challengeEnabled`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.challengeEnabled.value shouldBe true

            goalsFlow.value = goalsFlow.value.copy(challengesEnabled = false)
            advanceUntilIdle()
            vm.challengeEnabled.value shouldBe false
        }
    }
}
