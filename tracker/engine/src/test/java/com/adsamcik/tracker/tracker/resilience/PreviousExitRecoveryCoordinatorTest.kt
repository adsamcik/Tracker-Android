package com.adsamcik.tracker.tracker.resilience

import android.app.ApplicationExitInfo
import com.adsamcik.tracker.shared.base.database.dao.PriorProcessRegistrationReconciliationResult
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.source.runtime.SourceRegistrationRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PreviousExitRecoveryCoordinatorTest {
	@Test
	fun `abnormal exits enqueue an expedited WAL drain`() = runTest {
		val store = RecordingStore()
		val scheduler = RecordingScheduler()
		val finalizer = mockk<ForceStopSourceSessionFinalizer>(relaxed = true)
		val previousExitFinalizer = mockk<PreviousExitSourceSessionFinalizer>()
		coEvery { finalizer.finalize(any()) } returns
			ForceStopSourceSessionFinalization.NO_ACTIVE_SESSION
		coEvery { previousExitFinalizer.finalizeStaleSessions(any(), any()) } returns
			PreviousExitSourceSessionFinalization(emptySet())
		val registrationRepository = registrationRepository()
		val coordinator = PreviousExitRecoveryCoordinator(
			store,
			scheduler,
			finalizer,
			previousExitFinalizer,
			registrationRepository,
		)

		listOf(
			ApplicationExitInfo.REASON_LOW_MEMORY,
			ApplicationExitInfo.REASON_SIGNALED,
			ApplicationExitInfo.REASON_CRASH,
			ApplicationExitInfo.REASON_CRASH_NATIVE,
			ApplicationExitInfo.REASON_ANR,
		).forEach { reason ->
			coordinator.handle(
				reason = reason,
				completedAtMs = 1_234L,
				startupGeneration = 7L,
			) shouldBe
				PreviousExitRecoveryAction.ENQUEUE_WAL_DRAIN
		}

		scheduler.enqueueCount shouldBe 5
		scheduler.generations shouldBe List(5) { 7L }
		store.clearCount shouldBe 0
		coVerify(exactly = 0) { finalizer.finalize(any()) }
		coVerify(exactly = 5) {
			previousExitFinalizer.finalizeStaleSessions(
				1_234L,
				match { descriptor -> descriptor.logicalTrackingId == "default-manual" },
			)
		}
		coVerify(exactly = 5) {
			registrationRepository.reconcilePriorProcessRegistrations(any(), any())
		}
	}

	@Test
	fun `user requested exit is ambiguous and does not suppress recovery`() = runTest {
		val store = RecordingStore()
		val scheduler = RecordingScheduler()
		val finalizer = mockk<ForceStopSourceSessionFinalizer>(relaxed = true)
		val previousExitFinalizer = mockk<PreviousExitSourceSessionFinalizer>()
		coEvery { finalizer.finalize(any()) } returns
			ForceStopSourceSessionFinalization.NO_ACTIVE_SESSION
		coEvery { previousExitFinalizer.finalizeStaleSessions(any(), any()) } returns
			PreviousExitSourceSessionFinalization(emptySet())
		val coordinator = PreviousExitRecoveryCoordinator(
			store,
			scheduler,
			finalizer,
			previousExitFinalizer,
			registrationRepository(),
		)

		coordinator.handle(
			ApplicationExitInfo.REASON_USER_REQUESTED,
			completedAtMs = 2_345L,
			startupGeneration = 7L,
		) shouldBe
			PreviousExitRecoveryAction.NONE

		store.clearCount shouldBe 0
		scheduler.enqueueCount shouldBe 0
		coVerify(exactly = 0) { finalizer.finalize(any()) }
		coVerify(exactly = 1) {
			previousExitFinalizer.finalizeStaleSessions(
				2_345L,
				match { descriptor -> descriptor.logicalTrackingId == "default-manual" },
			)
		}
	}

	@Test
	fun `suppressed WAL recovery still reconciles stale sessions and prior process registrations`() = runTest {
		val store = RecordingStore()
		val scheduler = RecordingScheduler()
		val finalizer = mockk<ForceStopSourceSessionFinalizer>(relaxed = true)
		val previousExitFinalizer = mockk<PreviousExitSourceSessionFinalizer>()
		coEvery { previousExitFinalizer.finalizeStaleSessions(any(), any()) } returns
			PreviousExitSourceSessionFinalization(emptySet())
		val registrationRepository = registrationRepository()
		val coordinator = PreviousExitRecoveryCoordinator(
			store,
			scheduler,
			finalizer,
			previousExitFinalizer,
			registrationRepository,
		)

		coordinator.handle(
			reason = ApplicationExitInfo.REASON_USER_REQUESTED,
			completedAtMs = 2_456L,
			startupGeneration = 11L,
		) shouldBe
			PreviousExitRecoveryAction.NONE
		scheduler.enqueueCount shouldBe 0
		coVerify(exactly = 1) {
			previousExitFinalizer.finalizeStaleSessions(2_456L, any())
		}
		coVerify(exactly = 1) {
			registrationRepository.reconcilePriorProcessRegistrations(any(), any())
		}
	}

	@Test
	fun `WAL drain request is expedited with safe quota fallback`() {
		val request = buildPendingSignalDrainRequest(startupGeneration = 9L)
		request.workSpec.expedited shouldBe true
		request.workSpec.input.getLong(
			com.adsamcik.tracker.tracker.worker.PendingSignalDrainWorker.STARTUP_GENERATION_KEY,
			-1L,
		) shouldBe 9L
	}

	@Test
	fun `session authority and dead process providers are reconciled before WAL recovery`() = runTest {
		val events = mutableListOf<String>()
		val previousExitFinalizer = mockk<PreviousExitSourceSessionFinalizer>()
		coEvery { previousExitFinalizer.finalizeStaleSessions(any(), any()) } coAnswers {
			events += "sessions"
			PreviousExitSourceSessionFinalization(emptySet())
		}
		val registrationRepository = registrationRepository()
		coEvery {
			registrationRepository.reconcilePriorProcessRegistrations(any(), any())
		} coAnswers {
			events += "providers"
			PriorProcessRegistrationReconciliationResult(0, 0, 0)
		}
		val coordinator = PreviousExitRecoveryCoordinator(
			RecordingStore(initialDescriptor = null),
			RecordingScheduler { events += "wal" },
			mockk(relaxed = true),
			previousExitFinalizer,
			registrationRepository,
		)

		coordinator.handle(
			ApplicationExitInfo.REASON_CRASH,
			completedAtMs = 3_000L,
			startupGeneration = 8L,
		)

		events shouldBe listOf("sessions", "providers", "wal")
	}

	@Test
	fun `provider reconciliation failure keeps WAL recovery closed for retry`() = runTest {
		val scheduler = RecordingScheduler()
		val registrationRepository = registrationRepository()
		coEvery {
			registrationRepository.reconcilePriorProcessRegistrations(any(), any())
		} throws IllegalStateException("provider reconciliation failed")
		val previousExitFinalizer = mockk<PreviousExitSourceSessionFinalizer>()
		coEvery { previousExitFinalizer.finalizeStaleSessions(any(), any()) } returns
			PreviousExitSourceSessionFinalization(emptySet())
		val coordinator = PreviousExitRecoveryCoordinator(
			RecordingStore(initialDescriptor = null),
			scheduler,
			mockk(relaxed = true),
			previousExitFinalizer,
			registrationRepository,
		)

		shouldThrow<IllegalStateException> {
			coordinator.handle(
				ApplicationExitInfo.REASON_CRASH,
				completedAtMs = 3_000L,
				startupGeneration = 8L,
			)
		}

		scheduler.enqueueCount shouldBe 0
	}

	@Test
	fun `stale finalization clears only the exact finalized descriptor`() = runTest {
		val stale = descriptor(isUserInitiated = false, logicalTrackingId = "stale-auto")
		val store = RecordingStore(stale)
		val previousExitFinalizer = mockk<PreviousExitSourceSessionFinalizer>()
		coEvery { previousExitFinalizer.finalizeStaleSessions(null, stale) } returns
			PreviousExitSourceSessionFinalization(
				finalizedLogicalTrackingIds = setOf("stale-auto"),
				inspectedLogicalTrackingId = "stale-auto",
				inspectedSessionExists = true,
			)
		val coordinator = PreviousExitRecoveryCoordinator(
			store,
			RecordingScheduler(),
			mockk(relaxed = true),
			previousExitFinalizer,
			registrationRepository(),
		)

		coordinator.reconcileStaleSessions()

		store.clearCount shouldBe 1
		store.currentDescriptor shouldBe null
	}

	@Test
	fun `stale finalization preserves same boot restart eligible manual descriptor`() = runTest {
		val manual = descriptor(isUserInitiated = true, logicalTrackingId = "manual")
		val store = RecordingStore(manual)
		val previousExitFinalizer = mockk<PreviousExitSourceSessionFinalizer>()
		coEvery { previousExitFinalizer.finalizeStaleSessions(null, manual) } returns
			PreviousExitSourceSessionFinalization(
				finalizedLogicalTrackingIds = emptySet(),
				inspectedLogicalTrackingId = "manual",
				inspectedSessionExists = true,
			)
		val coordinator = PreviousExitRecoveryCoordinator(
			store,
			RecordingScheduler(),
			mockk(relaxed = true),
			previousExitFinalizer,
			registrationRepository(),
		)

		coordinator.reconcileStaleSessions()

		store.clearCount shouldBe 0
		store.currentDescriptor shouldBe manual
	}

	@Test
	fun `provisional descriptor without any Room session is cleared exactly`() = runTest {
		val orphan = descriptor(isUserInitiated = true, logicalTrackingId = "orphan")
		val store = RecordingStore(orphan)
		val previousExitFinalizer = mockk<PreviousExitSourceSessionFinalizer>()
		coEvery { previousExitFinalizer.finalizeStaleSessions(null, orphan) } returns
			PreviousExitSourceSessionFinalization(
				finalizedLogicalTrackingIds = emptySet(),
				inspectedLogicalTrackingId = "orphan",
				inspectedSessionExists = false,
			)
		val coordinator = PreviousExitRecoveryCoordinator(
			store,
			RecordingScheduler(),
			mockk(relaxed = true),
			previousExitFinalizer,
			registrationRepository(),
		)

		coordinator.reconcileStaleSessions()

		store.clearCount shouldBe 1
		store.currentDescriptor shouldBe null
	}

	private class RecordingScheduler(
		private val onEnqueue: () -> Unit = {},
	) : PendingSignalDrainScheduler {
		var enqueueCount = 0
		val generations = mutableListOf<Long>()
		override fun enqueueExpedited(startupGeneration: Long) {
			onEnqueue()
			enqueueCount++
			generations += startupGeneration
		}
	}

	private class RecordingStore(
		initialDescriptor: ActiveTrackingSessionDescriptor? = descriptor(
			isUserInitiated = true,
			logicalTrackingId = "default-manual",
		),
	) : ActiveTrackingSessionStore {
		var clearCount = 0
		private var descriptor: ActiveTrackingSessionDescriptor? = initialDescriptor
		val currentDescriptor: ActiveTrackingSessionDescriptor? get() = descriptor

		override suspend fun read(): ActiveTrackingSessionStoreResult =
			ActiveTrackingSessionStoreResult.Success(descriptor)

		override suspend fun save(
			descriptor: ActiveTrackingSessionDescriptor,
		): ActiveTrackingSessionStoreResult {
			this.descriptor = descriptor
			return ActiveTrackingSessionStoreResult.Success(descriptor)
		}

		override suspend fun clear(): ActiveTrackingSessionStoreResult {
			clearCount++
			descriptor = null
			return ActiveTrackingSessionStoreResult.Success(null)
		}
	}

	private companion object {
		fun registrationRepository() = mockk<SourceRegistrationRepository> {
			coEvery {
				reconcilePriorProcessRegistrations(any(), any())
			} returns PriorProcessRegistrationReconciliationResult(0, 0, 0)
		}

		fun descriptor(
			isUserInitiated: Boolean,
			logicalTrackingId: String,
		) = ActiveTrackingSessionDescriptor(
			isUserInitiated = isUserInitiated,
			isAmbient = false,
			policyTier = PolicyTier.PRECISION,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = "run:$logicalTrackingId",
			restartBootId = TEST_BOOT_ID,
			restartToken = "restart-token",
		)

		const val TEST_BOOT_ID = "current-boot"
	}
}
