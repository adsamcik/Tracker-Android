package com.adsamcik.tracker.app.startup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.app.ApplicationStartupRecoveryAction
import com.adsamcik.tracker.app.settings.CollectedDataDeletionCompletion
import com.adsamcik.tracker.app.settings.CollectedDataDeletionService
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicy
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicySnapshot
import com.adsamcik.tracker.shared.preferences.tracking.SourceQos
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.ManualTrackingStartSelection
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.api.TrackingCaptureSource
import com.adsamcik.tracker.tracker.api.TrackingStartPreparationResult
import com.adsamcik.tracker.tracker.api.TrackingStartRequest
import com.adsamcik.tracker.tracker.resilience.LockedTrackingStartResult
import com.adsamcik.tracker.tracker.resilience.SharedPreferencesTrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.projection.legacy.LegacyV27ProjectionRecoveryResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production startup and command locks with the existing provider-only acceptance path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE") // Exercise the engine without adding a production test API.
class ProviderStepsStartupAcceptanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val token = PreparedTrackingStartToken("provider-session")
    private val steps = mockk<com.adsamcik.tracker.tracker.service.ProviderStepsSessionLifecycle>()
    private val policies = mockk<SourcePolicyRepository>()
    private val settings = mockk<TrackingParamsRepository>()
    private val selection = ManualTrackingStartSelection(mapOf(TrackingCaptureSource.STEPS to 7L))
    private lateinit var commands: SharedPreferencesTrackingLifecycleCommandAuthority
    private lateinit var barrier: TrackingStartupDeletionBarrier
    private lateinit var gate: DefaultTrackingStartupGate
    private lateinit var startCoordinator: com.adsamcik.tracker.tracker.service.DefaultTrackingStartRequestCoordinator

    @Before
    fun setup() {
        context.getSharedPreferences("tracking_lifecycle_commands", Context.MODE_PRIVATE).edit().clear().commit()
        commands = SharedPreferencesTrackingLifecycleCommandAuthority(context, Dispatchers.IO,
            BootClockDomainProvider { "boot" })
        barrier = TrackingStartupDeletionBarrier()
        gate = startupGate()
        coEvery { policies.currentState() } returns SourcePolicyAuthorityState.Active(policy())
        every { settings.data } returns flowOf(TrackingParamsState(stepsEnabled = true,
            ambientStepsEnabled = true, sourcePolicyRevision = 1L))
        coEvery { steps.accept(token, any()) } returns true
        startCoordinator = coordinator()
    }

    @Test
    fun `provider session accepts with the real startup and Start locks and resumes saved Off`(): Unit = runBlocking {
        val request = prepareRequest()
        withTimeout(2_000L) { accept(request) } shouldBe LockedTrackingStartResult.Executed(true)
        commands.isAcceptedStart(request.command) shouldBe true
        commands.pendingCollectionResumeExitId shouldBe null
        coVerify(exactly = 1) { steps.accept(token, request.command.generation) }
    }

    @Test
    fun `failed provider acceptance keeps saved Off and does not accept Start`(): Unit = runBlocking {
        val request = prepareRequest()
        coEvery { steps.accept(token, request.command.generation) } returns false
        withTimeout(2_000L) { accept(request) } shouldBe LockedTrackingStartResult.Executed(false)
        commands.isAcceptedStart(request.command) shouldBe false
        commands.pendingCollectionResumeExitId shouldBe "off"
    }

    @Test
    fun `End before acceptance prevents a provider-only Start`(): Unit = runBlocking {
        val request = prepareRequest()
        val stop = requireNotNull(commands.reserveStop(TrackingStopCandidateReason.EXPLICIT_REQUEST, 2_000L))
        withTimeout(2_000L) { accept(request) } shouldBe LockedTrackingStartResult.BlockedByStop(stop)
        commands.isAcceptedStart(request.command) shouldBe false
        commands.pendingCollectionResumeExitId shouldBe "off"
        coVerify(exactly = 0) { steps.accept(any(), any()) }
    }

    @Test
    fun `closed deletion admission prevents provider acceptance without releasing Off`(): Unit = runBlocking {
        val request = prepareRequest()
        barrier.closeAdmission()
        withTimeout(2_000L) { accept(request) } shouldBe LockedTrackingStartResult.Executed(false)
        commands.isAcceptedStart(request.command) shouldBe false
        commands.pendingCollectionResumeExitId shouldBe "off"
        coVerify(exactly = 0) { steps.accept(any(), any()) }
    }

    @Test
    fun `provider cancellation propagates without accepting Start or releasing Off`(): Unit = runBlocking {
        val request = prepareRequest()
        val cancelled = CancellationException("provider cancelled")
        coEvery { steps.accept(token, request.command.generation) } throws cancelled
        val failure = shouldThrow<CancellationException> { withTimeout(2_000L) { accept(request) } }
        generateSequence<Throwable>(failure) { it.cause }.any { it === cancelled } shouldBe true
        commands.isAcceptedStart(request.command) shouldBe false
        commands.pendingCollectionResumeExitId shouldBe "off"
    }

    private suspend fun prepareRequest(): TrackingStartRequest {
        gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
        commands.recordUserRequestedExit("off") shouldBe true
        val command = requireNotNull(commands.reserveStart())
        commands.prepareCollectionResumeForManualStart("off", command) shouldBe true
        return TrackingStartRequest(command, isUserInitiated = true, isAmbient = false,
            manualSourceSelection = selection)
    }

    /** Same outer ownership as PreparedStartDispatch; production coordinator owns deletion exclusion. */
    private suspend fun accept(request: TrackingStartRequest): LockedTrackingStartResult<Boolean> =
        commands.withCurrentStart(request.command, acceptWhen = { it }) {
            startCoordinator.acceptWithoutForegroundService(TrackingStartPreparationResult.Prepared(token,
                startupGeneration = gate.currentGeneration, requiresForegroundService = false), request)
        }

    private fun coordinator() = com.adsamcik.tracker.tracker.service.DefaultTrackingStartRequestCoordinator(
        context = context, database = mockk(), trackingStartupGate = gate, trackingParamsRepository = settings,
        activeTrackingSessionStore = mockk(), sourceSession = mockk(), authoritativeSessionCoordinator = mockk(),
        trackingRolloutStateStore = mockk(), chosenSourceWriterSetup = mockk(),
        bootClockDomainProvider = BootClockDomainProvider { "boot" }, collectionMotionController = mockk(),
        activityAutomationEpochAuthority = mockk(), trackingLifecycleCommandAuthority = commands,
        sourceCallerDemandDispatcher = mockk(), activeTrackingSessionCallerAuthorityReconciler = mockk(),
        sourcePolicyRepository = policies, batteryAwarePolicy = mockk(), providerStepsSessions = steps,
        applicationScope = mockk())

    private fun startupGate(): DefaultTrackingStartupGate {
        val resolver = mockk<ApplicationStartupRecoveryResolver>()
        val storage = mockk<LegacyDatabaseUpgradeCoordinator>()
        val recovery = mockk<SourcePipelineRecovery>()
        val deletion = mockk<CollectedDataDeletionService>()
        every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
        coEvery { resolver.apply(any(), any()) } just Runs
        coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
        coEvery { recovery.recoverStartupAuthority() } returns LegacyV27ProjectionRecoveryResult.NotRequired
        coEvery { deletion.reconcilePendingDeletion() } returns CollectedDataDeletionCompletion.Complete
        return DefaultTrackingStartupGate(storage, Provider { deletion }, resolver, commands,
            Provider { mockk() }, Provider { recovery }, barrier)
    }

    private fun policy() = SourcePolicySnapshot(1L, TrackingSourceComponent.entries.associateWith { source ->
        val chosen = source == TrackingSourceComponent.STEPS
        SourcePolicy(source, chosen, if (chosen) { SourceQos.BALANCED } else { SourceQos.OFF },
            locationMinTimeSeconds = if (source == TrackingSourceComponent.LOCATION) { 10 } else { null },
            locationMinDistanceMeters = if (source == TrackingSourceComponent.LOCATION) { 5 } else { null },
            locationRequiredAccuracyMeters = if (source == TrackingSourceComponent.LOCATION) { 50 } else { null },
            captureConsentEpoch = if (chosen) { 7L } else { null }, controlConsentEpoch = null,
            ambientConsentEpoch = if (chosen) { 7L } else { null }, capturePersistenceEligible = chosen,
            controlPersistenceEligible = false, ambientPersistenceEligible = chosen,
            effectiveTime = SourcePolicyEffectiveTime("boot", 10L, 1_000L), policyRevision = 1L)
    })
}
