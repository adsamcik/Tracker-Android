package com.adsamcik.tracker.app.startup

import android.app.ActivityManager
import android.content.Context
import com.adsamcik.tracker.app.ApplicationStartupRecoveryAction
import com.adsamcik.tracker.app.settings.CollectedDataDeletionService
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.tracker.resilience.InactiveTrackingSessionStopHandler
import com.adsamcik.tracker.tracker.resilience.InactiveTrackingSessionStopOutcome
import com.adsamcik.tracker.tracker.resilience.PreviousExitRecoveryCoordinator
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import com.adsamcik.tracker.tracker.source.coordinator.LegacyV27ProjectionRecoveryNotReadyException
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainResult
import com.adsamcik.tracker.tracker.source.projection.legacy.LegacyV27ProjectionRecoveryResult
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ApplicationStartupRecoveryResolverTest {
	@Test
	fun `pre-storage exit resolution does not instantiate Room recovery`() {
		val context = mockk<Context>()
		val startupGuard = mockk<TrackingStartupGuard>()
		var recoveryResolutions = 0
		every { startupGuard.wasForceStopped(context) } returns false
		every { context.getSystemService(ActivityManager::class.java) } returns null
		val resolver = ApplicationStartupRecoveryResolver(
			context,
			Provider {
				recoveryResolutions += 1
				mockk<PreviousExitRecoveryCoordinator>()
			},
			startupGuard,
		)

		resolver.resolveAndPrepare() shouldBe ApplicationStartupRecoveryAction.None
		recoveryResolutions shouldBe 0
	}
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTrackingStartupGateTest {
	private val storage = mockk<LegacyDatabaseUpgradeCoordinator>()
	private val collectedDataDeletionService = mockk<CollectedDataDeletionService> {
		coEvery { reconcilePendingDeletion() } just Runs
	}
	private val resolver = mockk<ApplicationStartupRecoveryResolver>()
	private val lifecycleAuthority = mockk<TrackingLifecycleCommandAuthority> {
		every { latestUnhandledStop() } returns null
		every { latestStopGeneration() } returns 0L
	}
	private val inactiveStopHandler = mockk<InactiveTrackingSessionStopHandler>()
	private val sourceRecovery = mockk<SourcePipelineRecovery>()
	private val deletionBarrier = TrackingStartupDeletionBarrier()
	private val gate = DefaultTrackingStartupGate(
		storage,
		collectedDataDeletionService,
		resolver,
		lifecycleAuthority,
		inactiveStopHandler,
		Provider { sourceRecovery },
		deletionBarrier,
	)

	@Test
	fun `concurrent callers share one retryable startup flight`() = runTest {
		val storageStarted = CompletableDeferred<Unit>()
		val allowStorage = CompletableDeferred<Unit>()
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } coAnswers {
			storageStarted.complete(Unit)
			allowStorage.await()
			LegacyDatabaseStartupResult.Failed("vault unavailable")
		}

		val first = async { gate.reconcile() }
		storageStarted.await()
		val second = async { gate.reconcile() }
		runCurrent()
		allowStorage.complete(Unit)

		val expected = TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.STORAGE,
			"vault unavailable",
		)
		first.await() shouldBe expected
		second.await() shouldBe expected
		coVerify(exactly = 1) { collectedDataDeletionService.reconcilePendingDeletion() }
		coVerify(exactly = 1) { storage.ensureReady(false) }
	}

	@Test
	fun `concurrent callers share storage exit and legacy recovery then cache only ready`() = runTest {
		val allowStorage = CompletableDeferred<Unit>()
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } coAnswers {
			allowStorage.await()
			LegacyDatabaseStartupResult.Ready
		}
		coEvery { resolver.apply(ApplicationStartupRecoveryAction.None, 0L) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns
			LegacyV27ProjectionRecoveryResult.Complete(true, 2L)

		val first = async { gate.reconcile() }
		val second = async { gate.reconcile() }
		allowStorage.complete(Unit)

		val expected = TrackingStartupResult.Ready(true, 0L)
		first.await() shouldBe expected
		second.await() shouldBe expected
		gate.reconcile() shouldBe expected
		gate.isReady shouldBe true
		coVerify(exactly = 1) { storage.ensureReady(false) }
		coVerify(exactly = 1) { resolver.apply(ApplicationStartupRecoveryAction.None, 0L) }
		coVerify(exactly = 1) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `storage failure leaves the gate retryable and does not apply exit or resolve Room recovery`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Failed("vault unavailable")

		gate.reconcile() shouldBe TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.STORAGE,
			"vault unavailable",
		)
		gate.isReady shouldBe false
		coVerify(exactly = 0) { resolver.apply(any(), any()) }
		coVerify(exactly = 0) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `pending deletion failure precedes and blocks active database open`() = runTest {
		val failure = IllegalStateException("deletion remains pending")
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { collectedDataDeletionService.reconcilePendingDeletion() } throws failure

		gate.reconcile() shouldBe TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.STORAGE,
			"IllegalStateException",
		)
		coVerify(exactly = 1) { collectedDataDeletionService.reconcilePendingDeletion() }
		coVerify(exactly = 0) { storage.ensureReady(any()) }
		coVerify(exactly = 0) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `retry flag is forwarded after repair and successful retry becomes cached`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Failed("failed")
		coEvery { storage.ensureReady(true) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()

		gate.reconcile() shouldBe TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.STORAGE,
			"failed",
		)
		gate.reconcile(retryFailedStorage = true) shouldBe TrackingStartupResult.Ready(false, 0L)
		gate.reconcile(retryFailedStorage = true) shouldBe TrackingStartupResult.Ready(false, 0L)

		coVerify(exactly = 1) { storage.ensureReady(false) }
		coVerify(exactly = 1) { storage.ensureReady(true) }
		coVerify(exactly = 1) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `unknown legacy contract remains blocked and live v2 is not opened`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } throws
			LegacyV27ProjectionRecoveryNotReadyException(
				LegacyV27ProjectionRecoveryResult.Blocked("UNKNOWN_V27_OUTBOX_GENERATION"),
			)

		gate.reconcile() shouldBe TrackingStartupResult.Blocked(
			TrackingStartupStage.LEGACY_V27,
			"UNKNOWN_V27_OUTBOX_GENERATION",
		)
		gate.isReady shouldBe false
	}

	@Test
	fun `lifecycle supersession retries durable recovery without repeating completed admission`() = runTest {
		var recoveryAttempts = 0
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } coAnswers {
			if (recoveryAttempts++ == 0) {
				throw LegacyV27ProjectionRecoveryNotReadyException(
					LegacyV27ProjectionRecoveryResult.LifecycleSuperseded,
				)
			}
			completed()
		}

		gate.reconcile() shouldBe TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.LEGACY_V27,
			"LEGACY_V27_LIFECYCLE_SUPERSEDED",
		)
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		coVerify(exactly = 1) { storage.ensureReady(false) }
		coVerify(exactly = 1) { resolver.apply(any(), any()) }
		coVerify(exactly = 2) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `previous exit is applied after storage and before projection recovery`() = runTest {
		val action = ApplicationStartupRecoveryAction.PreviousExit(
			reason = 7,
			completedAtMs = 1_234L,
		)
		every { resolver.resolveAndPrepare() } returns action
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(action, 0L) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()

		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)

		coVerifyOrder {
			resolver.resolveAndPrepare()
			storage.ensureReady(false)
			resolver.apply(action, 0L)
			sourceRecovery.recoverStartupAuthority()
		}
	}

	@Test
	fun `durable pending stop is reconciled before previous exit and provider recovery`() = runTest {
		val stop = pendingStop()
		var pending: TrackingStopCommand? = stop
		every { lifecycleAuthority.latestUnhandledStop() } answers { pending }
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { inactiveStopHandler.finalizeStoredSession(stop) } coAnswers {
			pending = null
			InactiveTrackingSessionStopOutcome.FINALIZED
		}
		coEvery { resolver.apply(ApplicationStartupRecoveryAction.None, 0L) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()

		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)

		coVerifyOrder {
			resolver.resolveAndPrepare()
			storage.ensureReady(false)
			inactiveStopHandler.finalizeStoredSession(stop)
			resolver.apply(ApplicationStartupRecoveryAction.None, 0L)
			sourceRecovery.recoverStartupAuthority()
		}
	}

	@Test
	fun `failed pending stop finalization remains retryable and is attempted again`() = runTest {
		val stop = pendingStop()
		var finalizationAttempts = 0
		var pending: TrackingStopCommand? = stop
		every { lifecycleAuthority.latestUnhandledStop() } answers { pending }
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { inactiveStopHandler.finalizeStoredSession(stop) } coAnswers {
			if (finalizationAttempts++ == 0) {
				throw IllegalStateException("descriptor write failed")
			}
			pending = null
			InactiveTrackingSessionStopOutcome.ALREADY_FINALIZED
		}
		coEvery { resolver.apply(ApplicationStartupRecoveryAction.None, 0L) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()

		gate.reconcile() shouldBe TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.PREVIOUS_EXIT,
			"PENDING_STOP_FAILED:IllegalStateException",
		)
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)

		coVerify(exactly = 2) { inactiveStopHandler.finalizeStoredSession(stop) }
		coVerify(exactly = 1) { resolver.apply(any(), any()) }
		coVerify(exactly = 1) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `permanent mismatch runs previous exit once retries once and never opens providers`() = runTest {
		val stop = pendingStop()
		every { lifecycleAuthority.latestUnhandledStop() } returns stop
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { inactiveStopHandler.finalizeStoredSession(stop) } returns
			InactiveTrackingSessionStopOutcome.SESSION_MISMATCH
		coEvery { resolver.apply(ApplicationStartupRecoveryAction.None, 0L) } just Runs

		gate.reconcile() shouldBe TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.PREVIOUS_EXIT,
			"PENDING_STOP_SESSION_MISMATCH",
		)

		coVerify(exactly = 2) { inactiveStopHandler.finalizeStoredSession(stop) }
		coVerify(exactly = 1) { resolver.apply(any(), any()) }
		coVerify(exactly = 0) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `deletion first missing Room stop clears before previous exit and provider recovery`() = runTest {
		val stop = pendingStop()
		var pending: TrackingStopCommand? = stop
		every { lifecycleAuthority.latestUnhandledStop() } answers { pending }
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { inactiveStopHandler.finalizeStoredSession(stop) } coAnswers {
			pending = null
			InactiveTrackingSessionStopOutcome.NO_ROOM_SESSION
		}
		coEvery { resolver.apply(ApplicationStartupRecoveryAction.None, 1L) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
		deletionBarrier.close()
		deletionBarrier.reopen()

		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)

		coVerifyOrder {
			inactiveStopHandler.finalizeStoredSession(stop)
			resolver.apply(ApplicationStartupRecoveryAction.None, 1L)
			sourceRecovery.recoverStartupAuthority()
		}
	}

	@Test
	fun `pending stop invalidates cached ready and waiter resumes only after fresh recovery`() = runTest {
		val stop = pendingStop()
		var pending: TrackingStopCommand? = null
		every { lifecycleAuthority.latestUnhandledStop() } answers { pending }
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(ApplicationStartupRecoveryAction.None, 0L) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
		coEvery { inactiveStopHandler.finalizeStoredSession(stop) } coAnswers {
			pending = null
			InactiveTrackingSessionStopOutcome.FINALIZED
		}

		val expected = TrackingStartupResult.Ready(false, 0L)
		gate.reconcile() shouldBe expected
		pending = stop
		gate.isReady shouldBe false
		val waiter = async { gate.awaitReady() }
		runCurrent()
		waiter.isCompleted shouldBe false

		gate.reconcile() shouldBe expected
		waiter.await() shouldBe expected
		coVerify(exactly = 2) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `handled stop outside gate invalidates cached ready until recovery repeats`() = runTest {
		var stopGeneration = 0L
		every { lifecycleAuthority.latestStopGeneration() } answers { stopGeneration }
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(ApplicationStartupRecoveryAction.None, 0L) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()

		val expected = TrackingStartupResult.Ready(false, 0L)
		gate.reconcile() shouldBe expected
		stopGeneration = 2L
		gate.isReady shouldBe false
		val waiter = async { gate.awaitReady() }
		runCurrent()
		waiter.isCompleted shouldBe false

		gate.reconcile() shouldBe expected
		waiter.await() shouldBe expected
		coVerify(exactly = 2) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `stop reserved and handled during recovery cannot publish stale ready`() = runTest {
		var stopGeneration = 0L
		var recoveryCount = 0
		every { lifecycleAuthority.latestStopGeneration() } answers { stopGeneration }
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(ApplicationStartupRecoveryAction.None, 0L) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } coAnswers {
			recoveryCount += 1
			if (recoveryCount == 2) stopGeneration = 2L
			completed()
		}

		val expected = TrackingStartupResult.Ready(false, 0L)
		gate.reconcile() shouldBe expected
		stopGeneration = 1L

		gate.reconcile() shouldBe TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.PREVIOUS_EXIT,
			"PENDING_STOP_ARRIVED_DURING_RECOVERY",
		)
		gate.isReady shouldBe false
		gate.reconcile() shouldBe expected
		coVerify(exactly = 3) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `live Activity poison stays source local while sibling admission remains open`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
		coEvery { sourceRecovery.drainActivityAutomationEffects() } returns
			ActivityAutomationDrainResult.ProjectionDeferred()

		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		gate.withReadyGeneration(gate.currentGeneration) { "steps-admission-open" } shouldBe
			"steps-admission-open"
		sourceRecovery.drainActivityAutomationEffects() shouldBe
			ActivityAutomationDrainResult.ProjectionDeferred()
		gate.isReady shouldBe true
		coVerify(exactly = 1) { sourceRecovery.recoverStartupAuthority() }
		coVerify(exactly = 0) { sourceRecovery.recoverDurableState() }
	}

	@Test
	fun `admission compatibility call waits for released projection recovery`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()

		gate.reconcileAdmission() shouldBe
			com.adsamcik.tracker.shared.base.startup.TrackingAdmissionStartupResult.Ready
		gate.isAdmissionReady shouldBe true
		gate.isReady shouldBe true
		coVerify(exactly = 1) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `deletion generation invalidates both cached milestones until recovery repeats`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()

		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		deletionBarrier.close()
		gate.isAdmissionReady shouldBe false
		gate.isReady shouldBe false

		deletionBarrier.reopen()
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		coVerify(exactly = 2) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `deletion waits for active recovery and prevents stale ready publication`() = runTest {
		val recoveryStarted = CompletableDeferred<Unit>()
		val allowRecovery = CompletableDeferred<Unit>()
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } coAnswers {
			recoveryStarted.complete(Unit)
			allowRecovery.await()
			completed()
		}

		val reconciliation = async { gate.reconcile() }
		recoveryStarted.await()
		val close = async { deletionBarrier.close() }
		runCurrent()

		deletionBarrier.isClosed shouldBe true
		close.isCompleted shouldBe false
		allowRecovery.complete(Unit)
		reconciliation.await() shouldBe TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.STORAGE,
			"COLLECTED_DATA_DELETION_PENDING",
		)
		close.await()
		gate.isReady shouldBe false
		deletionBarrier.reopen()
	}

	@Test
	fun `deletion remains closed when runtime quiescence misses its deadline`() = runTest {
		val barrier = TrackingStartupDeletionBarrier(operationQuiescenceTimeoutMs = 10L)
		val operationStarted = CompletableDeferred<Unit>()
		val allowOperationToFinish = CompletableDeferred<Unit>()
		val operation = async {
			barrier.withStartupRecovery(onClosed = { "closed" }) {
				operationStarted.complete(Unit)
				allowOperationToFinish.await()
				"finished"
			}
		}
		operationStarted.await()

		val close = async { runCatching { barrier.close() } }
		runCurrent()
		testScheduler.advanceTimeBy(10L)
		runCurrent()

		(close.await().exceptionOrNull() is IllegalStateException) shouldBe true
		barrier.isClosed shouldBe true
		allowOperationToFinish.complete(Unit)
		operation.await() shouldBe "finished"
		barrier.reopen()
	}

	@Test
	fun `deletion waits for an active external handoff before destructive work may begin`() = runTest {
		val handoffStarted = CompletableDeferred<Unit>()
		val allowHandoff = CompletableDeferred<Unit>()
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()

		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		val generation = gate.currentGeneration
		val handoff = async(Dispatchers.Default) {
			gate.withReadyGeneration(generation) {
				handoffStarted.complete(Unit)
				kotlinx.coroutines.runBlocking { allowHandoff.await() }
				"enqueued"
			}
		}
		handoffStarted.await()
		val close = async(Dispatchers.Default) { deletionBarrier.close() }
		runCurrent()

		deletionBarrier.isClosed shouldBe false
		close.isCompleted shouldBe false
		allowHandoff.complete(Unit)
		handoff.await() shouldBe "enqueued"
		close.await()
		deletionBarrier.isClosed shouldBe true
		gate.isReady shouldBe false
		deletionBarrier.reopen()
	}

	@Test
	fun `deletion barrier signals only completed deletion generations`() = runTest {
		deletionBarrier.openGenerations.value shouldBe 0L

		deletionBarrier.close()

		deletionBarrier.openGenerations.value shouldBe 0L
		deletionBarrier.reopen()
		deletionBarrier.openGenerations.value shouldBe 1L
	}

	private fun completed(): LegacyV27ProjectionRecoveryResult =
		LegacyV27ProjectionRecoveryResult.NotRequired

	private fun pendingStop() = TrackingStopCommand(
		generation = 7L,
		reason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
		requestedAtEpochMs = 1_234L,
	)
}
