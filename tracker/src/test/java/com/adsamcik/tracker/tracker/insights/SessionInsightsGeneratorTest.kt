package com.adsamcik.tracker.tracker.insights

import android.content.Context
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.GoalProgress
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SessionInsightsGeneratorTest {

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        context = mockk(relaxed = true)
        // getString with format args returns the format string itself for testing
        every { context.getString(any()) } answers { "str_${firstArg<Int>()}" }
        every { context.getString(any(), any()) } answers { "str_${firstArg<Int>()}_${secondArg<Any>()}" }
    }

    private fun session(
        startMs: Long = 0L,
        endMs: Long = 0L,
        distanceInM: Float = 0f,
        steps: Int = 0,
    ): TrackerSession = TrackerSession(
        id = 1L,
        start = startMs,
        end = endMs,
        isUserInitiated = true,
        collections = 10,
        distanceInM = distanceInM,
        steps = steps,
    )

    @Nested
    inner class DurationInsights {

        @Test
        fun `short session under 5 min produces no duration insight`() {
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 4 * 60_000L),
                dailySummary = null,
                goalProgress = null,
            )
            insights.none { it.category == InsightCategory.DURATION } shouldBe true
        }

        @Test
        fun `5-19 min session produces quick session insight`() {
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 10 * 60_000L),
                dailySummary = null,
                goalProgress = null,
            )
            val duration = insights.filter { it.category == InsightCategory.DURATION }
            duration shouldHaveSize 1
        }

        @Test
        fun `20-59 min session produces solid session insight`() {
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 30 * 60_000L),
                dailySummary = null,
                goalProgress = null,
            )
            val duration = insights.filter { it.category == InsightCategory.DURATION }
            duration shouldHaveSize 1
        }

        @Test
        fun `60 plus min session produces extended session insight`() {
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 90 * 60_000L),
                dailySummary = null,
                goalProgress = null,
            )
            val duration = insights.filter { it.category == InsightCategory.DURATION }
            duration shouldHaveSize 1
        }
    }

    @Nested
    inner class DistanceInsights {

        @Test
        fun `zero distance produces no distance insight`() {
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 10 * 60_000L, distanceInM = 0f),
                dailySummary = null,
                goalProgress = null,
            )
            insights.none { it.category == InsightCategory.DISTANCE } shouldBe true
        }

        @Test
        fun `positive distance produces distance insight`() {
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 10 * 60_000L, distanceInM = 2500f),
                dailySummary = null,
                goalProgress = null,
            )
            insights.any { it.category == InsightCategory.DISTANCE } shouldBe true
        }
    }

    @Nested
    inner class StepsInsights {

        @Test
        fun `zero steps produces no steps insight`() {
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 10 * 60_000L, steps = 0),
                dailySummary = null,
                goalProgress = null,
            )
            insights.none { it.category == InsightCategory.STEPS } shouldBe true
        }

        @Test
        fun `positive steps produces steps insight`() {
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 10 * 60_000L, steps = 3000),
                dailySummary = null,
                goalProgress = null,
            )
            insights.any { it.category == InsightCategory.STEPS } shouldBe true
        }
    }

    @Nested
    inner class MultiSessionInsights {

        @Test
        fun `single session day produces no multi-session insight`() {
            val summary = DailySummary(
                totalDistanceM = 1000f,
                totalSteps = 5000,
                totalDurationMs = 600_000L,
                sessionCount = 1,
            )
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 10 * 60_000L),
                dailySummary = summary,
                goalProgress = null,
            )
            insights.none { it.category == InsightCategory.ACTIVITY } shouldBe true
        }

        @Test
        fun `multi-session day produces activity insight`() {
            val summary = DailySummary(
                totalDistanceM = 5000f,
                totalSteps = 10000,
                totalDurationMs = 3600_000L,
                sessionCount = 3,
            )
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 10 * 60_000L),
                dailySummary = summary,
                goalProgress = null,
            )
            insights.any { it.category == InsightCategory.ACTIVITY } shouldBe true
        }
    }

    @Nested
    inner class GoalInsights {

        @Test
        fun `null goal progress produces no goal insight`() {
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 10 * 60_000L),
                dailySummary = null,
                goalProgress = null,
            )
            insights.none { it.category == InsightCategory.GOAL } shouldBe true
        }

        @Test
        fun `gamification disabled produces no goal insight`() {
            val progress = GoalProgress(
                stepsToday = 8000,
                goalSteps = 10000,
                gamificationEnabled = false,
            )
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 10 * 60_000L),
                dailySummary = null,
                goalProgress = progress,
            )
            insights.none { it.category == InsightCategory.GOAL } shouldBe true
        }

        @Test
        fun `below 50 percent produces no goal insight`() {
            val progress = GoalProgress(
                stepsToday = 3000,
                goalSteps = 10000,
                gamificationEnabled = true,
            )
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 10 * 60_000L),
                dailySummary = null,
                goalProgress = progress,
            )
            insights.none { it.category == InsightCategory.GOAL } shouldBe true
        }

        @Test
        fun `above 50 percent produces goal close insight`() {
            val progress = GoalProgress(
                stepsToday = 7500,
                goalSteps = 10000,
                gamificationEnabled = true,
            )
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 10 * 60_000L),
                dailySummary = null,
                goalProgress = progress,
            )
            insights.any { it.category == InsightCategory.GOAL } shouldBe true
        }

        @Test
        fun `100 percent produces goal reached insight`() {
            val progress = GoalProgress(
                stepsToday = 10000,
                goalSteps = 10000,
                gamificationEnabled = true,
            )
            val insights = SessionInsightsGenerator.generate(
                context = context,
                session = session(endMs = 10 * 60_000L),
                dailySummary = null,
                goalProgress = progress,
            )
            insights.any { it.category == InsightCategory.GOAL } shouldBe true
        }
    }

    @Test
    fun `minimal session produces empty insights`() {
        val insights = SessionInsightsGenerator.generate(
            context = context,
            session = session(startMs = 0L, endMs = 60_000L, distanceInM = 0f, steps = 0),
            dailySummary = null,
            goalProgress = null,
        )
        insights.shouldBeEmpty()
    }

    @Test
    fun `rich session produces multiple insights`() {
        val summary = DailySummary(
            totalDistanceM = 8000f,
            totalSteps = 12000,
            totalDurationMs = 7200_000L,
            sessionCount = 3,
        )
        val goal = GoalProgress(
            stepsToday = 9000,
            goalSteps = 10000,
            gamificationEnabled = true,
        )
        val insights = SessionInsightsGenerator.generate(
            context = context,
            session = session(
                endMs = 45 * 60_000L,
                distanceInM = 3500f,
                steps = 5000,
            ),
            dailySummary = summary,
            goalProgress = goal,
        )
        // Duration + Distance + Steps + MultiSession + Goal = 5
        insights shouldHaveSize 5
    }
}
