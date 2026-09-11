package com.adsamcik.tracker.app.widget.glance

import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TodaySummaryWidgetPresentationTest {

    @Test
    fun `qualified Steps render without a legacy summary or invented metrics`() {
        todaySummaryWidgetPresentation(null, QualifiedStepCount.Ready(4_200)) shouldBe
            TodaySummaryWidgetPresentation(
                distanceM = null,
                steps = WidgetStepsPresentation.Ready(4_200),
                durationMs = null,
                sessionCount = null,
            )
    }

    @Test
    fun `qualified zero Steps remain a present widget value`() {
        val presentation = todaySummaryWidgetPresentation(null, QualifiedStepCount.Ready(0))

        presentation.hasData shouldBe true
        presentation.steps shouldBe WidgetStepsPresentation.Ready(0)
        presentation.distanceM shouldBe null
        presentation.durationMs shouldBe null
        presentation.sessionCount shouldBe null
    }

    @Test
    fun `unavailable Steps cannot make an empty non-Step summary present`() {
        val presentation = todaySummaryWidgetPresentation(
            summary = DailySummary(
                totalDistanceM = 0f,
                totalDurationMs = 0L,
                sessionCount = 0,
            ),
            steps = unavailableSteps(),
        )

        presentation.hasData shouldBe false
        presentation.steps shouldBe WidgetStepsPresentation.Unavailable
    }

    @Test
    fun `non-Step summary facts remain present when Steps are unavailable`() {
        todaySummaryWidgetPresentation(
            summary = DailySummary(
                totalDistanceM = 800f,
                totalDurationMs = 1_200_000L,
                sessionCount = 1,
            ),
            steps = unavailableSteps(),
        ) shouldBe TodaySummaryWidgetPresentation(
            distanceM = 800f,
            steps = WidgetStepsPresentation.Unavailable,
            durationMs = 1_200_000L,
            sessionCount = 1,
        )
    }

    @Test
    fun `every qualified nonnumeric reason keeps a distinct truthful widget state`() {
        val expected = mapOf(
            QualifiedStepCountUnavailableReason.MISSING to WidgetStepsPresentation.Unavailable,
            QualifiedStepCountUnavailableReason.MATERIALIZING to
                WidgetStepsPresentation.Materializing,
            QualifiedStepCountUnavailableReason.NOT_CAPTURED to
                WidgetStepsPresentation.NotCaptured,
            QualifiedStepCountUnavailableReason.PARTIAL_CAPTURE to
                WidgetStepsPresentation.Partial(),
            QualifiedStepCountUnavailableReason.SOURCE_EVIDENCE_UNAVAILABLE to
                WidgetStepsPresentation.Unavailable,
            QualifiedStepCountUnavailableReason.CALENDAR_AUTHORITY_UNAVAILABLE to
                WidgetStepsPresentation.Unavailable,
            QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE to
                WidgetStepsPresentation.StorageUnavailable,
        )

        expected.forEach { (reason, state) ->
            val presentation = todaySummaryWidgetPresentation(
                summary = null,
                steps = QualifiedStepCount.Unavailable(reason),
            )

            presentation.steps shouldBe state
            presentation.hasData shouldBe false
        }
    }

    private fun unavailableSteps() = QualifiedStepCount.Unavailable(
        QualifiedStepCountUnavailableReason.SOURCE_EVIDENCE_UNAVAILABLE,
    )
}
