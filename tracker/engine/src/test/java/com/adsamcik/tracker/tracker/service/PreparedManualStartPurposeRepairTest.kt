package com.adsamcik.tracker.tracker.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.ManualTrackingStartSelection
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.api.TrackingCaptureSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeReconciliationRetryScheduler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailure
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailureReason
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationResult
import com.adsamcik.tracker.tracker.api.TrackingStartPreparationResult
import com.adsamcik.tracker.tracker.api.TrackingStartRequest
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStore
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommandDisposition
import com.adsamcik.tracker.tracker.source.coordinator.AuthoritativeSessionCoordinator
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the actual coordinator after a controlled successful PREPARE result. The startup
 * lease supplies that result without running Room preparation; these are owner-refresh/error
 * boundary cases, not evidence of Room, Android permission delivery or provider registration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreparedManualStartPurposeRepairTest {
    private val authority = mockk<TrackingLifecycleCommandAuthority>(relaxed = true)
    private val reconciler = mockk<TrackingPurposeSettingsReconciler>()
    private val retry = mockk<TrackingPurposeReconciliationRetryScheduler>()
    private val gate = mockk<TrackingStartupGate>()
    private val database = mockk<AppDatabase>(relaxed = true)
    private val store = mockk<ActiveTrackingSessionStore>()
    private val sessionCoordinator = mockk<AuthoritativeSessionCoordinator>()
    private val prepared = TrackingStartPreparationResult.Prepared(PreparedTrackingStartToken("prepared-original"))
    private val selection = ManualTrackingStartSelection(mapOf(TrackingCaptureSource.STEPS to 7L))
    private val originalStart = TrackingStartRequest(TrackingStartCommand(1L), true, false,
        manualSourceSelection = selection)

    @Test
    fun `prepared original Start does not await or invoke optional all-day repair`(): Unit = runTest {
        val coordinator = coordinator(this)
        coEvery { reconciler.reconcileCurrentSettings() } coAnswers { awaitCancellation() }

        withTimeout(1_000L) { coordinator.prepare(originalStart) } shouldBe prepared

        coVerify(exactly = 0) { reconciler.reconcileCurrentSettings() }
        coVerify(exactly = 0) { retry.schedule(any()) }
        coVerify(exactly = 0) { authority.prepareCollectionResumeForManualStart(any(), any()) }
    }

    @Test
    fun `prepared Start while Off leaves all-day owners closed until accepted`(): Unit = runTest {
        val coordinator = coordinator(this)
        every { authority.pendingCollectionResumeExitId } returns "prior-off"
        coEvery { authority.prepareCollectionResumeForManualStart("prior-off", originalStart.command) } returns true

        coordinator.prepare(originalStart) shouldBe prepared

        coVerify(exactly = 0) { reconciler.reconcileCurrentSettings() }
        coVerify(exactly = 0) { authority.cancelPendingCollectionStart(any()) }
        authority.pendingCollectionResumeExitId shouldBe "prior-off"
    }

    @Test
    fun `accepted exact Start queues all-day repair after preparation returns`(): Unit = runTest {
        val coordinator = coordinator(this)
        coordinator.prepare(originalStart) shouldBe prepared
        coVerify(exactly = 0) { reconciler.reconcileCurrentSettings() }

        coordinator.reconcileAcceptedManualStart(originalStart.command)
        coVerify(exactly = 0) { reconciler.reconcileCurrentSettings() }
        runCurrent()

        coVerify(exactly = 1) { reconciler.reconcileCurrentSettings() }
        coVerify(exactly = 0) { retry.schedule(any()) }
    }

    @Test
    fun `provider-only acceptance does not await optional all-day repair`(): Unit = runTest {
        val coordinator = coordinator(this)
        coEvery { reconciler.reconcileCurrentSettings() } coAnswers { awaitCancellation() }

        withTimeout(1_000L) { coordinator.reconcileAcceptedSessionWithoutService(originalStart.command) }
        coVerify(exactly = 0) { reconciler.reconcileCurrentSettings() }
        advanceUntilIdle()

        coVerify(exactly = 1) { reconciler.reconcileCurrentSettings() }
        verifyOwnerFailureRetry()
    }

    @Test
    fun `independent post-acceptance owner debt schedules its existing retry`(): Unit = runTest {
        val coordinator = coordinator(this)
        val debt = TrackingPurposeSettingsReconciliationDebt(listOf(
            TrackingPurposeSettingsReconciliationFailure(AmbientTrackingSource.STEPS,
                TrackingPurposeSettingsReconciliationFailureReason.OWNER_OPERATION_IN_PROGRESS),
        ))
        coEvery { reconciler.reconcileCurrentSettings() } returns TrackingPurposeSettingsReconciliationResult.Debt(debt)

        coordinator.reconcileAcceptedManualStart(originalStart.command)
        runCurrent()

        coVerify(exactly = 1) { retry.schedule(debt) }
        verifyNoPreparedCompensation()
    }

    @Test
    fun `post-acceptance owner failure preserves the session and schedules recovery`(): Unit = runTest {
        val coordinator = coordinator(this)
        coEvery { reconciler.reconcileCurrentSettings() } throws IOException("owner unavailable")

        coordinator.reconcileAcceptedManualStart(originalStart.command)
        runCurrent()

        verifyOwnerFailureRetry()
        verifyNoPreparedCompensation()
    }

    @Test
    fun `stalled post-acceptance owner repair has a finite wait and schedules recovery`(): Unit = runTest {
        val coordinator = coordinator(this)
        coEvery { reconciler.reconcileCurrentSettings() } coAnswers { awaitCancellation() }

        coordinator.reconcileAcceptedManualStart(originalStart.command)
        advanceUntilIdle()

        verifyOwnerFailureRetry()
        verifyNoPreparedCompensation()
    }

    @Test
    fun `owner repair cancellation does not retry or compensate an accepted session`(): Unit = runTest {
        val coordinator = coordinator(this)
        coEvery { reconciler.reconcileCurrentSettings() } throws CancellationException("owner stopped")

        coordinator.reconcileAcceptedManualStart(originalStart.command)
        runCurrent()

        coVerify(exactly = 0) { retry.schedule(any()) }
        verifyNoPreparedCompensation()
    }

    @Test
    fun `later Stop suppresses recovery after a stalled owner repair`(): Unit = runTest {
        val coordinator = coordinator(this)
        coEvery { reconciler.reconcileCurrentSettings() } coAnswers {
            every { authority.latestCommandGeneration() } returns 2L
            awaitCancellation()
        }

        coordinator.reconcileAcceptedManualStart(originalStart.command)
        advanceUntilIdle()

        coVerify(exactly = 0) { retry.schedule(any()) }
    }

    @Test
    fun `later Off or successor command suppresses post-acceptance owner refresh`(): Unit = runTest {
        val coordinator = coordinator(this)
        every { gate.permitsNewCollection } returns false
        coordinator.reconcileAcceptedManualStart(originalStart.command)
        every { gate.permitsNewCollection } returns true
        every { authority.latestCommandGeneration() } returns 2L
        coordinator.reconcileAcceptedManualStart(originalStart.command)
        runCurrent()

        coVerify(exactly = 0) { reconciler.reconcileCurrentSettings() }
    }

    @Test
    fun `a newer Stop fences an already queued owner refresh`(): Unit = runTest {
        val coordinator = coordinator(this)
        coordinator.reconcileAcceptedManualStart(originalStart.command)
        every { authority.latestCommandGeneration() } returns 2L
        runCurrent()

        coVerify(exactly = 0) { reconciler.reconcileCurrentSettings() }
    }

    @Test
    fun `an unaccepted Start cannot refresh all-day owners`(): Unit = runTest {
        val coordinator = coordinator(this)
        every { authority.isAcceptedStart(originalStart.command) } returns false

        coordinator.reconcileAcceptedManualStart(originalStart.command)
        runCurrent()

        coVerify(exactly = 0) { reconciler.reconcileCurrentSettings() }
    }

    @Test
    fun `nonmanual recovery and rejected preparation do not refresh owners`(): Unit = runTest {
        val coordinator = coordinator(this)
        coordinator.prepare(TrackingStartRequest(TrackingStartCommand(2L), false, false)) shouldBe prepared
        coordinator.prepare(TrackingStartRequest(TrackingStartCommand(3L), true, false,
            recoveryDescriptor = mockk())) shouldBe prepared
        val rejected = TrackingStartPreparationResult.Rejected("SOURCE_UNAVAILABLE")
        coEvery { gate.withReadyGenerationOperation<TrackingStartPreparationResult>(any(), any()) } returns rejected

        coordinator.prepare(originalStart) shouldBe rejected

        coVerify(exactly = 0) { reconciler.reconcileCurrentSettings() }
        coVerify(exactly = 0) { retry.schedule(any()) }
    }

    private fun verifyOwnerFailureRetry() {
        coVerify(exactly = 1) { retry.schedule(match { debt ->
            debt.failures.single().reason == TrackingPurposeSettingsReconciliationFailureReason.OWNER_RECONCILIATION_FAILED
        }) }
    }

    private fun verifyNoPreparedCompensation() {
        coVerify(exactly = 0) {
            sessionCoordinator.compensatePreparedAndroidStart(any(), any(), any(), any(), any(), any())
        }
    }

    private fun coordinator(scope: CoroutineScope): DefaultTrackingStartRequestCoordinator {
        every { authority.pendingCollectionResumeExitId } returns null
        every { authority.latestCommandGeneration() } returns originalStart.command.generation
        every { authority.isAcceptedStart(originalStart.command) } returns true
        every { authority.resolveStart(originalStart.command) } returns TrackingStartCommandDisposition.Allowed
        every { gate.permitsNewCollection } returns true
        every { gate.isReady } returns true
        every { gate.currentGeneration } returns 0L
        coEvery { gate.reconcile(any()) } returns TrackingStartupResult.Ready(false, 0L)
        coEvery { gate.withReadyGenerationOperation<TrackingStartPreparationResult>(any(), any()) } returns prepared
        coEvery { reconciler.reconcileCurrentSettings() } returns TrackingPurposeSettingsReconciliationResult.Complete(
            setOf(AmbientTrackingSource.STEPS))
        coEvery { retry.schedule(any()) } returns true
        return DefaultTrackingStartRequestCoordinator(
            context = ApplicationProvider.getApplicationContext<Context>(), database = database,
            trackingStartupGate = gate, trackingParamsRepository = mockk<TrackingParamsRepository> {
                every { data } returns MutableStateFlow(TrackingParamsState())
            }, activeTrackingSessionStore = store,
            sourceSession = mockk(), authoritativeSessionCoordinator = sessionCoordinator, trackingRolloutStateStore = mockk(),
            chosenSourceWriterSetup = mockk {
                coEvery { ensure(any()) } coAnswers {
                    val chosen = firstArg<Set<com.adsamcik.tracker.tracker.source.model.SourceKind>>()
                    com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterSetupResult(chosen.associateWith {
                        com.adsamcik.tracker.tracker.source.coordinator.ChosenSourceWriterStatus.READY })
                }
            },
            bootClockDomainProvider = BootClockDomainProvider { "boot" }, collectionMotionController = mockk(),
            activityAutomationEpochAuthority = mockk(), trackingLifecycleCommandAuthority = authority,
            sourceCallerDemandDispatcher = mockk(), purposeSettingsReconciler = reconciler,
            purposeReconciliationRetryScheduler = retry, activeTrackingSessionCallerAuthorityReconciler = mockk(),
            sourcePolicyRepository = mockk(), batteryAwarePolicy = mockk(), applicationScope = scope,
        )
    }
}
