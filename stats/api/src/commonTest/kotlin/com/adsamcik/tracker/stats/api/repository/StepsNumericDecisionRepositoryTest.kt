package com.adsamcik.tracker.stats.api.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StepsNumericDecisionRepositoryTest {
	private val request = StepsNumericSummaryRequest(10L, 11L, "Europe/Prague")
	private val calendar = StepsNumericCalendarAuthority.Exact(
		listOf(
			StepsNumericCalendarDay(10L, "Europe/Prague"),
			StepsNumericCalendarDay(11L, "Europe/Prague"),
		),
	)
	private val summary = StepsNumericSummary.Ready(
		listOf(StepsNumericDay(10L, 0L), StepsNumericDay(11L, 17L)),
	)

	@Test
	fun `snapshot binds a bounded ordered source revision`() {
		val window = StepsNumericDecisionWindow(
			request = request,
			summary = summary,
			calendarAuthority = calendar,
			sourceResultDigest = "a".repeat(64),
		)

		assertEquals(
			StepsNumericDecisionBatch.Snapshot(4L, listOf(window)),
			StepsNumericDecisionBatch.Snapshot(4L, listOf(window)),
		)
		assertFailsWith<IllegalArgumentException> {
			StepsNumericDecisionBatch.Snapshot(-1L, listOf(window))
		}
		assertFailsWith<IllegalArgumentException> {
			StepsNumericDecisionBatch.Snapshot(4L, emptyList())
		}
	}

	@Test
	fun `window requires exact request coverage and lowercase digest`() {
		assertEquals(request, StepsNumericExactDecisionRequest(request, calendar).request)
		assertFailsWith<IllegalArgumentException> {
			StepsNumericExactDecisionRequest(
				request,
				StepsNumericCalendarAuthority.Exact(
					listOf(StepsNumericCalendarDay(10L, "Europe/Prague")),
				),
			)
		}
		assertEquals("10=Europe/Prague\n11=Europe/Prague", calendar.canonical)
		assertFailsWith<IllegalArgumentException> {
			StepsNumericDecisionWindow(request, summary, calendar, "A".repeat(64))
		}
		assertFailsWith<IllegalArgumentException> {
			StepsNumericDecisionWindow(
				request,
				summary,
				StepsNumericCalendarAuthority.Exact(
					listOf(StepsNumericCalendarDay(10L, "Europe/Prague")),
				),
				"a".repeat(64),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			StepsNumericDecisionWindow(
				request,
				StepsNumericSummary.Ready(listOf(StepsNumericDay(10L, 17L))),
				calendar,
				"a".repeat(64),
			)
		}
	}

	@Test
	fun `unavailable calendar remains an explicit nonnumeric boundary`() {
		val unavailable = StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
		)
		StepsNumericDecisionWindow(
			request,
			unavailable,
			StepsNumericCalendarAuthority.Unavailable,
			"a".repeat(64),
		)
		assertFailsWith<IllegalArgumentException> {
			StepsNumericDecisionWindow(
				request,
				StepsNumericSummary.Materializing,
				StepsNumericCalendarAuthority.Unavailable,
				"a".repeat(64),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			StepsNumericCalendarDay(10L, "UTC\nother")
		}
	}
}
