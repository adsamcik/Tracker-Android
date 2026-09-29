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
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
		paramsFlow.value.ambientLocationEnabled shouldBe false
	}

    private fun createViewModel(
        nearbyWifiGranted: Boolean = true,
		sourcePolicyReconciliationCoordinator:
			SourcePolicyRevisionReconciliationCoordinator =
			UnavailableSourcePolicyRevisionReconciliationCoordinator,
    ): TrackingSettingsViewModel {
        paramsFlow.value = TrackingParamsState()
		purposeAvailabilityFlow.value = CurrentTrackingPurposeAvailability.SAFE_DEFAULT
		exactPurposeAuthorityCurrent = true
        permissionsGranted = true
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
					if (fail) throw IllegalStateException("provider unavailable")
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
				if (paramsFlow.value.autoTrackingMode == 2) release.await()
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
