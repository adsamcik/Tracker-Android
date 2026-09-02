package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
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
		val accumulator = PressureWindowAccumulator(
			windowNanos = 1_000L,
			effectiveSamplePeriodMicros = 1,
			effectiveMaximumReportLatencyMicros = 0,
			boundary = boundary,
		)
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
		val accumulator = PressureWindowAccumulator(1_000L, 1, 0)

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
		val accumulator = PressureWindowAccumulator(100L, 1, 0)
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
			effectiveSamplePeriodMicros = 1,
			effectiveMaximumReportLatencyMicros = 0,
			boundary = PressureAccumulatorBoundary(7L, "authorization-a", 11L),
		)
		old.add(999f, 100L, 1L)
		old.add(1_001f, 200L, 2L)

		val replacement = PressureWindowAccumulator(
			windowNanos = 1_000L,
			effectiveSamplePeriodMicros = 1,
			effectiveMaximumReportLatencyMicros = 0,
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
			effectiveSamplePeriodMicros = 1,
			effectiveMaximumReportLatencyMicros = 0,
			boundary = PressureAccumulatorBoundary(9L, "authorization-1", 1L),
		)
		old.add(1_000f, 100L, 41L)
		old.add(1_002f, 200L, 42L)
		val oldReception = PressureReception(200L, 250L, 1_000L, 42L)

		val boundary = assertNotNull(drainPressureWindowAtBoundary(old, oldReception))
		val refreshed = PressureWindowAccumulator(
			windowNanos = 1_000L,
			effectiveSamplePeriodMicros = 1,
			effectiveMaximumReportLatencyMicros = 0,
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
		val accumulator = PressureWindowAccumulator(5_000L, 1, 0)
		accumulator.add(1_000f, 100L, 1L)

		assertFailsWith<IllegalStateException> {
			drainPressureWindowAtBoundary(accumulator, lastReception = null)
		}
		assertEquals(1, assertNotNull(accumulator.drain()).sampleCount)
	}

	@Test
	fun `quiesce drain returns the exact final partial window`() {
		val accumulator = PressureWindowAccumulator(1_000L, 1, 0)

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

	@Test
	fun `qualified window retains endpoints fit accuracy cadence coverage and trigger ownership`() {
		val oneSecondNanos = 1_000_000_000L
		val accumulator = PressureWindowAccumulator(
			windowNanos = 4L * oneSecondNanos,
			effectiveSamplePeriodMicros = 1_000_000,
			effectiveMaximumReportLatencyMicros = 5_000_000,
		)
		assertNull(accumulator.add(1_000f, oneSecondNanos, 1L, PressureSensorAccuracy.HIGH))
		assertNull(accumulator.add(1_001f, 2L * oneSecondNanos, 2L, PressureSensorAccuracy.MEDIUM))
		assertNull(accumulator.add(1_002f, 3L * oneSecondNanos, 3L, PressureSensorAccuracy.LOW))
		assertNull(accumulator.add(1_003f, 4L * oneSecondNanos, 4L, PressureSensorAccuracy.HIGH))

		val completed = assertNotNull(
			accumulator.add(1_004f, 5L * oneSecondNanos, 5L, PressureSensorAccuracy.HIGH),
		)

		assertEquals(4, completed.sampleCount)
		assertEquals(1_001.5, completed.meanHectopascals)
		assertEquals(5.0, completed.sumSquaredDeviations)
		assertEquals(1_000f, completed.firstHectopascals)
		assertEquals(1_003f, completed.lastHectopascals)
		assertEquals(1.0, requireNotNull(completed.slopeHectopascalsPerSecond), 0.000_000_000_001)
		assertEquals(1.0, requireNotNull(completed.rSquared), 0.000_000_000_001)
		assertEquals(PressureSensorAccuracy.LOW, completed.sensorAccuracy)
		assertEquals(1_000_000, completed.effectiveSamplePeriodMicros)
		assertEquals(5_000_000, completed.effectiveMaximumReportLatencyMicros)
		assertEquals(4L * oneSecondNanos, completed.targetWindowDurationNanos)
		assertEquals(4, completed.expectedSampleCount)
		assertEquals(oneSecondNanos, completed.maximumInterSampleGapNanos)
		assertEquals(PressureWindowClosureKind.TARGET_ELAPSED, completed.closureKind)
		assertEquals(4L, completed.lastProviderSequence)

		val next = assertNotNull(accumulator.drain())
		assertEquals(1, next.sampleCount)
		assertEquals(1_004f, next.firstHectopascals)
		assertEquals(5L, next.firstProviderSequence)
		assertEquals(PressureWindowClosureKind.SOURCE_BOUNDARY, next.closureKind)
	}

	@Test
	fun `fit remains unavailable when undefined and flat pressure does not fabricate r squared`() {
		val flat = PressureWindowAccumulator(2_000_000_000L, 1_000_000, 0)
		flat.add(1_000f, 1_000_000_000L, 1L, PressureSensorAccuracy.HIGH)
		flat.add(1_000f, 2_000_000_000L, 2L, PressureSensorAccuracy.HIGH)
		val flatWindow = assertNotNull(flat.drain())
		assertEquals(0.0, requireNotNull(flatWindow.slopeHectopascalsPerSecond), 0.0)
		assertNull(flatWindow.rSquared)

		val oneSample = PressureWindowAccumulator(2_000_000_000L, 1_000_000, 0)
		oneSample.add(1_000f, 1_000_000_000L, 1L, PressureSensorAccuracy.UNKNOWN)
		val oneSampleWindow = assertNotNull(oneSample.drain())
		assertNull(oneSampleWindow.slopeHectopascalsPerSecond)
		assertNull(oneSampleWindow.rSquared)
		assertEquals(0L, oneSampleWindow.maximumInterSampleGapNanos)
		assertEquals(PressureSensorAccuracy.UNKNOWN, oneSampleWindow.sensorAccuracy)
	}
}
