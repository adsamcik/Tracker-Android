package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingHistoryRepositoryTest {
	@Test
	fun `live snapshot rejects mixed-time capture authority`() {
		val stepsCapture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 1L,
					effectiveAt = EpochMs(100L),
					capturedSources = setOf(HistorySource.STEPS),
					controlSources = emptySet(),
				),
			),
		)
		val pressureCapture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 2L,
					effectiveAt = EpochMs(200L),
					capturedSources = setOf(HistorySource.PRESSURE),
					controlSources = emptySet(),
				),
			),
		)

		assertFailsWith<IllegalArgumentException> {
			LiveSessionHistorySnapshot(
				segmentId = 7L,
				session = SessionHistoryQuery.Found(
					SessionHistory(
						segmentId = 7L,
						capture = stepsCapture,
						qualifiedSources = emptySet(),
						steps = missingSteps(),
					),
				),
				activity = ActivityHistoryQuery.Found(unavailableActivityHistory()),
				pressure = PressureSessionHistoryQuery.Found(
					PressureSessionHistory(
						segmentId = 7L,
						capture = pressureCapture,
						qualifiedSources = emptySet(),
						pressure = unavailablePressureHistory(),
					),
				),
			)
		}
	}

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
	fun `Steps-aware page distinguishes a physical echo from an opaque logical row`() {
		val history = StepsOnlyHistoryEntry(
			key = TrackingHistoryEntryKey("logical:one"),
			startTime = EpochMs(100L),
			endTime = EpochMs(200L),
			state = StepsOnlyHistoryListState.AVAILABLE,
		)

		assertEquals(
			StepsAwareHistoryPageEntry.Physical(7L),
			StepsAwareHistoryPageEntry.Physical(7L),
		)
		val logicalRow = StepsAwareHistoryPageEntry.StepsOnly(history)
		assertEquals(history, logicalRow.history)
		assertFailsWith<IllegalArgumentException> {
			StepsAwareHistoryPageEntry.Physical(0L)
		}
	}

	@Test
	fun `source-aware page keeps Pressure identities opaque and physical echoes explicit`() {
		val pressure = PressureOnlyHistoryEntry(
			key = TrackingHistoryEntryKey("pressure:logical:one"),
			startTime = EpochMs(100L),
			endTime = EpochMs(200L),
			pressure = unavailablePressureHistory(),
		)

		val logicalRow = SourceAwareHistoryPageEntry.PressureOnly(pressure)
		assertEquals("TrackingHistoryEntryKey", logicalRow.history.key.toString())
		assertEquals(
			SourceAwareHistoryPageEntry.Physical(7L),
			SourceAwareHistoryPageEntry.Physical(7L),
		)
		assertFailsWith<IllegalArgumentException> {
			SourceAwareHistoryPageEntry.Physical(0L)
		}
	}

	@Test
	fun `source-aware Activity row requires exact Activity-only intent`() {
		val activity = ActivityHistoryEntry(
			key = ActivityHistoryEntryKey("activity"),
			startTime = EpochMs(100L),
			endTime = EpochMs(200L),
			storedZoneIds = setOf("UTC"),
			state = ActivityHistoryProductState.UNAVAILABLE,
			coverage = ActivityHistoryCoverage.NONE,
			activeTime = null,
			fragments = emptyList(),
			causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
		)

		assertFailsWith<IllegalArgumentException> {
			SourceAwareHistoryPageEntry.ActivityOnly(activity)
		}
		assertEquals(
			activity.copy(capturesOnlyActivity = true),
			SourceAwareHistoryPageEntry.ActivityOnly(
				activity.copy(capturesOnlyActivity = true),
			).history,
		)
	}

	@Test
	fun `combined live snapshot rejects mixed found and missing source reads`() {
		val session = SessionHistoryQuery.Found(
			SessionHistory(
				segmentId = 7L,
				capture = HistoryCapture.Exact(
					listOf(
						HistoryCaptureRevision(
							revision = 1L,
							effectiveAt = EpochMs(100L),
							capturedSources = setOf(HistorySource.ACTIVITY),
							controlSources = emptySet(),
						),
					),
				),
				qualifiedSources = emptySet(),
				steps = missingSteps(),
			),
		)

		assertFailsWith<IllegalArgumentException> {
			LiveSessionHistorySnapshot(
				segmentId = 7L,
				session = session,
				activity = ActivityHistoryQuery.NotFound,
				pressure = pressureQueryFor(session.history),
			)
		}
	}

	@Test
	fun `combined live snapshot rejects dual source-only truth`() {
		val capture = HistoryCapture.Exact(
			listOf(
				HistoryCaptureRevision(
					revision = 1L,
					effectiveAt = EpochMs(100L),
					capturedSources = setOf(HistorySource.PRESSURE),
					controlSources = emptySet(),
				),
			),
		)
		val session = SessionHistory(
			segmentId = 7L,
			capture = capture,
			qualifiedSources = emptySet(),
			steps = missingSteps(),
		)
		val activity = ActivityHistoryEntry(
			key = ActivityHistoryEntryKey("activity"),
			startTime = EpochMs(100L),
			endTime = EpochMs(200L),
			storedZoneIds = setOf("UTC"),
			state = ActivityHistoryProductState.UNAVAILABLE,
			coverage = ActivityHistoryCoverage.NONE,
			activeTime = null,
			fragments = emptyList(),
			causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
			capturesOnlyActivity = true,
		)

		assertFailsWith<IllegalArgumentException> {
			LiveSessionHistorySnapshot(
				segmentId = 7L,
				session = SessionHistoryQuery.Found(session),
				activity = ActivityHistoryQuery.Found(activity),
				pressure = pressureQueryFor(session),
			)
		}
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

	private fun missingSteps() = StepsHistory(
		count = null,
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = StepsHistoryCoverage.UNKNOWN,
		causes = setOf(StepsHistoryCause.LEGACY_UNVERIFIED),
	)

	private fun pressureQueryFor(session: SessionHistory) = PressureSessionHistoryQuery.Found(
		PressureSessionHistory(
			segmentId = session.segmentId,
			capture = session.capture,
			qualifiedSources = emptySet(),
			pressure = unavailablePressureHistory(),
		),
	)

	private fun unavailableActivityHistory() = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("activity:unavailable"),
		startTime = EpochMs(100L),
		endTime = EpochMs(200L),
		storedZoneIds = setOf("UTC"),
		state = ActivityHistoryProductState.UNAVAILABLE,
		coverage = ActivityHistoryCoverage.NONE,
		activeTime = null,
		fragments = emptyList(),
		causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
	)

	private fun unavailablePressureHistory() = PressureHistory(
		availability = HistoryAvailability.UNAVAILABLE,
		evidence = HistoryEvidence.NONE,
		productState = HistoryProductState.DEGRADED,
		coverage = PressureHistoryCoverage.UNKNOWN,
		windows = emptyList(),
		causes = setOf(PressureHistoryCause.LEGACY_UNATTRIBUTED),
	)
}
