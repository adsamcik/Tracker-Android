package com.adsamcik.tracker.tracker.resilience

import android.app.ApplicationExitInfo
import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PreviousExitRecoveryCoordinatorTest {
	@Test
	fun `abnormal exits enqueue an expedited WAL drain`() = runTest {
		val store = RecordingStore()
		val scheduler = RecordingScheduler()
		val coordinator = PreviousExitRecoveryCoordinator(store, scheduler)

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
	}

	@Test
	fun `user requested exit clears descriptor and suppresses restart`() = runTest {
		val store = RecordingStore()
		val scheduler = RecordingScheduler()
		val coordinator = PreviousExitRecoveryCoordinator(store, scheduler)

		coordinator.handle(ApplicationExitInfo.REASON_USER_REQUESTED) shouldBe
			PreviousExitRecoveryAction.SUPPRESS_RESTART

		store.clearCount shouldBe 1
		scheduler.enqueueCount shouldBe 0
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

