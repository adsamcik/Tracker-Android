package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.adsamcik.tracker.stats.api.repository.PagedTrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.CalendarHistoryPresenceRead
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode
import com.adsamcik.tracker.stats.api.repository.HistoryPagingItem
import com.adsamcik.tracker.stats.api.repository.HistoryContinuation
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.HistoryRangeRead
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import com.adsamcik.tracker.stats.api.repository.WifiObservedNetwork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.repository.ExplorationRepository
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.statistics.viewmodel.HistoryTab
import io.kotest.matchers.shouldBe
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
	private val trackingHistoryRepository = RecordingHistoryPages()
	private val explorationRepository: ExplorationRepository = mockk()

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		// Default: empty flows and results
		every { explorationRepository.observeCellCount(any()) } returns flowOf(0)
		coEvery { tripPresentationRepository.getTripsBetween(any(), any()) } returns emptyList()
		every { tripPresentationRepository.getPagedTrips() } returns mockk<PagingSource<Int, Trip>>()
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun createViewModel(
		savedStateHandle: SavedStateHandle = SavedStateHandle(),
	) = HistoryPresenterViewModel(
		tripPresentationRepository = tripPresentationRepository,
		trackingHistoryRepository = trackingHistoryRepository,
		explorationRepository = explorationRepository,
		savedStateHandle = savedStateHandle,
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

		@Test
		fun `selected tab and calendar state survive recreation`() = runTest {
			val savedStateHandle = SavedStateHandle()
			val selectedDay = LocalDate.of(2024, 6, 15)
			val firstViewModel = createViewModel(savedStateHandle)
			advanceUntilIdle()

			firstViewModel.selectTab(HistoryTab.CALENDAR)
			firstViewModel.selectDay(selectedDay)
			advanceUntilIdle()

			val recreatedViewModel = createViewModel(savedStateHandle)
			advanceUntilIdle()

			recreatedViewModel.selectedTab.value shouldBe HistoryTab.CALENDAR
			recreatedViewModel.calendarState.value.currentMonth shouldBe YearMonth.of(2024, 6)
			recreatedViewModel.calendarState.value.selectedDay shouldBe selectedDay
		}

		@Test
		fun `restored Timeline selection does not observe its saved calendar day`() = runTest {
			val selectedDay = LocalDate.of(2024, 6, 15)
			val savedStateHandle = SavedStateHandle(
				mapOf(
					"history_selected_tab" to HistoryTab.TIMELINE.name,
					"history_calendar_month" to YearMonth.from(selectedDay).toString(),
					"history_calendar_selected_day" to selectedDay.toEpochDay(),
				),
			)

			val vm = createViewModel(savedStateHandle)
			advanceUntilIdle()

			vm.selectedTab.value shouldBe HistoryTab.TIMELINE
			vm.calendarState.value.selectedDay shouldBe selectedDay
			coVerify(exactly = 0) { tripPresentationRepository.getTripsBetween(any(), any()) }
		}
	}

	@Nested
	@DisplayName("Measured observation paging")
	inner class ObservationsTest {
		@Test fun `Timeline requests all six sources without a trip prerequisite or age cutoff`() = runTest {
			val vm = createViewModel()
			backgroundScope.launch { vm.timelineHistory.collect {} }
			testScheduler.runCurrent()
			val range = trackingHistoryRepository.ranges.single()
			range.mode shouldBe HistoryBrowseMode.OBSERVATIONS
			range.fromInclusiveMs shouldBe 0L
			range.toExclusiveMs shouldBe Long.MAX_VALUE
			range.sources shouldBe HistorySource.entries.toSet()
			coVerify(exactly = 0) { tripPresentationRepository.getTripsBetween(any(), any()) }
		}

		@Test fun `Calendar uses the same source list and the actual 23-hour selected day`() = runTest {
			val vm = createViewModel()
			vm.useViewZone(ZoneId.of("Europe/Prague"))
			vm.selectDay(LocalDate.of(2026, 3, 29))
			backgroundScope.launch { vm.calendarHistory.collect {} }
			testScheduler.runCurrent()
			val range = trackingHistoryRepository.ranges.single()
			range.mode shouldBe HistoryBrowseMode.OBSERVATIONS
			range.sources shouldBe HistorySource.entries.toSet()
			range.toExclusiveMs - range.fromInclusiveMs shouldBe 23L * 60L * 60L * 1_000L
			range.viewZoneId shouldBe "Europe/Prague"
		}

		@Test fun `changing the selected day creates a distinct observation paging generation`() = runTest {
			val vm = createViewModel()
			val day = LocalDate.of(2026, 3, 29)
			vm.useViewZone(ZoneId.of("UTC"))
			vm.selectDay(day)
			backgroundScope.launch { vm.calendarHistory.collect {} }
			testScheduler.runCurrent()
			val first = trackingHistoryRepository.sources.single()
			vm.selectDay(day.plusDays(1L))
			testScheduler.runCurrent()
			trackingHistoryRepository.ranges.size shouldBe 2
			trackingHistoryRepository.ranges.last().fromInclusiveMs shouldBe day.plusDays(1L)
				.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
			(trackingHistoryRepository.sources.last() !== first) shouldBe true
		}

		@Test fun `month navigation clears the observation day instead of displaying stale rows`() = runTest {
			val vm = createViewModel()
			vm.selectDay(LocalDate.of(2026, 3, 29))
			backgroundScope.launch { vm.calendarHistory.collect {} }
			testScheduler.runCurrent()
			vm.selectMonth(YearMonth.of(2026, 4))
			testScheduler.runCurrent()
			vm.calendarState.value.selectedDay shouldBe null
			trackingHistoryRepository.ranges.size shouldBe 1
			vm.calendarState.value.selectedDayDetail shouldBe null
		}

		@Test fun `changing the displayed zone rebuilds the selected-day query`() = runTest {
			val vm = createViewModel()
			vm.useViewZone(ZoneId.of("UTC"))
			vm.selectDay(LocalDate.of(2026, 3, 29))
			backgroundScope.launch { vm.calendarHistory.collect {} }
			testScheduler.runCurrent()
			vm.useViewZone(ZoneId.of("Europe/Prague"))
			testScheduler.runCurrent()
			trackingHistoryRepository.ranges.map { it.viewZoneId } shouldBe listOf("UTC", "Europe/Prague")
		}
	}

    @Nested
    @DisplayName("Calendar month loading")
    inner class CalendarTest {
        @Test fun `Calendar consumes one qualified presence snapshot without per-day UI reads`() = runTest {
            val month = YearMonth.of(2026, 3)
            val dates = setOf(month.atDay(1), month.atDay(15))
            val problem = HistoryProblem(HistorySource.CELL, HistoryProblemReason.STORAGE_UNAVAILABLE)
            trackingHistoryRepository.calendar.value = CalendarHistoryPresenceRead.Snapshot(
                dates.map { it.toEpochDay() }.toSet(), TrackingHistoryReadSnapshot(0, 1), setOf(problem))
            val vm = createViewModel()
            vm.useViewZone(ZoneId.of("UTC")); vm.selectMonth(month); vm.selectTab(HistoryTab.CALENDAR)
            advanceUntilIdle()
            vm.calendarState.value.dayData.keys shouldBe dates
            vm.calendarState.value.monthProblems shouldBe setOf(problem)
            vm.calendarState.value.monthLoading shouldBe false
            trackingHistoryRepository.monthRequests.single().range.sources shouldBe HistorySource.entries.toSet()
            trackingHistoryRepository.dayReads shouldBe emptyList()
            coVerify(exactly = 0) { tripPresentationRepository.getTripsBetween(any(), any()) }
        }

        @Test fun `same-epoch refresh keeps dots and destructive epoch change clears them while loading`() = runTest {
            val month = YearMonth.of(2026, 3)
            val date = month.atDay(29)
            trackingHistoryRepository.calendar.value = CalendarHistoryPresenceRead.Snapshot(
                setOf(date.toEpochDay()), TrackingHistoryReadSnapshot(0, 1))
            val vm = createViewModel()
            vm.useViewZone(ZoneId.of("Europe/Prague")); vm.selectMonth(month); vm.selectTab(HistoryTab.CALENDAR)
            advanceUntilIdle()
            trackingHistoryRepository.calendar.value = CalendarHistoryPresenceRead.Loading(TrackingHistoryReadSnapshot(0, 2))
            testScheduler.runCurrent()
            vm.calendarState.value.dayData.keys shouldBe setOf(date)
            vm.calendarState.value.monthLoading shouldBe true
            trackingHistoryRepository.calendar.value = CalendarHistoryPresenceRead.Loading(TrackingHistoryReadSnapshot(1, 3))
            testScheduler.runCurrent()
            vm.calendarState.value.dayData shouldBe emptyMap()
            trackingHistoryRepository.calendar.value = CalendarHistoryPresenceRead.Snapshot(emptySet(), TrackingHistoryReadSnapshot(1, 3))
            testScheduler.runCurrent()
            vm.calendarState.value.monthLoading shouldBe false
        }

        @Test fun `Retry restarts the visible month and selected-day changes do not`() = runTest {
            val month = YearMonth.of(2026, 3)
            trackingHistoryRepository.calendar.value = CalendarHistoryPresenceRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE)
            val vm = createViewModel()
            vm.selectMonth(month); vm.selectTab(HistoryTab.CALENDAR)
            advanceUntilIdle()
            vm.calendarState.value.monthProblems shouldBe setOf(HistoryProblem(null, HistoryProblemReason.STORAGE_UNAVAILABLE))
            vm.selectDay(month.atDay(10)); vm.selectDay(month.atDay(11))
            advanceUntilIdle()
            trackingHistoryRepository.monthRequests.size shouldBe 1
            trackingHistoryRepository.calendar.value = CalendarHistoryPresenceRead.Snapshot(emptySet(), TrackingHistoryReadSnapshot(0, 1))
            vm.retryCalendarMonth(); advanceUntilIdle()
            trackingHistoryRepository.monthRequests.size shouldBe 2
            vm.calendarState.value.monthProblems shouldBe emptySet()
            vm.calendarState.value.selectedDay shouldBe month.atDay(11)
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

private class RecordingHistoryPages : PagedTrackingHistoryRepository by mockk<PagedTrackingHistoryRepository>(relaxed = true) {
    val revision = MutableStateFlow(0L)
    val dayReads = mutableListOf<HistoryRangeRequest>()
    val monthRequests = mutableListOf<HistoryRangeRequest>()
    val calendar = MutableStateFlow<CalendarHistoryPresenceRead>(
        CalendarHistoryPresenceRead.Snapshot(emptySet(), TrackingHistoryReadSnapshot(0, 1)))
    override fun observeCalendarPresence(range: HistoryRange) = kotlinx.coroutines.flow.flow {
        monthRequests += HistoryRangeRequest(range, 1)
        calendar.collect { emit(it) }
    }
    var readPage: suspend (HistoryRangeRequest) -> HistoryRangeRead = { page(it) }
    fun page(request: HistoryRangeRequest, rows: List<UnifiedHistoryRow> = emptyList(), next: HistoryContinuation? = null,
        problems: Set<HistoryProblem> = emptySet()) = HistoryRangeRead.Page(rows, TrackingHistoryReadSnapshot(revision.value, 1L), next, problems)
        .also { require(next == null || next.range == request.range) }
    override suspend fun readRange(request: HistoryRangeRequest): HistoryRangeRead { dayReads += request; return readPage(request) }
    override fun observeRange(request: HistoryRangeRequest) = revision.map {
        monthRequests += request
        page(request)
    }
	val ranges = mutableListOf<HistoryRange>()
	val sources = mutableListOf<PagingSource<HistoryContinuation, HistoryPagingItem>>()
	override fun pagingSource(range: HistoryRange): PagingSource<HistoryContinuation, HistoryPagingItem> {
		ranges += range
		return object : PagingSource<HistoryContinuation, HistoryPagingItem>() {
			override suspend fun load(params: LoadParams<HistoryContinuation>): LoadResult<HistoryContinuation, HistoryPagingItem> =
				LoadResult.Page(emptyList(), null, null)
			override fun getRefreshKey(state: PagingState<HistoryContinuation, HistoryPagingItem>): HistoryContinuation? = null
		}.also(sources::add)
	}
}

private fun measuredWifi(time: Long) = UnifiedHistoryRow(HistoryPosition(time, "local:ambient_wifi", "wifi:$time"), null,
    setOf(HistoryOrigin.LOCAL), HistoryPurpose.BACKGROUND, sections = emptyList(),
    measurement = HistoryMeasurement.Wifi(WifiObservedNetwork(time, 0, "ap", "Cafe", 2412, -45)))
