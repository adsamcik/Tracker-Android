package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingHistoryRepositoryTest {
	@Test
	fun `exact Steps-only capture ignores separately named control sources`() {
		val history = SessionHistory(
			segmentId = 7L,
			capture = HistoryCapture.Exact(
				listOf(
					HistoryCaptureRevision(
						revision = 1L,
						effectiveAt = EpochMs(100L),
						capturedSources = setOf(HistorySource.STEPS),
						controlSources = setOf(HistorySource.ACTIVITY),
					),
				),
			),
			qualifiedSources = setOf(HistorySource.STEPS),
			steps = completeSteps(4L),
		)

		assertTrue(history.capturesOnlySteps)
	}

	@Test
	fun `mixed or unverifiable capture cannot be presented as Steps-only`() {
		val mixed = SessionHistory(
			segmentId = 7L,
			capture = HistoryCapture.Exact(
				listOf(
					HistoryCaptureRevision(
						revision = 1L,
						effectiveAt = EpochMs(100L),
						capturedSources = setOf(HistorySource.STEPS, HistorySource.LOCATION),
						controlSources = emptySet(),
					),
				),
			),
			qualifiedSources = setOf(HistorySource.STEPS),
			steps = completeSteps(4L),
		)
		val unverifiable = mixed.copy(
			capture = HistoryCapture.Unverifiable,
			qualifiedSources = emptySet(),
		)

		assertFalse(mixed.capturesOnlySteps)
		assertFalse(unverifiable.capturesOnlySteps)
	}

	@Test
	fun `every retained capture revision must remain Steps-only`() {
		val history = SessionHistory(
			segmentId = 7L,
			capture = HistoryCapture.Exact(
				listOf(
					HistoryCaptureRevision(
						revision = 1L,
						effectiveAt = EpochMs(100L),
						capturedSources = setOf(HistorySource.STEPS),
						controlSources = emptySet(),
					),
					HistoryCaptureRevision(
						revision = 2L,
						effectiveAt = EpochMs(200L),
						capturedSources = setOf(HistorySource.STEPS, HistorySource.LOCATION),
						controlSources = emptySet(),
					),
				),
			),
			qualifiedSources = setOf(HistorySource.STEPS),
			steps = completeSteps(4L),
		)

		assertFalse(history.capturesOnlySteps)
	}

	@Test
	fun `qualification requires exact matching capture authority`() {
		assertFailsWith<IllegalArgumentException> {
			SessionHistory(
				segmentId = 7L,
				capture = HistoryCapture.Unverifiable,
				qualifiedSources = setOf(HistorySource.STEPS),
				steps = completeSteps(4L),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			SessionHistory(
				segmentId = 7L,
				capture = HistoryCapture.Exact(
					listOf(
						HistoryCaptureRevision(
							revision = 1L,
							effectiveAt = EpochMs(100L),
							capturedSources = setOf(HistorySource.STEPS),
							controlSources = emptySet(),
						),
					),
				),
				qualifiedSources = setOf(HistorySource.LOCATION),
				steps = completeSteps(4L),
			)
		}
	}

	@Test
	fun `recent Steps-only entry retains explicit nonnumeric product state`() {
		val entry = StepsOnlyHistoryEntry(
			key = TrackingHistoryEntryKey("logical:one"),
			startTime = EpochMs(100L),
			endTime = EpochMs(200L),
			state = StepsOnlyHistoryListState.PARTIAL,
		)
		assertEquals(StepsOnlyHistoryListState.PARTIAL, entry.state)
	}

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

	private fun completeSteps(count: Long) = StepsHistory(
		count = count,
		availability = HistoryAvailability.AVAILABLE,
		evidence = HistoryEvidence.RECORDED,
		productState = HistoryProductState.READY,
		coverage = StepsHistoryCoverage.COMPLETE,
	)
}
