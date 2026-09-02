package com.adsamcik.tracker.tracker.source.summary

import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryTotals
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.tracker.source.deletion.StepsDayNumericComposition
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPlan
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPreflight
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.time.ZoneId
import org.junit.Test

class RoomStepsNumericSummaryRepositoryTest {
	private val request = StepsNumericSummaryRequest(
		firstEpochDay = 10L,
		lastEpochDayInclusive = 11L,
		fallbackCalendarZoneId = "Europe/Prague",
	)
	private val zone = ZoneId.of(request.fallbackCalendarZoneId)
	private val zones = mapOf(10L to zone, 11L to zone)

	@Test
	fun `two-day aggregate state truth table never fabricates a complete number`() {
		val completeFirst = StepsDayNumericComposition.Complete(0L)
		val completeSecond = StepsDayNumericComposition.Complete(17L)
		val ready = StepsNumericSummary.Ready(
			listOf(
				StepsNumericDay(10L, 0L),
				StepsNumericDay(11L, 17L),
			),
		)
		val notCaptured = StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.NOT_CAPTURED,
		)
		val partial = StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.PARTIAL_CAPTURE,
		)
		val cases = listOf(
			Triple(completeFirst, completeSecond, ready),
			Triple(completeFirst, StepsDayNumericComposition.NotCaptured, partial),
			Triple(completeFirst, StepsDayNumericComposition.PartialCapture, partial),
			Triple(StepsDayNumericComposition.NotCaptured, completeSecond, partial),
			Triple(
				StepsDayNumericComposition.NotCaptured,
				StepsDayNumericComposition.NotCaptured,
				notCaptured,
			),
			Triple(
				StepsDayNumericComposition.NotCaptured,
				StepsDayNumericComposition.PartialCapture,
				partial,
			),
			Triple(StepsDayNumericComposition.PartialCapture, completeSecond, partial),
			Triple(
				StepsDayNumericComposition.PartialCapture,
				StepsDayNumericComposition.NotCaptured,
				partial,
			),
			Triple(
				StepsDayNumericComposition.PartialCapture,
				StepsDayNumericComposition.PartialCapture,
				partial,
			),
		)

		cases.forEach { (first, second, expected) ->
			withClue("$first + $second") {
				StepsDayRepairPreflight.Ready(
					listOf(plan(10L, first), plan(11L, second)),
				).toNumericSummary(request, zones) shouldBe expected
			}
		}
	}

	@Test
	fun `materializing and unverifiable source state carry no numeric fallback`() {
		StepsDayRepairPreflight.Materializing.toNumericSummary(request, zones) shouldBe
			StepsNumericSummary.Materializing
		StepsDayRepairPreflight.Unsupported(
			com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionUnsupportedReason
				.DAY_REPAIR_UNVERIFIABLE,
		).toNumericSummary(request, zones) shouldBe StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
		)
	}

	@Test
	fun `missing duplicate or wrong-zone plans fail closed`() {
		StepsDayRepairPreflight.Ready(
			listOf(plan(10L, StepsDayNumericComposition.Complete(1L))),
		).toNumericSummary(request, zones) shouldBe sourceUnavailable()
		StepsDayRepairPreflight.Ready(
			listOf(
				plan(10L, StepsDayNumericComposition.Complete(1L)),
				plan(10L, StepsDayNumericComposition.Complete(2L)),
			),
		).toNumericSummary(request, zones) shouldBe sourceUnavailable()
		StepsDayRepairPreflight.Ready(
			listOf(
				plan(10L, StepsDayNumericComposition.Complete(1L)),
				plan(
					11L,
					StepsDayNumericComposition.Complete(2L),
					ZoneId.of("UTC"),
				),
			),
		).toNumericSummary(request, zones) shouldBe sourceUnavailable()
	}

	private fun plan(
		epochDay: Long,
		numericSteps: StepsDayNumericComposition,
		zoneId: ZoneId = zone,
	) = StepsDayRepairPlan(
		epochDay = epochDay,
		zoneId = zoneId,
		totals = DailySummaryTotals(
			distanceM = 1f,
			steps = (numericSteps as? StepsDayNumericComposition.Complete)?.steps?.toInt() ?: 0,
			durationMs = 1L,
			tripCount = 1,
		),
		numericSteps = numericSteps,
	)

	private fun sourceUnavailable() = StepsNumericSummary.Unverifiable(
		StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
	)
}
