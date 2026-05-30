package com.adsamcik.tracker.statistics.presenter

import androidx.paging.PagingSource
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.stats.api.repository.DailySummary
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.repository.ExplorationRepository
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.statistics.viewmodel.CalendarDayData
import com.adsamcik.tracker.statistics.viewmodel.CalendarState
import com.adsamcik.tracker.statistics.viewmodel.HistoryTab
import com.adsamcik.tracker.statistics.viewmodel.TimelineState
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
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
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("HistoryPresenterViewModel")
class HistoryPresenterViewModelTest {

	private val testDispatcher = StandardTestDispatcher()

	private val tripPresentationRepository: TripPresentationRepository = mockk(relaxed = true)
	private val dailySummaryRepository: DailySummaryRepository = mockk()
	private val explorationRepository: ExplorationRepository = mockk()

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		// Default: empty flows and results
		every { explorationRepository.observeCellCount(any()) } returns flowOf(0)
		every { dailySummaryRepository.observeBetween(any(), any()) } returns flowOf(emptyList())
		coEvery { tripPresentationRepository.getTripsBetween(any(), any()) } returns emptyList()
		every { tripPresentationRepository.getPagedTrips() } returns mockk<PagingSource<Int, Trip>>()
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun createViewModel() = HistoryPresenterViewModel(
		tripPresentationRepository = tripPresentationRepository,
		dailySummaryRepository = dailySummaryRepository,
		explorationRepository = explorationRepository,
	)

	@Nested
	@DisplayName("Tab selection")
	inner class TabSelectionTest {
		@Test
		fun `default tab is TIMELINE`() = runTest {
			val vm = createViewModel()
			advanceUntilIdle()
			vm.selectedTab.value shouldBe HistoryTab.TIMELINE
		}

		@Test
		fun `selectTab updates selectedTab`() = runTest {
			val vm = createViewModel()
			advanceUntilIdle()

			vm.selectTab(HistoryTab.CALENDAR)
			vm.selectedTab.value shouldBe HistoryTab.CALENDAR

			vm.selectTab(HistoryTab.TRIPS)
			vm.selectedTab.value shouldBe HistoryTab.TRIPS
		}
	}

	@Nested
	@DisplayName("Timeline loading")
	inner class TimelineTest {
		@Test
		fun `empty trips yields TimelineState Empty`() = runTest {
			coEvery { tripPresentationRepository.getTripsBetween(any(), any()) } returns emptyList()

			val vm = createViewModel()
			advanceUntilIdle()

			vm.timelineState.value shouldBe TimelineState.Empty
		}

		@Test
		fun `trips produce Content with day summary and trip entries`() = runTest {
			val now = System.currentTimeMillis()
			val today = LocalDate.now()
			val todayStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
			val trip = makeTrip(id = 1, startTimeMs = todayStart + 3600_000, endTimeMs = todayStart + 7200_000, distanceM = 1500f)

			coEvery { tripPresentationRepository.getTripsBetween(any(), any()) } returns listOf(trip)
			every { dailySummaryRepository.observeBetween(any(), any()) } returns flowOf(
				listOf(
					DailySummary(
						dayEpoch = today.toEpochDay(),
						totalDistance = DistanceM(1500f),
						totalSteps = StepCount(2000),
						totalDuration = DurationMs(3600_000L),
						tripCount = 1,
						activeTrackingDuration = DurationMs(3600_000L),
					),
				),
			)

			val vm = createViewModel()
			advanceUntilIdle()

			val state = vm.timelineState.value
			state.shouldBeInstanceOf<TimelineState.Content>()
			// 1 day summary + 1 trip entry
			state.entries shouldHaveSize 2
		}

		@Test
		fun `timeline only loads trips from last 30 days`() = runTest {
			val vm = createViewModel()
			advanceUntilIdle()

			coVerify {
				tripPresentationRepository.getTripsBetween(
					match { fromMs ->
						val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
						val now = System.currentTimeMillis()
						fromMs in (now - thirtyDaysMs - 5000)..(now - thirtyDaysMs + 5000)
					},
					any(),
				)
			}
		}
	}

	@Nested
	@DisplayName("Calendar month loading")
	inner class CalendarTest {
		@Test
		fun `loadCalendarMonth populates dayData with intensity`() = runTest {
			val month = YearMonth.of(2024, 6)
			val day1 = LocalDate.of(2024, 6, 1)
			val day2 = LocalDate.of(2024, 6, 15)

			every { dailySummaryRepository.observeBetween(day1.toEpochDay(), month.atEndOfMonth().toEpochDay()) } returns flowOf(
				listOf(
					DailySummary(
						dayEpoch = day1.toEpochDay(),
						totalDistance = DistanceM(5000f),
						totalSteps = StepCount(8000),
						totalDuration = DurationMs(3600_000L),
						tripCount = 2,
						activeTrackingDuration = DurationMs(3600_000L),
					),
					DailySummary(
						dayEpoch = day2.toEpochDay(),
						totalDistance = DistanceM(2500f),
						totalSteps = StepCount(4000),
						totalDuration = DurationMs(1800_000L),
						tripCount = 1,
						activeTrackingDuration = DurationMs(1800_000L),
					),
				),
			)

			val vm = createViewModel()
			advanceUntilIdle()

			// Navigate to the target month
			vm.selectDay(day1)
			advanceUntilIdle()

			val calState = vm.calendarState.value
			calState.currentMonth shouldBe month
			val data1 = calState.dayData[day1]!!
			val data2 = calState.dayData[day2]!!
			// day1 has max distance so intensity = 1.0
			data1.intensity shouldBe 1.0f
			// day2 intensity = 2500/5000 = 0.5
			data2.intensity shouldBe 0.5f
			data1.tripCount shouldBe 2
			data2.tripCount shouldBe 1
		}
	}

	@Nested
	@DisplayName("Delete state management")
	inner class DeleteTest {
		@Test
		fun `requestDeleteTrip adds tripId to pendingDeletes`() = runTest {
			val vm = createViewModel()
			advanceUntilIdle()

			vm.requestDeleteTrip(42L)
			vm.pendingDeletes.value shouldBe setOf(42L)
		}

		@Test
		fun `undoDeleteTrip removes tripId from pendingDeletes`() = runTest {
			val vm = createViewModel()
			advanceUntilIdle()

			vm.requestDeleteTrip(42L)
			vm.requestDeleteTrip(99L)
			vm.undoDeleteTrip(42L)

			vm.pendingDeletes.value shouldBe setOf(99L)
		}

		@Test
		fun `confirmDeleteTrip removes from pendingDeletes and calls repository`() = runTest {
			coEvery { tripPresentationRepository.deleteTrip(any()) } returns Unit

			val vm = createViewModel()
			advanceUntilIdle()

			vm.requestDeleteTrip(42L)
			vm.confirmDeleteTrip(42L)
			advanceUntilIdle()

			vm.pendingDeletes.value shouldBe emptySet()
			coVerify { tripPresentationRepository.deleteTrip(42L) }
		}

		@Test
		fun `multiple pending deletes accumulate`() = runTest {
			val vm = createViewModel()
			advanceUntilIdle()

			vm.requestDeleteTrip(1L)
			vm.requestDeleteTrip(2L)
			vm.requestDeleteTrip(3L)

			vm.pendingDeletes.value shouldBe setOf(1L, 2L, 3L)
		}
	}

	@Nested
	@DisplayName("Exploration stats")
	inner class ExplorationTest {
		@Test
		fun `explorationStats maps cell count from repository`() = runTest {
			val cellCountFlow = MutableStateFlow(0)
			every { explorationRepository.observeCellCount(14) } returns cellCountFlow

			val vm = createViewModel()
			// explorationStats uses SharingStarted.WhileSubscribed; keep upstream active
			// for the lifetime of the test by collecting on backgroundScope.
			vm.explorationStats.launchIn(backgroundScope)
			advanceUntilIdle()

			vm.explorationStats.value.totalCells shouldBe 0

			cellCountFlow.value = 42
			advanceUntilIdle()

			vm.explorationStats.value.totalCells shouldBe 42
		}
	}

	companion object {
		fun makeTrip(
			id: Long = 1L,
			startTimeMs: Long = 1_700_000_000_000L,
			endTimeMs: Long = 1_700_003_600_000L,
			distanceM: Float = 1000f,
			steps: Int? = 500,
			primaryActivity: Int? = 7,
		) = Trip(
			id = id,
			startTimeMs = startTimeMs,
			endTimeMs = endTimeMs,
			distanceM = distanceM,
			steps = steps,
			primaryActivity = primaryActivity,
			activityConfidence = 90,
			sampleCount = 100,
			source = SegmentSource.USER_CREATED,
			createdAt = startTimeMs,
		)
	}
}
