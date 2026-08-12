package com.adsamcik.tracker.tracker.resilience

import android.app.ApplicationExitInfo
import com.adsamcik.tracker.stats.api.PolicyTier
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
		coEvery { finalizer.finalize(any()) } returns
			ForceStopSourceSessionFinalization.NO_ACTIVE_SESSION
		val coordinator = PreviousExitRecoveryCoordinator(store, scheduler, finalizer)

		listOf(
			ApplicationExitInfo.REASON_LOW_MEMORY,
			ApplicationExitInfo.REASON_SIGNALED,
			ApplicationExitInfo.REASON_CRASH,
			ApplicationExitInfo.REASON_CRASH_NATIVE,
			ApplicationExitInfo.REASON_ANR,
		).forEach { reason ->
			coordinator.handle(reason) shouldBe PreviousExitRecoveryAction.ENQUEUE_WAL_DRAIN
		}

		scheduler.enqueueCount shouldBe 5
		store.clearCount shouldBe 0
		coVerify(exactly = 0) { finalizer.finalize(any()) }
	}

	@Test
	fun `user requested exit is ambiguous and does not suppress recovery`() = runTest {
		val store = RecordingStore()
		val scheduler = RecordingScheduler()
		val finalizer = mockk<ForceStopSourceSessionFinalizer>(relaxed = true)
		coEvery { finalizer.finalize(any()) } returns
			ForceStopSourceSessionFinalization.NO_ACTIVE_SESSION
		val coordinator = PreviousExitRecoveryCoordinator(store, scheduler, finalizer)

		coordinator.handle(ApplicationExitInfo.REASON_USER_REQUESTED) shouldBe
			PreviousExitRecoveryAction.NONE

		store.clearCount shouldBe 0
		scheduler.enqueueCount shouldBe 0
		coVerify(exactly = 0) { finalizer.finalize(any()) }
	}

	@Test
	fun `WAL drain request is expedited with safe quota fallback`() {
		buildPendingSignalDrainRequest().workSpec.expedited shouldBe true
	}

	private class RecordingScheduler : PendingSignalDrainScheduler {
		var enqueueCount = 0
		override fun enqueueExpedited() {
			enqueueCount++
		}
	}

	private class RecordingStore : ActiveTrackingSessionStore {
		var clearCount = 0
		private var descriptor: ActiveTrackingSessionDescriptor? = ActiveTrackingSessionDescriptor(
			isUserInitiated = true,
			isAmbient = false,
			policyTier = PolicyTier.PRECISION,
		)

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
}
