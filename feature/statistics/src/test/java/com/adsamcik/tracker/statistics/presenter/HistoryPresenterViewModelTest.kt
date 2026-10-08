package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.adsamcik.tracker.stats.api.repository.PagedTrackingHistoryRepository
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
		@Test
        fun `Calendar dots come from saved source readings with no trip or distance prerequisite`() = runTest {
            val month = YearMonth.of(2024, 6)
            val first = month.atDay(1)
            val second = month.atDay(15)
            val zone = ZoneId.of("UTC")
            val times = setOf(first, second).map { it.atStartOfDay(zone).toInstant().toEpochMilli() + 1L }
            trackingHistoryRepository.readPage = { request ->
                trackingHistoryRepository.page(request, times.filter { it in request.range.fromInclusiveMs until request.range.toExclusiveMs }
                    .map { measuredWifi(it) })
            }
            val vm = createViewModel()
            vm.useViewZone(zone)
            vm.selectMonth(month)
            vm.selectTab(HistoryTab.CALENDAR)
            advanceUntilIdle()
            vm.calendarState.value.dayData.keys shouldBe setOf(first, second)
            vm.calendarState.value.monthLoading shouldBe false
            vm.calendarState.value.monthProblems shouldBe emptySet()
            trackingHistoryRepository.dayReads.all { it.limit in 1..100 && it.range.mode == HistoryBrowseMode.OBSERVATIONS &&
                it.range.sources == HistorySource.entries.toSet() } shouldBe true
            coVerify(exactly = 0) { tripPresentationRepository.getTripsBetween(any(), any()) }
        }

        @Test fun `a rejected raw frontier stays loading and resumes until an older qualified reading`() = runTest {
            val month = YearMonth.of(2026, 3)
            val day = month.atDay(31)
            val zone = ZoneId.of("UTC")
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val release = CompletableDeferred<Unit>()
            var attempts = 0
            trackingHistoryRepository.readPage = { request ->
                if (request.range.fromInclusiveMs != from) trackingHistoryRepository.page(request)
                else {
                    attempts++
                    if (attempts < 3) trackingHistoryRepository.page(request, next = HistoryContinuation(request.range,
                        HistoryPosition(from + 1_000L - attempts, "local:ambient_wifi", "rejected-$attempts")))
                    else { release.await(); trackingHistoryRepository.page(request, listOf(measuredWifi(from + 1L))) }
                }
            }
            val vm = createViewModel()
            vm.useViewZone(zone); vm.selectMonth(month); vm.selectTab(HistoryTab.CALENDAR)
            testScheduler.runCurrent()
            vm.calendarState.value.monthLoading shouldBe true
            vm.calendarState.value.dayData.containsKey(day) shouldBe false
            attempts shouldBe 3
            release.complete(Unit)
            advanceUntilIdle()
            vm.calendarState.value.dayData.containsKey(day) shouldBe true
            vm.calendarState.value.monthLoading shouldBe false
            trackingHistoryRepository.dayReads.filter { it.range.fromInclusiveMs == from }.map { it.after?.consumed?.stableId } shouldBe
                listOf(null, "rejected-1", "rejected-2")
        }

        @Test fun `a failed source keeps a healthy dot and Retry refreshes the existing month observer`() = runTest {
            val month = YearMonth.of(2026, 3)
            val zone = ZoneId.of("UTC")
            val from = month.atDay(10).atStartOfDay(zone).toInstant().toEpochMilli()
            var failed = true
            trackingHistoryRepository.readPage = { request -> trackingHistoryRepository.page(request,
                if (request.range.fromInclusiveMs == from) listOf(measuredWifi(from + 1L)) else emptyList(),
                problems = if (failed) setOf(HistoryProblem(HistorySource.CELL, HistoryProblemReason.STORAGE_UNAVAILABLE)) else emptySet()) }
            val vm = createViewModel()
            vm.useViewZone(zone); vm.selectMonth(month); vm.selectTab(HistoryTab.CALENDAR)
            advanceUntilIdle()
            vm.calendarState.value.dayData.keys shouldBe setOf(month.atDay(10))
            vm.calendarState.value.monthProblems.map { it.source }.toSet() shouldBe setOf(HistorySource.CELL)
            failed = false
            vm.retryCalendarMonth()
            advanceUntilIdle()
            vm.calendarState.value.dayData.keys shouldBe setOf(month.atDay(10))
            vm.calendarState.value.monthProblems shouldBe emptySet()
            trackingHistoryRepository.monthRequests.size shouldBe 2
        }

        @Test fun `destructive invalidation clears prior dots while the new epoch is loading`() = runTest {
            val month = YearMonth.of(2026, 3)
            val zone = ZoneId.of("UTC")
            val from = month.atDay(31).atStartOfDay(zone).toInstant().toEpochMilli()
            val release = CompletableDeferred<Unit>()
            trackingHistoryRepository.readPage = { request ->
                if (trackingHistoryRepository.revision.value == 0L)
                    trackingHistoryRepository.page(request, if (request.range.fromInclusiveMs == from) listOf(measuredWifi(from + 1L)) else emptyList())
                else { release.await(); trackingHistoryRepository.page(request) }
            }
            val vm = createViewModel()
            vm.useViewZone(zone); vm.selectMonth(month); vm.selectTab(HistoryTab.CALENDAR)
            advanceUntilIdle()
            vm.calendarState.value.dayData.keys shouldBe setOf(month.atDay(31))
            trackingHistoryRepository.revision.value = 1L
            testScheduler.runCurrent()
            vm.calendarState.value.dayData shouldBe emptyMap()
            vm.calendarState.value.monthLoading shouldBe true
            release.complete(Unit)
            advanceUntilIdle()
            vm.calendarState.value.dayData shouldBe emptyMap()
            vm.calendarState.value.monthLoading shouldBe false
        }

        @Test fun `month dots use real local DST days and restart after displayed zone changes`() = runTest {
            val vm = createViewModel()
            val month = YearMonth.of(2026, 3)
            vm.useViewZone(ZoneId.of("Europe/Prague")); vm.selectMonth(month); vm.selectTab(HistoryTab.CALENDAR)
            advanceUntilIdle()
            val day = month.atDay(29)
            val from = day.atStartOfDay(ZoneId.of("Europe/Prague")).toInstant().toEpochMilli()
            val probe = trackingHistoryRepository.dayReads.single { it.range.fromInclusiveMs == from }
            probe.range.toExclusiveMs - probe.range.fromInclusiveMs shouldBe 23L * 60L * 60L * 1_000L
            vm.useViewZone(ZoneId.of("UTC"))
            advanceUntilIdle()
            trackingHistoryRepository.monthRequests.map { it.range.viewZoneId } shouldBe listOf("Europe/Prague", "UTC")
            vm.calendarState.value.monthProblems shouldBe emptySet()
        }

        @Test fun `changing only the selected day does not rescan the entire month`() = runTest {
            val vm = createViewModel()
            val month = YearMonth.of(2026, 3)
            vm.selectMonth(month); vm.selectTab(HistoryTab.CALENDAR)
            advanceUntilIdle()
            val count = trackingHistoryRepository.dayReads.size
            vm.selectDay(month.atDay(10)); vm.selectDay(month.atDay(11))
            advanceUntilIdle()
            trackingHistoryRepository.dayReads.size shouldBe count
            vm.calendarState.value.selectedDay shouldBe month.atDay(11)
        }

        @Test fun `a snapshot mismatch cannot retain dots from two epochs`() = runTest {
            val vm = createViewModel()
            val month = YearMonth.of(2026, 3)
            trackingHistoryRepository.readPage = { request -> trackingHistoryRepository.page(request).copy(
                snapshot = TrackingHistoryReadSnapshot(5, 10)) }
            vm.selectMonth(month); vm.selectTab(HistoryTab.CALENDAR)
            advanceUntilIdle()
            vm.calendarState.value.dayData shouldBe emptyMap()
            vm.calendarState.value.monthProblems shouldBe setOf(HistoryProblem(null, HistoryProblemReason.INTEGRITY_UNAVAILABLE))
            vm.calendarState.value.monthLoading shouldBe false
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
