package com.adsamcik.tracker.app.settings

import app.cash.turbine.test
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
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("SettingsViewModel")
class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val settingsFlow = MutableStateFlow(
        TrackerSettingsState(
            autoUnitSwitch = false,
            lengthSystem = LengthSystem.Metric,
            speedFormat = SpeedFormat.Hour,
        )
    )
    private val repo: TrackerSettingsRepository = mockk()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { repo.data } returns settingsFlow
        coEvery { repo.setAutoUnitSwitch(any()) } answers {
            settingsFlow.value = settingsFlow.value.copy(autoUnitSwitch = firstArg())
        }
        coEvery { repo.setLengthSystem(any()) } answers {
            settingsFlow.value = settingsFlow.value.copy(lengthSystem = firstArg())
        }
        coEvery { repo.setSpeedFormat(any()) } answers {
            settingsFlow.value = settingsFlow.value.copy(speedFormat = firstArg())
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = SettingsViewModel(repo)

    @Nested
    @DisplayName("Initial state")
    inner class InitialState {

        @Test
        fun `settings has correct defaults before flow emits`() = runTest(testDispatcher) {
            val vm = createViewModel()
            val state = vm.settings.value
            state.autoUnitSwitch shouldBe false
            state.lengthSystem shouldBe LengthSystem.Metric
            state.speedFormat shouldBe SpeedFormat.Hour
        }
    }

    @Nested
    @DisplayName("Length system")
    inner class LengthSystemTests {

        @Test
        fun `setLengthSystem parses Metric`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setLengthSystem("Metric")
            advanceUntilIdle()
            coVerify { repo.setLengthSystem(LengthSystem.Metric) }
        }

        @Test
        fun `setLengthSystem parses Imperial`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setLengthSystem("Imperial")
            advanceUntilIdle()
            coVerify { repo.setLengthSystem(LengthSystem.Imperial) }
        }

        @Test
        fun `setLengthSystem parses Sailing`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setLengthSystem("Sailing")
            advanceUntilIdle()
            coVerify { repo.setLengthSystem(LengthSystem.Sailing) }
        }

        @Test
        fun `setLengthSystem throws on invalid string`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            assertThrows<IllegalArgumentException> {
                vm.setLengthSystem("InvalidSystem")
            }
        }

        @Test
        fun `setLengthSystem updates flow`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.settings.test {
                awaitItem() // initial

                vm.setLengthSystem("Imperial")
                awaitItem().lengthSystem shouldBe LengthSystem.Imperial
            }
        }
    }

    @Nested
    @DisplayName("Speed format")
    inner class SpeedFormatTests {

        @Test
        fun `setSpeedFormat parses Hour`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setSpeedFormat("Hour")
            advanceUntilIdle()
            coVerify { repo.setSpeedFormat(SpeedFormat.Hour) }
        }

        @Test
        fun `setSpeedFormat parses Minute`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setSpeedFormat("Minute")
            advanceUntilIdle()
            coVerify { repo.setSpeedFormat(SpeedFormat.Minute) }
        }

        @Test
        fun `setSpeedFormat parses Second`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setSpeedFormat("Second")
            advanceUntilIdle()
            coVerify { repo.setSpeedFormat(SpeedFormat.Second) }
        }

        @Test
        fun `setSpeedFormat throws on invalid string`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            assertThrows<IllegalArgumentException> {
                vm.setSpeedFormat("InvalidFormat")
            }
        }

        @Test
        fun `setSpeedFormat updates flow`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.settings.test {
                awaitItem() // initial

                vm.setSpeedFormat("Minute")
                awaitItem().speedFormat shouldBe SpeedFormat.Minute
            }
        }
    }

    @Nested
    @DisplayName("Auto unit switch")
    inner class AutoUnitSwitchTests {

        @Test
        fun `setAutoUnitSwitch enables`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.settings.test {
                awaitItem().autoUnitSwitch shouldBe false

                vm.setAutoUnitSwitch(true)
                awaitItem().autoUnitSwitch shouldBe true
            }
        }

        @Test
        fun `setAutoUnitSwitch disables`() = runTest(testDispatcher) {
            settingsFlow.value = settingsFlow.value.copy(autoUnitSwitch = true)
            val vm = createViewModel()
            vm.settings.test {
                awaitItem().autoUnitSwitch shouldBe true

                vm.setAutoUnitSwitch(false)
                awaitItem().autoUnitSwitch shouldBe false
            }
        }

        @Test
        fun `setAutoUnitSwitch delegates to repository`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setAutoUnitSwitch(true)
            advanceUntilIdle()
            coVerify { repo.setAutoUnitSwitch(true) }
        }
    }

    @Nested
    @DisplayName("Flow observation")
    inner class FlowObservation {

        @Test
        fun `external flow changes update settings state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.settings.test {
                awaitItem() // initial

                settingsFlow.value = TrackerSettingsState(
                    autoUnitSwitch = true,
                    lengthSystem = LengthSystem.Flying,
                    speedFormat = SpeedFormat.Second,
                )
                val state = awaitItem()
                state.autoUnitSwitch shouldBe true
                state.lengthSystem shouldBe LengthSystem.Flying
                state.speedFormat shouldBe SpeedFormat.Second
            }
        }
    }
}
