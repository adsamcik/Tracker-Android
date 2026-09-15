package com.adsamcik.tracker.tracker.source.summary

import com.adsamcik.tracker.stats.api.repository.StepsNumericCalendarAuthority
import com.adsamcik.tracker.stats.api.repository.StepsNumericCalendarDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class StepsSourceResultDigestTest {
	private val request = StepsNumericSummaryRequest(10L, 11L, "Europe/Prague")
	private val authority = StepsNumericCalendarAuthority.Exact(
		listOf(
			StepsNumericCalendarDay(10L, "Europe/Prague"),
			StepsNumericCalendarDay(11L, "Europe/Prague"),
		),
	)
	private val ready = StepsNumericSummary.Ready(
		listOf(StepsNumericDay(10L, 0L), StepsNumericDay(11L, 17L)),
	)

	@Test
	fun `identical semantic source result has stable lowercase digest`() {
		val first = stepsSourceResultDigest(request, authority, ready)
		val second = stepsSourceResultDigest(request.copy(), authority.copy(), ready.copy())

		first shouldBe second
		first.length shouldBe 64
		first shouldBe first.lowercase()
	}

	@Test
	fun `resolved calendar count and unavailable reason are independent digest authority`() {
		val original = stepsSourceResultDigest(request, authority, ready)
		stepsSourceResultDigest(
			request.copy(fallbackCalendarZoneId = "UTC"),
			authority,
			ready,
		) shouldBe original
		stepsSourceResultDigest(
			request,
			authority.copy(
				days = listOf(
					StepsNumericCalendarDay(10L, "UTC"),
					StepsNumericCalendarDay(11L, "UTC"),
				),
			),
			ready,
		) shouldNotBe original
		stepsSourceResultDigest(
			request,
			authority,
			ready.copy(days = listOf(StepsNumericDay(10L, 0L), StepsNumericDay(11L, 18L))),
		) shouldNotBe original
		stepsSourceResultDigest(
			request,
			authority,
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE),
		) shouldNotBe stepsSourceResultDigest(
			request,
			authority,
			StepsNumericSummary.Unverifiable(
				StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
			),
		)
		stepsSourceResultDigest(
			request,
			StepsNumericCalendarAuthority.Unavailable,
			StepsNumericSummary.Unverifiable(
				StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
			),
		) shouldNotBe stepsSourceResultDigest(
			request.copy(fallbackCalendarZoneId = "UTC"),
			StepsNumericCalendarAuthority.Unavailable,
			StepsNumericSummary.Unverifiable(
				StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
			),
		)
	}
}
