package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.assertions.throwables.shouldThrow
import kotlin.test.Test

class ActivityHistoryRepositoryTest {
	@Test
	fun `ready history requires complete nonempty content`() {
		shouldThrow<IllegalArgumentException> {
			entry(
				state = ActivityHistoryProductState.READY,
				coverage = ActivityHistoryCoverage.COMPLETE,
				activeTime = null,
				fragments = emptyList(),
			)
		}
	}

	@Test
	fun `unavailable history cannot fabricate zero durations or fragments`() {
		shouldThrow<IllegalArgumentException> {
			entry(
				state = ActivityHistoryProductState.UNAVAILABLE,
				coverage = ActivityHistoryCoverage.NONE,
				activeTime = ActivityActiveTime(0L, 0L, 0L, 0L),
				fragments = emptyList(),
				causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
			)
		}
	}

	@Test
	fun `failed history requires an integrity cause and hides content`() {
		shouldThrow<IllegalArgumentException> {
			entry(
				state = ActivityHistoryProductState.FAILED,
				coverage = ActivityHistoryCoverage.NONE,
				activeTime = null,
				fragments = emptyList(),
				causes = setOf(ActivityHistoryCause.PROVIDER_GAP),
			)
		}
	}

	private fun entry(
		state: ActivityHistoryProductState,
		coverage: ActivityHistoryCoverage,
		activeTime: ActivityActiveTime?,
		fragments: List<ActivityHistoryFragment>,
		causes: Set<ActivityHistoryCause> = emptySet(),
	) = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("opaque"),
		startTime = EpochMs(1L),
		endTime = EpochMs(2L),
		storedZoneIds = setOf("UTC"),
		state = state,
		coverage = coverage,
		activeTime = activeTime,
		fragments = fragments,
		causes = causes,
	)
}
