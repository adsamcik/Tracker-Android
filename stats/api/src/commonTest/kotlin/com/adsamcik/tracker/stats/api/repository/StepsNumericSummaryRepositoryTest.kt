package com.adsamcik.tracker.stats.api.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StepsNumericSummaryRepositoryTest {
	@Test
	fun `bounded request retains an explicit fallback calendar authority`() {
		val request = StepsNumericSummaryRequest(
			firstEpochDay = -3L,
			lastEpochDayInclusive = 2L,
			fallbackCalendarZoneId = "Europe/Prague",
		)

		assertEquals(6, request.dayCount)
		assertEquals("Europe/Prague", request.fallbackCalendarZoneId)
	}

	@Test
	fun `request rejects reversed oversized and overflowed ranges`() {
		assertFailsWith<IllegalArgumentException> {
			StepsNumericSummaryRequest(2L, 1L, "UTC")
		}
		assertFailsWith<IllegalArgumentException> {
			StepsNumericSummaryRequest(1L, 371L, "UTC")
		}
		assertFailsWith<IllegalArgumentException> {
			StepsNumericSummaryRequest(Long.MIN_VALUE, Long.MAX_VALUE, "UTC")
		}
		assertFailsWith<IllegalArgumentException> {
			StepsNumericSummaryRequest(1L, 1L, " ")
		}
	}

	@Test
	fun `only Ready exposes a complete number including verified zero`() {
		val ready = StepsNumericSummary.Ready(
			listOf(
				StepsNumericDay(epochDay = 1L, steps = 0L),
				StepsNumericDay(epochDay = 2L, steps = 17L),
			),
		)

		assertEquals(17L, ready.totalSteps)
	}

	@Test
	fun `Ready rejects empty unordered negative and overflowing totals`() {
		assertFailsWith<IllegalArgumentException> {
			StepsNumericSummary.Ready(emptyList())
		}
		assertFailsWith<IllegalArgumentException> {
			StepsNumericSummary.Ready(
				listOf(
					StepsNumericDay(2L, 1L),
					StepsNumericDay(1L, 2L),
				),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			StepsNumericDay(1L, -1L)
		}
		assertFailsWith<IllegalArgumentException> {
			StepsNumericSummary.Ready(
				listOf(
					StepsNumericDay(1L, Long.MAX_VALUE),
					StepsNumericDay(2L, 1L),
				),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			StepsNumericSummary.Ready(
				listOf(StepsNumericDay(1L, 1L), StepsNumericDay(3L, 2L)),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			StepsNumericSummary.Ready(
				(0L until 371L).map { day -> StepsNumericDay(day, 0L) },
			)
		}
		assertEquals(
			0L,
			StepsNumericSummary.Ready(
				(0L until StepsNumericSummaryRequest.MAX_DAY_COUNT.toLong()).map { day ->
					StepsNumericDay(day, 0L)
				},
			).totalSteps,
		)
	}
}
