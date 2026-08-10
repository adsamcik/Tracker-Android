package com.adsamcik.tracker.app.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasSelfPermission
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.battery.QualitativeBatteryImpactEstimator
import com.adsamcik.tracker.tracker.source.coordinator.DefaultTrackingSettingsStatusProvider
import com.adsamcik.tracker.tracker.source.coordinator.SemanticAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanResolver
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorTelemetry
import com.adsamcik.tracker.tracker.source.coordinator.EffectiveSourceState
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
class TrackingSettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private var permissionsGranted = true

    // Backing state for the fake repository
    private val paramsFlow = MutableStateFlow(TrackingParamsState())
    private val trackingParamsRepository: TrackingParamsRepository = mockk()
    private val trackingStatusProvider = DefaultTrackingSettingsStatusProvider(
        SemanticAcquisitionPlanFactory(),
        SourcePlanResolver(),
        QualitativeBatteryImpactEstimator(),
        TrackingCoordinatorTelemetry(),
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Mock hasSelfPermission (called by inline hasPreciseLocationPermission)
        mockkStatic("com.adsamcik.tracker.shared.base.extension.ContextExtensionsKt")
        every { context.hasSelfPermission(any()) } answers { permissionsGranted }
        every { context.hasPressureSensor } returns true
        every { context.hasStepCounterSensor } returns true
        every { context.packageManager } returns packageManager
        every { packageManager.hasSystemFeature(any()) } returns true
        every { context.getSystemService(PowerManager::class.java) } returns null

        every { trackingParamsRepository.data } returns paramsFlow

        coEvery { trackingParamsRepository.update(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val block = invocation.args[0] as (TrackingParamsState.() -> TrackingParamsState)
            paramsFlow.value = block(paramsFlow.value)
        }

        coEvery { trackingParamsRepository.setLocationEnabled(any()) } answers {
            paramsFlow.value = paramsFlow.value.copy(locationEnabled = firstArg())
        }
        coEvery { trackingParamsRepository.setActivityEnabled(any()) } answers {
            paramsFlow.value = paramsFlow.value.copy(activityEnabled = firstArg())
        }
        coEvery { trackingParamsRepository.setStepsEnabled(any()) } answers {
            paramsFlow.value = paramsFlow.value.copy(stepsEnabled = firstArg())
        }
        coEvery { trackingParamsRepository.setWifiEnabled(any()) } answers {
            paramsFlow.value = paramsFlow.value.copy(wifiEnabled = firstArg())
        }
        coEvery { trackingParamsRepository.setCellEnabled(any()) } answers {
            paramsFlow.value = paramsFlow.value.copy(cellEnabled = firstArg())
        }
        coEvery { trackingParamsRepository.setBarometerEnabled(any()) } answers {
            paramsFlow.value = paramsFlow.value.copy(barometerEnabled = firstArg())
        }
        coEvery { trackingParamsRepository.setTransitionDetectionEnabled(any()) } answers {
            paramsFlow.value = paramsFlow.value.copy(transitionDetectionEnabled = firstArg())
        }
        coEvery { trackingParamsRepository.setNotificationStyled(any()) } answers {
            paramsFlow.value = paramsFlow.value.copy(notificationStyled = firstArg())
        }
        coEvery { trackingParamsRepository.setMinDistanceMeters(any()) } answers {
            paramsFlow.value = paramsFlow.value.copy(minDistanceMeters = firstArg())
        }
        coEvery { trackingParamsRepository.setMinTimeSeconds(any()) } answers {
            paramsFlow.value = paramsFlow.value.copy(minTimeSeconds = firstArg())
        }
        coEvery { trackingParamsRepository.setRequiredAccuracyMeters(any()) } answers {
            paramsFlow.value = paramsFlow.value.copy(requiredAccuracyMeters = firstArg())
        }
        coEvery { trackingParamsRepository.setPreset(any()) } answers {
            paramsFlow.value = paramsFlow.value.copy(presetName = firstArg<TrackingPreset>().name)
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic("com.adsamcik.tracker.shared.base.extension.ContextExtensionsKt")
    }

    private fun createViewModel(): TrackingSettingsViewModel {
        paramsFlow.value = TrackingParamsState()
        permissionsGranted = true
        return TrackingSettingsViewModel(context, trackingParamsRepository, trackingStatusProvider)
    }

    // =========================================================================
    // Initial state
    // =========================================================================

    @Nested
    @DisplayName("Initial state")
    inner class InitialState {

        @Test
        fun `currentPreset defaults to DEFAULT`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.uiState.value.currentPreset shouldBe TrackingPreset.DEFAULT
        }

        @Test
        fun `locationEnabled defaults to true`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.uiState.value.locationEnabled shouldBe true
        }

        @Test
        fun `hasValidSources is true when sources enabled`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.uiState.value.hasValidSources shouldBe true
        }

        @Test
        fun `minDistance defaults to 10`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.uiState.value.minDistance shouldBe 10
        }

        @Test
        fun `minTime defaults to 2`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.uiState.value.minTime shouldBe 2
        }

        @Test
        fun `requiredAccuracy defaults to 50`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.uiState.value.requiredAccuracy shouldBe 50
        }
    }

    // =========================================================================
    // Source toggles
    // =========================================================================

    @Nested
    @DisplayName("Source toggles")
    inner class SourceToggles {

        @Test
        fun `setLocationEnabled updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setLocationEnabled(false)
            advanceUntilIdle()
            vm.uiState.value.locationEnabled shouldBe false
        }

        @Test
        fun `setActivityEnabled updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setActivityEnabled(false)
            advanceUntilIdle()
            vm.uiState.value.activityEnabled shouldBe false
        }

        @Test
        fun `setStepsEnabled updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setStepsEnabled(false)
            advanceUntilIdle()
            vm.uiState.value.stepsEnabled shouldBe false
        }

        @Test
        fun `setWifiEnabled updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setWifiEnabled(false)
            advanceUntilIdle()
            vm.uiState.value.wifiEnabled shouldBe false
        }

        @Test
        fun `setCellEnabled updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setCellEnabled(false)
            advanceUntilIdle()
            vm.uiState.value.cellEnabled shouldBe false
        }

        @Test
        fun `setBarometerEnabled updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setBarometerEnabled(false)
            advanceUntilIdle()
            vm.uiState.value.barometerEnabled shouldBe false
        }

        @Test
        fun `barometer request remains stored when the device has no pressure sensor`() = runTest(testDispatcher) {
            every { context.hasPressureSensor } returns false
            val vm = createViewModel()
            advanceUntilIdle()

            vm.uiState.value.barometerAvailable shouldBe false
            vm.uiState.value.barometerEnabled shouldBe true
            vm.uiState.value.sourceStatuses.getValue(SourceKind.PRESSURE).state shouldBe
                EffectiveSourceState.BLOCKED
        }
    }

    // =========================================================================
    // Tracking parameters
    // =========================================================================

    @Nested
    @DisplayName("Tracking parameters")
    inner class TrackingParameters {

        @Test
        fun `setMinDistance updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setMinDistance(25)
            advanceUntilIdle()
            vm.uiState.value.minDistance shouldBe 25
        }

        @Test
        fun `setMinTime updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setMinTime(15)
            advanceUntilIdle()
            vm.uiState.value.minTime shouldBe 15
        }

        @Test
        fun `setRequiredAccuracy updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setRequiredAccuracy(100)
            advanceUntilIdle()
            vm.uiState.value.requiredAccuracy shouldBe 100
        }
    }

    // =========================================================================
    // Notification and auto-tracking
    // =========================================================================

    @Nested
    @DisplayName("Notification and auto-tracking")
    inner class NotificationAndAutoTracking {

        @Test
        fun `setNotificationStyled updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setNotificationStyled(false)
            advanceUntilIdle()
            vm.uiState.value.notificationStyled shouldBe false
        }

        @Test
        fun `setTransitionDetectionEnabled updates state`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setTransitionDetectionEnabled(false)
            advanceUntilIdle()
            vm.uiState.value.transitionDetectionEnabled shouldBe false
        }

        @Test
        fun `autoTrackingEnabled reflects repository flow`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            // Default autoTrackingMode is 1 -> true
            vm.uiState.value.autoTrackingEnabled shouldBe true

            paramsFlow.value = paramsFlow.value.copy(autoTrackingMode = 0)
            advanceUntilIdle()
            vm.uiState.value.autoTrackingEnabled shouldBe false
        }
    }

    // =========================================================================
    // Source validation
    // =========================================================================

    @Nested
    @DisplayName("Source validation")
    inner class SourceValidation {

        @Test
        fun `disabling all sources sets hasValidSources to false`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setLocationEnabled(false)
            vm.setActivityEnabled(false)
            vm.setStepsEnabled(false)
            vm.setWifiEnabled(false)
            vm.setCellEnabled(false)
            vm.setBarometerEnabled(false)
            advanceUntilIdle()

            vm.uiState.value.hasValidSources shouldBe false
        }

        @Test
        fun `re-enabling one source restores hasValidSources`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setLocationEnabled(false)
            vm.setActivityEnabled(false)
            vm.setStepsEnabled(false)
            vm.setWifiEnabled(false)
            vm.setCellEnabled(false)
            vm.setBarometerEnabled(false)
            advanceUntilIdle()
            vm.uiState.value.hasValidSources shouldBe false

            vm.setLocationEnabled(true)
            advanceUntilIdle()
            vm.uiState.value.hasValidSources shouldBe true
        }

        @Test
        fun `single source enabled keeps hasValidSources true`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Enable cell first, then disable all others
            vm.setCellEnabled(true)
            vm.setLocationEnabled(false)
            vm.setActivityEnabled(false)
            vm.setStepsEnabled(false)
            vm.setWifiEnabled(false)
            vm.setBarometerEnabled(false)
            advanceUntilIdle()

            vm.uiState.value.hasValidSources shouldBe true
        }
    }

    // =========================================================================
    // Preset management
    // =========================================================================

    @Nested
    @DisplayName("Preset management")
    inner class PresetManagement {

        @Test
        fun `applyPreset POWER_SAVE updates all settings`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.applyPreset(TrackingPreset.POWER_SAVE)
            advanceUntilIdle()

            val config = TrackingPreset.POWER_SAVE
            vm.uiState.value.locationEnabled shouldBe config.locationEnabled
            vm.uiState.value.activityEnabled shouldBe config.activityEnabled
            vm.uiState.value.stepsEnabled shouldBe config.stepsEnabled
            vm.uiState.value.wifiEnabled shouldBe config.wifiEnabled
            vm.uiState.value.cellEnabled shouldBe config.cellEnabled
            vm.uiState.value.barometerEnabled shouldBe config.barometerEnabled
            vm.uiState.value.minDistance shouldBe config.minDistanceMeters
            vm.uiState.value.minTime shouldBe config.minTimeSeconds
            vm.uiState.value.requiredAccuracy shouldBe config.requiredAccuracyMeters
            vm.uiState.value.currentPreset shouldBe TrackingPreset.POWER_SAVE
            vm.uiState.value.currentBatteryImpact shouldBe BatteryImpact.LOW
        }

        @Test
        fun `applyPreset HIGH_ACCURACY updates all settings`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.applyPreset(TrackingPreset.HIGH_ACCURACY)
            advanceUntilIdle()

            val config = TrackingPreset.HIGH_ACCURACY
            vm.uiState.value.locationEnabled shouldBe config.locationEnabled
            vm.uiState.value.wifiEnabled shouldBe config.wifiEnabled
            vm.uiState.value.cellEnabled shouldBe config.cellEnabled
            vm.uiState.value.barometerEnabled shouldBe config.barometerEnabled
            vm.uiState.value.minDistance shouldBe config.minDistanceMeters
            vm.uiState.value.minTime shouldBe config.minTimeSeconds
            vm.uiState.value.requiredAccuracy shouldBe config.requiredAccuracyMeters
            vm.uiState.value.currentPreset shouldBe TrackingPreset.HIGH_ACCURACY
            vm.uiState.value.currentBatteryImpact shouldBe BatteryImpact.HIGH
        }

        @Test
        fun `changing a setting after preset marks custom`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.applyPreset(TrackingPreset.BALANCED)
            advanceUntilIdle()
            vm.uiState.value.currentPreset shouldBe TrackingPreset.BALANCED

            vm.setMinDistance(999)
            advanceUntilIdle()
            vm.uiState.value.currentPreset shouldBe TrackingPreset.CUSTOM
        }

        @Test
        fun `building from a preset keeps other preset values when one is edited`() =
            runTest(testDispatcher) {
                val vm = createViewModel()
                advanceUntilIdle()

                vm.applyPreset(TrackingPreset.POWER_SAVE)
                advanceUntilIdle()

                // Fine-tune a single control on top of the preset.
                vm.setMinDistance(42)
                advanceUntilIdle()

                // The profile becomes Custom, the edited value is applied, and the remaining
                // POWER_SAVE values are preserved (the user builds from the preset rather than
                // starting from scratch).
                vm.uiState.value.currentPreset shouldBe TrackingPreset.CUSTOM
                vm.uiState.value.minDistance shouldBe 42
                vm.uiState.value.minTime shouldBe TrackingPreset.POWER_SAVE.minTimeSeconds
                vm.uiState.value.requiredAccuracy shouldBe TrackingPreset.POWER_SAVE.requiredAccuracyMeters
                vm.uiState.value.stepsEnabled shouldBe TrackingPreset.POWER_SAVE.stepsEnabled
            }

        @Test
        fun `applying preset after custom restores named preset`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Go custom
            vm.setMinDistance(999)
            advanceUntilIdle()
            vm.uiState.value.currentPreset shouldBe TrackingPreset.CUSTOM

            // Apply named preset
            vm.applyPreset(TrackingPreset.BALANCED)
            advanceUntilIdle()
            vm.uiState.value.currentPreset shouldBe TrackingPreset.BALANCED
        }

        @Test
        fun `applyPreset validates sources`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // All presets have at least location enabled
            vm.applyPreset(TrackingPreset.POWER_SAVE)
            advanceUntilIdle()
            vm.uiState.value.hasValidSources shouldBe true
        }

        @Test
        fun `applyPreset persists requested wifi when wifi permission is denied`() =
            runTest(testDispatcher) {
                permissionsGranted = false
                val vm = TrackingSettingsViewModel(context, trackingParamsRepository, trackingStatusProvider)
                advanceUntilIdle()

                vm.applyPreset(TrackingPreset.HIGH_ACCURACY)
                advanceUntilIdle()

                paramsFlow.value.wifiEnabled shouldBe true
                vm.uiState.value.wifiEnabled shouldBe true
            }
    }

    @Nested
    @DisplayName("Permission refresh")
    inner class PermissionRefresh {

        @Test
        fun `refreshPermissionState updates effective wifi state without repository emission`() =
            runTest(testDispatcher) {
                permissionsGranted = false
                paramsFlow.value = TrackingParamsState(wifiEnabled = true)
                val vm = TrackingSettingsViewModel(context, trackingParamsRepository, trackingStatusProvider)
                advanceUntilIdle()
                vm.uiState.value.wifiEnabled shouldBe true

                permissionsGranted = true
                vm.refreshPermissionState()

                vm.uiState.value.wifiEnabled shouldBe true
            }
    }

    // =========================================================================
    // Battery impact
    // =========================================================================

    @Nested
    @DisplayName("Battery impact")
    inner class BatteryImpactTests {

        @Test
        fun `applyPreset updates battery impact`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.applyPreset(TrackingPreset.POWER_SAVE)
            advanceUntilIdle()
            vm.uiState.value.currentBatteryImpact shouldBe BatteryImpact.LOW

            vm.applyPreset(TrackingPreset.HIGH_ACCURACY)
            advanceUntilIdle()
            vm.uiState.value.currentBatteryImpact shouldBe BatteryImpact.HIGH
        }
    }
}
