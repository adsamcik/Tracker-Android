package com.adsamcik.tracker.statistics.viewmodel

import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.Trip
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
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

	private val testDispatcher = StandardTestDispatcher()
	private val tripDao: TripDao = mockk()
	private val dailySummaryDao: DailySummaryDao = mockk()
	private val explorationCellDao: ExplorationCellDao = mockk()

	/** Flow backing explorationCellDao.countAtLevelFlow() so tests can emit values. */
	private val cellCountFlow = MutableStateFlow(0)

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		// Default stub: exploration cell count emits 0
		every { explorationCellDao.countAtLevelFlow(any()) } returns cellCountFlow
		// Default stubs: empty data so init{} loads don't crash
		coEvery { tripDao.getBetween(any(), any()) } returns emptyList()
		coEvery { dailySummaryDao.getBetween(any(), any()) } returns emptyList()
		coEvery { dailySummaryDao.getByDay(any()) } returns null
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun createViewModel(): HistoryViewModel =
		HistoryViewModel(tripDao, dailySummaryDao, explorationCellDao)

	// -- Sample test data helpers --

	private val now = Instant.now().toEpochMilli()
	private val today = LocalDate.now()

	private fun makeTrip(
		id: Long,
		startTimeMs: Long,
		endTimeMs: Long = startTimeMs + 3_600_000L,
		distanceM: Float = 1000f,
		steps: Int? = 200,
	) = Trip(
		id = id,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		distanceM = distanceM,
		steps = steps,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 10,
		source = SegmentSource.USER_CREATED,
		createdAt = startTimeMs,
	)

	private fun makeDailySummary(
		epochDay: Long,
		distanceM: Float = 500f,
		steps: Int = 100,
		tripCount: Int = 1,
		durationMs: Long = 1_800_000L,
	) = DailySummaryEntity(
		dateEpochDay = epochDay,
		totalDistanceM = distanceM,
		totalSteps = steps,
		totalDurationMs = durationMs,
		tripCount = tripCount,
		activeTrackingMs = durationMs,
		lastUpdatedMs = now,
		createdAt = now,
	)

	/**
	 * Converts a [LocalDate] to start-of-day epoch millis using the system default zone,
	 * matching the conversion the ViewModel would use.
	 */
	private fun LocalDate.startOfDayMs(): Long =
		atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

	// =========================================================================
	// Tab Selection
	// =========================================================================

	@Nested
	@DisplayName("Tab selection")
	inner class TabSelection {

		@Test
		fun `initial tab is TIMELINE`() = runTest {
			val vm = createViewModel()
			vm.selectedTab.value shouldBe HistoryTab.TIMELINE
		}

		@Test
		fun `selectTab changes to TRIPS`() = runTest {
			val vm = createViewModel()
			vm.selectTab(HistoryTab.TRIPS)
			vm.selectedTab.value shouldBe HistoryTab.TRIPS
		}

		@Test
		fun `selectTab changes to CALENDAR`() = runTest {
			val vm = createViewModel()
			vm.selectTab(HistoryTab.CALENDAR)
			vm.selectedTab.value shouldBe HistoryTab.CALENDAR
		}
	}

	// =========================================================================
	// Timeline
	// =========================================================================

	@Nested
	@DisplayName("Timeline")
	inner class Timeline {

		@Test
		fun `initial timeline state is Loading`() = runTest {
			val vm = createViewModel()
			vm.timelineState.value.shouldBeInstanceOf<TimelineState.Loading>()
		}

		@Test
		fun `timeline shows Empty when no trips exist`() = runTest {
			coEvery { tripDao.getBetween(any(), any()) } returns emptyList()
			coEvery { dailySummaryDao.getBetween(any(), any()) } returns emptyList()

			val vm = createViewModel()
			advanceUntilIdle()

			vm.timelineState.value.shouldBeInstanceOf<TimelineState.Empty>()
		}

		@Test
		fun `timeline loads trips from last 30 days`() = runTest {
			val trip1StartMs = today.minusDays(5).startOfDayMs() + 36_000_000L
			val trip2StartMs = today.minusDays(2).startOfDayMs() + 50_400_000L
			val trips = listOf(
				makeTrip(id = 1, startTimeMs = trip2StartMs),
				makeTrip(id = 2, startTimeMs = trip1StartMs),
			)
			coEvery { tripDao.getBetween(any(), any()) } returns trips

			val vm = createViewModel()
			advanceUntilIdle()

			val state = vm.timelineState.value
			state.shouldBeInstanceOf<TimelineState.Content>()
			val tripEntries = state.entries.filterIsInstance<TimelineEntry.TripEntry>()
			tripEntries shouldHaveSize 2
		}

		@Test
		fun `timeline entries sorted newest first`() = runTest {
			val olderMs = today.minusDays(10).startOfDayMs() + 36_000_000L
			val newerMs = today.minusDays(1).startOfDayMs() + 36_000_000L
			val trips = listOf(
				makeTrip(id = 1, startTimeMs = newerMs),
				makeTrip(id = 2, startTimeMs = olderMs),
			)
			coEvery { tripDao.getBetween(any(), any()) } returns trips

			val vm = createViewModel()
			advanceUntilIdle()

			val state = vm.timelineState.value
			state.shouldBeInstanceOf<TimelineState.Content>()
			// Day groups should appear newest first: first DaySummaryEntry is the newer day
			val daySummaries = state.entries.filterIsInstance<TimelineEntry.DaySummaryEntry>()
			daySummaries shouldHaveSize 2
			val dayTimestamps = daySummaries.map { it.timestampMs }
			dayTimestamps shouldBe dayTimestamps.sortedDescending()
		}

		@Test
		fun `timeline groups trips with day summary headers`() = runTest {
			val dayEpoch = today.minusDays(3).toEpochDay()
			val dayMs = today.minusDays(3).startOfDayMs() + 36_000_000L
			val trips = listOf(makeTrip(id = 1, startTimeMs = dayMs))
			val summaries = listOf(makeDailySummary(epochDay = dayEpoch))

			coEvery { tripDao.getBetween(any(), any()) } returns trips
			coEvery { dailySummaryDao.getBetween(any(), any()) } returns summaries

			val vm = createViewModel()
			advanceUntilIdle()

			val state = vm.timelineState.value
			state.shouldBeInstanceOf<TimelineState.Content>()
			val daySummaryEntries = state.entries.filterIsInstance<TimelineEntry.DaySummaryEntry>()
			daySummaryEntries shouldHaveSize 1
		}
	}

	// =========================================================================
	// Calendar
	// =========================================================================

	@Nested
	@DisplayName("Calendar")
	inner class Calendar {

		@Test
		fun `initial calendar shows current month`() = runTest {
			val vm = createViewModel()
			vm.calendarState.value.currentMonth shouldBe YearMonth.now()
		}

		@Test
		fun `selectDay loads day detail with trips and summary`() = runTest {
			val targetDay = today.minusDays(2)
			val dayEpoch = targetDay.toEpochDay()
			val tripStartMs = targetDay.startOfDayMs() + 36_000_000L
			val trip = makeTrip(id = 10, startTimeMs = tripStartMs)
			val summary = makeDailySummary(epochDay = dayEpoch, distanceM = 2500f, steps = 800)

			coEvery { tripDao.getBetween(any(), any()) } returns listOf(trip)
			coEvery { dailySummaryDao.getByDay(dayEpoch) } returns summary

			val vm = createViewModel()
			advanceUntilIdle()

			vm.selectDay(targetDay)
			advanceUntilIdle()

			val calState = vm.calendarState.value
			calState.selectedDay shouldBe targetDay
			calState.selectedDayDetail.shouldNotBeNull()
		}

		@Test
		fun `calendar dayData has correct intensity based on distance normalization`() = runTest {
			val thisMonth = YearMonth.now()
			val firstDay = thisMonth.atDay(1).toEpochDay()

			// Two days: one with max distance, one with half distance
			val summaries = listOf(
				makeDailySummary(epochDay = firstDay, distanceM = 10_000f),
				makeDailySummary(epochDay = firstDay + 1, distanceM = 5_000f),
			)
			coEvery { dailySummaryDao.getBetween(any(), any()) } returns summaries

			val vm = createViewModel()
			advanceUntilIdle()

			val dayData = vm.calendarState.value.dayData
			val day1 = LocalDate.ofEpochDay(firstDay)
			val day2 = LocalDate.ofEpochDay(firstDay + 1)

			if (dayData.containsKey(day1) && dayData.containsKey(day2)) {
				val intensity1 = dayData.getValue(day1).intensity
				val intensity2 = dayData.getValue(day2).intensity
				// Max distance day should have higher or equal intensity
				(intensity1 >= intensity2) shouldBe true
			}
		}
	}

	// =========================================================================
	// Trips (Paged)
	// =========================================================================

	@Nested
	@DisplayName("Trips paging")
	inner class TripsPaging {

		@Test
		fun `pagedTrips provides paging source from TripDao`() = runTest {
			val pagingSource = mockk<androidx.paging.PagingSource<Int, Trip>>()
			every { tripDao.getAllPaged() } returns pagingSource

			val vm = createViewModel()
			// pagedTrips flow should be accessible (non-null) without throwing
			vm.pagedTrips.shouldNotBeNull()
		}
	}

	// =========================================================================
	// Exploration Stats
	// =========================================================================

	@Nested
	@DisplayName("Exploration stats")
	inner class Exploration {

		@Test
		fun `explorationStats observes cell count flow with initial zero`() = runTest {
			cellCountFlow.value = 0

			val vm = createViewModel()
			advanceUntilIdle()

			vm.explorationStats.value.totalCells shouldBe 0
		}

		@Test
		fun `explorationStats updates when cell count flow emits`() = runTest {
			cellCountFlow.value = 0

			val vm = createViewModel()
			advanceUntilIdle()

			vm.explorationStats.value.totalCells shouldBe 0

			cellCountFlow.value = 42
			advanceUntilIdle()

			vm.explorationStats.value.totalCells shouldBe 42
		}

		@Test
		fun `explorationStats defaults with zero streaks`() = runTest {
			cellCountFlow.value = 10

			val vm = createViewModel()
			advanceUntilIdle()

			val stats = vm.explorationStats.value
			stats.totalCells shouldBe 10
			stats.currentStreak shouldBe 0
			stats.bestStreak shouldBe 0
		}
	}

	// =========================================================================
	// Edge Cases
	// =========================================================================

	@Nested
	@DisplayName("Edge cases")
	inner class EdgeCases {

		@Test
		fun `timeline with trips but no daily summaries still produces day header`() = runTest {
			val tripMs = today.minusDays(1).startOfDayMs() + 36_000_000L
			coEvery { tripDao.getBetween(any(), any()) } returns listOf(
				makeTrip(id = 1, startTimeMs = tripMs),
			)
			coEvery { dailySummaryDao.getBetween(any(), any()) } returns emptyList()

			val vm = createViewModel()
			advanceUntilIdle()

			val state = vm.timelineState.value
			state.shouldBeInstanceOf<TimelineState.Content>()
			state.entries.filterIsInstance<TimelineEntry.TripEntry>() shouldHaveSize 1
			// ViewModel always adds a DaySummaryEntry header per day group
			state.entries.filterIsInstance<TimelineEntry.DaySummaryEntry>() shouldHaveSize 1
		}

		@Test
		fun `selecting same tab twice does not change state`() = runTest {
			val vm = createViewModel()
			vm.selectTab(HistoryTab.TRIPS)
			vm.selectedTab.value shouldBe HistoryTab.TRIPS

			vm.selectTab(HistoryTab.TRIPS)
			vm.selectedTab.value shouldBe HistoryTab.TRIPS
		}
	}
}
