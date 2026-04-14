package com.adsamcik.tracker.app.settings

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.map.MapSettingsRepository
import com.adsamcik.tracker.shared.preferences.map.MapSettingsState
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("MapSettingsViewModel")
class MapSettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val mapSettingsFlow = MutableStateFlow(MapSettingsState())
    private val mapSettingsRepository: MapSettingsRepository = mockk()
    private val context: Context = mockk(relaxed = true)
    private val dispatchers: DispatchersProvider = mockk(relaxed = true)
    private val preferences: Preferences = mockk(relaxed = true)
    private val tempDir = File(System.getProperty("java.io.tmpdir"), "map-settings-test-${System.nanoTime()}")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir.mkdirs()
        every { context.filesDir } returns tempDir
        every { mapSettingsRepository.data } returns mapSettingsFlow
        coEvery { mapSettingsRepository.setQuality(any()) } answers {
            mapSettingsFlow.value = mapSettingsFlow.value.copy(quality = firstArg())
        }
        coEvery { mapSettingsRepository.setMaxHeatPoints(any()) } answers {
            mapSettingsFlow.value = mapSettingsFlow.value.copy(maxHeatPoints = firstArg())
        }
        coEvery { mapSettingsRepository.setVisitThresholdSeconds(any()) } answers {
            mapSettingsFlow.value = mapSettingsFlow.value.copy(visitThresholdSeconds = firstArg())
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    private fun createViewModel() = MapSettingsViewModel(
        context = context,
        mapSettingsRepository = mapSettingsRepository,
        dispatchers = dispatchers,
        preferences = preferences,
    )

    @Nested
    @DisplayName("Initial state")
    inner class InitialState {

        @Test
        fun `quality defaults to DEFAULT_QUALITY`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.quality.value shouldBe MapSettingsState.DEFAULT_QUALITY
        }

        @Test
        fun `maxHeat defaults to DEFAULT_MAX_HEAT`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.maxHeat.value shouldBe MapSettingsState.DEFAULT_MAX_HEAT
        }

        @Test
        fun `visitThreshold defaults to DEFAULT_VISIT_THRESHOLD`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.visitThreshold.value shouldBe MapSettingsState.DEFAULT_VISIT_THRESHOLD
        }
    }

    @Nested
    @DisplayName("Quality management")
    inner class QualityManagement {

        @Test
        fun `setQuality updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setQuality(0.5f)
            advanceUntilIdle()
            vm.quality.value shouldBe 0.5f
        }

        @Test
        fun `setQuality delegates to repository`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setQuality(2.0f)
            advanceUntilIdle()
            coVerify { mapSettingsRepository.setQuality(2.0f) }
        }
    }

    @Nested
    @DisplayName("Heat points management")
    inner class HeatPointsManagement {

        @Test
        fun `setMaxHeat updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setMaxHeat(50)
            advanceUntilIdle()
            vm.maxHeat.value shouldBe 50
        }

        @Test
        fun `setMaxHeat delegates to repository`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setMaxHeat(25)
            advanceUntilIdle()
            coVerify { mapSettingsRepository.setMaxHeatPoints(25) }
        }
    }

    @Nested
    @DisplayName("Visit threshold management")
    inner class VisitThresholdManagement {

        @Test
        fun `setVisitThreshold updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setVisitThreshold(30)
            advanceUntilIdle()
            vm.visitThreshold.value shouldBe 30
        }

        @Test
        fun `setVisitThreshold delegates to repository`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setVisitThreshold(60)
            advanceUntilIdle()
            coVerify { mapSettingsRepository.setVisitThresholdSeconds(60) }
        }
    }

    @Nested
    @DisplayName("Flow observation")
    inner class FlowObservation {

        @Test
        fun `external flow changes update all fields`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            mapSettingsFlow.value = MapSettingsState(
                quality = 0.75f,
                maxHeatPoints = 100,
                visitThresholdSeconds = 45,
            )
            advanceUntilIdle()

            vm.quality.value shouldBe 0.75f
            vm.maxHeat.value shouldBe 100
            vm.visitThreshold.value shouldBe 45
        }
    }
}
