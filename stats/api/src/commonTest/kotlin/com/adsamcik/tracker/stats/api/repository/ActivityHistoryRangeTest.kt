package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ActivityHistoryRangeTest {
	@Test
	fun `wall and structural ranges remain bounded independently of current zone`() {
		assertEquals(
			EpochMs(10L),
			(ActivityHistoryRangeScope.WallTime(EpochMs(10L), EpochMs(20L))).fromInclusive,
		)
		ActivityHistoryRangeScope.StructuralDays(0L, 369L)
		assertFailsWith<IllegalArgumentException> {
			ActivityHistoryRangeScope.StructuralDays(0L, 370L)
		}
		assertFailsWith<IllegalArgumentException> {
			ActivityHistoryRangeRequest(
				ActivityHistoryRangeScope.WallTime(EpochMs(0L), EpochMs(1L)),
				101,
			)
		}
	}

	@Test
	fun `unavailable structural membership cannot carry invented days`() {
		val entry = unavailableEntry()
		ActivityHistoryRangeEntry(
			entry,
			emptySet(),
			ActivityHistoryStructuralDayCompleteness.UNAVAILABLE,
		)
		assertFailsWith<IllegalArgumentException> {
			ActivityHistoryRangeEntry(
				entry,
				setOf(ActivityHistoryStructuralDay(0L, "UTC")),
				ActivityHistoryStructuralDayCompleteness.UNAVAILABLE,
			)
		}
	}

	private fun unavailableEntry() = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("range"),
		startTime = EpochMs(0L),
		endTime = EpochMs(1L),
		storedZoneIds = emptySet(),
		state = ActivityHistoryProductState.UNAVAILABLE,
		coverage = ActivityHistoryCoverage.NONE,
		activeTime = null,
		fragments = emptyList(),
		causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
	)
}
