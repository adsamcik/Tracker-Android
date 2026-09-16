package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangeScope
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryStructuralDayCompleteness
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class ActivityHistoryRangeSupportTest {
	@Test
	fun `stored UTC minus 12 and plus 14 zones assign different structural days`() {
		val instant = Instant.parse("2026-09-15T12:30:00Z").toEpochMilli()
		val minusDay = Instant.ofEpochMilli(instant).atZone(ZoneId.of("Etc/GMT+12"))
			.toLocalDate().toEpochDay()
		val plusDay = Instant.ofEpochMilli(instant).atZone(ZoneId.of("Pacific/Kiritimati"))
			.toLocalDate().toEpochDay()
		val scope = ActivityHistoryRangeScope.StructuralDays(
			minOf(minusDay, plusDay),
			maxOf(minusDay, plusDay),
		)
		val minus = activityHistoryRangeEntry(
			unavailable(instant, "Etc/GMT+12"),
			ActivityTemporalAuthority(
				listOf(ActivityTemporalZoneRange(instant, instant, "Etc/GMT+12")),
				complete = true,
			),
			scope,
		)
		val plus = activityHistoryRangeEntry(
			unavailable(instant, "Pacific/Kiritimati"),
			ActivityTemporalAuthority(
				listOf(ActivityTemporalZoneRange(instant, instant, "Pacific/Kiritimati")),
				complete = true,
			),
			scope,
		)

		minus?.structuralDays?.single()?.epochDay shouldBe minusDay
		plus?.structuralDays?.single()?.epochDay shouldBe plusDay
	}

	@Test
	fun `stored Prague DST range remains on its structural day`() {
		val zone = ZoneId.of("Europe/Prague")
		val start = ZonedDateTime.of(2026, 3, 29, 1, 30, 0, 0, zone).toInstant().toEpochMilli()
		val end = Instant.parse("2026-03-29T01:30:00Z").toEpochMilli()

		val ranged = activityHistoryRangeEntry(
			unavailable(start, zone.id, end),
			ActivityTemporalAuthority(
				listOf(ActivityTemporalZoneRange(start, end, zone.id)),
				complete = true,
			),
			ActivityHistoryRangeScope.WallTime(EpochMs(start), EpochMs(end + 1L)),
		)

		ranged?.structuralDays?.single()?.epochDay shouldBe
			Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toEpochDay()
		ranged?.structuralDayCompleteness shouldBe ActivityHistoryStructuralDayCompleteness.EXACT
	}

	@Test
	fun `missing zone authority remains unavailable instead of using current zone`() {
		val entry = unavailable(10L, null, 20L)

		val ranged = activityHistoryRangeEntry(
			entry,
			authority = null,
			ActivityHistoryRangeScope.WallTime(EpochMs(0L), EpochMs(30L)),
		)

		ranged?.structuralDays shouldBe emptySet()
		ranged?.structuralDayCompleteness shouldBe
			ActivityHistoryStructuralDayCompleteness.UNAVAILABLE
	}

	private fun unavailable(
		startMs: Long,
		zoneId: String?,
		endMs: Long = startMs,
	) = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("range-$startMs-${zoneId.orEmpty()}"),
		startTime = EpochMs(startMs),
		endTime = EpochMs(endMs),
		storedZoneIds = zoneId?.let(::setOf).orEmpty(),
		state = ActivityHistoryProductState.UNAVAILABLE,
		coverage = ActivityHistoryCoverage.NONE,
		activeTime = null,
		fragments = emptyList(),
		causes = setOf(ActivityHistoryCause.NO_QUALIFIED_FACTS),
	)
}
