package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartContext
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTrackingStartRequestCoordinatorBudgetTest {
	@Test
	fun `PREPARE cancellation cleanup stops at absolute callback cleanup deadline`() = runTest {
		var cleanupStarted = false
		var cleanupCompleted: Boolean? = null
		val job = launch {
			try {
				awaitCancellation()
			} catch (cancelled: CancellationException) {
				cleanupCompleted = runBoundedStartPreparationCancellationCleanup(
					automaticTrigger = trigger,
					elapsedRealtimeNanos = { testScheduler.currentTime * 1_000_000L },
				) {
					cleanupStarted = true
					awaitCancellation()
				}
				throw cancelled
			}
		}
		runCurrent()
		testScheduler.advanceTimeBy(7_500L)

		job.cancel()
		advanceUntilIdle()

		cleanupStarted shouldBe true
		cleanupCompleted shouldBe false
		testScheduler.currentTime shouldBe 7_750L
		job.isCancelled shouldBe true
	}

	@Test
	fun `exhausted callback cleanup budget leaves PREPARED state untouched`() = runTest {
		var cleanupInvoked = false

		runBoundedStartPreparationCancellationCleanup(
			automaticTrigger = trigger,
			elapsedRealtimeNanos = { 7_750_000_000L },
		) {
			cleanupInvoked = true
		} shouldBe false

		cleanupInvoked shouldBe false
	}

	private companion object {
		val trigger = AutomaticTrackingStartTrigger(
			triggerId = "activity-transition:boot-1:1",
			kind = "ACTIVITY_TRANSITION:WALKING:ENTER",
			bootId = "boot-1",
			observedElapsedRealtimeNanos = 0L,
			receivedElapsedRealtimeNanos = 0L,
			expiresElapsedRealtimeNanos = 60_000_000_000L,
			automationEpoch = 1L,
			startContext = AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK,
			sourcePolicyRevision = 1L,
			intendedCaptureSourceMask = 1L,
			requestedCaptureSourceMask = 1L,
			intendedForegroundServiceTypeMask = 0L,
			collectedDataEpoch = 1L,
		)
	}
}
