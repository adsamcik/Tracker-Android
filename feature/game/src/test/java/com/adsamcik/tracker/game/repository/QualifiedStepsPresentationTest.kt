package com.adsamcik.tracker.game.repository

import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QualifiedStepsPresentationTest {
	@Test
	fun `ready summary exposes its complete total including verified zero`() {
		StepsNumericSummary.Ready(listOf(StepsNumericDay(epochDay = 10L, steps = 0L)))
			.toQualifiedStepCount() shouldBe QualifiedStepCount.Ready(0)
		StepsNumericSummary.Ready(
			listOf(
				StepsNumericDay(epochDay = 10L, steps = 12L),
				StepsNumericDay(epochDay = 11L, steps = 34L),
			),
		).toQualifiedStepCount() shouldBe QualifiedStepCount.Ready(46)
	}

	@Test
	fun `complete totals above the Int presentation range saturate without wrapping`() {
		StepsNumericSummary.Ready(
			listOf(StepsNumericDay(epochDay = 10L, steps = Int.MAX_VALUE.toLong() + 1L)),
		).toQualifiedStepCount() shouldBe QualifiedStepCount.Ready(Int.MAX_VALUE)
	}

	@Test
	fun `materializing summary remains nonnumeric`() {
		StepsNumericSummary.Materializing.toQualifiedStepCount() shouldBe
			QualifiedStepCount.Unavailable(QualifiedStepCountUnavailableReason.MATERIALIZING)
	}

	@Test
	fun `every unverifiable summary keeps its stable reason`() {
		val expected = mapOf(
			StepsNumericUnverifiableReason.NOT_CAPTURED to
				QualifiedStepCountUnavailableReason.NOT_CAPTURED,
			StepsNumericUnverifiableReason.PARTIAL_CAPTURE to
				QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE,
			StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE to
				QualifiedStepCountUnavailableReason.SOURCE_EVIDENCE_UNAVAILABLE,
			StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE to
				QualifiedStepCountUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE,
			StepsNumericUnverifiableReason.STORAGE_UNAVAILABLE to
				QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE,
		)

		expected.forEach { (reason, presentationReason) ->
			StepsNumericSummary.Unverifiable(reason).toQualifiedStepCount() shouldBe
				QualifiedStepCount.Unavailable(presentationReason)
		}
	}

	@Test
	fun `missing repository state remains nonnumeric`() {
		val progress = null.toGoalProgress()

		progress.stepsToday shouldBe QualifiedStepCount.Unavailable(
			QualifiedStepCountUnavailableReason.MISSING,
		)
		progress.progress shouldBe null
	}

	@Test
	fun `goal progress exists only for ready source-qualified Steps`() {
		val ready = StepsSummaryData(
			stepsToday = QualifiedStepCount.Ready(8_000),
			stepsWeek = QualifiedStepCount.Ready(25_000),
			goalDay = 10_000,
			goalWeek = 50_000,
		).toGoalProgress()
		val unavailable = StepsSummaryData(
			stepsToday = QualifiedStepCount.Unavailable(
				QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE,
			),
			stepsWeek = QualifiedStepCount.Unavailable(
				QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE,
			),
			goalDay = 10_000,
			goalWeek = 50_000,
		).toGoalProgress()

		ready.progress shouldBe 0.8f
		unavailable.progress shouldBe null
	}
}
