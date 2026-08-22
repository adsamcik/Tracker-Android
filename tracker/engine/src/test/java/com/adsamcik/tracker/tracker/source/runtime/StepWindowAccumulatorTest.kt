package com.adsamcik.tracker.tracker.source.runtime

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StepWindowAccumulatorTest {
	@Test
	fun `first callback establishes a baseline without inventing steps`() {
		val payload = StepWindowAccumulator(null).accept("boot:1", 1_234L, 10L, 1L)

		assertEquals(0L, payload.deltaCount)
		assertEquals(1_234L, payload.firstCumulativeCount)
		assertEquals(1_234L, payload.lastCumulativeCount)
		assertTrue(payload.baselineReset)
	}

	@Test
	fun `persisted same-session baseline survives recreation and detects reset`() {
		val boundary = StepBaselineBoundary(1L, "capture-manifest-1")
		val original = StepWindowAccumulator(null, boundary)
		original.accept("boot:1", 100L, 1_000L, 5L)
		val restored = decodeStepBaseline(
			requireNotNull(original.snapshot()).encode(),
			STEP_BASELINE_VERSION,
			boundary,
		)
		val accumulator = StepWindowAccumulator(restored, boundary)

		val normal = accumulator.accept("boot:1", 108L, 2_000L, 6L)
		val reset = accumulator.accept("boot:1", 3L, 3_000L, 7L)

		assertEquals(8L, normal.deltaCount)
		assertFalse(normal.baselineReset)
		assertEquals(3L, reset.deltaCount)
		assertTrue(reset.baselineReset)
	}

	@Test
	fun `registration generation change discards restored baseline`() {
		val priorBoundary = StepBaselineBoundary(1L, "capture-manifest-1")
		val currentBoundary = StepBaselineBoundary(2L, "capture-manifest-1")
		val prior = StepBaseline(100L, 1_000L, 5L, priorBoundary)

		val restored = decodeStepBaseline(prior.encode(), STEP_BASELINE_VERSION, currentBoundary)
		val first = StepWindowAccumulator(restored, currentBoundary)
			.accept("boot:1", 108L, 2_000L, 1L)

		assertEquals(0L, first.deltaCount)
		assertEquals(108L, first.firstCumulativeCount)
		assertTrue(first.baselineReset)
	}

	@Test
	fun `eligibility fingerprint change discards restored baseline`() {
		val priorBoundary = StepBaselineBoundary(7L, "capture-manifest-1")
		val currentBoundary = StepBaselineBoundary(7L, "capture-manifest-2")
		val prior = StepBaseline(100L, 1_000L, 5L, priorBoundary)

		val restored = decodeStepBaseline(prior.encode(), STEP_BASELINE_VERSION, currentBoundary)
		val first = StepWindowAccumulator(restored, currentBoundary)
			.accept("boot:1", 108L, 2_000L, 1L)

		assertEquals(0L, first.deltaCount)
		assertEquals(108L, first.firstCumulativeCount)
		assertTrue(first.baselineReset)
	}
}
