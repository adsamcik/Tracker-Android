package com.adsamcik.tracker.stats.data.repository

import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import org.junit.jupiter.api.Test

class DiscoveryPeriodStreakTest {
	private val zone = ZoneId.of("Europe/Prague")
	private fun timestamp(date: String, hour: Int = 12): Long =
		LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

	@Test fun `empty discoveries cannot form a streak`() {
		longestDiscoveryPeriodStreak(emptyList(), zone, null) shouldBe 0L
	}

	@Test fun `multiple cells and late arrival on one day count once`() {
		longestDiscoveryPeriodStreak(listOf(timestamp("2026-01-04"), timestamp("2026-01-01"),
			timestamp("2026-01-02", 1), timestamp("2026-01-02", 23), timestamp("2026-01-03")), zone, null) shouldBe 4L
	}

	@Test fun `a missing discovery day separates runs`() {
		longestDiscoveryPeriodStreak(listOf("2026-01-01", "2026-01-02", "2026-01-04",
			"2026-01-05", "2026-01-06").map { timestamp(it) }, zone, null) shouldBe 3L
	}

	@Test fun `consecutive local days survive a short daylight saving day`() {
		longestDiscoveryPeriodStreak(listOf("2026-03-28", "2026-03-29", "2026-03-30")
			.map { timestamp(it) }, zone, null) shouldBe 3L
	}

	@Test fun `consecutive local days survive a long daylight saving day`() {
		longestDiscoveryPeriodStreak(listOf("2026-10-24", "2026-10-25", "2026-10-26")
			.map { timestamp(it) }, zone, null) shouldBe 3L
	}

	@Test fun `consecutive days cross month year and the epoch without reset`() {
		longestDiscoveryPeriodStreak(listOf("1969-12-30", "1969-12-31", "1970-01-01",
			"1970-01-02").map { timestamp(it) }, zone, null) shouldBe 4L
	}

	@Test fun `calendar zone determines day membership rather than UTC date`() {
		val utc = ZoneId.of("UTC")
		val points = listOf("2026-01-01T23:30:00Z", "2026-01-02T00:30:00Z")
			.map { java.time.Instant.parse(it).toEpochMilli() }
		longestDiscoveryPeriodStreak(points, utc, null) shouldBe 2L
		longestDiscoveryPeriodStreak(points, zone, null) shouldBe 1L
	}

	@Test fun `weekly periods reuse locale first day instead of UTC week numbers`() {
		val points = listOf(timestamp("2026-01-03"), timestamp("2026-01-04"))
		longestDiscoveryPeriodStreak(points, zone, WeekFields.of(Locale.US)) shouldBe 2L
		longestDiscoveryPeriodStreak(points, zone, WeekFields.of(Locale.forLanguageTag("cs-CZ"))) shouldBe 1L
	}

	@Test fun `weekly streak crosses week year and deduplicates discoveries inside each week`() {
		val points = listOf("2025-12-28", "2025-12-29", "2026-01-01", "2026-01-05")
			.map { timestamp(it) }
		longestDiscoveryPeriodStreak(points, zone, WeekFields.ISO) shouldBe 3L
	}

	@Test fun `missing week separates weekly streaks`() {
		val points = listOf("2026-01-05", "2026-01-12", "2026-01-26").map { timestamp(it) }
		longestDiscoveryPeriodStreak(points, zone, WeekFields.ISO) shouldBe 2L
	}
}
