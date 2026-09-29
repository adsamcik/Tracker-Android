package com.adsamcik.tracker.app.onboarding.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.app.onboarding.data.SetupCompletionError
import com.adsamcik.tracker.maintenance.DataRetentionScheduler
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasSelfPermission
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.extension.TrackingPermissionCapabilities
import com.adsamcik.tracker.shared.base.extension.trackingPermissionCapabilities
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesRepository
import com.adsamcik.tracker.shared.preferences.map.OnlineMapTilesState
import com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupViewModelCompletionRobolectricTest {
	private val testDispatcher = UnconfinedTestDispatcher()
	private lateinit var appContext: Context
	private val paramsFlow = MutableStateFlow(TrackingParamsState())
	private val trackingParamsRepository: TrackingParamsRepository = mockk()
	private val onlineTilesFlow = MutableStateFlow(OnlineMapTilesState())
	private val onlineMapTilesRepository: OnlineMapTilesRepository = mockk()
	private val onboardingRepository: OnboardingRepository = mockk(relaxed = true)
	private val activityWatcherController: ActivityWatcherServiceController = mockk(relaxed = true)
	private val dataRetentionScheduler: DataRetentionScheduler = mockk(relaxed = true)
	private var currentCapabilities = preciseCapabilities(backgroundGranted = true)

	private val dispatchers = object : DispatchersProvider {
		override val main: CoroutineDispatcher = testDispatcher
		override val default: CoroutineDispatcher = testDispatcher
		override val io: CoroutineDispatcher = testDispatcher
		override val unconfined: CoroutineDispatcher = testDispatcher
	}

	@Before
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		appContext = ApplicationProvider.getApplicationContext()
		paramsFlow.value = TrackingParamsState()
		currentCapabilities = preciseCapabilities(backgroundGranted = true)
		mockkStatic("com.adsamcik.tracker.shared.base.extension.ContextExtensionsKt")
		mockkStatic("com.adsamcik.tracker.shared.base.extension.TrackingPermissionCapabilitiesKt")
		every { appContext.hasSelfPermission(any()) } returns true
		every { appContext.trackingPermissionCapabilities(any()) } answers { currentCapabilities }
		every { appContext.hasPressureSensor } returns true
		every { appContext.hasStepCounterSensor } returns true
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
		coEvery { dataRetentionScheduler.prepareForSetupCompletion() } returns Unit
		every { dataRetentionScheduler.initialize() } returns Unit
	}

	@Test
	fun `retention failure keeps setup open and retry uses the same chosen sources`() = runTest(testDispatcher) {
		val vm = SetupViewModel(
			appContext = appContext,
			savedStateHandle = SavedStateHandle(),
			dispatchers = dispatchers,
			onboardingRepository = onboardingRepository,
			activityWatcherController = activityWatcherController,
			dataRetentionScheduler = dataRetentionScheduler,
			trackingParamsRepository = trackingParamsRepository,
			onlineMapTilesRepository = onlineMapTilesRepository,
		)
		vm.setWifiEnabled(true)
		var finished = 0
		coEvery { dataRetentionScheduler.prepareForSetupCompletion() } throws IllegalStateException("unavailable")

		vm.completeSetup { finished++ }
		advanceUntilIdle()
		finished shouldBe 0
		vm.state.value.completionError shouldBe SetupCompletionError.RETENTION
		vm.state.value.completionInProgress shouldBe false
		vm.state.value.wifiEnabled shouldBe true
		coVerify(exactly = 0) { onboardingRepository.markCompleted() }
		coVerify(exactly = 0) { trackingParamsRepository.update(any()) }
		io.mockk.verify(exactly = 0) { activityWatcherController.poke(autoTracking = any()) }

		coEvery { dataRetentionScheduler.prepareForSetupCompletion() } returns Unit
		vm.completeSetup { finished++ }
		advanceUntilIdle()
		finished shouldBe 1
		vm.state.value.completionError shouldBe null
		coVerify(exactly = 1) { onboardingRepository.markCompleted() }
		coVerifyOrder {
			dataRetentionScheduler.prepareForSetupCompletion()
			trackingParamsRepository.update(any())
			onboardingRepository.markCompleted()
			activityWatcherController.poke(autoTracking = 1)
		}
	}

	@Test
	fun `settings save failure keeps setup open and leaves choices for retry`() = runTest(testDispatcher) {
		val vm = SetupViewModel(
			appContext = appContext,
			savedStateHandle = SavedStateHandle(),
			dispatchers = dispatchers,
			onboardingRepository = onboardingRepository,
			activityWatcherController = activityWatcherController,
			dataRetentionScheduler = dataRetentionScheduler,
			trackingParamsRepository = trackingParamsRepository,
			onlineMapTilesRepository = onlineMapTilesRepository,
		)
		vm.setOnlineMapTilesEnabled(false)
		var finished = false
		coEvery { onlineMapTilesRepository.setEnabled(false) } throws IllegalStateException("unavailable")

		vm.completeSetup { finished = true }
		advanceUntilIdle()
		finished shouldBe false
		vm.state.value.completionError shouldBe SetupCompletionError.SETTINGS
		vm.state.value.onlineMapTilesEnabled shouldBe false
		coVerify(exactly = 0) { onboardingRepository.markCompleted() }
		coVerify(exactly = 1) { dataRetentionScheduler.prepareForSetupCompletion() }
		io.mockk.verify(exactly = 0) { activityWatcherController.poke(autoTracking = any()) }
	}

	@Test
	fun `completion marker failure keeps automatic activation stopped and choices available`() =
		runTest(testDispatcher) {
			val vm = SetupViewModel(
				appContext = appContext,
				savedStateHandle = SavedStateHandle(),
				dispatchers = dispatchers,
				onboardingRepository = onboardingRepository,
				activityWatcherController = activityWatcherController,
				dataRetentionScheduler = dataRetentionScheduler,
				trackingParamsRepository = trackingParamsRepository,
				onlineMapTilesRepository = onlineMapTilesRepository,
			)
			vm.setAutoTrackingMode(2)
			coEvery { onboardingRepository.markCompleted() } throws IllegalStateException("unavailable")
			var finished = false

			vm.completeSetup { finished = true }
			advanceUntilIdle()

			finished shouldBe false
			vm.state.value.completionError shouldBe SetupCompletionError.SETTINGS
			vm.state.value.autoTrackingMode shouldBe 2
			paramsFlow.value.autoTrackingMode shouldBe 2
			io.mockk.verify(exactly = 0) { activityWatcherController.poke(autoTracking = any()) }
		}

	@Test
	fun `watcher bridge failure after completion still finishes setup`() = runTest(testDispatcher) {
		val vm = SetupViewModel(
			appContext = appContext,
			savedStateHandle = SavedStateHandle(),
			dispatchers = dispatchers,
			onboardingRepository = onboardingRepository,
			activityWatcherController = activityWatcherController,
			dataRetentionScheduler = dataRetentionScheduler,
			trackingParamsRepository = trackingParamsRepository,
			onlineMapTilesRepository = onlineMapTilesRepository,
		)
		every { activityWatcherController.poke(autoTracking = any()) } throws
			IllegalStateException("bridge unavailable")
		var finished = false

		vm.completeSetup { finished = true }
		advanceUntilIdle()

		finished shouldBe true
		vm.state.value.completionInProgress shouldBe false
		vm.state.value.completionError shouldBe null
		coVerify(exactly = 1) { onboardingRepository.markCompleted() }
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
		unmockkStatic("com.adsamcik.tracker.shared.base.extension.ContextExtensionsKt")
		unmockkStatic("com.adsamcik.tracker.shared.base.extension.TrackingPermissionCapabilitiesKt")
	}

	@Test
	fun `completeSetup writes tracking parameters to repository`() = runTest(testDispatcher) {
		val vm = SetupViewModel(
			appContext = appContext,
			savedStateHandle = SavedStateHandle(),
			dispatchers = dispatchers,
			onboardingRepository = onboardingRepository,
			activityWatcherController = activityWatcherController,
			dataRetentionScheduler = dataRetentionScheduler,
			trackingParamsRepository = trackingParamsRepository,
			onlineMapTilesRepository = onlineMapTilesRepository,
		)
		vm.setTrackingPreset(TrackingPolicyPreset.HIGH_PRECISION)
		vm.setWifiEnabled(true)
		vm.setCellEnabled(true)
		vm.setPressureEnabled(true)
		vm.setAutoTrackingMode(2)
		vm.onActivityPermissionResult(true)
		vm.setOnlineMapTilesEnabled(true)

		vm.completeSetup { }
		advanceUntilIdle()

		val state = paramsFlow.value
		state.locationEnabled shouldBe true
		state.activityEnabled shouldBe true
		state.stepsEnabled shouldBe true
		state.wifiEnabled shouldBe true
		state.cellEnabled shouldBe true
		state.barometerEnabled shouldBe true
        state.balanceProfileVersion shouldBe 1
        state.overallBalanceLevel shouldBe 5
        state.locationBalanceLevel shouldBe 5
		state.autoTrackingMode shouldBe 2
		state.transitionDetectionEnabled shouldBe false
		state.minDistanceMeters shouldBe 5
		state.minTimeSeconds shouldBe 5
		state.requiredAccuracyMeters shouldBe 20
		state.preset shouldBe TrackingPreset.HIGH_ACCURACY
		onlineTilesFlow.value.enabled shouldBe true
	}

	@Test
	fun `declined background access preserves manual location and saved automatic intent`() =
		runTest(testDispatcher) {
			currentCapabilities = preciseCapabilities(backgroundGranted = false)
			val vm = SetupViewModel(
				appContext = appContext,
				savedStateHandle = SavedStateHandle(),
				dispatchers = dispatchers,
				onboardingRepository = onboardingRepository,
				activityWatcherController = activityWatcherController,
				dataRetentionScheduler = dataRetentionScheduler,
				trackingParamsRepository = trackingParamsRepository,
				onlineMapTilesRepository = onlineMapTilesRepository,
			)
			vm.setLocationEnabled(true)
			vm.setAutoTrackingMode(2)
			vm.onActivityPermissionResult(true)
			vm.declineBackgroundLocation()

			vm.completeSetup { }
			advanceUntilIdle()

			paramsFlow.value.locationEnabled shouldBe true
			paramsFlow.value.autoTrackingMode shouldBe 2
			vm.state.value.backgroundLocationDeclined shouldBe true
			vm.state.value.manualLocationOnly shouldBe true
		}

	@Test
	fun `denied source permissions do not erase selected sources at completion`() =
		runTest(testDispatcher) {
			currentCapabilities = TrackingPermissionCapabilities.denied(apiLevel = 34)
			val vm = SetupViewModel(
				appContext = appContext,
				savedStateHandle = SavedStateHandle(),
				dispatchers = dispatchers,
				onboardingRepository = onboardingRepository,
				activityWatcherController = activityWatcherController,
				dataRetentionScheduler = dataRetentionScheduler,
				trackingParamsRepository = trackingParamsRepository,
				onlineMapTilesRepository = onlineMapTilesRepository,
			)
			vm.setLocationEnabled(true)
			vm.setWifiEnabled(true)
			vm.setCellEnabled(true)
			vm.onLocationPermissionResult(emptyMap())
			vm.onWifiPermissionResult(emptyMap())
			vm.onCellPermissionResult(false)

			vm.completeSetup { }
			advanceUntilIdle()

			paramsFlow.value.locationEnabled shouldBe true
			paramsFlow.value.wifiEnabled shouldBe true
			paramsFlow.value.cellEnabled shouldBe true
			vm.state.value.locationPermissionDenied shouldBe true
			vm.state.value.wifiPermissionDenied shouldBe true
			vm.state.value.cellPermissionDenied shouldBe true
		}

	@Test
	fun `returning from Settings clears Activity and Cell repair after permission grant`() =
		runTest(testDispatcher) {
			every { appContext.hasSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) } returns false
			every { appContext.hasSelfPermission(android.Manifest.permission.READ_PHONE_STATE) } returns false
			every { appContext.hasSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) } returns false
			val vm = SetupViewModel(
				appContext = appContext,
				savedStateHandle = SavedStateHandle(),
				dispatchers = dispatchers,
				onboardingRepository = onboardingRepository,
				activityWatcherController = activityWatcherController,
				dataRetentionScheduler = dataRetentionScheduler,
				trackingParamsRepository = trackingParamsRepository,
				onlineMapTilesRepository = onlineMapTilesRepository,
			)
			vm.onActivityPermissionResult(false)
			vm.setCellEnabled(true)
			vm.onCellPermissionResult(false)
			vm.state.value.activityPermissionDenied shouldBe true
			vm.state.value.cellPermissionDenied shouldBe true

			every { appContext.hasSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) } returns true
			every { appContext.hasSelfPermission(android.Manifest.permission.READ_PHONE_STATE) } returns true
			every { appContext.hasSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) } returns true
			vm.refreshPermissionCapabilities()

			vm.state.value.activityEnabled shouldBe true
			vm.state.value.cellEnabled shouldBe true
			vm.state.value.activityPermissionGranted shouldBe true
			vm.state.value.cellPermissionGranted shouldBe true
			vm.state.value.activityPermissionDenied shouldBe false
			vm.state.value.cellPermissionDenied shouldBe false
		}

	@Test
	fun `denied activity permission does not erase saved automatic intent at completion`() =
		runTest(testDispatcher) {
			every { appContext.hasSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) } returns false
			val vm = SetupViewModel(
				appContext = appContext,
				savedStateHandle = SavedStateHandle(),
				dispatchers = dispatchers,
				onboardingRepository = onboardingRepository,
				activityWatcherController = activityWatcherController,
				dataRetentionScheduler = dataRetentionScheduler,
				trackingParamsRepository = trackingParamsRepository,
				onlineMapTilesRepository = onlineMapTilesRepository,
			)
			vm.setAutoTrackingMode(2)
			vm.onActivityPermissionResult(false)

			vm.completeSetup { }
			advanceUntilIdle()

			paramsFlow.value.autoTrackingMode shouldBe 2
			vm.state.value.activityPermissionGranted shouldBe false
			io.mockk.verify { activityWatcherController.poke(autoTracking = 0) }
		}
}

private fun preciseCapabilities(backgroundGranted: Boolean) =
	TrackingPermissionCapabilities.evaluate(
		apiLevel = 34,
		locationFeatureAvailable = true,
		wifiFeatureAvailable = true,
		locationServicesEnabled = true,
		coarseLocationGranted = true,
		preciseLocationGranted = true,
		backgroundLocationGranted = backgroundGranted,
		nearbyWifiGranted = true,
	)
