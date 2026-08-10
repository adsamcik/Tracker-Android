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
		val baseline = StepBaseline(100L, 1_000L, 5L)
		val restored = decodeStepBaseline(baseline.encode(), STEP_BASELINE_VERSION)
		val accumulator = StepWindowAccumulator(restored)

		val normal = accumulator.accept("boot:1", 108L, 2_000L, 6L)
		val reset = accumulator.accept("boot:1", 3L, 3_000L, 7L)

		assertEquals(8L, normal.deltaCount)
		assertFalse(normal.baselineReset)
		assertEquals(3L, reset.deltaCount)
		assertTrue(reset.baselineReset)
	}
}
