package com.adsamcik.tracker.tracker.source.runtime

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PressureWindowAccumulatorTest {
	@Test
	fun `uses stable Welford aggregation and rolls windows by event time`() {
		val accumulator = PressureWindowAccumulator(windowNanos = 1_000L)

		assertNull(accumulator.add(1_000f, 100L, 1L))
		assertNull(accumulator.add(1_002f, 500L, 2L))
		val completed = assertNotNull(accumulator.add(1_010f, 1_100L, 3L))

		assertEquals(2, completed.sampleCount)
		assertEquals(1_001.0, completed.meanHectopascals)
		assertEquals(2.0, completed.sumSquaredDeviations)
		assertEquals(1L, completed.firstProviderSequence)
		assertEquals(2L, completed.lastProviderSequence)
		assertEquals(1_010.0, assertNotNull(accumulator.drain()).meanHectopascals)
	}

	@Test
	fun `partial window round trips across process recreation`() {
		val accumulator = PressureWindowAccumulator(windowNanos = 10_000L)
		accumulator.add(999.5f, 100L, 11L)
		accumulator.add(1_000.5f, 200L, 12L)
		val encoded = assertNotNull(accumulator.snapshot()).encode()
		val restored = assertNotNull(decodePressureAccumulator(encoded, PRESSURE_ACCUMULATOR_VERSION))

		assertEquals(2, restored.sampleCount)
		assertTrue(abs(restored.mean - 1_000.0) < 0.0001)
		assertEquals(11L, restored.firstProviderSequence)
		assertEquals(12L, restored.lastProviderSequence)
	}
}
