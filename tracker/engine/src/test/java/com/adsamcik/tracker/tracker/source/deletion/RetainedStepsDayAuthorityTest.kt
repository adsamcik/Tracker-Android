package com.adsamcik.tracker.tracker.source.deletion

import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

class RetainedStepsDayAuthorityTest {
	private val zone = ZoneId.of("Europe/Prague")
	private val firstDay = LocalDate.of(2026, 4, 2).toEpochDay()
	private val firstStart = LocalDate.ofEpochDay(firstDay).atStartOfDay(zone).toInstant().toEpochMilli()
	private val secondStart = LocalDate.ofEpochDay(firstDay + 1L).atStartOfDay(zone).toInstant().toEpochMilli()
	private val thirdStart = LocalDate.ofEpochDay(firstDay + 2L).atStartOfDay(zone).toInstant().toEpochMilli()

	@Test
	fun `retention boundary day is excluded while the next whole day keeps captured authority`() {
		val zones = sortedMapOf<Long, ZoneId>()

		addRetainedAuthorityInterval(
			startMs = firstStart,
			endMs = thirdStart,
			zoneId = zone,
			firstEpochDay = firstDay,
			lastEpochDayInclusive = firstDay + 1L,
			retainedFromMs = firstStart + 1L,
			zoneByDay = zones,
		) shouldBe true

		zones shouldBe mapOf(firstDay + 1L to zone)
	}

	@Test
	fun `fact wall outside presentation envelope still contributes its structural day`() {
		val zones = sortedMapOf<Long, ZoneId>()

		addRetainedAuthorityInterval(
			startMs = secondStart,
			endMs = thirdStart,
			zoneId = zone,
			firstEpochDay = firstDay,
			lastEpochDayInclusive = firstDay + 1L,
			retainedFromMs = null,
			zoneByDay = zones,
		) shouldBe true

		zones shouldBe mapOf(firstDay + 1L to zone)
	}

	@Test
	fun `conflicting authority for one structural day fails closed`() {
		val zones = sortedMapOf(firstDay to zone)

		addRetainedAuthorityInterval(
			startMs = firstStart,
			endMs = secondStart,
			zoneId = ZoneId.of("UTC"),
			firstEpochDay = firstDay,
			lastEpochDayInclusive = firstDay,
			retainedFromMs = null,
			zoneByDay = zones,
		) shouldBe false
	}
}
