package com.adsamcik.tracker.stats.api.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StepsRetainedMetricsRepositoryTest {
	@Test
	fun `Ready preserves a verified zero and its qualified-day proof`() {
		val ready = StepsRetainedMetrics.Ready(
			totalSteps = 0L,
			bestDailySteps = 0L,
			qualifiedDayCount = 1L,
		)

		assertEquals(0L, ready.totalSteps)
		assertEquals(0L, ready.bestDailySteps)
		assertEquals(1L, ready.qualifiedDayCount)
	}

	@Test
	fun `Ready rejects fabricated or contradictory metrics`() {
		assertFailsWith<IllegalArgumentException> {
			StepsRetainedMetrics.Ready(-1L, 0L, 1L)
		}
		assertFailsWith<IllegalArgumentException> {
			StepsRetainedMetrics.Ready(1L, -1L, 1L)
		}
		assertFailsWith<IllegalArgumentException> {
			StepsRetainedMetrics.Ready(0L, 0L, 0L)
		}
		assertFailsWith<IllegalArgumentException> {
			StepsRetainedMetrics.Ready(4L, 5L, 1L)
		}
	}

	@Test
	fun `Snapshot requires exact digest and nonnegative revision`() {
		val snapshot = StepsRetainedMetricsDecision.Snapshot(
			sourceEvidenceRevision = 7L,
			result = StepsRetainedMetrics.Materializing,
			sourceResultDigest = "a".repeat(64),
		)

		assertEquals(7L, snapshot.sourceEvidenceRevision)
		assertFailsWith<IllegalArgumentException> {
			StepsRetainedMetricsDecision.Snapshot(
				sourceEvidenceRevision = -1L,
				result = StepsRetainedMetrics.Materializing,
				sourceResultDigest = "a".repeat(64),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			StepsRetainedMetricsDecision.Snapshot(
				sourceEvidenceRevision = 1L,
				result = StepsRetainedMetrics.Unverifiable(
					StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
				),
				sourceResultDigest = "A".repeat(64),
			)
		}
	}
}
