package com.adsamcik.tracker.tracker.source.runtime

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PressureWindowAccumulatorTest {
	@Test
	fun `atomic fact checkpoint never encodes the future unadmitted partial`() {
		val boundary = PressureAccumulatorBoundary(
			registrationGeneration = 7L,
			eligibilityFingerprint = "authorization-7",
			appliedRevision = 11L,
			authorizationRevision = 13L,
		)
		val accumulator = PressureWindowAccumulator(windowNanos = 1_000L, boundary = boundary)
		accumulator.add(1_003f, elapsedNanos = 400L, providerSequence = 9L)
		val completed = assertNotNull(accumulator.add(1_004f, elapsedNanos = 1_500L, providerSequence = 10L))
		val futurePartial = assertNotNull(accumulator.snapshot())
		val checkpoint = pressureAtomicRuntimeCheckpoint(
			RuntimeAdmissionSnapshot(null, null, 0L, null, null, emptySet()),
			causalOrderElapsedRealtimeNanos = 500L,
		)

		assertEquals(9L, completed.lastProviderSequence)
		assertEquals(10L, futurePartial.firstProviderSequence)
		assertTrue(checkpoint.componentPayload.isEmpty())
		assertEquals(500L, checkpoint.causalOrderElapsedRealtimeNanos)
		assertEquals(987L, pressureCheckpointOrderMillis(987_654_321L))
	}

	@Test
	fun `pressure admission rejects missing future duplicate and reordered provider time`() {
		assertFalse(isFreshPressureSampleTimestamp(0L, 100L, null))
		assertFalse(isFreshPressureSampleTimestamp(101L, 100L, null))
		assertFalse(isFreshPressureSampleTimestamp(90L, 100L, 90L))
		assertFalse(isFreshPressureSampleTimestamp(89L, 100L, 90L))
		assertTrue(isFreshPressureSampleTimestamp(91L, 100L, 90L))
	}

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
		assertNull(accumulator.add(1_012f, 1_200L, 4L))
		assertEquals(1_011.0, assertNotNull(accumulator.drain()).meanHectopascals)
	}

	@Test
	fun `thousands of samples produce exact window proportional work`() {
		val accumulator = PressureWindowAccumulator(windowNanos = 100L)
		val windows = buildList {
			repeat(10_000) { index ->
				accumulator.add(1_000f + index % 100, index.toLong(), index + 1L)?.let(::add)
			}
			accumulator.drain()?.let(::add)
		}

		// The runtime performs exactly one admission and checkpoint for each item produced here.
		assertEquals(100, windows.size)
		windows.forEachIndexed { index, window ->
			assertEquals(100, window.sampleCount)
			assertEquals(1_049.5, window.meanHectopascals, 0.000_001)
			assertEquals(83_325.0, window.sumSquaredDeviations, 0.000_001)
			assertEquals(1_000f, window.minimumHectopascals)
			assertEquals(1_099f, window.maximumHectopascals)
			assertEquals(index * 100L + 1L, window.firstProviderSequence)
			assertEquals((index + 1L) * 100L, window.lastProviderSequence)
		}
	}

	@Test
	fun `fresh generation and authorization use a fresh accumulator`() {
		val old = PressureWindowAccumulator(
			windowNanos = 1_000L,
			boundary = PressureAccumulatorBoundary(7L, "authorization-a", 11L),
		)
		old.add(999f, 100L, 1L)
		old.add(1_001f, 200L, 2L)

		val replacement = PressureWindowAccumulator(
			windowNanos = 1_000L,
			boundary = PressureAccumulatorBoundary(8L, "authorization-b", 12L),
		)
		assertNull(replacement.add(1_010f, 300L, 1L))
		val terminal = assertNotNull(replacement.drain())

		assertEquals(1, terminal.sampleCount)
		assertEquals(1_010.0, terminal.meanHectopascals)
		assertEquals(1L, terminal.firstProviderSequence)
	}

	@Test
	fun `compatible authorization refresh closes old partial before starting new policy window`() {
		val old = PressureWindowAccumulator(
			windowNanos = 5_000L,
			boundary = PressureAccumulatorBoundary(9L, "authorization-1", 1L),
		)
		old.add(1_000f, 100L, 41L)
		old.add(1_002f, 200L, 42L)
		val oldReception = PressureReception(200L, 250L, 1_000L, 42L)

		val boundary = assertNotNull(drainPressureWindowAtBoundary(old, oldReception))
		val refreshed = PressureWindowAccumulator(
			windowNanos = 1_000L,
			boundary = PressureAccumulatorBoundary(9L, "authorization-2", 2L),
		)
		assertNull(refreshed.add(1_010f, 300L, 43L))
		val refreshedWindow = assertNotNull(refreshed.drain())

		assertEquals(2, boundary.payload.sampleCount)
		assertEquals(1_001.0, boundary.payload.meanHectopascals)
		assertEquals(41L, boundary.payload.firstProviderSequence)
		assertEquals(42L, boundary.payload.lastProviderSequence)
		assertEquals(oldReception, boundary.reception)
		assertEquals(1, refreshedWindow.sampleCount)
		assertEquals(43L, refreshedWindow.firstProviderSequence)
		assertNull(old.drain())
	}

	@Test
	fun `authorization boundary refuses to relabel accumulator without reception metadata`() {
		val accumulator = PressureWindowAccumulator(windowNanos = 5_000L)
		accumulator.add(1_000f, 100L, 1L)

		assertFailsWith<IllegalStateException> {
			drainPressureWindowAtBoundary(accumulator, lastReception = null)
		}
		assertEquals(1, assertNotNull(accumulator.drain()).sampleCount)
	}

	@Test
	fun `quiesce drain returns the exact final partial window`() {
		val accumulator = PressureWindowAccumulator(windowNanos = 1_000L)

		assertNull(accumulator.add(1_000f, 100L, 1L))
		assertNull(accumulator.add(1_002f, 200L, 2L))
		val partial = assertNotNull(accumulator.drain())

		assertEquals(2, partial.sampleCount)
		assertEquals(1_001.0, partial.meanHectopascals)
		assertEquals(2.0, partial.sumSquaredDeviations)
		assertEquals(1L, partial.firstProviderSequence)
		assertEquals(2L, partial.lastProviderSequence)
		assertNull(accumulator.drain())
	}
}
