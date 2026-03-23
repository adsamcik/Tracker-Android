package com.adsamcik.tracker.tracker.insights

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
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
    private val explorationCellDao = mockk<ExplorationCellDao>()
    private val dailySummaryDaoProvider = mockk<Provider<DailySummaryDao>>()
    private val explorationCellDaoProvider = mockk<Provider<ExplorationCellDao>>()
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
        every { explorationCellDaoProvider.get() } returns explorationCellDao
        generator = SessionInsightsGenerator(
            context = context,
            dailySummaryDaoProvider = dailySummaryDaoProvider,
            explorationCellDaoProvider = explorationCellDaoProvider,
            dispatchers = TestDispatchersProvider(dispatcher),
        )
    }

    @Test
    fun `adds achievement and fun fact for strong walking session`() = runTest(dispatcher) {
        coEvery { explorationCellDao.countDiscoveredSince(any(), any()) } returns 0
        coEvery { dailySummaryDao.getBetween(any(), any()) } returns emptyList()

        val insights = generator.generate(session(steps = 4_500, distanceInM = 2_500f))

        insights.map { it.category } shouldContain InsightCategory.ACHIEVEMENT
        insights.map { it.category } shouldContain InsightCategory.FUN_FACT
    }

    @Test
    fun `adds exploration insight when new cells discovered`() = runTest(dispatcher) {
        coEvery { explorationCellDao.countDiscoveredSince(any(), any()) } returns 3
        coEvery { dailySummaryDao.getBetween(any(), any()) } returns emptyList()

        val insights = generator.generate(session())

        insights.map { it.category } shouldContain InsightCategory.EXPLORATION
    }

    @Test
    fun `adds comparison insight when session beats recent average`() = runTest(dispatcher) {
        coEvery { explorationCellDao.countDiscoveredSince(any(), any()) } returns 0
        coEvery { dailySummaryDao.getBetween(any(), any()) } returns listOf(
            summary(distanceM = 4_000f, tripCount = 2),
            summary(distanceM = 3_000f, tripCount = 2),
        )

        val insights = generator.generate(session(distanceInM = 3_000f))

        insights.map { it.category } shouldContain InsightCategory.COMPARISON
    }

    @Test
    fun `skips comparison insight without historical trips`() = runTest(dispatcher) {
        coEvery { explorationCellDao.countDiscoveredSince(any(), any()) } returns 0
        coEvery { dailySummaryDao.getBetween(any(), any()) } returns listOf(summary(distanceM = 0f, tripCount = 0))

        val insights = generator.generate(session(distanceInM = 3_000f))

        insights.map { it.category } shouldNotContain InsightCategory.COMPARISON
    }

    @Test
    fun `returns at most four insights`() = runTest(dispatcher) {
        coEvery { explorationCellDao.countDiscoveredSince(any(), any()) } returns 5
        coEvery { dailySummaryDao.getBetween(any(), any()) } returns listOf(
            summary(distanceM = 4_000f, tripCount = 2),
            summary(distanceM = 3_000f, tripCount = 2),
        )

        val insights = generator.generate(session(steps = 5_000, distanceInM = 6_000f))

        insights.size shouldBe 4
    }

    private fun session(
        startMs: Long = LocalDate.now().minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        endMs: Long = startMs + 45 * 60_000L,
        distanceInM: Float = 1_500f,
        steps: Int = 1_000,
    ) = TrackerSession(
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
