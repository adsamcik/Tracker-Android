package com.adsamcik.tracker.app.settings

import android.content.Context
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import android.content.pm.PackageManager
import android.os.PowerManager
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasSelfPermission
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.extension.TrackingPermissionCapabilities
import com.adsamcik.tracker.shared.base.extension.trackingPermissionCapabilities
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationException
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationFailure
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationCoordinator
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationDebt
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationResult
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRevisionReconciliationState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicySnapshot
import com.adsamcik.tracker.shared.preferences.tracking.UnavailableSourcePolicyRevisionReconciliationCoordinator
import com.adsamcik.tracker.tracker.source.battery.QualitativeBatteryImpactEstimator
import com.adsamcik.tracker.tracker.source.coordinator.DefaultTrackingSettingsStatusProvider
import com.adsamcik.tracker.tracker.source.coordinator.SemanticAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanResolver
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorTelemetry
import com.adsamcik.tracker.tracker.source.coordinator.EffectiveSourceState
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AutomaticTrackingUnavailableReason
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailability
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.TrackingPurpose
import com.adsamcik.tracker.tracker.api.TrackingPurposeAuthorityRevision
import com.adsamcik.tracker.tracker.api.TrackingPurposeAuthorityVector
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationResult
import com.adsamcik.tracker.tracker.api.TrackingSource
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackingTurnOffResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
// One settings ViewModel fixture shares repository flows and permission state across edits and repair
// races.
@Suppress("LargeClass")
class TrackingSettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private var permissionsGranted = true
    private var nearbyWifiGranted = true
	private var exactPurposeAuthorityCurrent = true

    // Backing state for the fake repository
	private val paramsFlow = MutableStateFlow(TrackingParamsState(sourcePolicyRevision = 1L))
	private val purposeAvailabilityFlow =
		MutableStateFlow(CurrentTrackingPurposeAvailability.SAFE_DEFAULT)
	private val purposeAvailabilityReader = object : CurrentTrackingPurposeAvailabilityReader {
		override val availability = purposeAvailabilityFlow
		override val authorityRevision =
			MutableStateFlow(TrackingPurposeAuthorityRevision.UNAVAILABLE)

		override suspend fun isCurrent(identity: TrackingPurposeLeaseIdentity): Boolean =
			exactPurposeAuthorityCurrent && availability.value.isCurrent(identity)
	}
	private val trackingParamsRepository: TrackingParamsRepository = mockk()
	private val activityWatcherController: ActivityWatcherController = mockk(relaxed = true)
    private val trackingStatusProvider = DefaultTrackingSettingsStatusProvider(
        SemanticAcquisitionPlanFactory(),
        SourcePlanResolver(),
        QualitativeBatteryImpactEstimator(),
        TrackingCoordinatorTelemetry(),
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        paramsFlow.value = TrackingParamsState(sourcePolicyRevision = 1L)
        purposeAvailabilityFlow.value = CurrentTrackingPurposeAvailability.SAFE_DEFAULT
        exactPurposeAuthorityCurrent = true
        permissionsGranted = true
        nearbyWifiGranted = true

        // Mock hasSelfPermission (called by inline hasPreciseLocationPermission)
        mockkStatic("com.adsamcik.tracker.shared.base.extension.ContextExtensionsKt")
        mockkStatic("com.adsamcik.tracker.shared.base.extension.TrackingPermissionCapabilitiesKt")
        every { context.hasSelfPermission(any()) } answers { permissionsGranted }
        every { context.trackingPermissionCapabilities(any()) } answers {
            TrackingPermissionCapabilities.evaluate(
                apiLevel = 34,
                locationFeatureAvailable = true,
                wifiFeatureAvailable = true,
                locationServicesEnabled = true,
                coarseLocationGranted = permissionsGranted,
                preciseLocationGranted = permissionsGranted,
                backgroundLocationGranted = permissionsGranted,
                nearbyWifiGranted = nearbyWifiGranted,
            )
        }
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
            paramsFlow.value = paramsFlow.value.copy(
				stepsEnabled = firstArg(), ambientStepsEnabled = firstArg(),
			)
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
		coEvery { trackingParamsRepository.setAmbientLocationEnabled(any()) } answers {
			paramsFlow.value = paramsFlow.value.copy(ambientLocationEnabled = firstArg())
		}
		coEvery { trackingParamsRepository.enableAdaptiveBackgroundLocation() } answers {
			paramsFlow.value = paramsFlow.value.copy(
				ambientLocationEnabled = true, locationProfileVersion = 1, locationBalanceLevel = 3,
				locationBackgroundMode = LocationBackgroundMode.ADAPTIVE,
			)
		}
        coEvery { trackingParamsRepository.adoptBalanceProfile(any()) } answers {
            val level = firstArg<Int>()
            paramsFlow.value = paramsFlow.value.copy(balanceProfileVersion = 1, overallBalanceLevel = level,
                locationProfileVersion = 1, locationBalanceLevel = paramsFlow.value.sourceBalanceOverrides.location ?: level)
        }
        coEvery { trackingParamsRepository.setSourceBalanceOverride(any(), any()) } answers {
            val source = firstArg<TrackingSourceComponent>()
            val level = secondArg<Int?>()
            val overrides = paramsFlow.value.sourceBalanceOverrides.with(source, level)
            paramsFlow.value = paramsFlow.value.copy(sourceBalanceOverrides = overrides,
                locationBalanceLevel = overrides.location ?: paramsFlow.value.overallBalanceLevel)
        }
		coEvery { trackingParamsRepository.adoptLocationProfile(any()) } answers {
			paramsFlow.value = paramsFlow.value.copy(locationProfileVersion = 1, locationBalanceLevel = firstArg())
		}
		coEvery { trackingParamsRepository.setLocationMotionAssistanceEnabled(any()) } answers {
			paramsFlow.value = paramsFlow.value.copy(locationMotionAssistanceEnabled = firstArg())
		}
		coEvery { trackingParamsRepository.setAmbientWifiEnabled(any()) } answers {
			paramsFlow.value = paramsFlow.value.copy(ambientWifiEnabled = firstArg())
		}
		coEvery { trackingParamsRepository.setAmbientCellEnabled(any()) } answers {
			paramsFlow.value = paramsFlow.value.copy(ambientCellEnabled = firstArg())
		}
		coEvery { trackingParamsRepository.setBackgroundWifiNotificationEnabled(any()) } answers {
			paramsFlow.value = paramsFlow.value.copy(backgroundWifiNotificationEnabled = firstArg())
		}
		coEvery { trackingParamsRepository.setBackgroundCellNotificationEnabled(any()) } answers {
			paramsFlow.value = paramsFlow.value.copy(backgroundCellNotificationEnabled = firstArg())
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
        unmockkStatic("com.adsamcik.tracker.shared.base.extension.TrackingPermissionCapabilitiesKt")
    }

	@Test
	fun `Location intent is saved before prompt and late grant cannot revive disabled intent`() = runTest(testDispatcher) {
		val vm = createViewModel()
		val prompt = async { vm.locationPermissionEvents.first() }
		vm.setAmbientLocationEnabled(true)
		val request = prompt.await()
		paramsFlow.value.ambientLocationEnabled shouldBe true
		paramsFlow.value.locationBackgroundMode shouldBe LocationBackgroundMode.ADAPTIVE
		paramsFlow.value.locationMotionAssistanceEnabled shouldBe false
		vm.isCurrentLocationPermissionRequest(request) shouldBe true
		vm.setAmbientLocationEnabled(false)
		advanceUntilIdle()
		vm.refreshPermissionState()
		vm.isCurrentLocationPermissionRequest(request) shouldBe false
		paramsFlow.value.ambientLocationEnabled shouldBe false
		coVerify(exactly = 0) { trackingParamsRepository.setActivityEnabled(any()) }
	}

	@Test
	fun `only explicit saved background enable acknowledges exact pending user exit`() = runTest(testDispatcher) {
		val lifecycle = mockk<TrackingLifecycleCommandAuthority>(relaxed = true)
		every { lifecycle.pendingCollectionResumeExitId } returns "exit-1"
		coEvery { lifecycle.resumeCollectionAfterUserExit("exit-1") } returns true
		val vm = TrackingSettingsViewModel(context, trackingParamsRepository, trackingStatusProvider,
			activityWatcherController, purposeAvailabilityReader,
			lifecycleCommandAuthority = lifecycle,
			activityPermissionProbe = { permissionsGranted })
		advanceUntilIdle()
		vm.refreshPermissionState()
		coVerify(exactly = 0) { lifecycle.resumeCollectionAfterUserExit(any()) }
		vm.setAmbientLocationEnabled(true)
		advanceUntilIdle()
		paramsFlow.value.ambientLocationEnabled shouldBe true
		coVerify(exactly = 1) { lifecycle.resumeCollectionAfterUserExit("exit-1") }
	}

	@Test
	fun `Location settings edit cannot resume explicit Turn off`() = runTest(testDispatcher) {
		val lifecycle = mockk<TrackingLifecycleCommandAuthority>(relaxed = true)
		every { lifecycle.pendingCollectionResumeExitId } returns "turn-off:prior-command"
		val vm = TrackingSettingsViewModel(context, trackingParamsRepository, trackingStatusProvider,
			activityWatcherController, purposeAvailabilityReader,
			lifecycleCommandAuthority = lifecycle,
			activityPermissionProbe = { permissionsGranted })
		vm.setAmbientLocationEnabled(true)
		advanceUntilIdle()
		paramsFlow.value.ambientLocationEnabled shouldBe true
		vm.uiState.value.locationSettingsSaveFailed shouldBe false
		coVerify(exactly = 0) { lifecycle.resumeCollectionAfterUserExit(any()) }
	}

	@Test
	fun `changed pending user exit remains blocked when explicit resume CAS loses`() = runTest(testDispatcher) {
		val lifecycle = mockk<TrackingLifecycleCommandAuthority>(relaxed = true)
		every { lifecycle.pendingCollectionResumeExitId } returns "exit-1"
		coEvery { lifecycle.resumeCollectionAfterUserExit("exit-1") } returns false
		val vm = TrackingSettingsViewModel(context, trackingParamsRepository, trackingStatusProvider,
			activityWatcherController, purposeAvailabilityReader,
			lifecycleCommandAuthority = lifecycle,
			activityPermissionProbe = { permissionsGranted })
		vm.setAmbientLocationEnabled(true)
		advanceUntilIdle()
		vm.uiState.value.locationSettingsSaveFailed shouldBe true
		coVerify(exactly = 1) { lifecycle.resumeCollectionAfterUserExit("exit-1") }
	}

	@Test
	fun `radio host choices save independently without granting background collection`() = runTest(testDispatcher) {
		val vm = createViewModel()
		advanceUntilIdle()
		vm.setBackgroundWifiNotificationEnabled(true)
		vm.setBackgroundCellNotificationEnabled(true)
		vm.setBackgroundWifiNotificationEnabled(false)
		advanceUntilIdle()
		vm.uiState.value.backgroundWifiNotificationEnabled shouldBe false
		vm.uiState.value.backgroundCellNotificationEnabled shouldBe true
		vm.uiState.value.ambientWifiEnabled shouldBe false
		vm.uiState.value.ambientCellEnabled shouldBe false
		coVerify(exactly = 0) { trackingParamsRepository.setAmbientWifiEnabled(any()) }
		coVerify(exactly = 0) { trackingParamsRepository.setAmbientCellEnabled(any()) }
	}

	@Test
	fun `failed radio host choice retains the edit and Retry saves only that choice`() = runTest(testDispatcher) {
		val vm = createViewModel()
		advanceUntilIdle()
		coEvery { trackingParamsRepository.setBackgroundWifiNotificationEnabled(true) } throws IllegalStateException("write failed")
		vm.setBackgroundWifiNotificationEnabled(true)
		advanceUntilIdle()
		vm.uiState.value.backgroundWifiNotificationEnabled shouldBe true
		vm.uiState.value.settingsSaveFailed shouldBe true
		paramsFlow.value.backgroundWifiNotificationEnabled shouldBe false
		coEvery { trackingParamsRepository.setBackgroundWifiNotificationEnabled(true) } answers {
			paramsFlow.value = paramsFlow.value.copy(backgroundWifiNotificationEnabled = true)
		}
		vm.retryFailedSettings()
		advanceUntilIdle()
		vm.uiState.value.settingsSaveFailed shouldBe false
		paramsFlow.value.backgroundWifiNotificationEnabled shouldBe true
		paramsFlow.value.ambientWifiEnabled shouldBe false
	}

	@Test
	fun `helper refusal leaves Location enabled and grants no Activity intent`() = runTest(testDispatcher) {
		val vm = createViewModel()
		vm.setAmbientLocationEnabled(true)
		advanceUntilIdle()
		vm.setLocationMotionAssistanceEnabled(true)
		advanceUntilIdle()
		permissionsGranted = false
		vm.refreshPermissionState()
		vm.uiState.value.ambientLocationEnabled shouldBe true
		vm.uiState.value.locationMotionAssistanceEnabled shouldBe true
		vm.uiState.value.activityPermissionGranted shouldBe false
		coVerify(exactly = 0) { trackingParamsRepository.setActivityEnabled(any()) }
		coVerify(exactly = 0) { trackingParamsRepository.setTransitionDetectionEnabled(any()) }
	}

	@Test
	fun `failed Location intent save is visible and does not enable background collection`() = runTest(testDispatcher) {
		val vm = createViewModel()
		coEvery { trackingParamsRepository.enableAdaptiveBackgroundLocation() } throws IllegalStateException("write failed")
		vm.setAmbientLocationEnabled(true)
		advanceUntilIdle()
		vm.uiState.value.locationSettingsSaveFailed shouldBe true
		vm.uiState.value.settingsSaveFailed shouldBe true
		vm.uiState.value.ambientLocationEnabled shouldBe true
		paramsFlow.value.ambientLocationEnabled shouldBe false
	}

	@Test
	fun `failed Location draft cannot prompt permission until Retry commits the choice`() = runTest(testDispatcher) {
		val vm = createViewModel()
		coEvery { trackingParamsRepository.enableAdaptiveBackgroundLocation() } throws IllegalStateException("write failed")
		vm.setAmbientLocationEnabled(true)
		advanceUntilIdle()
		vm.requestSavedLocationPermission(LocationSettingsPermissionKind.LOCATION)
		val permission = backgroundScope.async(testDispatcher) { vm.locationPermissionEvents.first() }
		advanceUntilIdle()
		permission.isCompleted shouldBe false
		coEvery { trackingParamsRepository.enableAdaptiveBackgroundLocation() } answers {
			paramsFlow.value = paramsFlow.value.copy(ambientLocationEnabled = true,
				locationProfileVersion = 1, locationBalanceLevel = 3, locationBackgroundMode = LocationBackgroundMode.ADAPTIVE)
		}
		vm.retryFailedSettings()
		advanceUntilIdle()
		permission.await().kind shouldBe LocationSettingsPermissionKind.LOCATION
		vm.uiState.value.settingsSaveFailed shouldBe false
		vm.uiState.value.locationSettingsSaveFailed shouldBe false
	}

	@Test
	fun `saved Location permission uses committed state before the UI collector catches up`() = runTest(testDispatcher) {
		every { trackingParamsRepository.data } returns flow {
			emit(paramsFlow.value)
			awaitCancellation()
		}
		val vm = createViewModel()
		advanceUntilIdle()
		val permission = backgroundScope.async(testDispatcher) { vm.locationPermissionEvents.first() }

		vm.setAmbientLocationEnabled(true)
		advanceUntilIdle()

		paramsFlow.value.ambientLocationEnabled shouldBe true
		permission.await().kind shouldBe LocationSettingsPermissionKind.LOCATION
		vm.uiState.value.ambientLocationEnabled shouldBe true
		vm.uiState.value.settingsSaveFailed shouldBe false
	}

	@Test
	fun `failed source save retains intended choice through refresh and Retry`() = runTest(testDispatcher) {
		val vm = createViewModel()
		coEvery { trackingParamsRepository.setCellEnabled(true) } throws IllegalStateException("write failed")
		var permissionRequests = 0
		vm.setCellEnabled(true) { permissionRequests += 1 }
		advanceUntilIdle()
		vm.refreshPermissionState()
		vm.uiState.value.cellEnabled shouldBe true
		vm.uiState.value.settingsSaveFailed shouldBe true
		paramsFlow.value.cellEnabled shouldBe false
		permissionRequests shouldBe 0
		coEvery { trackingParamsRepository.setCellEnabled(true) } answers {
			paramsFlow.value = paramsFlow.value.copy(cellEnabled = true)
		}
		vm.retryFailedSettings()
		advanceUntilIdle()
		paramsFlow.value.cellEnabled shouldBe true
		vm.uiState.value.settingsSaveFailed shouldBe false
		permissionRequests shouldBe 1
	}

	@Test
	fun `superseding a failed choice prevents Retry from restoring old intent`() = runTest(testDispatcher) {
		val vm = createViewModel()
		coEvery { trackingParamsRepository.setCellEnabled(true) } throws IllegalStateException("write failed")
		vm.setCellEnabled(true)
		advanceUntilIdle()
		vm.setCellEnabled(false)
		vm.retryFailedSettings()
		advanceUntilIdle()
		vm.uiState.value.cellEnabled shouldBe false
		vm.uiState.value.settingsSaveFailed shouldBe false
		coVerify(exactly = 1) { trackingParamsRepository.setCellEnabled(true) }
	}

	@Test
	fun `rapid settings saves are ordered and older completion cannot hide a newer choice`() = runTest(testDispatcher) {
		val vm = createViewModel()
		val firstSave = CompletableDeferred<Unit>()
		val writes = mutableListOf<Boolean>()
		coEvery { trackingParamsRepository.setCellEnabled(any()) } coAnswers {
			val enabled = firstArg<Boolean>()
			if (enabled) {
				firstSave.await()
			}
			writes += enabled
			paramsFlow.value = paramsFlow.value.copy(cellEnabled = enabled)
		}
		vm.setCellEnabled(true)
		vm.setCellEnabled(false)
		vm.uiState.value.cellEnabled shouldBe false
		firstSave.complete(Unit)
		advanceUntilIdle()
		writes shouldBe listOf(true, false)
		paramsFlow.value.cellEnabled shouldBe false
		vm.uiState.value.cellEnabled shouldBe false
	}

	@Test
	fun `a failed custom preset write retries only the preset not the saved source`() = runTest(testDispatcher) {
		val vm = createViewModel()
		coEvery { trackingParamsRepository.setPreset(TrackingPreset.CUSTOM) } throws IllegalStateException("write failed")
		vm.setCellEnabled(true)
		advanceUntilIdle()
		paramsFlow.value.cellEnabled shouldBe true
		vm.uiState.value.settingsSaveFailed shouldBe true
		coEvery { trackingParamsRepository.setPreset(TrackingPreset.CUSTOM) } answers {
			paramsFlow.value = paramsFlow.value.copy(presetName = TrackingPreset.CUSTOM.name)
		}
		vm.retryFailedSettings()
		advanceUntilIdle()
		coVerify(exactly = 1) { trackingParamsRepository.setCellEnabled(true) }
		paramsFlow.value.preset shouldBe TrackingPreset.CUSTOM
		vm.uiState.value.settingsSaveFailed shouldBe false
	}

	@Test
	fun `Retry preserves separate failed choices and a successful sibling edit`() = runTest(testDispatcher) {
		val vm = createViewModel()
		coEvery { trackingParamsRepository.setCellEnabled(true) } throws IllegalStateException("cell write failed")
		coEvery { trackingParamsRepository.setBarometerEnabled(false) } throws IllegalStateException("pressure write failed")
		vm.setCellEnabled(true)
		vm.setBarometerEnabled(false)
		vm.setNotificationStyled(false)
		advanceUntilIdle()
		vm.uiState.value.cellEnabled shouldBe true
		vm.uiState.value.barometerEnabled shouldBe false
		paramsFlow.value.notificationStyled shouldBe false
		coEvery { trackingParamsRepository.setCellEnabled(true) } answers {
			paramsFlow.value = paramsFlow.value.copy(cellEnabled = true)
		}
		coEvery { trackingParamsRepository.setBarometerEnabled(false) } answers {
			paramsFlow.value = paramsFlow.value.copy(barometerEnabled = false)
		}
		vm.retryFailedSettings()
		advanceUntilIdle()
		vm.uiState.value.settingsSaveFailed shouldBe false
		paramsFlow.value.cellEnabled shouldBe true
		paramsFlow.value.barometerEnabled shouldBe false
		coVerify(exactly = 1) { trackingParamsRepository.setNotificationStyled(false) }
	}

	@Test
	fun `session Location repair has foreground scope and keeps all-day Off`() = runTest(testDispatcher) {
		paramsFlow.value = paramsFlow.value.copy(locationEnabled = true, ambientLocationEnabled = false)
		val lifecycle = mockk<TrackingLifecycleCommandAuthority>(relaxed = true)
		every { lifecycle.pendingCollectionResumeExitId } returns "turn-off:permission-repair"
		val vm = TrackingSettingsViewModel(context, trackingParamsRepository, trackingStatusProvider,
			activityWatcherController, purposeAvailabilityReader, lifecycleCommandAuthority = lifecycle,
			activityPermissionProbe = { permissionsGranted })
		val before = paramsFlow.value
		val prompt = backgroundScope.async(testDispatcher) { vm.locationPermissionEvents.first() }
		vm.requestSavedLocationPermission(LocationSettingsPermissionKind.FOREGROUND_LOCATION)
		advanceUntilIdle()
		val request = prompt.await()
		request.kind shouldBe LocationSettingsPermissionKind.FOREGROUND_LOCATION
		vm.isCurrentLocationPermissionRequest(request) shouldBe true
		paramsFlow.value shouldBe before
		coVerify(exactly = 0) { lifecycle.resumeCollectionAfterUserExit(any()) }
		vm.setLocationEnabled(false)
		advanceUntilIdle()
		vm.isCurrentLocationPermissionRequest(request) shouldBe false
	}

	@Test
	fun `permission repair eligibility uses committed choices instead of failed previews`() = runTest(testDispatcher) {
		paramsFlow.value = paramsFlow.value.copy(wifiEnabled = false, ambientWifiEnabled = false)
		val vm = createViewModel()
		coEvery { trackingParamsRepository.setWifiEnabled(true) } throws IllegalStateException("write failed")
		vm.setWifiEnabled(true)
		advanceUntilIdle()
		vm.uiState.value.wifiEnabled shouldBe true
		vm.isSavedSourceSelected(TrackingSourceComponent.WIFI) shouldBe false
	}

	@Test
	fun `automatic permission repair remains eligible without Activity recording`() = runTest(testDispatcher) {
		paramsFlow.value = paramsFlow.value.copy(activityEnabled = false, autoTrackingMode = 1)
		val vm = createViewModel()
		val before = paramsFlow.value
		vm.isSavedSourceSelected(TrackingSourceComponent.ACTIVITY) shouldBe true
		paramsFlow.value shouldBe before
		paramsFlow.value = paramsFlow.value.copy(autoTrackingMode = 0)
		vm.isSavedSourceSelected(TrackingSourceComponent.ACTIVITY) shouldBe false
	}

	@Test
	fun `permission repair accepts independent saved background radio choices`() = runTest(testDispatcher) {
		paramsFlow.value = paramsFlow.value.copy(wifiEnabled = false, cellEnabled = false,
			ambientWifiEnabled = true, ambientCellEnabled = true)
		val vm = createViewModel()
		vm.isSavedSourceSelected(TrackingSourceComponent.WIFI) shouldBe true
		vm.isSavedSourceSelected(TrackingSourceComponent.CELL) shouldBe true
	}

    private fun createViewModel(
        nearbyWifiGranted: Boolean = true,
		sourcePolicyReconciliationCoordinator:
			SourcePolicyRevisionReconciliationCoordinator =
			UnavailableSourcePolicyRevisionReconciliationCoordinator,
    ): TrackingSettingsViewModel {
        this.nearbyWifiGranted = nearbyWifiGranted
        return TrackingSettingsViewModel(
            context,
            trackingParamsRepository,
            trackingStatusProvider,
            activityWatcherController,
			purposeAvailabilityReader,
			sourcePolicyReconciliationCoordinator,
			activityPermissionProbe = { permissionsGranted },
		)
    }

	@Test
	fun `Turn off keeps chosen sources and reports retry when durable command is unavailable`() =
		runTest(testDispatcher) {
			mockkObject(TrackerServiceApi)
			try {
				coEvery { TrackerServiceApi.turnOffTrackingAndAwait(context, any()) } returnsMany
					listOf(TrackingTurnOffResult.UNAVAILABLE, TrackingTurnOffResult.COMPLETE)
				val vm = createViewModel()
				advanceUntilIdle()
				val chosenSteps = paramsFlow.value.stepsEnabled

				vm.turnOffTracking()
				advanceUntilIdle()
				vm.uiState.value.turnOffTrackingFailed shouldBe true
				vm.uiState.value.trackingTurnedOff shouldBe false

				vm.turnOffTracking()
				advanceUntilIdle()
				vm.uiState.value.turnOffTrackingFailed shouldBe false
				vm.uiState.value.trackingTurnedOff shouldBe true
				paramsFlow.value.stepsEnabled shouldBe chosenSteps
				coVerify(exactly = 2) { TrackerServiceApi.turnOffTrackingAndAwait(context, any()) }
			} finally {
				unmockkObject(TrackerServiceApi)
			}
		}

	@Test
	fun `Health Connect repair rechecks the shared Steps owner without changing selection`() =
		runTest(testDispatcher) {
			var reconciliations = 0
			var fail = false
			val vm = TrackingSettingsViewModel(
				context,
				trackingParamsRepository,
				trackingStatusProvider,
				activityWatcherController,
				purposeAvailabilityReader,
				purposeSettingsReconciler = TrackingPurposeSettingsReconciler {
					reconciliations++
					if (fail) {
						throw IllegalStateException("provider unavailable")
					}
					TrackingPurposeSettingsReconciliationResult.Complete(setOf(AmbientTrackingSource.STEPS))
				},
				activityPermissionProbe = { permissionsGranted },
			)
			paramsFlow.value = paramsFlow.value.copy(stepsEnabled = true)
			vm.refreshStepsProviderPermissionState()
			advanceUntilIdle()
			reconciliations shouldBe 1
			paramsFlow.value.stepsEnabled shouldBe true
			vm.uiState.value.stepsProviderRepairFailed shouldBe false
			fail = true
			vm.refreshStepsProviderPermissionState()
			advanceUntilIdle()
			reconciliations shouldBe 2
			vm.uiState.value.stepsProviderRepairFailed shouldBe true
			paramsFlow.value.stepsEnabled shouldBe true
		}

	@Test
	fun `Steps grant rechecks its owner when the unchanged policy already reports complete`() =
		runTest(testDispatcher) {
			paramsFlow.value = paramsFlow.value.copy(stepsEnabled = true, ambientStepsEnabled = true)
			permissionsGranted = false
			val original = paramsFlow.value
			val snapshot = mockk<SourcePolicySnapshot>()
			every { snapshot.revision } returns requireNotNull(original.sourcePolicyRevision)
			val coordinator = mockk<SourcePolicyRevisionReconciliationCoordinator>()
			every { coordinator.reconciliationState } returns MutableStateFlow(
				SourcePolicyRevisionReconciliationState.Complete(snapshot.revision),
			)
			coEvery { coordinator.reconcileCurrentPolicyRevision() } returns
				SourcePolicyRevisionReconciliationResult.Complete(snapshot)
			val ownerPermissionReads = mutableListOf<Boolean>()
			val vm = TrackingSettingsViewModel(
				context, trackingParamsRepository, trackingStatusProvider,
				activityWatcherController, purposeAvailabilityReader, coordinator,
				purposeSettingsReconciler = TrackingPurposeSettingsReconciler {
					ownerPermissionReads += permissionsGranted
					TrackingPurposeSettingsReconciliationResult.Complete(setOf(AmbientTrackingSource.STEPS))
				},
				activityPermissionProbe = { permissionsGranted },
			)
			advanceUntilIdle()

			permissionsGranted = true
			vm.onStepsPermissionResult(true)
			advanceUntilIdle()

			ownerPermissionReads shouldBe listOf(true)
			coVerify(exactly = 1) { coordinator.reconcileCurrentPolicyRevision() }
			paramsFlow.value shouldBe original
			vm.uiState.value.activityPermissionGranted shouldBe true
			vm.uiState.value.stepsProviderRepairFailed shouldBe false
			coVerify(exactly = 0) { trackingParamsRepository.setStepsEnabled(any()) }
		}

	@Test
	fun `Activity and automatic grants refresh selected Steps without rewriting their saved choices`() =
		runTest(testDispatcher) {
			val completions: List<(TrackingSettingsViewModel) -> Unit> = listOf(
				{ it.onActivityPermissionResult(true) },
				{ it.onAutoTrackingPermissionResult(2, true) },
			)
			for (complete in completions) {
				paramsFlow.value = paramsFlow.value.copy(
					stepsEnabled = true, ambientStepsEnabled = true, activityEnabled = false,
					autoTrackingMode = 0,
				)
				permissionsGranted = false
				val original = paramsFlow.value
				val ownerPermissionReads = mutableListOf<Boolean>()
				val vm = TrackingSettingsViewModel(
					context, trackingParamsRepository, trackingStatusProvider,
					activityWatcherController, purposeAvailabilityReader,
					purposeSettingsReconciler = TrackingPurposeSettingsReconciler {
						ownerPermissionReads += permissionsGranted
						TrackingPurposeSettingsReconciliationResult.Complete(setOf(AmbientTrackingSource.STEPS))
					},
					activityPermissionProbe = { permissionsGranted },
				)
				advanceUntilIdle()

				permissionsGranted = true
				complete(vm)
				advanceUntilIdle()

				ownerPermissionReads shouldBe listOf(true)
				paramsFlow.value shouldBe original
				vm.uiState.value.activityPermissionGranted shouldBe true
			}
			coVerify(exactly = 0) { trackingParamsRepository.setStepsEnabled(any()) }
			coVerify(exactly = 0) { trackingParamsRepository.setActivityEnabled(any()) }
			coVerify(exactly = 0) { trackingParamsRepository.update(any()) }
			coVerify(exactly = 0) { activityWatcherController.applyAutoTrackingMode(any()) }
		}

	@Test
	fun `Activity and automatic permission callbacks keep Turn off and disabled Steps intact`() =
		runTest(testDispatcher) {
			val completions: List<(TrackingSettingsViewModel) -> Unit> = listOf(
				{ it.onActivityPermissionResult(true) },
				{ it.onAutoTrackingPermissionResult(2, true) },
			)
			val lifecycle = mockk<TrackingLifecycleCommandAuthority>(relaxed = true)
			every { lifecycle.pendingCollectionResumeExitId } returns "turn-off:another-permission-route"
			for (complete in completions) {
				paramsFlow.value = paramsFlow.value.copy(stepsEnabled = false, ambientStepsEnabled = false)
				val original = paramsFlow.value
				val ownerSelections = mutableListOf<Pair<Boolean, String?>>()
				val vm = TrackingSettingsViewModel(
					context, trackingParamsRepository, trackingStatusProvider,
					activityWatcherController, purposeAvailabilityReader,
					lifecycleCommandAuthority = lifecycle,
					purposeSettingsReconciler = TrackingPurposeSettingsReconciler {
						ownerSelections += paramsFlow.value.stepsEnabled to lifecycle.pendingCollectionResumeExitId
						TrackingPurposeSettingsReconciliationResult.Complete(setOf(AmbientTrackingSource.STEPS))
					},
					activityPermissionProbe = { permissionsGranted },
				)
				advanceUntilIdle()

				complete(vm)
				advanceUntilIdle()

				ownerSelections shouldBe listOf(false to "turn-off:another-permission-route")
				paramsFlow.value shouldBe original
				vm.uiState.value.trackingTurnedOff shouldBe true
			}
			coVerify(exactly = 0) { lifecycle.resumeCollectionAfterUserExit(any()) }
			coVerify(exactly = 0) { trackingParamsRepository.setStepsEnabled(any()) }
		}

	@Test
	fun `Steps callback rechecks missing permission instead of trusting an old grant result`() =
		runTest(testDispatcher) {
			paramsFlow.value = paramsFlow.value.copy(stepsEnabled = true, ambientStepsEnabled = true)
			permissionsGranted = false
			val original = paramsFlow.value
			val ownerPermissionReads = mutableListOf<Boolean>()
			val vm = TrackingSettingsViewModel(
				context, trackingParamsRepository, trackingStatusProvider,
				activityWatcherController, purposeAvailabilityReader,
				purposeSettingsReconciler = TrackingPurposeSettingsReconciler {
					ownerPermissionReads += permissionsGranted
					val unavailable = AmbientSourceOperationalAvailability(
						source = AmbientTrackingSource.STEPS,
						state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
						mechanism = AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
						reason = AmbientSourceUnavailableReason.LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED,
					)
					purposeAvailabilityFlow.value = purposeAvailabilityFlow.value.copy(
						published = purposeAvailabilityFlow.value.published.copy(
							ambientSources = purposeAvailabilityFlow.value.published.ambientSources +
								(AmbientTrackingSource.STEPS to unavailable),
						),
					)
					TrackingPurposeSettingsReconciliationResult.Complete(setOf(AmbientTrackingSource.STEPS))
				},
				activityPermissionProbe = { permissionsGranted },
			)
			advanceUntilIdle()

			vm.onStepsPermissionResult(true)
			advanceUntilIdle()

			ownerPermissionReads shouldBe listOf(false)
			paramsFlow.value shouldBe original
			vm.uiState.value.activityPermissionGranted shouldBe false
			vm.uiState.value.ambientSourceAvailability.getValue(AmbientTrackingSource.STEPS).state shouldBe
				AmbientSourceOperationalState.PERMISSION_REQUIRED
			coVerify(exactly = 0) { trackingParamsRepository.setStepsEnabled(any()) }
		}

	@Test
	fun `late Steps grant reconciles the disabled choice without enabling it again`() =
		runTest(testDispatcher) {
			paramsFlow.value = paramsFlow.value.copy(stepsEnabled = false, ambientStepsEnabled = false)
			val original = paramsFlow.value
			val ownerSelections = mutableListOf<Boolean>()
			val vm = TrackingSettingsViewModel(
				context, trackingParamsRepository, trackingStatusProvider,
				activityWatcherController, purposeAvailabilityReader,
				purposeSettingsReconciler = TrackingPurposeSettingsReconciler {
					ownerSelections += paramsFlow.value.stepsEnabled
					TrackingPurposeSettingsReconciliationResult.Complete(setOf(AmbientTrackingSource.STEPS))
				},
				activityPermissionProbe = { permissionsGranted },
			)
			advanceUntilIdle()

			vm.onStepsPermissionResult(true)
			advanceUntilIdle()

			ownerSelections shouldBe listOf(false)
			paramsFlow.value shouldBe original
			coVerify(exactly = 0) { trackingParamsRepository.setStepsEnabled(any()) }
		}

	@Test
	fun `late Steps grant after Turn off rechecks the owner without acknowledging the stop`() =
		runTest(testDispatcher) {
			paramsFlow.value = paramsFlow.value.copy(stepsEnabled = true, ambientStepsEnabled = true)
			permissionsGranted = false
			val original = paramsFlow.value
			var pendingExit: String? = null
			val lifecycle = mockk<TrackingLifecycleCommandAuthority>(relaxed = true)
			every { lifecycle.pendingCollectionResumeExitId } answers { pendingExit }
			val ownerStops = mutableListOf<String?>()
			val vm = TrackingSettingsViewModel(
				context, trackingParamsRepository, trackingStatusProvider,
				activityWatcherController, purposeAvailabilityReader,
				lifecycleCommandAuthority = lifecycle,
				purposeSettingsReconciler = TrackingPurposeSettingsReconciler {
					ownerStops += lifecycle.pendingCollectionResumeExitId
					TrackingPurposeSettingsReconciliationResult.Complete(setOf(AmbientTrackingSource.STEPS))
				},
				activityPermissionProbe = { permissionsGranted },
			)
			advanceUntilIdle()
			mockkObject(TrackerServiceApi)
			try {
				coEvery { TrackerServiceApi.turnOffTrackingAndAwait(context, any()) } coAnswers {
					pendingExit = "turn-off:permission-race"
					TrackingTurnOffResult.COMPLETE
				}
				vm.turnOffTracking()
				advanceUntilIdle()
				permissionsGranted = true
				vm.onStepsPermissionResult(true)
				advanceUntilIdle()

				ownerStops shouldBe listOf("turn-off:permission-race")
				pendingExit shouldBe "turn-off:permission-race"
				paramsFlow.value shouldBe original
				vm.uiState.value.trackingTurnedOff shouldBe true
				coVerify(exactly = 0) { lifecycle.resumeCollectionAfterUserExit(any()) }
				coVerify(exactly = 0) { trackingParamsRepository.setStepsEnabled(any()) }
			} finally {
				unmockkObject(TrackerServiceApi)
			}
		}

    @Test
    fun `late source grants refresh actual capability and cannot revive selections`() = runTest(testDispatcher) {
        val vm = createViewModel()
        vm.setActivityEnabled(false)
        vm.setStepsEnabled(false)
        vm.setWifiEnabled(false)
        vm.setCellEnabled(false)
        advanceUntilIdle()
        permissionsGranted = false

        vm.onActivityPermissionResult(true)
        vm.onStepsPermissionResult(true)
        vm.onWifiPermissionResult(true)
        vm.onCellPermissionResult(true)
        advanceUntilIdle()

        paramsFlow.value.activityEnabled shouldBe false
        paramsFlow.value.stepsEnabled shouldBe false
        paramsFlow.value.wifiEnabled shouldBe false
        paramsFlow.value.cellEnabled shouldBe false
        vm.uiState.value.activityPermissionGranted shouldBe false
        vm.uiState.value.wifiPermissionGranted shouldBe false
        vm.uiState.value.cellPermissionGranted shouldBe false
        coVerify(exactly = 0) { trackingParamsRepository.setActivityEnabled(true) }
        coVerify(exactly = 0) { trackingParamsRepository.setStepsEnabled(true) }
        coVerify(exactly = 0) { trackingParamsRepository.setWifiEnabled(true) }
        coVerify(exactly = 0) { trackingParamsRepository.setCellEnabled(true) }
    }

    @Test
    fun `source intent precedes prompt and denial preserves each saved selection`() = runTest(testDispatcher) {
        permissionsGranted = false
        val vm = createViewModel()
        var prompts = 0
        vm.setActivityEnabled(true) { paramsFlow.value.activityEnabled shouldBe true; prompts++ }
        vm.setStepsEnabled(true) { paramsFlow.value.stepsEnabled shouldBe true; prompts++ }
        vm.setWifiEnabled(true) { paramsFlow.value.wifiEnabled shouldBe true; prompts++ }
        vm.setCellEnabled(true) { paramsFlow.value.cellEnabled shouldBe true; prompts++ }
        advanceUntilIdle()
        prompts shouldBe 4
        vm.onActivityPermissionResult(false)
        vm.onStepsPermissionResult(false)
        vm.onWifiPermissionResult(false)
        vm.onCellPermissionResult(false)
        advanceUntilIdle()
        paramsFlow.value.activityEnabled shouldBe true
        paramsFlow.value.stepsEnabled shouldBe true
        paramsFlow.value.wifiEnabled shouldBe true
        paramsFlow.value.cellEnabled shouldBe true
    }

    @Test
    fun `superseded suspended source save cannot launch its old permission prompt`() = runTest(testDispatcher) {
        val saveEntered = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        coEvery { trackingParamsRepository.setWifiEnabled(true) } coAnswers {
            saveEntered.complete(Unit)
            releaseSave.await()
        }
        val vm = createViewModel()
        var prompts = 0
        vm.setWifiEnabled(true) { prompts++ }
        saveEntered.await()
        vm.setWifiEnabled(false)
        advanceUntilIdle()
        releaseSave.complete(Unit)
        advanceUntilIdle()
        prompts shouldBe 0
        paramsFlow.value.wifiEnabled shouldBe false
    }

    @Test
    fun `source reconciliation debt suppresses the permission prompt`() = runTest(testDispatcher) {
        val failure = SourcePolicyRevisionReconciliationException(
            listOf(SourcePolicyRevisionReconciliationFailure.SourcePolicyUnavailable),
        )
        coEvery { trackingParamsRepository.setStepsEnabled(true) } throws failure
        val vm = createViewModel()
        var prompts = 0
        vm.setStepsEnabled(true) { prompts++ }
        advanceUntilIdle()
        prompts shouldBe 0
    }

    private fun publishStepsAvailability(availability: AmbientSourceOperationalAvailability) {
        purposeAvailabilityFlow.value = purposeAvailabilityFlow.value.copy(
            published = purposeAvailabilityFlow.value.published.copy(
                ambientSources = purposeAvailabilityFlow.value.published.ambientSources +
                    (AmbientTrackingSource.STEPS to availability),
            ),
        )
    }

	@Test
	fun `saved edits and permission repair reuse the existing session join without new source intent`() =
		runTest(testDispatcher) {
			mockkObject(TrackerServiceApi)
			try {
				val observedChoices = mutableListOf<Boolean>()
				every { TrackerServiceApi.reconcileProviderStepsSession(context) } answers {
					observedChoices += paramsFlow.value.locationEnabled
				}
				val vm = createViewModel()
				advanceUntilIdle()
				vm.setLocationEnabled(false)
				advanceUntilIdle()
				observedChoices shouldBe listOf(false)
				vm.onLocationPermissionResult()
				advanceUntilIdle()
				observedChoices shouldBe listOf(false, false)
				paramsFlow.value.locationEnabled shouldBe false
				coVerify(exactly = 1) { trackingParamsRepository.setLocationEnabled(false) }
			} finally { unmockkObject(TrackerServiceApi) }
		}

	@Test
	fun `failed settings save does not request a session source join`() = runTest(testDispatcher) {
		mockkObject(TrackerServiceApi)
		try {
			every { TrackerServiceApi.reconcileProviderStepsSession(context) } returns Unit
			coEvery { trackingParamsRepository.setLocationEnabled(false) } throws IllegalStateException("Full storage")
			val vm = createViewModel()
			advanceUntilIdle()
			vm.setLocationEnabled(false)
			advanceUntilIdle()
			verify(exactly = 0) { TrackerServiceApi.reconcileProviderStepsSession(context) }
			paramsFlow.value.locationEnabled shouldBe true
		} finally { unmockkObject(TrackerServiceApi) }
	}

    private fun providerOnlyStepsPermission() = AmbientSourceOperationalAvailability(
        source = AmbientTrackingSource.STEPS,
        state = AmbientSourceOperationalState.PERMISSION_REQUIRED,
        mechanism = AmbientAcquisitionMechanism.LOCAL_RECORDING_STEPS,
        reason = AmbientSourceUnavailableReason.LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED,
    )

    @Test
    fun `provider-only enable waits for permission publication before its one opportunity`() = runTest(testDispatcher) {
        every { context.hasStepCounterSensor } returns false
        val vm = createViewModel()
        permissionsGranted = false
        vm.refreshPermissionState()
        var opportunities = 0
        vm.setStepsEnabled(true) {
            vm.uiState.value.ambientSourceAvailability.getValue(AmbientTrackingSource.STEPS).reason shouldBe
                AmbientSourceUnavailableReason.LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED
            opportunities++
        }
        runCurrent()
        opportunities shouldBe 0
        advanceTimeBy(500L)
        publishStepsAvailability(providerOnlyStepsPermission())
        runCurrent()
        opportunities shouldBe 1
        publishStepsAvailability(AmbientSourceOperationalAvailability.reconciliationPending(AmbientTrackingSource.STEPS))
        publishStepsAvailability(providerOnlyStepsPermission())
        runCurrent()
        opportunities shouldBe 1
    }

    @Test
    fun `provider-only enable stops waiting after two seconds without retrying the save`() = runTest(testDispatcher) {
        every { context.hasStepCounterSensor } returns false
        val vm = createViewModel()
        permissionsGranted = false
        vm.refreshPermissionState()
        var opportunities = 0
        vm.setStepsEnabled(true) { opportunities++ }
        runCurrent()
        advanceTimeBy(1_999L)
        runCurrent()
        opportunities shouldBe 0
        advanceTimeBy(1L)
        runCurrent()
        opportunities shouldBe 1
        paramsFlow.value.stepsEnabled shouldBe true
        coVerify(exactly = 1) { trackingParamsRepository.setStepsEnabled(true) }
    }

    @Test
    fun `disabling Steps cancels an earlier waiting permission opportunity`() = runTest(testDispatcher) {
        every { context.hasStepCounterSensor } returns false
        val vm = createViewModel()
        permissionsGranted = false
        vm.refreshPermissionState()
        var staleOpportunities = 0
        vm.setStepsEnabled(true) { staleOpportunities++ }
        runCurrent()
        vm.setStepsEnabled(false)
        publishStepsAvailability(providerOnlyStepsPermission())
        advanceUntilIdle()
        staleOpportunities shouldBe 0
        paramsFlow.value.stepsEnabled shouldBe false
    }

    @Test
    fun `a newer enable owns the sole permission opportunity`() = runTest(testDispatcher) {
        every { context.hasStepCounterSensor } returns false
        val vm = createViewModel()
        permissionsGranted = false
        vm.refreshPermissionState()
        var staleOpportunities = 0
        var currentOpportunities = 0
        vm.setStepsEnabled(true) { staleOpportunities++ }
        runCurrent()
        vm.setStepsEnabled(true) { currentOpportunities++ }
        runCurrent()
        publishStepsAvailability(providerOnlyStepsPermission())
        runCurrent()
        staleOpportunities shouldBe 0
        currentOpportunities shouldBe 1
    }

    @Test
    fun `waiting for Steps does not hold up another settings save`() = runTest(testDispatcher) {
        every { context.hasStepCounterSensor } returns false
        val vm = createViewModel()
        permissionsGranted = false
        vm.refreshPermissionState()
        var opportunities = 0
        vm.setStepsEnabled(true) { opportunities++ }
        runCurrent()
        vm.setWifiEnabled(true)
        runCurrent()
        paramsFlow.value.wifiEnabled shouldBe true
        opportunities shouldBe 0
        coVerify(exactly = 1) { trackingParamsRepository.setWifiEnabled(true) }
    }

    @Test
    fun `direct Steps sensor keeps the immediate saved permission opportunity`() = runTest(testDispatcher) {
        val vm = createViewModel()
        permissionsGranted = false
        vm.refreshPermissionState()
        var opportunities = 0
        vm.setStepsEnabled(true) { opportunities++ }
        runCurrent()
        opportunities shouldBe 1
    }

    @Test
    fun `late permission launch failure preserves saved Steps and its repair`() = runTest(testDispatcher) {
        every { context.hasStepCounterSensor } returns false
        val vm = createViewModel()
        permissionsGranted = false
        vm.refreshPermissionState()
        var attempts = 0
        vm.setStepsEnabled(true) {
            attempts++
            throw IllegalStateException("Screen no longer accepts a permission launch")
        }
        runCurrent()
        publishStepsAvailability(providerOnlyStepsPermission())
        runCurrent()
        attempts shouldBe 1
        paramsFlow.value.stepsEnabled shouldBe true
        vm.uiState.value.ambientSourceAvailability.getValue(AmbientTrackingSource.STEPS).reason shouldBe
            AmbientSourceUnavailableReason.LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED
        advanceUntilIdle()
        attempts shouldBe 1
    }

    @Test
    fun `closing the ViewModel cancels the waiting Steps permission opportunity`() = runTest(testDispatcher) {
        every { context.hasStepCounterSensor } returns false
        val vm = createViewModel()
        val store = androidx.lifecycle.ViewModelStore()
        store.put("tracking", vm)
        permissionsGranted = false
        vm.refreshPermissionState()
        var opportunities = 0
        vm.setStepsEnabled(true) { opportunities++ }
        runCurrent()
        store.clear()
        publishStepsAvailability(providerOnlyStepsPermission())
        advanceUntilIdle()
        opportunities shouldBe 0
        paramsFlow.value.stepsEnabled shouldBe true
    }

    @Test
    fun `frequency Off cancels an old Steps opportunity before its suspended save finishes`() = runTest(testDispatcher) {
        every { context.hasStepCounterSensor } returns false
        val vm = createViewModel()
        permissionsGranted = false
        vm.refreshPermissionState()
        var opportunities = 0
        vm.setStepsEnabled(true) { opportunities++ }
        runCurrent()
        val saveEntered = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        coEvery {
            trackingParamsRepository.setSourceFrequency(TrackingSourceComponent.STEPS, SourceCollectionFrequency.OFF)
        } coAnswers {
            saveEntered.complete(Unit)
            releaseSave.await()
            paramsFlow.value = paramsFlow.value.copy(stepsEnabled = false, ambientStepsEnabled = false)
        }
        vm.setSourceFrequency(TrackingSourceComponent.STEPS, SourceCollectionFrequency.OFF)
        saveEntered.await()
        publishStepsAvailability(providerOnlyStepsPermission())
        advanceTimeBy(2_000L)
        runCurrent()
        opportunities shouldBe 0
        releaseSave.complete(Unit)
        advanceUntilIdle()
        opportunities shouldBe 0
        paramsFlow.value.stepsEnabled shouldBe false
    }

    @Test
    fun `failed frequency Off never revives an old Steps permission opportunity`() = runTest(testDispatcher) {
        every { context.hasStepCounterSensor } returns false
        val vm = createViewModel()
        permissionsGranted = false
        vm.refreshPermissionState()
        var opportunities = 0
        vm.setStepsEnabled(true) { opportunities++ }
        runCurrent()
        coEvery {
            trackingParamsRepository.setSourceFrequency(TrackingSourceComponent.STEPS, SourceCollectionFrequency.OFF)
        } throws java.io.IOException("Storage unavailable")
        vm.setSourceFrequency(TrackingSourceComponent.STEPS, SourceCollectionFrequency.OFF)
        runCurrent()
        publishStepsAvailability(providerOnlyStepsPermission())
        advanceUntilIdle()
        opportunities shouldBe 0
        vm.uiState.value.settingsSaveFailed shouldBe true
        paramsFlow.value.stepsEnabled shouldBe true
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
		fun `ambient radio and Location settings persist intent without claiming operational readiness`() =
			runTest(testDispatcher) {
				val vm = createViewModel()
				advanceUntilIdle()

				vm.setAmbientLocationEnabled(true)
				vm.setAmbientWifiEnabled(true)
				vm.setAmbientCellEnabled(true)
				advanceUntilIdle()

				vm.uiState.value.ambientLocationEnabled shouldBe true
				vm.uiState.value.ambientStepsEnabled shouldBe true
				vm.uiState.value.ambientWifiEnabled shouldBe true
				vm.uiState.value.ambientCellEnabled shouldBe true
				vm.uiState.value.ambientSourceAvailability.values.forEach { availability ->
					availability.isOperational shouldBe false
					availability.state shouldBe AmbientSourceOperationalState.WAITING
					availability.reason shouldBe
						AmbientSourceUnavailableReason.RECONCILIATION_PENDING
				}
			}

		@Test
		fun `published source status replaces safe default without changing preference intent`() =
			runTest(testDispatcher) {
				val vm = createViewModel()
				purposeAvailabilityFlow.value = CurrentTrackingPurposeAvailability(
					published = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
						ambientSources =
							TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.ambientSources +
								(
									AmbientTrackingSource.WIFI to
										AmbientSourceOperationalAvailability.unavailable(
												AmbientTrackingSource.WIFI,
												AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE,
											)
								),
					),
					currentAuthorities = emptyMap(),
				)

				advanceUntilIdle()

				vm.uiState.value.ambientWifiEnabled shouldBe false
				vm.uiState.value.ambientSourceAvailability
					.getValue(AmbientTrackingSource.WIFI)
					.reason shouldBe AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE
			}

		@Test
		// Keep stale saved-purpose publication through its visible state and disabled watcher assertions
		// together.
		@Suppress("LongMethod")
		fun `stale automatic and ambient ready identities project as nonoperational`() =
			runTest(testDispatcher) {
				paramsFlow.value = TrackingParamsState(
					sourcePolicyRevision = 12L,
					autoTrackingMode = 1,
					ambientStepsEnabled = true,
				)
				val vm = TrackingSettingsViewModel(
					context,
					trackingParamsRepository,
					trackingStatusProvider,
					activityWatcherController,
					purposeAvailabilityReader,
					activityPermissionProbe = { permissionsGranted },
				)
				val control = purposeIdentity(
					source = TrackingSource.ACTIVITY,
					purpose = TrackingPurpose.CONTROL,
					collectedDataEpoch = 3L,
					rolloutRevision = 9L,
				)
				val ambient = purposeIdentity(
					source = TrackingSource.STEPS,
					purpose = TrackingPurpose.AMBIENT_PRODUCT,
					collectedDataEpoch = 3L,
					rolloutRevision = 9L,
				)
				purposeAvailabilityFlow.value = CurrentTrackingPurposeAvailability(
					published = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
						automaticControl =
							AutomaticTrackingOperationalAvailability.Ready(control),
						ambientSources =
							TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.ambientSources +
								(
									AmbientTrackingSource.STEPS to
										AmbientSourceOperationalAvailability.ready(
											AmbientTrackingSource.STEPS,
											AmbientAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS,
											ambient,
										)
								),
					),
					currentAuthorities = mapOf(
						control.sourcePurpose to control.toAuthorityVector(
							collectedDataEpoch = 4L,
						),
						ambient.sourcePurpose to ambient.toAuthorityVector(
							rolloutRevision = 10L,
						),
					),
				)

				advanceUntilIdle()

				vm.uiState.value.automaticTrackingOperational shouldBe false
				vm.uiState.value.automaticControlAvailability shouldBe
					AutomaticTrackingOperationalAvailability.Unavailable(
						AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
						control,
					)
				vm.uiState.value.ambientSourceAvailability
					.getValue(AmbientTrackingSource.STEPS).state shouldBe
					AmbientSourceOperationalState.WAITING

				vm.setAutoTrackingMode(2)
				advanceUntilIdle()
				io.mockk.verify { activityWatcherController.applyAutoTrackingMode(0) }
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
            vm.uiState.value.autoTrackingMode shouldBe 1

            paramsFlow.value = paramsFlow.value.copy(autoTrackingMode = 0)
            advanceUntilIdle()
            vm.uiState.value.autoTrackingEnabled shouldBe false
            vm.uiState.value.autoTrackingMode shouldBe 0
        }

        @Test
        fun `setAutoTrackingMode preserves intent but keeps unavailable control disabled`() =
			runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setAutoTrackingMode(2)
            advanceUntilIdle()
            vm.uiState.value.autoTrackingMode shouldBe 2
            vm.uiState.value.autoTrackingEnabled shouldBe true
			vm.uiState.value.automaticTrackingOperational shouldBe false
			vm.uiState.value.automaticControlAvailability shouldBe
				AutomaticTrackingOperationalAvailability.Unavailable(
					AutomaticTrackingUnavailableReason.CONTROL_RETENTION_POLICY_UNAVAILABLE,
				)
            io.mockk.verify { activityWatcherController.applyAutoTrackingMode(0) }

            vm.setAutoTrackingMode(0)
            advanceUntilIdle()
            vm.uiState.value.autoTrackingMode shouldBe 0
            vm.uiState.value.autoTrackingEnabled shouldBe false
        }

		@Test
		fun `setAutoTrackingMode rechecks exact authority before applying watcher mode`() =
			runTest(testDispatcher) {
				val vm = createViewModel()
				val control = purposeIdentity(
					source = TrackingSource.ACTIVITY,
					purpose = TrackingPurpose.CONTROL,
					collectedDataEpoch = 3L,
					rolloutRevision = 9L,
				)
				purposeAvailabilityFlow.value = CurrentTrackingPurposeAvailability(
					published = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
						automaticControl =
							AutomaticTrackingOperationalAvailability.Ready(control),
					),
					currentAuthorities = mapOf(
						control.sourcePurpose to control.toAuthorityVector(),
					),
				)
				exactPurposeAuthorityCurrent = false
				advanceUntilIdle()

				vm.setAutoTrackingMode(2)
				advanceUntilIdle()

				io.mockk.verify { activityWatcherController.applyAutoTrackingMode(0) }
			}

		@Test
		fun `automatic permission repair remains visible before owner can publish Ready`() = runTest(testDispatcher) {
			permissionsGranted = false
			paramsFlow.value = paramsFlow.value.copy(autoTrackingMode = 2)
			val vm = createViewModel()
			advanceUntilIdle()
			vm.uiState.value.autoTrackingMode shouldBe 2
			vm.uiState.value.automaticTrackingPermissionRequired shouldBe true
			vm.uiState.value.automaticTrackingOperational shouldBe false
		}

		@Test
		fun `stale Ready cannot conceal disabled transition detection`() = runTest(testDispatcher) {
			paramsFlow.value = paramsFlow.value.copy(autoTrackingMode = 2, transitionDetectionEnabled = false)
			val control = purposeIdentity(TrackingSource.ACTIVITY, TrackingPurpose.CONTROL,
				collectedDataEpoch = 3L, rolloutRevision = 9L)
			purposeAvailabilityFlow.value = CurrentTrackingPurposeAvailability(
				published = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT.copy(
					automaticControl = AutomaticTrackingOperationalAvailability.Ready(control)),
				currentAuthorities = mapOf(control.sourcePurpose to control.toAuthorityVector()))
			val vm = createViewModel()
			advanceUntilIdle()
			vm.uiState.value.transitionDetectionEnabled shouldBe false
			vm.uiState.value.automaticTrackingOperational shouldBe false
			vm.uiState.value.autoTrackingMode shouldBe 2
		}

        @Test
        fun `granted auto tracking permission preserves the choice saved before launch`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setAutoTrackingMode(2)
            advanceUntilIdle()
            vm.onAutoTrackingPermissionResult(requestedMode = 2, granted = true)
            advanceUntilIdle()

            vm.uiState.value.autoTrackingMode shouldBe 2
            vm.uiState.value.activityPermissionGranted shouldBe true
        }

        @Test
        fun `denied auto tracking permission preserves saved requested mode`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setAutoTrackingMode(2)
            advanceUntilIdle()
            permissionsGranted = false
            vm.onAutoTrackingPermissionResult(requestedMode = 2, granted = false)
            advanceUntilIdle()

            vm.uiState.value.autoTrackingMode shouldBe 2
            vm.uiState.value.activityPermissionGranted shouldBe false
        }

		@Test
		fun `automatic permission callback refreshes actual grant instead of trusting stale result`() = runTest(testDispatcher) {
			val vm = createViewModel()
			advanceUntilIdle()
			vm.setAutoTrackingMode(2)
			advanceUntilIdle()
			permissionsGranted = false

			vm.onAutoTrackingPermissionResult(requestedMode = 2, granted = true)
			advanceUntilIdle()

			vm.uiState.value.activityPermissionGranted shouldBe false
			vm.uiState.value.autoTrackingMode shouldBe 2
		}

		@Test
		fun `late automatic permission grant cannot restore mode after Off`() = runTest(testDispatcher) {
			val vm = createViewModel()
			advanceUntilIdle()
			vm.setAutoTrackingMode(2)
			advanceUntilIdle()
			vm.setAutoTrackingMode(0)
			advanceUntilIdle()

			vm.onAutoTrackingPermissionResult(requestedMode = 2, granted = true)
			advanceUntilIdle()

			paramsFlow.value.autoTrackingMode shouldBe 0
			vm.uiState.value.autoTrackingMode shouldBe 0
		}

		@Test
		fun `late automatic permission grant cannot replace a newer movement choice`() = runTest(testDispatcher) {
			val vm = createViewModel()
			advanceUntilIdle()
			vm.setAutoTrackingMode(2)
			advanceUntilIdle()
			vm.setAutoTrackingMode(1)
			advanceUntilIdle()

			vm.onAutoTrackingPermissionResult(requestedMode = 2, granted = true)
			advanceUntilIdle()

			paramsFlow.value.autoTrackingMode shouldBe 1
		}

		@Test
		fun `automatic permission opportunity follows saved intent`() = runTest(testDispatcher) {
			val vm = createViewModel()
			advanceUntilIdle()
			var observedSavedMode: Int? = null

			vm.setAutoTrackingMode(2) { observedSavedMode = paramsFlow.value.autoTrackingMode }
			advanceUntilIdle()

			observedSavedMode shouldBe 2
		}

		@Test
		fun `superseded automatic save cannot request permission for old choice`() = runTest(testDispatcher) {
			val release = kotlinx.coroutines.CompletableDeferred<Unit>()
			coEvery { trackingParamsRepository.update(any()) } coAnswers {
				@Suppress("UNCHECKED_CAST")
				val block = invocation.args[0] as (TrackingParamsState.() -> TrackingParamsState)
				paramsFlow.value = block(paramsFlow.value)
				if (paramsFlow.value.autoTrackingMode == 2) {
					release.await()
				}
			}
			val vm = createViewModel()
			advanceUntilIdle()
			var permissionRequested = false
			vm.setAutoTrackingMode(2) { permissionRequested = true }
			vm.setAutoTrackingMode(0)
			release.complete(Unit)
			advanceUntilIdle()

			permissionRequested shouldBe false
			paramsFlow.value.autoTrackingMode shouldBe 0
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
		fun `post commit source reconciliation debt still marks the preset custom`() =
			runTest(testDispatcher) {
				val failure = SourcePolicyRevisionReconciliationException(
					listOf(SourcePolicyRevisionReconciliationFailure.SourcePolicyUnavailable),
				)
				coEvery { trackingParamsRepository.setWifiEnabled(false) } throws failure
				val vm = createViewModel()
				advanceUntilIdle()

				vm.setWifiEnabled(false)
				advanceUntilIdle()

				coVerify(exactly = 1) {
					trackingParamsRepository.setPreset(TrackingPreset.CUSTOM)
				}
				vm.uiState.value.sourcePolicyReconciliationDebt?.failures shouldBe
					failure.failures
			}

		@Test
		fun `custom preset post commit debt is surfaced instead of escaping the mutation`() =
			runTest(testDispatcher) {
				val failure = SourcePolicyRevisionReconciliationException(
					listOf(SourcePolicyRevisionReconciliationFailure.SourcePolicyUnavailable),
				)
				coEvery {
					trackingParamsRepository.setPreset(TrackingPreset.CUSTOM)
				} throws failure
				val vm = createViewModel()
				advanceUntilIdle()

				vm.setWifiEnabled(false)
				advanceUntilIdle()

				vm.uiState.value.sourcePolicyReconciliationDebt?.failures shouldBe
					failure.failures
			}

		@Test
		fun `published source reconciliation debt exposes an explicit retry`() =
			runTest(testDispatcher) {
				val debt = SourcePolicyRevisionReconciliationDebt(
					policyRevision = 4L,
					failures = listOf(
						SourcePolicyRevisionReconciliationFailure.SourcePolicyUnavailable,
					),
				)
				val coordinator = mockk<SourcePolicyRevisionReconciliationCoordinator>()
				every { coordinator.reconciliationState } returns MutableStateFlow(
					SourcePolicyRevisionReconciliationState.Debt(debt),
				)
				coEvery { coordinator.reconcileCurrentPolicyRevision() } returns
					SourcePolicyRevisionReconciliationResult.Retryable(debt)
				val vm = createViewModel(
					sourcePolicyReconciliationCoordinator = coordinator,
				)
				advanceUntilIdle()

				vm.uiState.value.sourcePolicyReconciliationDebt shouldBe debt
				vm.retrySourcePolicyReconciliation()
				advanceUntilIdle()

				coVerify(exactly = 1) { coordinator.reconcileCurrentPolicyRevision() }
			}

        @Test
        fun `preset compatibility actions select quality without changing sources consent or custom settings`() = runTest(testDispatcher) {
			val vm = createViewModel()
            paramsFlow.value = paramsFlow.value.copy(locationEnabled = false, wifiEnabled = true, cellEnabled = false,
                ambientWifiEnabled = true, ambientStepsEnabled = true, autoTrackingMode = 0,
                minTimeSeconds = 17, minDistanceMeters = 43, requiredAccuracyMeters = 71, presetName = "CUSTOM")
            val original = paramsFlow.value
            advanceUntilIdle()
            listOf(TrackingPreset.POWER_SAVE to 1, TrackingPreset.BALANCED to 3, TrackingPreset.HIGH_ACCURACY to 5).forEach { (preset, level) ->
                vm.applyPreset(preset)
                advanceUntilIdle()
                vm.uiState.value.overallBalanceLevel shouldBe level
                paramsFlow.value.copy(balanceProfileVersion = null, overallBalanceLevel = null,
                    locationProfileVersion = null, locationBalanceLevel = null) shouldBe original
            }
            vm.uiState.value.locationEnabled shouldBe false
            vm.uiState.value.wifiEnabled shouldBe true
            coVerify(exactly = 0) { trackingParamsRepository.setWifiEnabled(any()) }
            coVerify(exactly = 0) { trackingParamsRepository.setAmbientLocationEnabled(any()) }
        }

        @Test
        fun `quality override can be changed while a source remains off`() = runTest(testDispatcher) {
            val vm = createViewModel()
            vm.adoptBalanceProfile(3)
            advanceUntilIdle()
            vm.setSourceBalanceOverride(TrackingSourceComponent.WIFI, 5)
            advanceUntilIdle()
            vm.uiState.value.sourceBalanceOverrides.wifi shouldBe 5
            vm.uiState.value.wifiEnabled shouldBe false
            vm.uiState.value.ambientWifiEnabled shouldBe false
            vm.setSourceBalanceOverride(TrackingSourceComponent.WIFI, null)
            advanceUntilIdle()
            vm.uiState.value.sourceBalanceOverrides.wifi shouldBe null
        }
    }

    @Nested
    @DisplayName("Permission refresh")
    inner class PermissionRefresh {

        @Test
        fun `scan-only Wi-Fi remains permitted when nearby devices is denied`() =
            runTest(testDispatcher) {
                val vm = createViewModel(nearbyWifiGranted = false)

                advanceUntilIdle()

                vm.uiState.value.wifiPermissionGranted shouldBe true
                vm.uiState.value.permissionCapabilities.nearbyWifiGranted shouldBe false
            }

        @Test
        fun `refreshPermissionState updates effective wifi state without repository emission`() =
            runTest(testDispatcher) {
                permissionsGranted = false
                paramsFlow.value = TrackingParamsState(wifiEnabled = true)
                val vm = TrackingSettingsViewModel(
                    context,
                    trackingParamsRepository,
                    trackingStatusProvider,
                    activityWatcherController,
					purposeAvailabilityReader,
					activityPermissionProbe = { permissionsGranted },
				)
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
        fun `profile changes keep estimate derived from the resulting source plans`() = runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()
            vm.adoptBalanceProfile(1)
            advanceUntilIdle()
            vm.uiState.value.overallBalanceLevel shouldBe 1
            (vm.uiState.value.sourcePlans[SourceKind.LOCATION] as LocationPlan).requestedIntervalMs shouldBe 60_000L
            vm.adoptBalanceProfile(5)
            advanceUntilIdle()
            vm.uiState.value.overallBalanceLevel shouldBe 5
            (vm.uiState.value.sourcePlans[SourceKind.LOCATION] as LocationPlan).requestedIntervalMs shouldBe 1_000L
        }
    }

	private fun purposeIdentity(
		source: TrackingSource,
		purpose: TrackingPurpose,
		collectedDataEpoch: Long,
		rolloutRevision: Long,
	) = TrackingPurposeLeaseIdentity(
		source = source,
		purpose = purpose,
		policyRevision = 12L,
		consentEpoch = 5L,
		collectedDataEpoch = collectedDataEpoch,
		rolloutRevision = rolloutRevision,
		executionRevision = 1L,
		ownerCasToken = "${source.name.lowercase()}-${purpose.name.lowercase()}",
	)

	private fun TrackingPurposeLeaseIdentity.toAuthorityVector(
		collectedDataEpoch: Long = this.collectedDataEpoch,
		rolloutRevision: Long = this.rolloutRevision,
	) = TrackingPurposeAuthorityVector(
		sourcePurpose = sourcePurpose,
		policyRevision = policyRevision,
		consentEpoch = consentEpoch,
		collectedDataEpoch = collectedDataEpoch,
		rolloutRevision = rolloutRevision,
		executionRevision = executionRevision,
	)
}
