package com.adsamcik.tracker.shared.base.di

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DailySummaryPresentationTest {

    @Test
    fun `qualified Steps create a presentation summary without legacy metrics`() {
        val summary = null.withSourceQualifiedSteps(QualifiedStepCount.Ready(4_200))

        summary shouldBe DailySummary(
            totalDistanceM = 0f,
            totalSteps = 4_200,
            totalDurationMs = 0L,
            sessionCount = 0,
        )
    }

    @Test
    fun `qualified zero Steps remain present`() {
        null.withSourceQualifiedSteps(QualifiedStepCount.Ready(0)) shouldBe DailySummary(
            totalDistanceM = 0f,
            totalSteps = 0,
            totalDurationMs = 0L,
            sessionCount = 0,
        )
    }

    @Test
    fun `unavailable Steps cannot make a raw Steps-only summary present`() {
        rawStepsOnlySummary().withSourceQualifiedSteps(unavailableSteps()) shouldBe null
    }

    @Test
    fun `unavailable Steps preserve non-Step facts but remove raw Steps`() {
        DailySummary(
            totalDistanceM = 800f,
            totalSteps = 4_200,
            totalDurationMs = 1_200_000L,
            sessionCount = 1,
        ).withSourceQualifiedSteps(unavailableSteps()) shouldBe DailySummary(
            totalDistanceM = 800f,
            totalSteps = 0,
            totalDurationMs = 1_200_000L,
            sessionCount = 1,
        )
    }

    private fun rawStepsOnlySummary() = DailySummary(
        totalDistanceM = 0f,
        totalSteps = 4_200,
        totalDurationMs = 0L,
        sessionCount = 0,
    )

    private fun unavailableSteps() = QualifiedStepCount.Unavailable(
        QualifiedStepCountUnavailableReason.SOURCE_EVIDENCE_UNAVAILABLE,
    )
}
