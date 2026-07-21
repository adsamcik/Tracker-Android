package com.adsamcik.tracker.app.onboarding.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.adsamcik.tracker.app.onboarding.data.SetupStep
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.maintenance.DataRetentionScheduler
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesRepository
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesState
import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private const val AUTO_TRACKING_MODE_DISABLED = 0
private const val AUTO_TRACKING_MODE_ON_FOOT = 1
private const val AUTO_TRACKING_MODE_IN_MOTION = 2

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("SetupViewModel")
class SetupViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val appContext: Context = mockk(relaxed = true)
    private val dispatchers: DispatchersProvider = mockk(relaxed = true)
    private val onboardingRepository: OnboardingRepository = mockk(relaxed = true)
    private val activityWatcherController: ActivityWatcherServiceController = mockk(relaxed = true)
    private val dataRetentionScheduler: DataRetentionScheduler = mockk(relaxed = true)
    private val paramsFlow = MutableStateFlow(TrackingParamsState())
    private val trackingParamsRepository: TrackingParamsRepository = mockk()
    private val onlineTilesFlow = MutableStateFlow(OnlineMapTilesState())
    private val onlineMapTilesRepository: OnlineMapTilesRepository = mockk()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        paramsFlow.value = TrackingParamsState()
        onlineTilesFlow.value = OnlineMapTilesState()
        every { dispatchers.io } returns testDispatcher
        every { trackingParamsRepository.data } returns paramsFlow
        coEvery { trackingParamsRepository.update(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val block = invocation.args[0] as (TrackingParamsState.() -> TrackingParamsState)
            paramsFlow.value = block(paramsFlow.value)
        }
        every { onlineMapTilesRepository.data } returns onlineTilesFlow
        coEvery { onlineMapTilesRepository.setEnabled(any()) } answers {
            onlineTilesFlow.value = onlineTilesFlow.value.copy(enabled = firstArg())
        }
        coEvery { onboardingRepository.markCompleted() } returns Unit
        every { dataRetentionScheduler.initialize() } returns Unit
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) = SetupViewModel(
        appContext = appContext,
        savedStateHandle = savedStateHandle,
        dispatchers = dispatchers,
        onboardingRepository = onboardingRepository,
        activityWatcherController = activityWatcherController,
        dataRetentionScheduler = dataRetentionScheduler,
        trackingParamsRepository = trackingParamsRepository,
        onlineMapTilesRepository = onlineMapTilesRepository,
    )

    @Nested
    @DisplayName("Initial state")
    inner class InitialState {

        @Test
        fun `starts on Welcome step`() {
            val vm = createViewModel()
            vm.state.value.currentStep shouldBe SetupStep.Welcome
        }

        @Test
        fun `default auto-tracking mode is on foot`() {
            val vm = createViewModel()
            vm.state.value.autoTrackingMode shouldBe AUTO_TRACKING_MODE_ON_FOOT
        }

        @Test
        fun `default tracking preset is DEFAULT`() {
            val vm = createViewModel()
            vm.state.value.trackingPreset shouldBe TrackingPolicyPreset.DEFAULT
        }

        @Test
        fun `location is enabled by default`() {
            val vm = createViewModel()
            vm.state.value.locationEnabled shouldBe true
        }

        @Test
        fun `permissions default to not granted`() {
            val vm = createViewModel()
            val state = vm.state.value
            state.locationPermissionGranted shouldBe false
            state.backgroundLocationGranted shouldBe false
            state.activityPermissionGranted shouldBe false
            state.notificationPermissionGranted shouldBe false
            state.wifiPermissionGranted shouldBe false
            state.cellPermissionGranted shouldBe false
        }

        @Test
        fun `restores partial draft from saved state handle`() {
            val savedStateHandle = SavedStateHandle(
                mapOf(
                    "setup_step" to SetupStep.WhatToCollect.index,
                    "setup_tracking_preset" to TrackingPolicyPreset.HIGH_PRECISION.name,
                    "setup_location_enabled" to false,
                    "setup_location_precision" to LocationPrecisionMode.APPROXIMATE.name,
                    "setup_wifi_enabled" to true,
                    "setup_cell_enabled" to true,
                    "setup_online_map_tiles_enabled" to true,
                ),
            )

            val state = createViewModel(savedStateHandle).state.value

            state.currentStep shouldBe SetupStep.WhatToCollect
            state.trackingPreset shouldBe TrackingPolicyPreset.HIGH_PRECISION
            state.locationEnabled shouldBe false
            state.locationPrecision shouldBe LocationPrecisionMode.APPROXIMATE
            state.wifiEnabled shouldBe true
            state.cellEnabled shouldBe true
            state.onlineMapTilesEnabled shouldBe true
        }
    }

    @Nested
    @DisplayName("Step navigation")
    inner class StepNavigation {

        @Test
        fun `goToNextStep advances from Welcome to HowToTrack`() {
            val vm = createViewModel()
            vm.goToNextStep()
            vm.state.value.currentStep shouldBe SetupStep.HowToTrack
        }

        @Test
        fun `mutations are saved to and restored from the same saved state handle`() {
            val savedStateHandle = SavedStateHandle()
            val original = createViewModel(savedStateHandle)

            original.goToNextStep()
            original.setWifiEnabled(true)

            savedStateHandle.get<Int>("setup_step") shouldBe SetupStep.HowToTrack.index
            savedStateHandle.get<Boolean>("setup_wifi_enabled") shouldBe true

            val recreated = createViewModel(savedStateHandle)
            recreated.state.value.currentStep shouldBe SetupStep.HowToTrack
            recreated.state.value.wifiEnabled shouldBe true
        }

        @Test
        fun `goToNextStep advances from HowToTrack to WhatToCollect`() {
            val vm = createViewModel()
            vm.goToNextStep()
            vm.goToNextStep()
            vm.state.value.currentStep shouldBe SetupStep.WhatToCollect
        }

        @Test
        fun `goToNextStep advances from WhatToCollect to BackgroundAccess`() {
            val vm = createViewModel()
            vm.goToNextStep()
            vm.goToNextStep()
            vm.state.value.currentStep shouldBe SetupStep.WhatToCollect
            vm.goToNextStep()
            vm.state.value.currentStep shouldBe SetupStep.BackgroundAccess
        }

        @Test
        fun `goToNextStep advances from BackgroundAccess to OnlineMapTiles`() {
            val vm = createViewModel()
            vm.goToNextStep()
            vm.goToNextStep()
            vm.goToNextStep()
            vm.goToNextStep()
            vm.state.value.currentStep shouldBe SetupStep.OnlineMapTiles
        }

        @Test
        fun `goToNextStep does not advance past last step`() {
            val vm = createViewModel()
            vm.goToNextStep() // HowToTrack
            vm.goToNextStep() // WhatToCollect
            vm.goToNextStep() // BackgroundAccess
            vm.goToNextStep() // OnlineMapTiles
            vm.goToNextStep() // should stay
            vm.state.value.currentStep shouldBe SetupStep.OnlineMapTiles
        }

        @Test
        fun `goToPreviousStep goes back from HowToTrack to Welcome`() {
            val vm = createViewModel()
            vm.goToNextStep()
            vm.goToPreviousStep()
            vm.state.value.currentStep shouldBe SetupStep.Welcome
        }

        @Test
        fun `goToPreviousStep does not go before Welcome`() {
            val vm = createViewModel()
            vm.goToPreviousStep()
            vm.state.value.currentStep shouldBe SetupStep.Welcome
        }

        @Test
        fun `full forward then backward navigation`() {
            val vm = createViewModel()
            vm.goToNextStep()
            vm.goToNextStep()
            vm.goToNextStep()
            vm.goToNextStep()
            vm.state.value.currentStep shouldBe SetupStep.OnlineMapTiles

            vm.goToPreviousStep()
            vm.state.value.currentStep shouldBe SetupStep.BackgroundAccess

            vm.goToPreviousStep()
            vm.state.value.currentStep shouldBe SetupStep.WhatToCollect

            vm.goToPreviousStep()
            vm.state.value.currentStep shouldBe SetupStep.HowToTrack

            vm.goToPreviousStep()
            vm.state.value.currentStep shouldBe SetupStep.Welcome
        }
    }

    @Nested
    @DisplayName("Online map tiles step")
    inner class OnlineMapTilesStep {

        @Test
        fun `online map tiles default to disabled`() {
            val vm = createViewModel()
            vm.state.value.onlineMapTilesEnabled shouldBe false
        }

        @Test
        fun `setOnlineMapTilesEnabled toggles state`() {
            val vm = createViewModel()
            vm.setOnlineMapTilesEnabled(true)
            vm.state.value.onlineMapTilesEnabled shouldBe true
            vm.setOnlineMapTilesEnabled(false)
            vm.state.value.onlineMapTilesEnabled shouldBe false
        }
    }

    @Nested
    @DisplayName("How to Track settings")
    inner class HowToTrack {

        @Test
        fun `setAutoTrackingMode updates mode`() {
            val vm = createViewModel()
            vm.setAutoTrackingMode(AUTO_TRACKING_MODE_IN_MOTION)
            vm.state.value.autoTrackingMode shouldBe AUTO_TRACKING_MODE_IN_MOTION
        }

        @Test
        fun `setAutoTrackingMode accepts disabled mode`() {
            val vm = createViewModel()
            vm.setAutoTrackingMode(AUTO_TRACKING_MODE_DISABLED)
            vm.state.value.autoTrackingMode shouldBe AUTO_TRACKING_MODE_DISABLED
        }

        @Test
        fun `setTrackingPreset updates preset`() {
            val vm = createViewModel()
            vm.setTrackingPreset(TrackingPolicyPreset.HIGH_PRECISION)
            vm.state.value.trackingPreset shouldBe TrackingPolicyPreset.HIGH_PRECISION
        }
    }

    @Nested
    @DisplayName("What to Collect settings")
    inner class WhatToCollect {

        @Test
        fun `setLocationEnabled updates state`() {
            val vm = createViewModel()
            vm.setLocationEnabled(false)
            vm.state.value.locationEnabled shouldBe false
        }

        @Test
        fun `setLocationPrecision updates state`() {
            val vm = createViewModel()
            vm.setLocationPrecision(LocationPrecisionMode.APPROXIMATE)
            vm.state.value.locationPrecision shouldBe LocationPrecisionMode.APPROXIMATE
        }

        @Test
        fun `setActivityEnabled updates state`() {
            val vm = createViewModel()
            vm.setActivityEnabled(false)
            vm.state.value.activityEnabled shouldBe false
        }

        @Test
        fun `setStepsEnabled updates state`() {
            val vm = createViewModel()
            vm.setStepsEnabled(false)
            vm.state.value.stepsEnabled shouldBe false
        }

        @Test
        fun `setWifiEnabled updates state`() {
            val vm = createViewModel()
            vm.setWifiEnabled(true)
            vm.state.value.wifiEnabled shouldBe true
        }

        @Test
        fun `setCellEnabled updates state`() {
            val vm = createViewModel()
            vm.setCellEnabled(true)
            vm.state.value.cellEnabled shouldBe true
        }
    }

    @Nested
    @DisplayName("Permission tracking")
    inner class PermissionTracking {

        @Test
        fun `onLocationPermissionResult updates state`() {
            val vm = createViewModel()
            vm.onLocationPermissionResult(true)
            vm.state.value.locationPermissionGranted shouldBe true
        }

        @Test
        fun `onBackgroundLocationResult updates state`() {
            val vm = createViewModel()
            vm.onBackgroundLocationResult(true)
            vm.state.value.backgroundLocationGranted shouldBe true
        }

        @Test
        fun `onActivityPermissionResult updates state`() {
            val vm = createViewModel()
            vm.onActivityPermissionResult(true)
            vm.state.value.activityPermissionGranted shouldBe true
        }

        @Test
        fun `onNotificationPermissionResult updates state`() {
            val vm = createViewModel()
            vm.onNotificationPermissionResult(true)
            vm.state.value.notificationPermissionGranted shouldBe true
        }

        @Test
        fun `onWifiPermissionResult updates state`() {
            val vm = createViewModel()
            vm.onWifiPermissionResult(true)
            vm.state.value.wifiPermissionGranted shouldBe true
        }

        @Test
        fun `onCellPermissionResult updates state`() {
            val vm = createViewModel()
            vm.onCellPermissionResult(true)
            vm.state.value.cellPermissionGranted shouldBe true
        }

        @Test
        fun `denied location permission disables location and flags alert`() {
            val vm = createViewModel()
            vm.setLocationEnabled(true)
            vm.onLocationPermissionResult(false)
            val state = vm.state.value
            state.locationEnabled shouldBe false
            state.locationPermissionGranted shouldBe false
            state.locationPermissionDenied shouldBe true
        }

        @Test
        fun `denied activity permission disables activity and flags alert`() {
            val vm = createViewModel()
            vm.setActivityEnabled(true)
            vm.onActivityPermissionResult(false)
            val state = vm.state.value
            state.activityEnabled shouldBe false
            state.activityPermissionDenied shouldBe true
        }

        @Test
        fun `denied wifi permission disables wifi and flags alert`() {
            val vm = createViewModel()
            vm.setWifiEnabled(true)
            vm.onWifiPermissionResult(false)
            val state = vm.state.value
            state.wifiEnabled shouldBe false
            state.wifiPermissionDenied shouldBe true
        }

        @Test
        fun `denied cell permission disables cell and flags alert`() {
            val vm = createViewModel()
            vm.setCellEnabled(true)
            vm.onCellPermissionResult(false)
            val state = vm.state.value
            state.cellEnabled shouldBe false
            state.cellPermissionDenied shouldBe true
        }

        @Test
        fun `re-enabling a denied source clears its alert`() {
            val vm = createViewModel()
            vm.setWifiEnabled(true)
            vm.onWifiPermissionResult(false)
            vm.state.value.wifiPermissionDenied shouldBe true

            vm.setWifiEnabled(true)
            val state = vm.state.value
            state.wifiEnabled shouldBe true
            state.wifiPermissionDenied shouldBe false
        }

        @Test
        fun `granting after a prior denial clears the alert`() {
            val vm = createViewModel()
            vm.setActivityEnabled(true)
            vm.onActivityPermissionResult(false)
            vm.state.value.activityPermissionDenied shouldBe true

            vm.setActivityEnabled(true)
            vm.onActivityPermissionResult(true)
            val state = vm.state.value
            state.activityPermissionGranted shouldBe true
            state.activityPermissionDenied shouldBe false
        }
    }

    @Nested
    @DisplayName("State consistency")
    inner class StateConsistency {

        @Test
        fun `multiple updates are reflected in single state snapshot`() {
            val vm = createViewModel()
            vm.setAutoTrackingMode(AUTO_TRACKING_MODE_IN_MOTION)
            vm.setTrackingPreset(TrackingPolicyPreset.HIGH_PRECISION)
            vm.setLocationEnabled(true)
            vm.setCellEnabled(true)
            vm.onLocationPermissionResult(true)

            val state = vm.state.value
            state.autoTrackingMode shouldBe AUTO_TRACKING_MODE_IN_MOTION
            state.trackingPreset shouldBe TrackingPolicyPreset.HIGH_PRECISION
            state.locationEnabled shouldBe true
            state.cellEnabled shouldBe true
            state.locationPermissionGranted shouldBe true
        }

        @Test
        fun `navigation does not reset data selections`() {
            val vm = createViewModel()
            vm.setAutoTrackingMode(AUTO_TRACKING_MODE_IN_MOTION)
            vm.goToNextStep()
            vm.setCellEnabled(true)
            vm.goToPreviousStep()

            val state = vm.state.value
            state.autoTrackingMode shouldBe AUTO_TRACKING_MODE_IN_MOTION
            state.cellEnabled shouldBe true
            state.currentStep shouldBe SetupStep.Welcome
        }
    }
}
