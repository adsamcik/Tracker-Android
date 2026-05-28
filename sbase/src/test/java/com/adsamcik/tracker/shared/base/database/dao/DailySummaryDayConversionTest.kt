package com.adsamcik.tracker.shared.base.database.dao

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Verifies the `epoch-ms → epoch-day` conversion used by [DailySummaryDao] window
 * predicates. The mapping must keep `SELECT ... WHERE date_epoch_day BETWEEN
 * fromMsToFromDay(fromMs) AND toMsToToDay(toMs)` semantically identical to the
 * legacy `... date_epoch_day * 86400000 BETWEEN fromMs AND toMs`, so callers that
 * depended on the old inclusive bounds keep working.
 */
class DailySummaryDayConversionTest {

	@Test
	fun `fromMsToFromDay maps exact-midnight to that day`() {
		// 86_400_000 ms == start of day 1
		fromMsToFromDay(0L) shouldBe 0L
		fromMsToFromDay(86_400_000L) shouldBe 1L
		fromMsToFromDay(2L * 86_400_000L) shouldBe 2L
	}

	@Test
	fun `fromMsToFromDay rounds up mid-day timestamps to the next day`() {
		// 1ms after midnight of day 1 — day 1 starts BEFORE fromMs, so should be excluded.
		// The first day fully at-or-after fromMs is day 2.
		fromMsToFromDay(86_400_001L) shouldBe 2L
		fromMsToFromDay(86_400_000L + 12_345L) shouldBe 2L
	}

	@Test
	fun `toMsToToDay maps midnight to that day`() {
		toMsToToDay(0L) shouldBe 0L
		toMsToToDay(86_400_000L) shouldBe 1L
		toMsToToDay(2L * 86_400_000L) shouldBe 2L
	}

	@Test
	fun `toMsToToDay rounds down — last day at-or-before toMs is included`() {
		// 86_399_999 ms is still day 0 (just before midnight of day 1).
		toMsToToDay(86_399_999L) shouldBe 0L
		// 1 ms into day 1 — day 1 IS at-or-before that, so included.
		toMsToToDay(86_400_001L) shouldBe 1L
	}

	@Test
	fun `round-trip preserves the legacy inclusive window for whole-day boundaries`() {
		// Window: from midnight day 1 to end-of-day day 3 (inclusive).
		val fromMs = 86_400_000L          // day 1 start
		val toMs = 4L * 86_400_000L - 1L  // day 3, 23:59:59.999
		fromMsToFromDay(fromMs) shouldBe 1L
		toMsToToDay(toMs) shouldBe 3L
	}

	@Test
	fun `window strictly inside a single day collapses to no rows`() {
		// Window: 12:00 to 14:00 on day 0. No whole day is fully inside.
		val fromMs = 12L * 3_600_000L
		val toMs = 14L * 3_600_000L
		val fromDay = fromMsToFromDay(fromMs)
		val toDay = toMsToToDay(toMs)
		// fromDay = 1 (since 12:00 isn't at midnight, day 0 starts BEFORE fromMs)
		// toDay   = 0 (since 14:00 is still day 0; day 1 starts AFTER toMs)
		// fromDay > toDay => SQL BETWEEN returns no rows, matching legacy semantics.
		fromDay shouldBe 1L
		toDay shouldBe 0L
		(fromDay > toDay) shouldBe true
	}
}
