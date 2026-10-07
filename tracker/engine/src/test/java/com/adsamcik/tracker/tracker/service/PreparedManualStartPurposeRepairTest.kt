package com.adsamcik.tracker.tracker.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
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
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStore
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.source.coordinator.AuthoritativeSessionCoordinator
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the actual coordinator after a controlled successful PREPARE result. The startup
 * lease supplies that result without running Room preparation; these are owner-refresh/error
 * boundary cases, not evidence of Room, Android permission delivery or provider registration.
 */
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
    fun `prepared original Start refreshes all-day owners without requiring an earlier Off`() = runBlocking {
        val coordinator = coordinator()
        coordinator.prepare(originalStart) shouldBe prepared
        coVerify(exactly = 1) { reconciler.reconcileCurrentSettings() }
        coVerify(exactly = 0) { retry.schedule(any()) }
        coVerify(exactly = 0) { authority.prepareCollectionResumeForManualStart(any(), any()) }
    }

    @Test
    fun `owner refresh reads a later Off rather than treating original choices as renewed consent`() = runBlocking {
        val coordinator = coordinator()
        var stopped = false
        coEvery { gate.withReadyGenerationOperation<TrackingStartPreparationResult>(any(), any()) } answers {
            stopped = true
            prepared
        }
        every { authority.pendingCollectionResumeExitId } answers { if (stopped) "later-off" else null }
        var ownerSawStop = false
        coEvery { reconciler.reconcileCurrentSettings() } answers {
            ownerSawStop = authority.pendingCollectionResumeExitId == "later-off"
            TrackingPurposeSettingsReconciliationResult.Complete(emptySet())
        }

        coordinator.prepare(originalStart) shouldBe prepared
        ownerSawStop shouldBe true
        authority.pendingCollectionResumeExitId shouldBe "later-off"
        coVerify(exactly = 0) { authority.prepareCollectionResumeForManualStart(any(), any()) }
    }

    @Test
    fun `independent owner debt schedules its existing retry and preserves prepared session`() = runBlocking {
        val coordinator = coordinator()
        val debt = TrackingPurposeSettingsReconciliationDebt(listOf(
            TrackingPurposeSettingsReconciliationFailure(AmbientTrackingSource.STEPS,
                TrackingPurposeSettingsReconciliationFailureReason.OWNER_OPERATION_IN_PROGRESS),
        ))
        coEvery { reconciler.reconcileCurrentSettings() } returns TrackingPurposeSettingsReconciliationResult.Debt(debt)
        coordinator.prepare(originalStart) shouldBe prepared
        coVerify(exactly = 1) { retry.schedule(debt) }
    }

    @Test
    fun `independent owner failure does not discard prepared session and schedules bounded recovery`() = runBlocking {
        val coordinator = coordinator()
        coEvery { reconciler.reconcileCurrentSettings() } throws IOException("owner unavailable")
        coordinator.prepare(originalStart) shouldBe prepared
        coVerify(exactly = 1) { retry.schedule(match { debt ->
            debt.failures.single().reason == TrackingPurposeSettingsReconciliationFailureReason.OWNER_RECONCILIATION_FAILED
        }) }
    }

    @Test
    fun `cancellation in owner refresh remains cancellation without ordinary recovery scheduling`() = runBlocking {
        val coordinator = coordinator()
        invokeCancellationCleanupLease()
        val cancelled = CancellationException("caller stopped")
        coEvery { reconciler.reconcileCurrentSettings() } throws cancelled
        val failure = try {
            coordinator.prepare(originalStart)
            error("Cancellation must escape")
        } catch (caught: CancellationException) {
            caught
        }
        (failure === cancelled) shouldBe true
        coVerify(exactly = 0) { retry.schedule(any()) }
        coVerify(exactly = 1) { sessionCoordinator.compensatePreparedAndroidStart(
            prepared.token, originalStart.command.generation, "START_PREPARATION_CANCELLED", "boot", any(), any()) }
    }

    @Test
    fun `cancellation while scheduling recovery also compensates the exact prepared Start`() = runBlocking {
        val coordinator = coordinator()
        invokeCancellationCleanupLease()
        val cancelled = CancellationException("retry cancelled")
        coEvery { reconciler.reconcileCurrentSettings() } throws IOException("owner unavailable")
        coEvery { retry.schedule(any()) } throws cancelled
        val failure = try {
            coordinator.prepare(originalStart)
            error("Cancellation must escape")
        } catch (caught: CancellationException) {
            caught
        }
        (failure === cancelled) shouldBe true
        coVerify(exactly = 1) { sessionCoordinator.compensatePreparedAndroidStart(
            prepared.token, originalStart.command.generation, "START_PREPARATION_CANCELLED", "boot", any(), any()) }
    }

    @Test
    fun `failed prepared compensation does not replace the original cancellation`() = runBlocking {
        val coordinator = coordinator()
        invokeCancellationCleanupLease()
        val cancelled = CancellationException("caller stopped")
        coEvery { reconciler.reconcileCurrentSettings() } throws cancelled
        coEvery { sessionCoordinator.compensatePreparedAndroidStart(any(), any(), any(), any(), any(), any()) } throws
            IOException("cleanup unavailable")
        val failure = try {
            coordinator.prepare(originalStart)
            error("Cancellation must escape")
        } catch (caught: CancellationException) {
            caught
        }
        (failure === cancelled) shouldBe true
        coVerify(exactly = 0) { retry.schedule(any()) }
    }

    @Test
    fun `prepared Start while Off leaves all-day owners closed until accepted`() = runBlocking {
        val coordinator = coordinator()
        every { authority.pendingCollectionResumeExitId } returns "prior-off"
        coEvery { authority.prepareCollectionResumeForManualStart("prior-off", originalStart.command) } returns true
        coordinator.prepare(originalStart) shouldBe prepared
        coVerify(exactly = 0) { reconciler.reconcileCurrentSettings() }
        coVerify(exactly = 0) { authority.cancelPendingCollectionStart(any()) }
        authority.pendingCollectionResumeExitId shouldBe "prior-off"
    }

    @Test
    fun `accepted exact Start refreshes all-day owners after the command mutex is released`() = runBlocking {
        val coordinator = coordinator()
        every { gate.permitsNewCollection } returns true
        every { authority.latestCommandGeneration() } returns originalStart.command.generation
        every { authority.resolveStart(originalStart.command) } returns
            com.adsamcik.tracker.tracker.resilience.TrackingStartCommandDisposition.Allowed
        coordinator.reconcileAcceptedManualStart(originalStart.command)
        coVerify(exactly = 1) { reconciler.reconcileCurrentSettings() }
    }

    @Test
    fun `later Off or successor command suppresses post-acceptance owner refresh`() = runBlocking {
        val coordinator = coordinator()
        every { gate.permitsNewCollection } returns false
        coordinator.reconcileAcceptedManualStart(originalStart.command)
        every { gate.permitsNewCollection } returns true
        every { authority.latestCommandGeneration() } returns 2L
        coordinator.reconcileAcceptedManualStart(originalStart.command)
        coVerify(exactly = 0) { reconciler.reconcileCurrentSettings() }
    }

    @Test
    fun `nonmanual recovery and rejected requests do not acquire the manual refresh opportunity`() = runBlocking {
        val coordinator = coordinator()
        coordinator.prepare(TrackingStartRequest(TrackingStartCommand(2L), false, false)) shouldBe prepared
        coordinator.prepare(TrackingStartRequest(TrackingStartCommand(3L), true, false,
            recoveryDescriptor = mockk())) shouldBe prepared
        val rejected = TrackingStartPreparationResult.Rejected("SOURCE_UNAVAILABLE")
        coEvery { gate.withReadyGenerationOperation<TrackingStartPreparationResult>(any(), any()) } returns rejected
        coordinator.prepare(originalStart) shouldBe rejected
        coVerify(exactly = 0) { reconciler.reconcileCurrentSettings() }
        coVerify(exactly = 0) { retry.schedule(any()) }
    }

    private fun coordinator(): DefaultTrackingStartRequestCoordinator {
        every { authority.pendingCollectionResumeExitId } returns null
        every { gate.isReady } returns true
        every { gate.currentGeneration } returns 0L
        coEvery { gate.reconcile(any()) } returns TrackingStartupResult.Ready(false, 0L)
        coEvery { gate.withReadyGenerationOperation<TrackingStartPreparationResult>(any(), any()) } returns prepared
        coEvery { reconciler.reconcileCurrentSettings() } returns TrackingPurposeSettingsReconciliationResult.Complete(
            setOf(AmbientTrackingSource.STEPS))
        coEvery { retry.schedule(any()) } returns true
        return DefaultTrackingStartRequestCoordinator(
            context = ApplicationProvider.getApplicationContext<Context>(), database = database,
            trackingStartupGate = gate, trackingParamsRepository = mockk(), activeTrackingSessionStore = store,
            sourceSession = mockk(), authoritativeSessionCoordinator = sessionCoordinator, trackingRolloutStateStore = mockk(),
            bootClockDomainProvider = BootClockDomainProvider { "boot" }, collectionMotionController = mockk(),
            activityAutomationEpochAuthority = mockk(), trackingLifecycleCommandAuthority = authority,
            sourceCallerDemandDispatcher = mockk(), purposeSettingsReconciler = reconciler,
            purposeReconciliationRetryScheduler = retry, activeTrackingSessionCallerAuthorityReconciler = mockk(),
            sourcePolicyRepository = mockk(), batteryAwarePolicy = mockk(),
        )
    }

    private fun invokeCancellationCleanupLease() {
        var calls = 0
        coEvery { gate.withReadyGenerationOperation<Any>(any(), any()) } coAnswers {
            if (calls++ == 0) prepared else secondArg<suspend () -> Any?>().invoke()
        }
        coEvery { database.sourceSessionDao().serviceRunByDeliveryToken(prepared.token.value) } returns null
        coEvery { store.read() } returns ActiveTrackingSessionStoreResult.Success(null)
        coEvery { sessionCoordinator.compensatePreparedAndroidStart(any(), any(), any(), any(), any(), any()) } returns true
    }
}
