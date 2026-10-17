package com.adsamcik.tracker.app.startup

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.app.ApplicationStartupRecoveryAction
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.app.settings.CollectedDataDeletionCompletion
import com.adsamcik.tracker.app.settings.CollectedDataDeletionService
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicy
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicySnapshot
import com.adsamcik.tracker.shared.preferences.tracking.SourceQos
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.DefaultManualTrackingStartReadinessReader
import com.adsamcik.tracker.tracker.api.ManualTrackingStartPreflight
import com.adsamcik.tracker.tracker.api.ManualTrackingStartReadiness
import com.adsamcik.tracker.tracker.api.ManualTrackingStartSelection
import com.adsamcik.tracker.tracker.api.TrackingCaptureSource
import com.adsamcik.tracker.tracker.resilience.InactiveTrackingSessionStopHandler
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterSetup
import com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterSetupResult
import com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterStatus
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.projection.legacy.LegacyV27ProjectionRecoveryResult
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real startup gate and manual preflight; storage/recovery, source setup and Android are fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualStartStartupRecoveryTest {
	@Test
	@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
	fun `pending Application startup leaves Room backed maintenance unconstructed`() = runTest {
		var retentionConstructions = 0
		val dispatcher = StandardTestDispatcher(testScheduler)
		val application = Application().apply {
			appScope = backgroundScope
			dispatchers = mockk<DispatchersProvider> { every { io } returns dispatcher }
			dataRetentionSchedulerProvider = Provider {
				retentionConstructions++
				error("Retention construction must wait for startup Ready")
			}
		}

		application.startMaintenanceStartupIfNeeded()
		application.startMaintenanceStartupIfNeeded()
		runCurrent()

		retentionConstructions shouldBe 0
		application.isStartupReady shouldBe false
	}

    private val storage = mockk<LegacyDatabaseUpgradeCoordinator>()
    private val deletion = mockk<CollectedDataDeletionService>()
    private val resolver = mockk<ApplicationStartupRecoveryResolver>()
    private val commands = mockk<TrackingLifecycleCommandAuthority>()
    private val inactiveStop = mockk<InactiveTrackingSessionStopHandler>()
    private val recovery = mockk<SourcePipelineRecovery>()
    private val setup = mockk<ChosenSourceWriterSetup>()
    private val policies = mockk<SourcePolicyRepository>()
    private val params = mockk<TrackingParamsRepository>()
    private val deletionBarrier = TrackingStartupDeletionBarrier()
    private var stopGeneration = 0L
    private var unhandledStop: TrackingStopCommand? = null
    private var savedOff: String? = "saved-off"
    private lateinit var gate: DefaultTrackingStartupGate
    private lateinit var reader: DefaultManualTrackingStartReadinessReader

    // Keep one manual-start fixture with its real startup gate, chosen Steps policy and Android capability
    // overrides together.
    @Suppress("LongMethod")
    @Before fun installFixture() {
        every { commands.pendingCollectionResumeExitId } answers { savedOff }
        every { commands.collectionResumeBoundary } returns null
        every { commands.latestUnhandledStop() } answers { unhandledStop }
        every { commands.latestStopGeneration() } answers { stopGeneration }
        coEvery { commands.runWithHandledStopGeneration(any(), any()) } coAnswers {
            if (firstArg<Long>() != stopGeneration || unhandledStop != null) {
                false
            } else {
                secondArg<suspend () -> Unit>().invoke()
                true
            }
        }
        every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
        coEvery { resolver.apply(any(), any()) } just Runs
        coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
        coEvery { deletion.reconcilePendingDeletion() } returns CollectedDataDeletionCompletion.Complete
        coEvery { recovery.recoverStartupAuthority() } returns LegacyV27ProjectionRecoveryResult.NotRequired
        gate = DefaultTrackingStartupGate(storage, Provider { deletion }, resolver, commands, Provider { inactiveStop },
            Provider { recovery }, deletionBarrier)

        val policy = SourcePolicySnapshot(1L, TrackingSourceComponent.entries.associateWith { source ->
            val enabled = source == TrackingSourceComponent.STEPS
            SourcePolicy(source = source, enabled = enabled,
                qos = if (enabled) {
                    SourceQos.BALANCED
                } else {
                    SourceQos.OFF
                },
                locationMinTimeSeconds = if (source == TrackingSourceComponent.LOCATION) {
                    10
                } else {
                    null
                },
                locationMinDistanceMeters = if (source == TrackingSourceComponent.LOCATION) {
                    5
                } else {
                    null
                },
                locationRequiredAccuracyMeters = if (source == TrackingSourceComponent.LOCATION) {
                    50
                } else {
                    null
                },
                captureConsentEpoch = if (enabled) {
                    7L
                } else {
                    null
                }, controlConsentEpoch = null,
                ambientConsentEpoch = null, capturePersistenceEligible = enabled,
                controlPersistenceEligible = false, ambientPersistenceEligible = false,
                effectiveTime = SourcePolicyEffectiveTime("fixture-boot", 10L, 1_000L), policyRevision = 1L)
        })
        coEvery { policies.currentState() } returns SourcePolicyAuthorityState.Active(policy)
        val ready = ChosenSourceWriterSetupResult(mapOf(SourceKind.STEPS to ChosenSourceWriterStatus.READY),
            rolloutRevision = 3L)
        coEvery { setup.ensure(setOf(SourceKind.STEPS)) } coAnswers {
            gate.isReady shouldBe true
            ready
        }
        coEvery { setup.readReadiness(setOf(SourceKind.STEPS)) } returns ready

        val application = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = mockk<PackageManager>()
        every { packageManager.hasSystemFeature(any()) } answers {
            firstArg<String>() == PackageManager.FEATURE_SENSOR_STEP_COUNTER
        }
        val sensors = mockk<SensorManager>()
        every { sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } returns mockk<Sensor>()
        every { sensors.getDefaultSensor(Sensor.TYPE_PRESSURE) } returns null
        val context = object : ContextWrapper(application) {
            override fun getPackageManager(): PackageManager = packageManager
            override fun getSystemService(name: String): Any? =
                if (name == Context.SENSOR_SERVICE) {
                    sensors
                } else {
                    super.getSystemService(name)
                }
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.ACTIVITY_RECOGNITION) {
                    PackageManager.PERMISSION_GRANTED
                }
                else {
                    PackageManager.PERMISSION_DENIED
                }
            override fun checkSelfPermission(permission: String): Int = checkPermission(permission, 0, 0)
        }
        mockkObject(Assist)
        every { Assist.isPlayServicesAvailable(context) } returns false
        reader = DefaultManualTrackingStartReadinessReader(context, Provider { gate }, Provider { policies },
            Provider { setup }, Provider { params })
    }

    @After fun removeFixture() {
        unmockkObject(Assist)
    }

    @Test fun `explicit Start reconciles handled End before setup while display stays read only and Off stays saved`() = runTest {
        gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
        val originalGeneration = gate.currentGeneration
        stopGeneration = 2L // Actual whole-service End marks Stop handled without a new gate publication.
        gate.isReady shouldBe false

        reader.readPreflight() shouldBe ManualTrackingStartPreflight(ManualTrackingStartReadiness.TrackingUnavailable, null)
        gate.isReady shouldBe false
        coVerify(exactly = 1) { recovery.recoverStartupAuthority() }
        coVerify(exactly = 0) { setup.ensure(any()) }
        coVerify(exactly = 0) { setup.readReadiness(any()) }

        reader.preparePreflight() shouldBe ManualTrackingStartPreflight(ManualTrackingStartReadiness.Ready(3L),
            ManualTrackingStartSelection(mapOf(TrackingCaptureSource.STEPS to 7L)))
        gate.currentGeneration shouldBe originalGeneration
        gate.isReady shouldBe true
        gate.permitsNewCollection shouldBe false
        savedOff shouldBe "saved-off"
        coVerify(exactly = 2) { recovery.recoverStartupAuthority() }
        coVerify(exactly = 1) { setup.ensure(setOf(SourceKind.STEPS)) }
        coVerify(exactly = 1) { setup.readReadiness(setOf(SourceKind.STEPS)) }
    }

    @Test fun `unhandled Stop cannot be bypassed by explicit setup preflight`() = runTest {
        gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
        val stop = TrackingStopCommand(7L, TrackingStopCandidateReason.EXPLICIT_REQUEST, 1_234L)
        stopGeneration = stop.generation
        unhandledStop = stop
        coEvery { inactiveStop.finalizeStoredSession(stop) } throws IllegalStateException("pending cleanup fixture")

        reader.preparePreflight() shouldBe ManualTrackingStartPreflight(ManualTrackingStartReadiness.TrackingUnavailable, null)
        gate.isReady shouldBe false
        unhandledStop shouldBe stop
        savedOff shouldBe "saved-off"
        coVerify(exactly = 1) { inactiveStop.finalizeStoredSession(stop) }
        coVerify(exactly = 1) { recovery.recoverStartupAuthority() }
        coVerify(exactly = 0) { setup.ensure(any()) }
        coVerify(exactly = 0) { setup.readReadiness(any()) }
    }

    @Test fun `closed deletion admission prevents explicit setup and preserves the stopped state`() = runTest {
        gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
        deletionBarrier.close()

        reader.preparePreflight() shouldBe ManualTrackingStartPreflight(ManualTrackingStartReadiness.TrackingUnavailable, null)
        deletionBarrier.isClosed shouldBe true
        gate.isReady shouldBe false
        savedOff shouldBe "saved-off"
        coVerify(exactly = 1) { recovery.recoverStartupAuthority() }
        coVerify(exactly = 0) { setup.ensure(any()) }
        coVerify(exactly = 0) { setup.readReadiness(any()) }
    }

    @Test fun `cancelling explicit startup recovery propagates before any writer setup`() = runTest {
        gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
        stopGeneration = 2L
        val enteredRecovery = CompletableDeferred<Unit>()
        coEvery { recovery.recoverStartupAuthority() } coAnswers {
            enteredRecovery.complete(Unit)
            awaitCancellation()
        }

        withTimeout(5_000L) {
            val preparation = async { reader.preparePreflight() }
            enteredRecovery.await()
            preparation.cancelAndJoin()
            preparation.isCancelled shouldBe true
        }
        gate.isReady shouldBe false
        savedOff shouldBe "saved-off"
        coVerify(exactly = 0) { setup.ensure(any()) }
        coVerify(exactly = 0) { setup.readReadiness(any()) }
    }
}
