package com.adsamcik.tracker.tracker.service

import android.location.Location
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class TrackingCycleDispatcherTest {

	@Test
	fun `bounded dispatcher preserves all location fixes in order when merging queued cycles`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val firstCycleMayFinish = CompletableDeferred<Unit>()
		val processed = mutableListOf<TrackingCycle>()
		var isFirstCycle = true
		val subject = TrackingCycleDispatcher(
			scope = this,
			dispatcher = dispatcher,
			capacity = 2,
			processCycle = { cycle ->
				processed += cycle
				if (isFirstCycle) {
					isFirstCycle = false
					firstCycleMayFinish.await()
				}
			},
		)

		val firstJob = subject.enqueue(cycleWithFixes(1L))
		runCurrent()

		val secondJob = subject.enqueue(cycleWithFixes(2L, 3L))
		val thirdJob = subject.enqueue(cycleWithFixes(4L))
		val fourthJob = subject.enqueue(cycleWithFixes(5L))

		subject.pendingCycleCount shouldBe 2
		subject.maxBufferedCyclesObserved shouldBe 2

		firstCycleMayFinish.complete(Unit)
		advanceUntilIdle()

		listOf(firstJob, secondJob, thirdJob, fourthJob).forEach { it.isCompleted shouldBe true }
		val processedFixTimes = processed.map { cycle -> cycle.location?.lastLocation?.time }
		processedFixTimes shouldContainExactly listOf(1L, 2L, 3L, 4L, 5L)
		subject.cancel()
	}

	@Test
	fun `processing failures do not terminate the worker`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val expectedFailure = IllegalStateException("cycle failed")
		val processedTimes = mutableListOf<Long>()
		val subject = TrackingCycleDispatcher(
			scope = this,
			dispatcher = dispatcher,
			capacity = 2,
			processCycle = { cycle ->
				if (cycle.timestampMs == 1L) throw expectedFailure
				processedTimes += cycle.timestampMs
			},
		)

		try {
			subject.enqueue(cycleWithFixes(1L))
			subject.enqueue(cycleWithFixes(2L))
			advanceUntilIdle()

			processedTimes shouldContainExactly listOf(2L)
		} finally {
			subject.cancel()
		}
	}

	@Test
	fun `cancelAndJoin waits for in-flight processing to terminate`() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val processingStarted = CompletableDeferred<Unit>()
		val subject = TrackingCycleDispatcher(
			scope = this,
			dispatcher = dispatcher,
			capacity = 1,
			processCycle = {
				processingStarted.complete(Unit)
				awaitCancellation()
			},
		)
		val completion = subject.enqueue(cycleWithFixes(1L))
		runCurrent()
		processingStarted.await()

		val cancellation = launch { subject.cancelAndJoin() }
		advanceUntilIdle()

		cancellation.isCompleted shouldBe true
		completion.isCancelled shouldBe true
	}

	private fun cycleWithFixes(vararg times: Long): TrackingCycle {
		val locations = times.map { time ->
			Location("gps").apply {
				this.time = time
				elapsedRealtimeNanos = time * 1_000_000L
				latitude = 50.0 + time / 10_000.0
				longitude = 14.0 + time / 10_000.0
				accuracy = 5f
			}
		}
		return TrackingCycle(
			timestampMs = times.last(),
			elapsedRealtimeNanos = times.last() * 1_000_000L,
			location = LocationData(
				locations = locations,
				previousLocation = null,
				distance = null,
			),
		)
	}
}
