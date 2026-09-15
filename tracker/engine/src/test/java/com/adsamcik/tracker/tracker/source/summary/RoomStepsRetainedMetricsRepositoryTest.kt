package com.adsamcik.tracker.tracker.source.summary

import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryTotals
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.StepsRetainedMetrics
import com.adsamcik.tracker.tracker.source.deletion.StepsDayNumericComposition
import com.adsamcik.tracker.tracker.source.deletion.StepsDayRepairPlan
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.time.ZoneId
import org.junit.Test

class RoomStepsRetainedMetricsRepositoryTest {
	private val zone = ZoneId.of("Europe/Prague")

	@Test
	fun `qualified batches fold verified zero total and best day`() {
		val accumulator = StepsRetainedMetricsAccumulator()

		accumulator.consume(
			plans = listOf(plan(10L, 0L), plan(11L, 7L)),
			zoneByDay = linkedMapOf(10L to zone, 11L to zone),
		) shouldBe null
		accumulator.consume(
			plans = listOf(plan(20L, 5L)),
			zoneByDay = mapOf(20L to zone),
		) shouldBe null

		accumulator.result() shouldBe StepsRetainedMetrics.Ready(
			totalSteps = 12L,
			bestDailySteps = 7L,
			qualifiedDayCount = 3L,
		)
	}

	@Test
	fun `not captured days add nothing while partial and mismatched authority fail closed`() {
		val empty = StepsRetainedMetricsAccumulator()
		empty.consume(
			plans = listOf(plan(10L, StepsDayNumericComposition.NotCaptured)),
			zoneByDay = mapOf(10L to zone),
		) shouldBe null
		empty.result() shouldBe StepsRetainedMetrics.Unverifiable(
			StepsNumericUnverifiableReason.NOT_CAPTURED,
		)

		StepsRetainedMetricsAccumulator().consume(
			plans = listOf(plan(10L, StepsDayNumericComposition.PartialCapture)),
			zoneByDay = mapOf(10L to zone),
		) shouldBe StepsRetainedMetrics.Unverifiable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE)
		StepsRetainedMetricsAccumulator().consume(
			plans = listOf(plan(10L, 1L)),
			zoneByDay = mapOf(11L to zone),
		) shouldBe sourceUnavailable()
	}

	@Test
	fun `checked lifetime addition rejects overflow instead of publishing a prefix`() {
		val accumulator = StepsRetainedMetricsAccumulator()
		accumulator.consume(
			plans = listOf(plan(10L, Long.MAX_VALUE)),
			zoneByDay = mapOf(10L to zone),
		) shouldBe null

		accumulator.consume(
			plans = listOf(plan(11L, 1L)),
			zoneByDay = mapOf(11L to zone),
		) shouldBe sourceUnavailable()
	}

	@Test
	fun `digest is stable and binds floor authority and result`() {
		fun digest(floor: Long?, zoneId: String, result: StepsRetainedMetrics): String =
			StepsRetainedResultDigester(floor).apply {
				addAuthority(10L, zoneId)
			}.finish(result)

		val ready = StepsRetainedMetrics.Ready(7L, 7L, 1L)
		val expected = digest(1_000L, "Europe/Prague", ready)
		digest(1_000L, "Europe/Prague", ready) shouldBe expected
		(digest(null, "Europe/Prague", ready) == expected) shouldBe false
		(digest(1_000L, "UTC", ready) == expected) shouldBe false
		(digest(1_000L, "Europe/Prague", StepsRetainedMetrics.Materializing) == expected) shouldBe false
	}

	@Test
	fun `digest rejects duplicate or regressed day authority`() {
		val digester = StepsRetainedResultDigester(null)
		digester.addAuthority(10L, "UTC")

		shouldThrow<IllegalStateException> { digester.addAuthority(10L, "UTC") }
		shouldThrow<IllegalStateException> { digester.addAuthority(9L, "UTC") }
	}

	private fun plan(epochDay: Long, steps: Long) = plan(
		epochDay,
		StepsDayNumericComposition.Complete(steps),
	)

	private fun plan(
		epochDay: Long,
		numeric: StepsDayNumericComposition,
	) = StepsDayRepairPlan(
		epochDay = epochDay,
		zoneId = zone,
		totals = DailySummaryTotals(
			distanceM = 0f,
			steps = 0,
			durationMs = 0L,
			tripCount = 0,
		),
		numericSteps = numeric,
	)

	private fun sourceUnavailable() = StepsRetainedMetrics.Unverifiable(
		StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE,
	)
}
