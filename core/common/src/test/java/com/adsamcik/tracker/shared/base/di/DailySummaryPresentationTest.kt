package com.adsamcik.tracker.shared.base.di

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DailySummaryPresentationTest {

    @Test
    fun `qualified Steps create a nonnumeric presentation shell`() {
        val summary = null.withQualifiedStepsPresence(QualifiedStepCount.Ready(4_200))

        summary shouldBe DailySummary(
            totalDistanceM = 0f,
            totalDurationMs = 0L,
            sessionCount = 0,
        )
    }

    @Test
    fun `qualified zero Steps remain present`() {
        null.withQualifiedStepsPresence(QualifiedStepCount.Ready(0)) shouldBe DailySummary(
            totalDistanceM = 0f,
            totalDurationMs = 0L,
            sessionCount = 0,
        )
    }

    @Test
    fun `unavailable Steps cannot make an empty non-Step summary present`() {
        emptySummary().withQualifiedStepsPresence(unavailableSteps()) shouldBe null
    }

    @Test
    fun `unavailable Steps preserve independent non-Step facts`() {
        DailySummary(
            totalDistanceM = 800f,
            totalDurationMs = 1_200_000L,
            sessionCount = 1,
        ).withQualifiedStepsPresence(unavailableSteps()) shouldBe DailySummary(
            totalDistanceM = 800f,
            totalDurationMs = 1_200_000L,
            sessionCount = 1,
        )
    }

    private fun emptySummary() = DailySummary(
        totalDistanceM = 0f,
        totalDurationMs = 0L,
        sessionCount = 0,
    )

    private fun unavailableSteps() = QualifiedStepCount.Unavailable(
        QualifiedStepCountUnavailableReason.SOURCE_EVIDENCE_UNAVAILABLE,
    )
}
