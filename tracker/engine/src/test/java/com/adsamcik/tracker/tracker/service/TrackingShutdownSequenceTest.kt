package com.adsamcik.tracker.tracker.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingShutdownSequenceTest {
	@Test
	fun `drain timeout cancels queued cycles and still runs teardown`() = runTest {
		val calls = mutableListOf<String>()

		val result = drainCyclesThenShutdown(
			drainTimeoutMillis = 1L,
			shutdownTimeoutMillis = 1L,
			drain = { awaitCancellation() },
			cancelPendingCycles = { calls += "cancel" },
			shutdown = {
				calls += "shutdown"
				"complete"
			},
		)

		result.drained shouldBe false
		result.drainFailure shouldBe null
		result.cycleCancellationFailure shouldBe null
		result.shutdownResult shouldBe "complete"
		result.shutdownFailure shouldBe null
		calls shouldContainExactly listOf("cancel", "shutdown")
	}

	@Test
	fun `drain failure cancels queued cycles and still runs teardown`() = runTest {
		val failure = IOException("drain failed")
		val calls = mutableListOf<String>()

		val result = drainCyclesThenShutdown(
			drain = { throw failure },
			cancelPendingCycles = { calls += "cancel" },
			shutdown = {
				calls += "shutdown"
				"complete"
			},
		)

		result.drained shouldBe false
		result.drainFailure shouldBe failure
		result.cycleCancellationFailure shouldBe null
		result.shutdownResult shouldBe "complete"
		result.shutdownFailure shouldBe null
		calls shouldContainExactly listOf("cancel", "shutdown")
	}

	@Test
	fun `shutdown failure is reported without pretending teardown completed`() = runTest {
		val failure = IOException("shutdown failed")

		val result = drainCyclesThenShutdown(
			drain = {},
			cancelPendingCycles = {},
			shutdown = { throw failure },
		)

		result.drained shouldBe true
		result.cycleCancellationFailure shouldBe null
		result.shutdownResult shouldBe null
		result.shutdownFailure shouldBe failure
	}

	@Test
	fun `cycle cancellation timeout defers teardown until cycles quiesce`() = runTest {
		val calls = mutableListOf<String>()

		val result = drainCyclesThenShutdown(
			cancelTimeoutMillis = 1L,
			drain = {},
			cancelPendingCycles = { awaitCancellation() },
			shutdown = {
				calls += "shutdown"
				"complete"
			},
		)

		(result.cycleCancellationFailure is IllegalStateException) shouldBe true
		result.shutdownResult shouldBe null
		calls shouldBe emptyList()
	}

	@Test
	fun `shutdown timeout is reported explicitly`() = runTest {
		val result = drainCyclesThenShutdown(
			shutdownTimeoutMillis = 1L,
			drain = {},
			cancelPendingCycles = {},
			shutdown = { awaitCancellation() },
		)

		result.shutdownResult shouldBe null
		(result.shutdownFailure is IllegalStateException) shouldBe true
	}

	@Test
	fun `bounded shutdown retry keeps trying until cleanup succeeds`() = runTest {
		var attempts = 0

		val result = retryTrackingShutdown(
			maxAttempts = 4,
			retryDelayMillis = 0L,
		) {
			attempts++
			if (attempts < 4) throw TrackingShutdownRetryException("COORDINATOR_BUSY")
			"complete"
		}

		result shouldBe "complete"
		attempts shouldBe 4
	}

	@Test
	fun `bounded shutdown retry does not retry programmer failures`() = runTest {
		var attempts = 0

		shouldThrow<IllegalStateException> {
			retryTrackingShutdown(
				maxAttempts = 3,
				retryDelayMillis = 0L,
			) {
				attempts++
					error("permanent failure")
			}
		}

		attempts shouldBe 1
	}

	@Test
	fun `bounded shutdown retry times out each stalled cleanup attempt`() = runTest {
		var attempts = 0

		shouldThrow<IllegalStateException> {
			retryTrackingShutdown(
				maxAttempts = 2,
				retryDelayMillis = 0L,
				attemptTimeoutMillis = 1L,
			) {
				attempts++
				awaitCancellation()
			}
		}

		attempts shouldBe 2
	}

	@Test
	fun `replacement initialization waits for prior teardown`() = runTest {
		val barrier = TrackingLifecycleBarrier()
		val teardownMayFinish = CompletableDeferred<Unit>()
		val order = mutableListOf<String>()
		val teardown = launch {
			barrier.runAfter(previousTeardown = null) {
				order += "teardown-start"
				teardownMayFinish.await()
				order += "teardown-end"
			}
		}
		runCurrent()

		val replacement = launch {
			barrier.runAfter(previousTeardown = teardown) {
				order += "replacement-start"
			}
		}
		runCurrent()
		order shouldContainExactly listOf("teardown-start")

		teardownMayFinish.complete(Unit)
		advanceUntilIdle()

		replacement.isCompleted shouldBe true
		order shouldContainExactly listOf("teardown-start", "teardown-end", "replacement-start")
	}

	@Test
	fun `replacement initialization can recover after teardown failure`() = runTest {
		val barrier = TrackingLifecycleBarrier()
		val teardown = CompletableDeferred<Unit>().apply {
			completeExceptionally(IllegalStateException("teardown failed"))
		}
		var replacementStarted = false

		barrier.runAfter(teardown) {
			replacementStarted = true
		}

		replacementStarted shouldBe true
	}

	@Test
	fun `replacement initialization times out while teardown continues`() = runTest {
		val barrier = TrackingLifecycleBarrier()
		val teardown = CompletableDeferred<Unit>()

		shouldThrow<TimeoutCancellationException> {
			barrier.runAfter(
				previousTeardown = teardown,
				waitTimeoutMillis = 1L,
			) {
				error("replacement must not start")
			}
		}

		teardown.isActive shouldBe true
	}

	@Test
	fun `shutdown retry preserves failure until a later attempt succeeds`() = runTest {
		var attempts = 0

		val result = retryTrackingShutdown(
			maxAttempts = 3,
			retryDelayMillis = 0L,
		) {
			attempts++
			if (attempts < 3) throw TrackingShutdownRetryException("DRAIN_PENDING")
			"complete"
		}

		result shouldBe "complete"
		attempts shouldBe 3
	}

	@Test
	fun `cancellation propagates from every shutdown boundary`() = runTest {
		shouldThrow<CancellationException> {
			drainCyclesThenShutdown(
				drain = { throw CancellationException("cancel drain") },
				cancelPendingCycles = {},
				shutdown = { "unreachable" },
			)
		}
		shouldThrow<CancellationException> {
			drainCyclesThenShutdown(
				drain = {},
				cancelPendingCycles = { throw CancellationException("cancel pending") },
				shutdown = { "unreachable" },
			)
		}
		shouldThrow<CancellationException> {
			drainCyclesThenShutdown(
				drain = {},
				cancelPendingCycles = {},
				shutdown = { throw CancellationException("cancel shutdown") },
			)
		}
		shouldThrow<CancellationException> {
			retryTrackingShutdown(retryDelayMillis = 0L) {
				throw CancellationException("cancel retry")
			}
		}
	}

	@Test
	fun `programmer errors propagate instead of becoming degraded shutdown`() = runTest {
		val invariant = IllegalStateException("broken invariant")

		shouldThrow<IllegalStateException> {
			drainCyclesThenShutdown(
				drain = { throw invariant },
				cancelPendingCycles = {},
				shutdown = { "unreachable" },
			)
		}
	}
}
