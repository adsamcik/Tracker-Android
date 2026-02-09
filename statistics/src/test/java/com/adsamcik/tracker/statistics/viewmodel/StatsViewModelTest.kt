package com.adsamcik.tracker.statistics.viewmodel

import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.detail.StatisticDisplayType
import com.adsamcik.tracker.statistics.repository.SessionRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val tripDao: TripDao = mockk()
    private val sessionRepository: SessionRepository = mockk()
    private val dailySummaryDao: DailySummaryDao = mockk()

    private val now = System.currentTimeMillis()
    private val today = LocalDate.now()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Default stubs
        coEvery { dailySummaryDao.getBetween(any(), any()) } returns emptyList()
        coEvery { sessionRepository.getSummaryStats() } returns emptyList()
        coEvery { sessionRepository.getWeeklyStats() } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): StatsViewModel =
        StatsViewModel(tripDao, sessionRepository, dailySummaryDao)

    private fun makeDailySummary(
        epochDay: Long,
        distanceM: Float = 500f,
        steps: Int = 100,
    ) = DailySummaryEntity(
        dateEpochDay = epochDay,
        totalDistanceM = distanceM,
        totalSteps = steps,
        totalDurationMs = 1_800_000L,
        tripCount = 1,
        activeTrackingMs = 1_800_000L,
        lastUpdatedMs = now,
        createdAt = now,
    )

    // =========================================================================
    // Paging
    // =========================================================================

    @Nested
    @DisplayName("Trips paging")
    inner class TripsPaging {

        @Test
        fun `tripsFlow provides paging source from TripDao`() = runTest {
            val pagingSource = mockk<androidx.paging.PagingSource<Int, Trip>>()
            every { tripDao.getAllPaged() } returns pagingSource

            val vm = createViewModel()
            vm.tripsFlow.shouldNotBeNull()
        }
    }

    // =========================================================================
    // Weekly Bars
    // =========================================================================

    @Nested
    @DisplayName("Weekly bars")
    inner class WeeklyBars {

        @Test
        fun `weeklyBars initially empty`() = runTest {
            val vm = createViewModel()
            vm.weeklyBars.value.shouldBeEmpty()
        }

        @Test
        fun `weeklyBars loads 7 days of data`() = runTest {
            val todayEpochDay = today.toEpochDay()
            val summaries = (0L..6L).map { offset ->
                makeDailySummary(epochDay = todayEpochDay - 6 + offset, distanceM = 1000f * (offset + 1))
            }
            coEvery { dailySummaryDao.getBetween(any(), any()) } returns summaries

            val vm = createViewModel()
            advanceUntilIdle()

            vm.weeklyBars.value shouldHaveSize 7
        }

        @Test
        fun `weeklyBars shows zero when no summaries`() = runTest {
            coEvery { dailySummaryDao.getBetween(any(), any()) } returns emptyList()

            val vm = createViewModel()
            advanceUntilIdle()

            val bars = vm.weeklyBars.value
            bars shouldHaveSize 7
            bars.all { it.distanceM == 0f } shouldBe true
        }

        @Test
        fun `weeklyBars handles partial data gracefully`() = runTest {
            val todayEpochDay = today.toEpochDay()
            // Only 2 days have data
            val summaries = listOf(
                makeDailySummary(epochDay = todayEpochDay, distanceM = 2000f),
                makeDailySummary(epochDay = todayEpochDay - 3, distanceM = 500f),
            )
            coEvery { dailySummaryDao.getBetween(any(), any()) } returns summaries

            val vm = createViewModel()
            advanceUntilIdle()

            val bars = vm.weeklyBars.value
            bars shouldHaveSize 7
            // Days without data should be zero
            val nonZeroBars = bars.filter { it.distanceM > 0f }
            nonZeroBars shouldHaveSize 2
        }
    }

    // =========================================================================
    // Summary Stats
    // =========================================================================

    @Nested
    @DisplayName("Summary stats")
    inner class SummaryStats {

        @Test
        fun `initial summaryStatsState is Idle`() = runTest {
            val vm = createViewModel()
            vm.summaryStatsState.value.shouldBeInstanceOf<StatsLoadState.Idle>()
        }

        @Test
        fun `loadSummaryStats transitions to Loading then Success`() = runTest {
            val stats = listOf(Stat(0, 0, StatisticDisplayType.INFORMATION, "test"))
            coEvery { sessionRepository.getSummaryStats() } returns stats

            val vm = createViewModel()
            vm.loadSummaryStats()
            advanceUntilIdle()

            val state = vm.summaryStatsState.value
            state.shouldBeInstanceOf<StatsLoadState.Success>()
            state.stats shouldHaveSize 1
        }

        @Test
        fun `loadSummaryStats handles error`() = runTest {
            coEvery { sessionRepository.getSummaryStats() } throws RuntimeException("DB error")

            val vm = createViewModel()
            vm.loadSummaryStats()
            advanceUntilIdle()

            vm.summaryStatsState.value.shouldBeInstanceOf<StatsLoadState.Error>()
        }
    }

    // =========================================================================
    // Weekly Stats
    // =========================================================================

    @Nested
    @DisplayName("Weekly stats")
    inner class WeeklyStats {

        @Test
        fun `initial weeklyStatsState is Idle`() = runTest {
            val vm = createViewModel()
            vm.weeklyStatsState.value.shouldBeInstanceOf<StatsLoadState.Idle>()
        }

        @Test
        fun `loadWeeklyStats transitions to Loading then Success`() = runTest {
            val stats = listOf(Stat(0, 0, StatisticDisplayType.INFORMATION, "weekly"))
            coEvery { sessionRepository.getWeeklyStats() } returns stats

            val vm = createViewModel()
            vm.loadWeeklyStats()
            advanceUntilIdle()

            val state = vm.weeklyStatsState.value
            state.shouldBeInstanceOf<StatsLoadState.Success>()
            state.stats shouldHaveSize 1
        }

        @Test
        fun `loadWeeklyStats handles error`() = runTest {
            coEvery { sessionRepository.getWeeklyStats() } throws RuntimeException("DB error")

            val vm = createViewModel()
            vm.loadWeeklyStats()
            advanceUntilIdle()

            vm.weeklyStatsState.value.shouldBeInstanceOf<StatsLoadState.Error>()
        }
    }
}
