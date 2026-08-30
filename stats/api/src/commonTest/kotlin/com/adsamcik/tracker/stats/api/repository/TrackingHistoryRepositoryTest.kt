package com.adsamcik.tracker.stats.api.repository

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingHistoryRepositoryTest {
	@Test
	fun `covered zero remains distinct from missing evidence`() {
		val history = StepsHistory(
			count = 0L,
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.ACTIVE,
			productState = HistoryProductState.READY,
			coverage = StepsHistoryCoverage.COMPLETE,
		)

		assertTrue(history.hasCompleteValue)
		assertFalse(history.isLowerBound)
	}

	@Test
	fun `zero without covered active evidence is rejected`() {
		assertFailsWith<IllegalArgumentException> {
			StepsHistory(
				count = 0L,
				availability = HistoryAvailability.AVAILABLE,
				evidence = HistoryEvidence.NONE,
				productState = HistoryProductState.PARTIAL,
				coverage = StepsHistoryCoverage.NONE,
			)
		}
	}

	@Test
	fun `covered zero cannot claim positive-delta recording onset`() {
		assertFailsWith<IllegalArgumentException> {
			StepsHistory(
				count = 0L,
				availability = HistoryAvailability.AVAILABLE,
				evidence = HistoryEvidence.RECORDED,
				productState = HistoryProductState.READY,
				coverage = StepsHistoryCoverage.COMPLETE,
			)
		}
	}

	@Test
	fun `partial positive count is explicitly a lower bound`() {
		val history = StepsHistory(
			count = 12L,
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.PARTIAL,
			causes = setOf(StepsHistoryCause.PROVIDER_GAP),
		)

		assertFalse(history.hasCompleteValue)
		assertTrue(history.isLowerBound)
	}

	@Test
	fun `complete historical value does not depend on later acquisition availability`() {
		HistoryAvailability.entries.forEach { availability ->
			val history = StepsHistory(
				count = 12L,
				availability = availability,
				evidence = HistoryEvidence.RECORDED,
				productState = HistoryProductState.READY,
				coverage = StepsHistoryCoverage.COMPLETE,
			)

			assertTrue(history.hasCompleteValue)
			assertFalse(history.isLowerBound)
		}
	}

	@Test
	fun `unknown legacy coverage is neither exact nor a lower bound`() {
		val history = StepsHistory(
			count = 12L,
			availability = HistoryAvailability.UNAVAILABLE,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.DEGRADED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			causes = setOf(StepsHistoryCause.LEGACY_UNVERIFIED),
		)

		assertFalse(history.hasCompleteValue)
		assertFalse(history.isLowerBound)
	}

	@Test
	fun `recorded acquisition may precede a materialized product value`() {
		val history = StepsHistory(
			count = null,
			availability = HistoryAvailability.AVAILABLE,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.MATERIALIZING,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.MATERIALIZATION_BEHIND),
		)

		assertFalse(history.hasCompleteValue)
		assertFalse(history.isLowerBound)
	}

	@Test
	fun `positive value without recorded evidence is rejected`() {
		assertFailsWith<IllegalArgumentException> {
			StepsHistory(
				count = 1L,
				availability = HistoryAvailability.AVAILABLE,
				evidence = HistoryEvidence.ACTIVE,
				productState = HistoryProductState.READY,
				coverage = StepsHistoryCoverage.COMPLETE,
			)
		}
	}

	@Test
	fun `incomplete product state requires a named cause`() {
		assertFailsWith<IllegalArgumentException> {
			StepsHistory(
				count = null,
				availability = HistoryAvailability.AVAILABLE,
				evidence = HistoryEvidence.NONE,
				productState = HistoryProductState.PARTIAL,
				coverage = StepsHistoryCoverage.NONE,
			)
		}
	}
}
