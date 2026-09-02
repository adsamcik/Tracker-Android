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
                steps = 4_200,
                durationMs = null,
                sessionCount = null,
            )
    }

    @Test
    fun `qualified zero Steps remain a present widget value`() {
        val presentation = todaySummaryWidgetPresentation(null, QualifiedStepCount.Ready(0))

        presentation.hasData shouldBe true
        presentation.steps shouldBe 0
        presentation.distanceM shouldBe null
        presentation.durationMs shouldBe null
        presentation.sessionCount shouldBe null
    }

    @Test
    fun `unavailable Steps cannot make a raw Steps-only summary present`() {
        val presentation = todaySummaryWidgetPresentation(
            summary = DailySummary(
                totalDistanceM = 0f,
                totalSteps = 4_200,
                totalDurationMs = 0L,
                sessionCount = 0,
            ),
            steps = unavailableSteps(),
        )

        presentation.hasData shouldBe false
        presentation.steps shouldBe null
    }

    @Test
    fun `non-Step summary facts remain present when Steps are unavailable`() {
        todaySummaryWidgetPresentation(
            summary = DailySummary(
                totalDistanceM = 800f,
                totalSteps = 4_200,
                totalDurationMs = 1_200_000L,
                sessionCount = 1,
            ),
            steps = unavailableSteps(),
        ) shouldBe TodaySummaryWidgetPresentation(
            distanceM = 800f,
            steps = null,
            durationMs = 1_200_000L,
            sessionCount = 1,
        )
    }

    private fun unavailableSteps() = QualifiedStepCount.Unavailable(
        QualifiedStepCountUnavailableReason.SOURCE_EVIDENCE_UNAVAILABLE,
    )
}
