package com.adsamcik.tracker.app.startup

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import com.adsamcik.tracker.app.ApplicationStartupRecoveryAction
import com.adsamcik.tracker.app.settings.CollectedDataDeletionService
import com.adsamcik.tracker.app.settings.CollectedDataDeletionCompletion
import com.adsamcik.tracker.app.settings.CollectedDataDeletionReconciliationFailure
import com.adsamcik.tracker.shared.base.database.ActiveDatabaseBlockReason
import com.adsamcik.tracker.shared.base.database.ActiveDatabaseRetryableReason
import com.adsamcik.tracker.shared.base.startup.TrackingDatabaseContainment
import com.adsamcik.tracker.shared.base.startup.TrackingDatabaseContainmentReason
import com.adsamcik.tracker.shared.base.startup.TrackingDatabaseRetryable
import com.adsamcik.tracker.shared.base.startup.TrackingDatabaseRetryableReason
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.tracker.resilience.InactiveTrackingSessionStopHandler
import com.adsamcik.tracker.tracker.resilience.InactiveTrackingSessionStopOutcome
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreCorruptionException
import com.adsamcik.tracker.tracker.resilience.PreviousExitRecoveryCoordinator
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import com.adsamcik.tracker.tracker.source.coordinator.LegacyV27ProjectionRecoveryNotReadyException
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult
import com.adsamcik.tracker.tracker.source.projection.ProjectionDispatcher
import com.adsamcik.tracker.tracker.source.model.SourceKind
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
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
			mockk<TrackingLifecycleCommandAuthority>(),
		)

		resolver.resolveAndPrepare() shouldBe ApplicationStartupRecoveryAction.None
		recoveryResolutions shouldBe 0
	}

	@Test
	fun `ambiguous user exit and confirmed force stop preserve the same bounded stop receipt`() = runTest {
		val recovery = mockk<PreviousExitRecoveryCoordinator>(relaxed = true)
		val commands = mockk<TrackingLifecycleCommandAuthority> {
			coEvery { recordUserRequestedExit(any()) } returns true
		}
		val resolver = ApplicationStartupRecoveryResolver(mockk(), Provider { recovery }, mockk(), commands)
		resolver.apply(ApplicationStartupRecoveryAction.PreviousExit(ApplicationExitInfo.REASON_USER_REQUESTED, 123L), 0L)
		resolver.apply(ApplicationStartupRecoveryAction.ConfirmedForceStop(123L), 0L)
		coVerify(exactly = 2) { commands.recordUserRequestedExit("user-requested:123") }
		coVerify(exactly = 1) { recovery.handle(ApplicationExitInfo.REASON_USER_REQUESTED, 123L, 0L) }
		coVerify(exactly = 1) { recovery.suppressAfterForceStop(123L) }
	}
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTrackingStartupGateTest {
	private val storage = mockk<LegacyDatabaseUpgradeCoordinator>()
	private val collectedDataDeletionService = mockk<CollectedDataDeletionService> {
		coEvery { reconcilePendingDeletion() } returns
			CollectedDataDeletionCompletion.Complete
	}
	private val resolver = mockk<ApplicationStartupRecoveryResolver>()
	private val lifecycleAuthority = mockk<TrackingLifecycleCommandAuthority> {
		every { pendingCollectionResumeExitId } returns null
		every { collectionResumeBoundary } returns null
		every { latestUnhandledStop() } returns null
		every { latestStopGeneration() } returns 0L
		coEvery { runWithHandledStopGeneration(any(), any()) } coAnswers {
			secondArg<suspend () -> Unit>().invoke()
			true
		}
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
		gate.withRetainedDataGeneration(0L) { "unrecovered-data" } shouldBe null
		coVerify(exactly = 0) { resolver.apply(any(), any()) }
		coVerify(exactly = 0) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `stale development v28 blocks before lifecycle and source consumers`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns
			LegacyDatabaseStartupResult.ActiveDatabaseBlocked(
				ActiveDatabaseBlockReason.STALE_DEVELOPMENT_V28,
			)

		gate.reconcile() shouldBe TrackingStartupResult.Blocked(
			stage = TrackingStartupStage.STORAGE,
			failureCode = "STALE_DEVELOPMENT_V28",
			databaseContainment = TrackingDatabaseContainment(
				TrackingDatabaseContainmentReason.STALE_DEVELOPMENT_V28,
			),
		)

		gate.isReady shouldBe false
		coVerify(exactly = 0) { resolver.apply(any(), any()) }
		coVerify(exactly = 0) { inactiveStopHandler.finalizeStoredSession(any()) }
		coVerify(exactly = 0) { sourceRecovery.recoverStartupAuthority() }
	}

	@Test
	fun `database contention stays retryable and does not start consumers`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns
			LegacyDatabaseStartupResult.ActiveDatabaseRetryable(
				ActiveDatabaseRetryableReason.CONTENDED,
			)

		gate.reconcile() shouldBe TrackingStartupResult.RetryableFailure(
			stage = TrackingStartupStage.STORAGE,
			failureCode = "ACTIVE_DATABASE_CONTENDED",
			databaseRetryable = TrackingDatabaseRetryable(
				TrackingDatabaseRetryableReason.CONTENDED,
			),
		)

		gate.isReady shouldBe false
		coVerify(exactly = 0) { resolver.apply(any(), any()) }
		coVerify(exactly = 0) { inactiveStopHandler.finalizeStoredSession(any()) }
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
	fun `typed pending deletion retry debt blocks startup retryably`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { collectedDataDeletionService.reconcilePendingDeletion() } returns
			CollectedDataDeletionCompletion.Retryable(
				CollectedDataDeletionReconciliationFailure.PurposeSettings(
					com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationDebt(listOf(
						com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailure(
							null,
							com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailureReason.OWNER_RECONCILIATION_FAILED,
						),
					)),
				),
			)

		gate.reconcile() shouldBe TrackingStartupResult.RetryableFailure(
			TrackingStartupStage.STORAGE,
			"POST_DELETE_PURPOSE_SETTINGS",
		)
		coVerify(exactly = 0) { storage.ensureReady(any()) }
	}

	@Test
	fun `typed pending deletion unverifiable debt blocks startup`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { collectedDataDeletionService.reconcilePendingDeletion() } returns
			CollectedDataDeletionCompletion.Unverifiable(
				CollectedDataDeletionReconciliationFailure.RetentionResultSetInvalid,
			)

		gate.reconcile() shouldBe TrackingStartupResult.Blocked(
			TrackingStartupStage.STORAGE,
			"POST_DELETE_RETENTION_RESULT_SET_INVALID",
		)
		coVerify(exactly = 0) { storage.ensureReady(any()) }
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
	fun `corrupt active descriptor blocks startup instead of retrying as empty state`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } throws
			ActiveTrackingSessionStoreCorruptionException(
				java.io.IOException("corrupt proto"),
			)

		gate.reconcile() shouldBe TrackingStartupResult.Blocked(
			TrackingStartupStage.PREVIOUS_EXIT,
			"ACTIVE_DESCRIPTOR_CORRUPT",
		)
		coVerify(exactly = 0) { sourceRecovery.recoverStartupAuthority() }
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
	fun `deletion after foreground authorization rejects handoff until replacement Ready`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
		var controlInitializations = 0
		var suppressionReleased = false

		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		val foregroundAuthorizedGeneration = gate.currentGeneration
		deletionBarrier.close()
		deletionBarrier.reopen()

		gate.withReadyGenerationOperation(foregroundAuthorizedGeneration) {
			controlInitializations += 1
			suppressionReleased = true
			true
		} shouldBe null
		controlInitializations shouldBe 0
		suppressionReleased shouldBe false
		gate.isReady shouldBe false

		val replacementReady = async { gate.awaitReady() }
		runCurrent()
		replacementReady.isCompleted shouldBe false
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		replacementReady.await() shouldBe TrackingStartupResult.Ready(false, 0L)
		val replacementGeneration = gate.currentGeneration
		replacementGeneration shouldBe foregroundAuthorizedGeneration + 1L

		gate.withReadyGenerationOperation(replacementGeneration) {
			controlInitializations += 1
			suppressionReleased = true
			true
		} shouldBe true
		controlInitializations shouldBe 1
		suppressionReleased shouldBe true
	}

	@Test
	fun `unhandled STOP after foreground authorization rejects handoff until terminal recovery`() =
		runTest {
			val stop = pendingStop()
			var pending: TrackingStopCommand? = null
			var stopGeneration = 0L
			every { lifecycleAuthority.latestUnhandledStop() } answers { pending }
			every { lifecycleAuthority.latestStopGeneration() } answers { stopGeneration }
			every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
			coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
			coEvery { resolver.apply(any(), any()) } just Runs
			coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
			coEvery { inactiveStopHandler.finalizeStoredSession(stop) } coAnswers {
				pending = null
				InactiveTrackingSessionStopOutcome.FINALIZED
			}
			var controlInitializations = 0
			var suppressionReleased = false

			gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
			val foregroundAuthorizedGeneration = gate.currentGeneration
			stopGeneration = stop.generation
			pending = stop

			gate.withReadyGenerationOperation(foregroundAuthorizedGeneration) {
				controlInitializations += 1
				suppressionReleased = true
				true
			} shouldBe null
			controlInitializations shouldBe 0
			suppressionReleased shouldBe false
			gate.isReady shouldBe false

			val replacementReady = async { gate.awaitReady() }
			runCurrent()
			replacementReady.isCompleted shouldBe false
			gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
			replacementReady.await() shouldBe TrackingStartupResult.Ready(false, 0L)

			gate.withReadyGenerationOperation(foregroundAuthorizedGeneration) {
				controlInitializations += 1
				suppressionReleased = true
				true
			} shouldBe true
			controlInitializations shouldBe 1
			suppressionReleased shouldBe true
			coVerify(exactly = 1) { inactiveStopHandler.finalizeStoredSession(stop) }
		}

	@Test
	fun `STOP reservation is ordered after an admitted foreground handoff`() = runTest {
		val commandGate = Mutex()
		val stop = pendingStop()
		var stopGeneration = 0L
		var pending: TrackingStopCommand? = null
		val events = mutableListOf<String>()
		val handoffEntered = CompletableDeferred<Unit>()
		val allowHandoff = CompletableDeferred<Unit>()
		every { lifecycleAuthority.latestStopGeneration() } answers { stopGeneration }
		every { lifecycleAuthority.latestUnhandledStop() } answers { pending }
		coEvery { lifecycleAuthority.runWithHandledStopGeneration(any(), any()) } coAnswers {
			val expectedGeneration = firstArg<Long>()
			val operation = secondArg<suspend () -> Unit>()
			commandGate.withLock {
				if (stopGeneration != expectedGeneration || pending != null) {
					false
				} else {
					operation()
					true
				}
			}
		}
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()

		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		val generation = gate.currentGeneration
		val handoff = async(Dispatchers.Default) {
			gate.withReadyGenerationOperation(generation) {
				events += "HANDOFF_ENTERED"
				handoffEntered.complete(Unit)
				allowHandoff.await()
				events += "CONTROL_INITIALIZED_AND_RELEASED"
				true
			}
		}
		handoffEntered.await()
		val stopReservation = async(Dispatchers.Default) {
			commandGate.withLock {
				stopGeneration = stop.generation
				pending = stop
				events += "STOP_RESERVED"
			}
		}
		withTimeoutOrNull(100L) { stopReservation.await() } shouldBe null

		allowHandoff.complete(Unit)
		handoff.await() shouldBe true
		stopReservation.await()
		events shouldBe listOf(
			"HANDOFF_ENTERED",
			"CONTROL_INITIALIZED_AND_RELEASED",
			"STOP_RESERVED",
		)
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
	fun `admitted provider start completes before close admission returns`() = runTest {
		val operationAdmitted = CompletableDeferred<Unit>()
		val allowProviderStart = CompletableDeferred<Unit>()
		val events = mutableListOf<String>()
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		val generation = gate.currentGeneration

		val provider = async {
			gate.withReadyGenerationOperation(generation) {
				operationAdmitted.complete(Unit)
				allowProviderStart.await()
				events += "PROVIDER_STARTED"
			}
		}
		operationAdmitted.await()
		val close = async {
			deletionBarrier.closeAdmission()
			events += "ADMISSION_CLOSED"
		}
		runCurrent()

		close.isCompleted shouldBe false
		allowProviderStart.complete(Unit)
		provider.await() shouldBe Unit
		close.await()
		events shouldBe listOf("PROVIDER_STARTED", "ADMISSION_CLOSED")
		deletionBarrier.reopen()
	}

	@Test
	fun `nested ready generation operation reuses the held deletion boundary`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		val generation = gate.currentGeneration

		gate.withReadyGenerationOperation(generation) {
			gate.withReadyGenerationOperation(generation) { "nested" }
		} shouldBe "nested"
	}

	@Test
	fun `nested ready operation rejects once deletion begins closing admission`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		val generation = gate.currentGeneration
		val outerEntered = CompletableDeferred<Unit>()
		val attemptNested = CompletableDeferred<Unit>()
		var providerStarts = 0

		val outer = async {
			gate.withReadyGenerationOperation(generation) {
				outerEntered.complete(Unit)
				attemptNested.await()
				gate.withReadyGenerationOperation(generation) {
					providerStarts += 1
					"started"
				}
			}
		}
		outerEntered.await()
		val close = async { deletionBarrier.closeAdmission() }
		runCurrent()

		deletionBarrier.isClosed shouldBe true
		close.isCompleted shouldBe false
		attemptNested.complete(Unit)
		outer.await() shouldBe null
		close.await()
		providerStarts shouldBe 0
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

	@Test
	fun `pending manual Start never opens general collection or a closed storage generation`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
		every { lifecycleAuthority.pendingCollectionResumeExitId } returns "user-off"
		every { lifecycleAuthority.isPendingCollectionStart(any()) } answers { firstArg<com.adsamcik.tracker.tracker.resilience.TrackingStartCommand>().generation == 41L }
		gate.permitsPendingManualStart(41L) shouldBe false
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		gate.permitsPendingManualStart(41L) shouldBe true
		gate.permitsPendingManualStart(42L) shouldBe false
		gate.permitsNewCollection shouldBe false
		deletionBarrier.closeAdmission()
		gate.permitsPendingManualStart(41L) shouldBe false
		gate.permitsNewCollection shouldBe false
	}

	private fun completed(): LegacyV27ProjectionRecoveryResult =
		LegacyV27ProjectionRecoveryResult.NotRequired

	@Test
	fun `nested history transfers share admission while collection is stopped`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
		every { lifecycleAuthority.pendingCollectionResumeExitId } returns "user-off"
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		gate.permitsNewCollection shouldBe false
		gate.withReadyGenerationTransfer(0L) {
			gate.withReadyGenerationTransfer(0L) { "retained-file" }
		} shouldBe "retained-file"
	}

	@Test
	fun `old transfer cannot hand off a share after deletion reopens`() = runTest {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
		gate.reconcile()
		val oldGeneration = gate.currentGeneration
		deletionBarrier.closeAdmission()
		deletionBarrier.reopen()
		gate.reconcile()
		var published = 0
		gate.withRetainedDataGeneration(oldGeneration) { published++ } shouldBe null
		gate.withReadyGenerationTransfer(oldGeneration) { published++ } shouldBe null
		published shouldBe 0
	}

	@Test
	fun `handled Off during an admitted history export preserves its file handoff but not live authority`() = runTest {
		var stopGeneration = 0L
		var pending: TrackingStopCommand? = null
		every { lifecycleAuthority.latestStopGeneration() } answers { stopGeneration }
		every { lifecycleAuthority.latestUnhandledStop() } answers { pending }
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		val generation = gate.currentGeneration
		val writing = CompletableDeferred<Unit>()
		val finish = CompletableDeferred<Unit>()
		val bytes = ByteArrayOutputStream()
		var shared = false
		val export = async {
			gate.withReadyGenerationTransfer(generation) {
				bytes.use { output -> output.write(7); writing.complete(Unit); finish.await(); output.write(8) }
				gate.withReadyGenerationTransfer(generation) { "same-retained-generation" } shouldBe
					"same-retained-generation"
				gate.withRetainedDataGeneration(generation) { shared = true; true }
			}
		}
		writing.await()
		stopGeneration = pendingStop().generation
		pending = pendingStop()
		gate.withReadyGeneration(generation) { "provider-start" } shouldBe null
		// The real service marks Off handled after owners retire; it need not reconcile startup.
		pending = null
		every { lifecycleAuthority.pendingCollectionResumeExitId } returns "user-off"
		gate.currentGeneration shouldBe generation
		gate.isReady shouldBe false
		gate.permitsNewCollection shouldBe false
		finish.complete(Unit)
		export.await() shouldBe true
		shared shouldBe true
		bytes.toByteArray().toList() shouldBe listOf(7.toByte(), 8.toByte())
		coVerify(exactly = 1) { sourceRecovery.recoverStartupAuthority() }
		deletionBarrier.closeAdmission()
		gate.withRetainedDataGeneration(generation) { "stale-share" } shouldBe null
		deletionBarrier.reopen()
		gate.reconcile()
		gate.withRetainedDataGeneration(generation) { "stale-share" } shouldBe null
	}

	@Test
	fun `actual retained history recovery failure revokes a previously ready transfer generation`() = runTest {
		var stopGeneration = 0L
		every { lifecycleAuthority.latestStopGeneration() } answers { stopGeneration }
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		gate.withRetainedDataGeneration(0L) { "retained-data" } shouldBe "retained-data"
		stopGeneration = 1L
		coEvery { sourceRecovery.recoverStartupAuthority() } throws java.io.IOException("History unavailable")
		gate.reconcile().javaClass shouldBe TrackingStartupResult.RetryableFailure::class.java
		gate.withRetainedDataGeneration(0L) { "unavailable-data" } shouldBe null
		gate.withReadyGenerationTransfer(0L) { "unavailable-data" } shouldBe null
	}

	@Test
	@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE") // Exercises the existing engine constructor without adding a production test API.
	fun `actual radio recovery shares retained gate without serializing sources or borrowing acquisition after Off`() = runTest {
		var stopGeneration = 0L
		every { lifecycleAuthority.latestStopGeneration() } answers { stopGeneration }
		prepareRetainedGate()
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		val projections = mockk<ProjectionDispatcher>()
		val wifiEntered = CompletableDeferred<Unit>()
		val finishWifi = CompletableDeferred<Unit>()
		coEvery { projections.drainAmbientRadioProjection(SourceKind.WIFI, any()) } coAnswers {
			wifiEntered.complete(Unit)
			finishWifi.await()
			CoordinatorDrainResult.Complete(1L, 1)
		}
		coEvery { projections.drainAmbientRadioProjection(SourceKind.CELL, any()) } returns
			CoordinatorDrainResult.Complete(2L, 1)
		val recovery = actualRadioRecovery(projections, backgroundScope)
		recovery.requestWifiSessionFactDrain()
		runCurrent()
		wifiEntered.isCompleted shouldBe true
		// The startup/Stop control plane is available while the background Wi-Fi write is suspended.
		withTimeoutOrNull(1_000L) {
			gate.withReadyGenerationOperation(gate.currentGeneration) { "control-available" }
		} shouldBe "control-available"
		stopGeneration = 1L
		every { lifecycleAuthority.pendingCollectionResumeExitId } returns "user-off"
		gate.isReady shouldBe false
		gate.permitsNewCollection shouldBe false
		recovery.requestCellSessionFactDrain()
		runCurrent()
		coVerify(exactly = 1) { projections.drainAmbientRadioProjection(SourceKind.CELL, any()) }
		gate.withReadyGeneration(gate.currentGeneration) { "provider-start" } shouldBe null
		finishWifi.complete(Unit)
		runCurrent()
		coVerify(exactly = 1) { projections.drainAmbientRadioProjection(SourceKind.WIFI, any()) }
	}

	@Test
	@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
	fun `real gate cancels and physically joins radio cleanup then the same actor recovers the reopened generation`() = runTest {
		prepareRetainedGate()
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		val projections = mockk<ProjectionDispatcher>()
		val entered = CompletableDeferred<Unit>()
		val cleanupEntered = CompletableDeferred<Unit>()
		val finishCleanup = CompletableDeferred<Unit>()
		var cleanupFinished = false
		val admittedGenerations = mutableListOf<Long>()
		coEvery { projections.drainAmbientRadioProjection(SourceKind.WIFI, any()) } coAnswers {
			admittedGenerations += gate.currentGeneration
			if (admittedGenerations.size == 1) {
				try {
					entered.complete(Unit)
					awaitCancellation()
				} finally {
					withContext(NonCancellable) {
						cleanupEntered.complete(Unit)
						finishCleanup.await()
						cleanupFinished = true
					}
				}
			}
			CoordinatorDrainResult.Complete(2L, 1)
		}
		val recovery = actualRadioRecovery(projections, backgroundScope)
		recovery.requestWifiSessionFactDrain()
		runCurrent()
		entered.isCompleted shouldBe true
		val oldGeneration = gate.currentGeneration
		val deletion = async { deletionBarrier.closeAdmission() }
		runCurrent()
		cleanupEntered.isCompleted shouldBe true
		deletion.isCompleted shouldBe false
		gate.withRetainedDataGeneration(oldGeneration) { "old-write" } shouldBe null
		recovery.requestWifiSessionFactDrain()
		runCurrent()
		admittedGenerations shouldBe listOf(oldGeneration)
		finishCleanup.complete(Unit)
		runCurrent()
		deletion.await()
		cleanupFinished shouldBe true
		admittedGenerations shouldBe listOf(oldGeneration)
		deletionBarrier.reopen()
		gate.reconcile() shouldBe TrackingStartupResult.Ready(false, 0L)
		runCurrent()
		// The queued hint reads only the new generation; cancellation did not destroy its actor.
		admittedGenerations shouldBe listOf(oldGeneration, oldGeneration + 1L)
		gate.withReadyGenerationTransfer(oldGeneration) { "old-write" } shouldBe null
		coVerify(exactly = 2) { projections.drainAmbientRadioProjection(SourceKind.WIFI, any()) }
	}

	private fun prepareRetainedGate() {
		every { resolver.resolveAndPrepare() } returns ApplicationStartupRecoveryAction.None
		coEvery { storage.ensureReady(false) } returns LegacyDatabaseStartupResult.Ready
		coEvery { resolver.apply(any(), any()) } just Runs
		coEvery { sourceRecovery.recoverStartupAuthority() } returns completed()
	}

	@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
	private fun actualRadioRecovery(projections: ProjectionDispatcher, scope: CoroutineScope) = SourcePipelineRecovery(
		legacyRecovery = mockk(), coordinator = mockk(), activityProjectionLane = mockk(), activityEffects = mockk(),
		stepsProjectionLane = mockk(relaxed = true), pressureProjectionLane = mockk(relaxed = true),
		capturedActivityProjectionLane = mockk(relaxed = true), cellProjectionLane = mockk(relaxed = true),
		wifiProjectionLane = mockk(relaxed = true), applicationScope = scope,
		ambientProjections = projections, ambientIngress = mockk(), startupGateProvider = Provider { gate },
	)

	private fun pendingStop() = TrackingStopCommand(
		generation = 7L,
		reason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
		requestedAtEpochMs = 1_234L,
	)
}
