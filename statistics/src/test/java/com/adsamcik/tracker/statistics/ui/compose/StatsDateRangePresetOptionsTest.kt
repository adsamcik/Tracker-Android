package com.adsamcik.tracker.statistics.ui.compose

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pure JUnit 5 tests for the date-range preset helpers in
 * `StatsDateRangeDialog.kt`. These cover both
 *  - [statsDateRangePresetOptions], the producer that computes the start/end
 *    millis for each preset against a fixed clock and zone, and
 *  - [matchingStatsDateRangePreset], the reverse heuristic that decides
 *    which preset (if any) is currently active.
 *
 * All tests pin a deterministic clock so the preset boundaries are
 * day-aligned to a known LocalDate and never flake when run at midnight or
 * across DST cutovers.
 */
@DisplayName("StatsDateRangePresetOptions")
class StatsDateRangePresetOptionsTest {

	companion object {
		// 2024-06-15 12:34:56.789 UTC — a Saturday.
		// Using UTC avoids DST surprises that would shift "start of day" by
		// an hour on the affected dates.
		private const val FIXED_MS: Long = 1_718_455_896_789L
		private val UTC: ZoneId = ZoneOffset.UTC

		private const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L

		private fun millisAtStartOfDay(date: LocalDate): Long =
			date.atStartOfDay(UTC).toInstant().toEpochMilli()
	}

	@Nested
	@DisplayName("statsDateRangePresetOptions")
	inner class OptionsTests {

		@Test
		fun `returns one option per preset enum value`() {
			val options = statsDateRangePresetOptions(FIXED_MS, UTC)
			options.size shouldBe StatsDateRangePreset.entries.size
			options.map { it.preset } shouldBe StatsDateRangePreset.entries.toList()
		}

		@Test
		fun `TODAY spans start of today to end of today exclusive`() {
			val options = statsDateRangePresetOptions(FIXED_MS, UTC)
			val today = options.first { it.preset == StatsDateRangePreset.TODAY }
			val startOfDay = millisAtStartOfDay(LocalDate.of(2024, 6, 15))
			today.startMs shouldBe startOfDay
			today.endMs shouldBe startOfDay + MILLIS_PER_DAY - 1L
		}

		@Test
		fun `YESTERDAY spans the previous calendar day`() {
			val options = statsDateRangePresetOptions(FIXED_MS, UTC)
			val yesterday = options.first { it.preset == StatsDateRangePreset.YESTERDAY }
			val startOfYesterday = millisAtStartOfDay(LocalDate.of(2024, 6, 14))
			yesterday.startMs shouldBe startOfYesterday
			yesterday.endMs shouldBe startOfYesterday + MILLIS_PER_DAY - 1L
		}

		@Test
		fun `LAST_SEVEN_DAYS includes today plus the previous six days`() {
			val options = statsDateRangePresetOptions(FIXED_MS, UTC)
			val last7 = options.first { it.preset == StatsDateRangePreset.LAST_SEVEN_DAYS }
			// 2024-06-15 minus 6 days = 2024-06-09.
			last7.startMs shouldBe millisAtStartOfDay(LocalDate.of(2024, 6, 9))
			// End is end-of-today exclusive (start of tomorrow minus 1ms).
			last7.endMs shouldBe millisAtStartOfDay(LocalDate.of(2024, 6, 16)) - 1L
		}

		@Test
		fun `LAST_THIRTY_DAYS includes today plus the previous 29 days`() {
			val options = statsDateRangePresetOptions(FIXED_MS, UTC)
			val last30 = options.first { it.preset == StatsDateRangePreset.LAST_THIRTY_DAYS }
			// 2024-06-15 minus 29 days = 2024-05-17.
			last30.startMs shouldBe millisAtStartOfDay(LocalDate.of(2024, 5, 17))
			last30.endMs shouldBe millisAtStartOfDay(LocalDate.of(2024, 6, 16)) - 1L
		}

		@Test
		fun `THIS_MONTH starts at first of current month`() {
			val options = statsDateRangePresetOptions(FIXED_MS, UTC)
			val thisMonth = options.first { it.preset == StatsDateRangePreset.THIS_MONTH }
			thisMonth.startMs shouldBe millisAtStartOfDay(LocalDate.of(2024, 6, 1))
			thisMonth.endMs shouldBe millisAtStartOfDay(LocalDate.of(2024, 6, 16)) - 1L
		}

		@Test
		fun `LAST_MONTH spans the entire previous calendar month`() {
			val options = statsDateRangePresetOptions(FIXED_MS, UTC)
			val lastMonth = options.first { it.preset == StatsDateRangePreset.LAST_MONTH }
			lastMonth.startMs shouldBe millisAtStartOfDay(LocalDate.of(2024, 5, 1))
			// End-of-last-month = startOfThisMonth - 1ms.
			lastMonth.endMs shouldBe millisAtStartOfDay(LocalDate.of(2024, 6, 1)) - 1L
		}

		@Test
		fun `THIS_YEAR starts at January 1 of the current year`() {
			val options = statsDateRangePresetOptions(FIXED_MS, UTC)
			val thisYear = options.first { it.preset == StatsDateRangePreset.THIS_YEAR }
			thisYear.startMs shouldBe millisAtStartOfDay(LocalDate.of(2024, 1, 1))
			thisYear.endMs shouldBe millisAtStartOfDay(LocalDate.of(2024, 6, 16)) - 1L
		}

		@Test
		fun `ALL_TIME uses sentinel Long MIN and MAX values`() {
			val options = statsDateRangePresetOptions(FIXED_MS, UTC)
			val allTime = options.first { it.preset == StatsDateRangePreset.ALL_TIME }
			allTime.startMs shouldBe Long.MIN_VALUE
			allTime.endMs shouldBe Long.MAX_VALUE
		}

		@Test
		fun `LAST_MONTH start is always before THIS_MONTH start`() {
			// Sanity invariant — guards against off-by-one or wrong month
			// adjuster in a refactor.
			val options = statsDateRangePresetOptions(FIXED_MS, UTC)
			val lastMonthStart = options.first { it.preset == StatsDateRangePreset.LAST_MONTH }.startMs
			val thisMonthStart = options.first { it.preset == StatsDateRangePreset.THIS_MONTH }.startMs
			assert(lastMonthStart < thisMonthStart) {
				"Expected last-month start < this-month start, got $lastMonthStart vs $thisMonthStart"
			}
		}
	}

	@Nested
	@DisplayName("matchingStatsDateRangePreset")
	inner class MatchingTests {

		@Test
		fun `null inputs map to ALL_TIME`() {
			matchingStatsDateRangePreset(null, null, FIXED_MS, UTC) shouldBe
				StatsDateRangePreset.ALL_TIME
		}

		@Test
		fun `null start with non-null end still maps to ALL_TIME`() {
			matchingStatsDateRangePreset(null, 1_000L, FIXED_MS, UTC) shouldBe
				StatsDateRangePreset.ALL_TIME
		}

		@Test
		fun `exact TODAY range matches TODAY preset`() {
			val today = statsDateRangePresetOptions(FIXED_MS, UTC)
				.first { it.preset == StatsDateRangePreset.TODAY }
			matchingStatsDateRangePreset(today.startMs, today.endMs, FIXED_MS, UTC) shouldBe
				StatsDateRangePreset.TODAY
		}

		@Test
		fun `exact YESTERDAY range matches YESTERDAY preset`() {
			val yesterday = statsDateRangePresetOptions(FIXED_MS, UTC)
				.first { it.preset == StatsDateRangePreset.YESTERDAY }
			matchingStatsDateRangePreset(
				yesterday.startMs,
				yesterday.endMs,
				FIXED_MS,
				UTC,
			) shouldBe StatsDateRangePreset.YESTERDAY
		}

		@Test
		fun `exact LAST_SEVEN_DAYS range matches that preset, not LAST_THIRTY_DAYS`() {
			val last7 = statsDateRangePresetOptions(FIXED_MS, UTC)
				.first { it.preset == StatsDateRangePreset.LAST_SEVEN_DAYS }
			val matched = matchingStatsDateRangePreset(last7.startMs, last7.endMs, FIXED_MS, UTC)
			matched shouldBe StatsDateRangePreset.LAST_SEVEN_DAYS
			matched shouldNotBe StatsDateRangePreset.LAST_THIRTY_DAYS
		}

		@Test
		fun `exact THIS_MONTH range matches that preset`() {
			val thisMonth = statsDateRangePresetOptions(FIXED_MS, UTC)
				.first { it.preset == StatsDateRangePreset.THIS_MONTH }
			matchingStatsDateRangePreset(
				thisMonth.startMs,
				thisMonth.endMs,
				FIXED_MS,
				UTC,
			) shouldBe StatsDateRangePreset.THIS_MONTH
		}

		@Test
		fun `arbitrary custom range returns null`() {
			// A span that does not match any preset must return null so the
			// dialog renders no chip as "selected" and lets the user pick.
			matchingStatsDateRangePreset(
				startMs = millisAtStartOfDay(LocalDate.of(2024, 4, 17)),
				endMs = millisAtStartOfDay(LocalDate.of(2024, 5, 23)) - 1L,
				nowMillis = FIXED_MS,
				zoneId = UTC,
			) shouldBe null
		}

		@Test
		fun `function never returns ALL_TIME for non-null inputs even at sentinel values`() {
			// matchingStatsDateRangePreset explicitly filters ALL_TIME out
			// of the option scan, so passing the sentinel pair returns null
			// rather than ALL_TIME (ALL_TIME is reserved for null inputs).
			matchingStatsDateRangePreset(
				startMs = Long.MIN_VALUE,
				endMs = Long.MAX_VALUE,
				nowMillis = FIXED_MS,
				zoneId = UTC,
			) shouldBe null
		}
	}
}
