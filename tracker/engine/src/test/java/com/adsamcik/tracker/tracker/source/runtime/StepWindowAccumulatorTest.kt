package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class StepWindowAccumulatorTest {
	@Test
	fun `first callback establishes a baseline without inventing steps`() {
		val payload = requireNotNull(StepWindowAccumulator(null).accept("boot:1", 1_234L, 10L, 1L))

		assertEquals(0L, payload.deltaCount)
		assertEquals(1_234L, payload.firstCumulativeCount)
		assertEquals(1_234L, payload.lastCumulativeCount)
		assertTrue(payload.baselineReset)
		assertEquals(StepBoundaryKind.BASELINE, payload.boundaryKind)
	}

	@Test
	fun `counter reset establishes a zero baseline and next callback counts only the new domain`() {
		val boundary = StepBaselineBoundary(1L)
		val original = StepWindowAccumulator(null, boundary)
		original.accept("boot:1", 100L, 1_000L, 5L)
		val restored = decodeStepBaseline(
			requireNotNull(original.snapshot()).encode(),
			STEP_BASELINE_VERSION,
			boundary,
		)
		val accumulator = StepWindowAccumulator(restored, boundary)

		val normal = requireNotNull(accumulator.accept("boot:1", 108L, 2_000L, 6L))
		val reset = requireNotNull(accumulator.accept("boot:1", 3L, 3_000L, 7L))
		val afterReset = requireNotNull(accumulator.accept("boot:1", 5L, 4_000L, 8L))

		assertEquals(8L, normal.deltaCount)
		assertFalse(normal.baselineReset)
		assertEquals(StepBoundaryKind.COVERED, normal.boundaryKind)
		assertEquals(0L, reset.deltaCount)
		assertTrue(reset.baselineReset)
		assertEquals(StepBoundaryKind.COUNTER_RESET, reset.boundaryKind)
		assertEquals(3L, reset.firstCumulativeCount)
		assertEquals(3L, reset.lastCumulativeCount)
		assertEquals(3_000L, reset.windowStartElapsedRealtimeNanos)
		assertEquals(3_000L, reset.windowEndElapsedRealtimeNanos)
		assertEquals(7L, reset.firstProviderSequence)
		assertEquals(7L, reset.lastProviderSequence)
		assertEquals(2L, afterReset.deltaCount)
		assertFalse(afterReset.baselineReset)
		assertEquals(StepBoundaryKind.COVERED, afterReset.boundaryKind)
		assertEquals(3L, afterReset.firstCumulativeCount)
		assertEquals(5L, afterReset.lastCumulativeCount)
	}

	@Test
	fun `registration generation change discards restored baseline`() {
		val priorBoundary = StepBaselineBoundary(1L)
		val currentBoundary = StepBaselineBoundary(2L)
		val prior = StepBaseline(100L, 1_000L, 5L, priorBoundary)

		val restored = decodeStepBaseline(prior.encode(), STEP_BASELINE_VERSION, currentBoundary)
		val first = requireNotNull(
			StepWindowAccumulator(restored, currentBoundary)
				.accept("boot:1", 108L, 2_000L, 1L),
		)

		assertEquals(0L, first.deltaCount)
		assertEquals(108L, first.firstCumulativeCount)
		assertTrue(first.baselineReset)
	}

	@Test
	fun `same authorization at the same physical generation preserves restored baseline`() {
		val physicalBoundary = StepBaselineBoundary(7L)
		val authorization = authorization(1L, "control", SourceBrokerPurpose.MASK_CONTROL_AUTOSTART)
		val prior = StepBaseline(100L, 1_000L, 5L, physicalBoundary, authorization)

		val restored = decodeStepBaseline(prior.encode(), STEP_BASELINE_VERSION, physicalBoundary)
		val first = requireNotNull(
			StepWindowAccumulator(restored, physicalBoundary)
				.accept("boot:1", 108L, 2_000L, 6L, authorizationBoundary = authorization),
		)

		assertEquals(8L, first.deltaCount)
		assertEquals(100L, first.firstCumulativeCount)
		assertFalse(first.baselineReset)
	}

	@Test
	fun `queued control then capture authorization boundary emits no cross-purpose delta`() {
		val accumulator = StepWindowAccumulator(null, StepBaselineBoundary(7L))
		val control = authorization(
			1L,
			"control-only",
			SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
		)
		val capture = authorization(
			2L,
			"control-and-capture",
			SourceBrokerPurpose.MASK_CONTROL_AUTOSTART or SourceBrokerPurpose.MASK_SESSION_CAPTURE,
		)
		requireNotNull(accumulator.accept(
			"boot:1", 100L, 1_000L, 1L, authorizationBoundary = control,
		))
		requireNotNull(accumulator.accept(
			"boot:1", 105L, 2_000L, 2L, authorizationBoundary = control,
		))

		val boundary = requireNotNull(accumulator.accept(
			"boot:1", 110L, 3_000L, 3L, authorizationBoundary = capture,
		))
		val captured = requireNotNull(accumulator.accept(
			"boot:1", 114L, 4_000L, 4L, authorizationBoundary = capture,
		))

		assertEquals(0L, boundary.deltaCount)
		assertEquals(110L, boundary.firstCumulativeCount)
		assertEquals(110L, boundary.lastCumulativeCount)
		assertEquals(3L, boundary.firstProviderSequence)
		assertTrue(boundary.baselineReset)
		assertEquals(StepBoundaryKind.BASELINE, boundary.boundaryKind)
		assertEquals(4L, captured.deltaCount)
		assertEquals(110L, captured.firstCumulativeCount)
		assertFalse(captured.baselineReset)
		assertEquals(4L, boundary.deltaCount + captured.deltaCount)
		assertEquals(capture, accumulator.snapshot()?.authorizationBoundary)
	}

	@Test
	fun `policy revision boundary cannot attribute the prior policy interval`() {
		val accumulator = StepWindowAccumulator(null, StepBaselineBoundary(9L))
		val priorPolicy = authorization(11L, "capture-policy-11", SourceBrokerPurpose.MASK_SESSION_CAPTURE)
		val nextPolicy = authorization(12L, "capture-policy-12", SourceBrokerPurpose.MASK_SESSION_CAPTURE)
		requireNotNull(accumulator.accept(
			"boot:1", 200L, 1_000L, 1L, authorizationBoundary = priorPolicy,
		))

		val boundary = requireNotNull(accumulator.accept(
			"boot:1", 209L, 2_000L, 2L, authorizationBoundary = nextPolicy,
		))

		assertEquals(0L, boundary.deltaCount)
		assertEquals(209L, boundary.firstCumulativeCount)
		assertEquals(209L, boundary.lastCumulativeCount)
		assertTrue(boundary.baselineReset)
		assertEquals(StepBoundaryKind.BASELINE, boundary.boundaryKind)
		assertEquals(nextPolicy, accumulator.snapshot()?.authorizationBoundary)
	}

	@Test
	fun `fresh unchanged counter is covered zero rather than another baseline`() {
		val accumulator = StepWindowAccumulator(null, StepBaselineBoundary(9L))
		requireNotNull(accumulator.accept("boot:1", 200L, 1_000L, 1L))

		val coveredZero = requireNotNull(accumulator.accept("boot:1", 200L, 2_000L, 2L))

		assertEquals(0L, coveredZero.deltaCount)
		assertFalse(coveredZero.baselineReset)
		assertEquals(StepBoundaryKind.COVERED, coveredZero.boundaryKind)
		assertEquals(1_000L, coveredZero.windowStartElapsedRealtimeNanos)
		assertEquals(2_000L, coveredZero.windowEndElapsedRealtimeNanos)
	}

	@Test
	fun `delayed pre-boundary sample cannot seed refreshed authorization baseline`() {
		val accumulator = StepWindowAccumulator(null, StepBaselineBoundary(9L))
		val refreshed = authorization(
			revision = 12L,
			fingerprint = "capture-policy-12",
			purposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			effectiveElapsedRealtimeNanos = 3_000L,
		)

		assertNull(accumulator.accept(
			"boot:1",
			cumulativeCount = 209L,
			elapsedRealtimeNanos = 2_999L,
			providerSequence = 2L,
			receivedElapsedRealtimeNanos = 3_100L,
			authorizationBoundary = refreshed,
		))
		assertNull(accumulator.snapshot())

		val boundary = requireNotNull(accumulator.accept(
			"boot:1",
			cumulativeCount = 210L,
			elapsedRealtimeNanos = 3_000L,
			providerSequence = 3L,
			receivedElapsedRealtimeNanos = 3_100L,
			authorizationBoundary = refreshed,
		))
		val durableDelta = requireNotNull(accumulator.accept(
			"boot:1",
			cumulativeCount = 214L,
			elapsedRealtimeNanos = 4_000L,
			providerSequence = 4L,
			receivedElapsedRealtimeNanos = 4_100L,
			authorizationBoundary = refreshed,
		))

		assertEquals(0L, boundary.deltaCount)
		assertTrue(boundary.baselineReset)
		assertEquals(4L, durableDelta.deltaCount)
		assertFalse(durableDelta.baselineReset)
		assertEquals(3_000L, durableDelta.windowStartElapsedRealtimeNanos)
	}

	@Test
	fun `missing future duplicate and reordered provider times never mutate the baseline`() {
		val accumulator = StepWindowAccumulator(null, StepBaselineBoundary(4L))
		requireNotNull(accumulator.accept("boot:1", 100L, 100L, 1L, 100L))
		val acceptedBaseline = accumulator.snapshot()

		assertNull(accumulator.accept("boot:1", 101L, 0L, 2L, 200L))
		assertNull(accumulator.accept("boot:1", 102L, 201L, 2L, 200L))
		assertNull(accumulator.accept("boot:1", 103L, 100L, 2L, 200L))
		assertNull(accumulator.accept("boot:1", 104L, 99L, 2L, 200L))
		assertEquals(acceptedBaseline, accumulator.snapshot())

		val next = requireNotNull(accumulator.accept("boot:1", 108L, 150L, 2L, 200L))
		assertEquals(8L, next.deltaCount)
		assertEquals(100L, next.windowStartElapsedRealtimeNanos)
		assertFalse(next.baselineReset)
	}

	private fun authorization(
		revision: Long,
		fingerprint: String,
		purposeMask: Long,
		effectiveElapsedRealtimeNanos: Long = 0L,
	) = StepAuthorizationBoundary(
		revision,
		fingerprint,
		purposeMask,
		effectiveElapsedRealtimeNanos,
	)
}
