package com.adsamcik.tracker.app.settings

import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat
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
@DisplayName("StatisticsSettingsViewModel")
class StatisticsSettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val settingsFlow = MutableStateFlow(
        TrackerSettingsState(
            autoUnitSwitch = false,
            lengthSystem = LengthSystem.Metric,
            speedFormat = SpeedFormat.Hour,
        )
    )
    private val settingsRepository: TrackerSettingsRepository = mockk()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.data } returns settingsFlow
        coEvery { settingsRepository.setAutoUnitSwitch(any()) } answers {
            settingsFlow.value = settingsFlow.value.copy(autoUnitSwitch = firstArg())
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = StatisticsSettingsViewModel(settingsRepository)

    @Nested
    @DisplayName("Initial state")
    inner class InitialState {

        @Test
        fun `autoUnitSwitch defaults to false`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.autoUnitSwitch.value shouldBe false
        }

        @Test
        fun `autoUnitSwitch reflects true initial state`() = runTest(testDispatcher) {
            settingsFlow.value = settingsFlow.value.copy(autoUnitSwitch = true)
            val vm = createViewModel()
            advanceUntilIdle()
            vm.autoUnitSwitch.value shouldBe true
        }
    }

    @Nested
    @DisplayName("Auto unit switch toggle")
    inner class AutoUnitSwitchToggle {

        @Test
        fun `setAutoUnitSwitch enables`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setAutoUnitSwitch(true)
            advanceUntilIdle()
            vm.autoUnitSwitch.value shouldBe true
        }

        @Test
        fun `setAutoUnitSwitch disables`() = runTest(testDispatcher) {
            settingsFlow.value = settingsFlow.value.copy(autoUnitSwitch = true)
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setAutoUnitSwitch(false)
            advanceUntilIdle()
            vm.autoUnitSwitch.value shouldBe false
        }

        @Test
        fun `setAutoUnitSwitch delegates to repository`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setAutoUnitSwitch(true)
            advanceUntilIdle()

            coVerify { settingsRepository.setAutoUnitSwitch(true) }
        }
    }

    @Nested
    @DisplayName("Flow observation")
    inner class FlowObservation {

        @Test
        fun `external flow changes update autoUnitSwitch`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.autoUnitSwitch.value shouldBe false

            settingsFlow.value = settingsFlow.value.copy(autoUnitSwitch = true)
            advanceUntilIdle()
            vm.autoUnitSwitch.value shouldBe true
        }
    }
}
