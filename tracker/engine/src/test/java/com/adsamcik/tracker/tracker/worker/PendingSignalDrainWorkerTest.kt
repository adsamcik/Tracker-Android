package com.adsamcik.tracker.tracker.worker

import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PendingSignalDrainWorkerTest {
	@Test
	fun `Room drain waits for full startup Ready`() = runTest {
		var drainCount = 0

		runPendingSignalDrain(
			expectedGeneration = 4L,
			currentGeneration = { 4L },
			isReady = { false },
			reconcileStartup = {
				TrackingStartupResult.RetryableFailure(
					TrackingStartupStage.LEGACY_V27,
					"NOT_TERMINAL",
				)
			},
			drain = { drainCount++; true },
		) shouldBe PendingSignalDrainWorkOutcome.RETRY

		drainCount shouldBe 0
	}

	@Test
	fun `superseded startup generation is a terminal no-op`() = runTest {
		var reconcileCount = 0
		var drainCount = 0

		runPendingSignalDrain(
			expectedGeneration = 4L,
			currentGeneration = { 5L },
			isReady = { true },
			reconcileStartup = {
				reconcileCount++
				TrackingStartupResult.Ready(false, 0L)
			},
			drain = { drainCount++; true },
		) shouldBe PendingSignalDrainWorkOutcome.COMPLETE

		reconcileCount shouldBe 0
		drainCount shouldBe 0
	}

	@Test
	fun `exact Ready generation drains once`() = runTest {
		var drainCount = 0

		runPendingSignalDrain(
			expectedGeneration = 4L,
			currentGeneration = { 4L },
			isReady = { true },
			reconcileStartup = { TrackingStartupResult.Ready(false, 12L) },
			drain = { verifyCollectedDataAccess ->
				verifyCollectedDataAccess()
				drainCount++
				true
			},
		) shouldBe PendingSignalDrainWorkOutcome.COMPLETE

		drainCount shouldBe 1
	}

	@Test
	fun `generation superseded during drain never requests redelivery`() = runTest {
		var generation = 4L

		runPendingSignalDrain(
			expectedGeneration = 4L,
			currentGeneration = { generation },
			isReady = { true },
			reconcileStartup = { TrackingStartupResult.Ready(false, 12L) },
			drain = { verifyCollectedDataAccess ->
				verifyCollectedDataAccess()
				generation = 5L
				verifyCollectedDataAccess()
				false
			},
		) shouldBe PendingSignalDrainWorkOutcome.COMPLETE
	}

	@Test
	fun `permanent startup block finishes without resolving the drain`() = runTest {
		var drainCount = 0

		runPendingSignalDrain(
			expectedGeneration = 4L,
			currentGeneration = { 4L },
			isReady = { false },
			reconcileStartup = {
				TrackingStartupResult.Blocked(
					TrackingStartupStage.LEGACY_V27,
					"UNSUPPORTED_RELEASED_WRITER",
				)
			},
			drain = { drainCount++; true },
		) shouldBe PendingSignalDrainWorkOutcome.COMPLETE

		drainCount shouldBe 0
	}
}
