package com.adsamcik.tracker.tracker.insights

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import javax.inject.Provider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class SessionInsightsGeneratorTest {

    private val context = mockk<Context>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>()
    private val dailySummaryDaoProvider = mockk<Provider<DailySummaryDao>>()
    private val dispatcher = StandardTestDispatcher()

    private lateinit var generator: SessionInsightsGenerator

    @BeforeEach
    fun setUp() {
        every { context.getString(any()) } answers { "str_${firstArg<Int>()}" }
        every { context.getString(any(), *anyVararg()) } answers {
            buildString {
                append("str_${args.first()}")
                args.drop(1).forEach { append("_$it") }
            }
        }
        every { dailySummaryDaoProvider.get() } returns dailySummaryDao
        generator = DefaultSessionInsightsGenerator(
            context = context,
            dailySummaryDaoProvider = dailySummaryDaoProvider,
            dispatchers = TestDispatchersProvider(dispatcher),
        )
    }

    @Test
    fun `unqualified Steps do not create achievement insight`() = runTest(dispatcher) {
        coEvery { dailySummaryDao.getBetween(any(), any()) } returns emptyList()

        val insights = generator.generate(session(steps = 4_500, distanceInM = 2_500f))

        insights.map { it.category } shouldNotContain InsightCategory.ACHIEVEMENT
        insights.map { it.category } shouldContain InsightCategory.FUN_FACT
    }

    @Test
    fun `independent distance still creates achievement insight`() = runTest(dispatcher) {
        coEvery { dailySummaryDao.getBetween(any(), any()) } returns emptyList()

        val insights = generator.generate(session(steps = 0, distanceInM = 4_500f))

        insights.map { it.category } shouldContain InsightCategory.ACHIEVEMENT
    }

    @Test
    fun `does not query or claim exploration without exact session ownership`() = runTest(dispatcher) {
        coEvery { dailySummaryDao.getBetween(any(), any()) } returns emptyList()

        val insights = generator.generate(session())

        insights.map { it.category } shouldNotContain InsightCategory.EXPLORATION
        coVerify(exactly = 1) { dailySummaryDao.getBetween(any(), any()) }
    }

    @Test
    fun `adds comparison insight when session beats recent average`() = runTest(dispatcher) {
        coEvery { dailySummaryDao.getBetween(any(), any()) } returns listOf(
            summary(distanceM = 4_000f, tripCount = 2),
            summary(distanceM = 3_000f, tripCount = 2),
        )

        val insights = generator.generate(session(distanceInM = 3_000f))

        insights.map { it.category } shouldContain InsightCategory.COMPARISON
    }

    @Test
    fun `skips comparison insight without historical trips`() = runTest(dispatcher) {
        coEvery { dailySummaryDao.getBetween(any(), any()) } returns listOf(summary(distanceM = 0f, tripCount = 0))

        val insights = generator.generate(session(distanceInM = 3_000f))

        insights.map { it.category } shouldNotContain InsightCategory.COMPARISON
    }

    @Test
    fun `returns only supported bounded insights`() = runTest(dispatcher) {
        coEvery { dailySummaryDao.getBetween(any(), any()) } returns listOf(
            summary(distanceM = 4_000f, tripCount = 2),
            summary(distanceM = 3_000f, tripCount = 2),
        )

        val insights = generator.generate(session(steps = 5_000, distanceInM = 6_000f))

        insights.size shouldBe 3
    }

    private fun session(
        startMs: Long = LocalDate.now().minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        endMs: Long = startMs + 45 * 60_000L,
        distanceInM: Float = 1_500f,
        steps: Int = 1_000,
    ) = TrackerSessionSnapshot(
        id = 1L,
        start = startMs,
        end = endMs,
        isUserInitiated = true,
        collections = 10,
        distanceInM = distanceInM,
        steps = steps,
    )

    private fun summary(
        distanceM: Float,
        tripCount: Int,
    ) = DailySummaryEntity(
        dateEpochDay = LocalDate.now().minusDays(2).toEpochDay(),
        totalDistanceM = distanceM,
        totalSteps = 0,
        totalDurationMs = 0,
        tripCount = tripCount,
        activeTrackingMs = 0,
        lastUpdatedMs = 0,
        createdAt = 0,
    )
}
