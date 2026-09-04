package com.adsamcik.tracker.statistics.presenter

import arrow.core.left
import arrow.core.right
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingSource
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.DailySummary
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.repository.SessionStatsRepository
import com.adsamcik.tracker.stats.api.repository.SessionStatsSnapshot
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.stats.api.repository.WifiObservationRepository
import com.adsamcik.tracker.stats.api.repository.WifiObservationStatsSummary
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.export.GpxShareHelper
import com.adsamcik.tracker.statistics.viewmodel.StatsLoadState
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class StatsPresenterViewModelSessionStatsTest {

	private val testDispatcher = StandardTestDispatcher()
	private val sessionStatsUiFormatter: SessionStatsUiFormatter = mockk()
	private val dailySummaryRepository: DailySummaryRepository = mockk()
	private val stepsNumericSummaryRepository = FakeStepsNumericSummaryRepository()
	private val tripPresentationRepository: TripPresentationRepository = mockk()
	private val wifiObservationRepository: WifiObservationRepository = mockk()
	private val gpxShareHelper: GpxShareHelper = mockk(relaxed = true)
	private val dailySummariesFlow = MutableStateFlow<List<DailySummary>>(emptyList())

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		every { dailySummaryRepository.observeBetween(any(), any()) } returns dailySummariesFlow
		coEvery {
			wifiObservationRepository.getStatsSummary()
		} returns WifiObservationStatsSummary(
			uniqueNetworks = 0L,
			totalScans = 0L,
			averageNetworksPerScan = 0.0,
		).right()
		every { tripPresentationRepository.getPagedTrips() } returns mockk<PagingSource<Int, Trip>>()
		every {
			tripPresentationRepository.getPagedTripsOverlapping(any(), any())
		} returns mockk<PagingSource<Int, Trip>>()
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `loadSummaryStats maps snapshot through formatter`() = runTest {
		val snapshot = sampleSnapshot()
		val sessionStatsRepository = FakeSessionStatsRepository(
			allTimeResult = snapshot.right(),
			betweenResult = StatsError.DatabaseError("unused").left(),
		)
		val stats = listOf(
			Stat(
				nameRes = 1,
				iconRes = 2,
				displayType = com.adsamcik.tracker.statistics.detail.StatisticDisplayType.INFORMATION,
				data = "summary",
			),
		)
		every { sessionStatsUiFormatter.formatSummary(snapshot) } returns stats

		val viewModel = createViewModel(sessionStatsRepository = sessionStatsRepository)
		runCurrent()

		viewModel.loadSummaryStats()
		runCurrent()

		viewModel.summaryStatsState.value shouldBe StatsLoadState.Success(stats)
		sessionStatsRepository.allTimeCalls shouldBe 1
		verify(exactly = 1) { sessionStatsUiFormatter.formatSummary(snapshot) }
	}

	@Test
	fun `loadWeeklyStats maps repository error and requests a week window`() = runTest {
		val sessionStatsRepository = FakeSessionStatsRepository(
			allTimeResult = sampleSnapshot().right(),
			betweenResult = StatsError.DatabaseError("weekly failure").left(),
		)
		val now = System.currentTimeMillis()
		val viewModel = createViewModel(
			sessionStatsRepository = sessionStatsRepository,
			clock = FixedClock(now),
		)
		runCurrent()

		viewModel.loadWeeklyStats()
		runCurrent()

		viewModel.weeklyStatsState.value shouldBe StatsLoadState.Error("weekly failure")
		assertTrue(sessionStatsRepository.capturedFrom != null)
		assertTrue(sessionStatsRepository.capturedTo != null)
		assertTrue(sessionStatsRepository.capturedFrom!!.raw < sessionStatsRepository.capturedTo!!.raw)
		sessionStatsRepository.capturedTo!!.raw shouldBe now
		val windowMs = sessionStatsRepository.capturedTo!!.raw - sessionStatsRepository.capturedFrom!!.raw
		val minimumWeekMs = 6L * 24L * 60L * 60L * 1000L
		val maximumWeekMs = 8L * 24L * 60L * 60L * 1000L
		assertTrue(windowMs in minimumWeekMs..maximumWeekMs)
		sessionStatsRepository.betweenCalls shouldBe 1
	}

	@Test
	fun `weekly bars react when daily summaries arrive after init`() = runTest {
		val todayEpochDay = java.time.LocalDate.now().toEpochDay()
		val viewModel = createViewModel()
		val summaryCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
			viewModel.weeklyStepsSummary.collect()
		}
		runCurrent()

		dailySummariesFlow.value = listOf(
			DailySummary(
				dayEpoch = todayEpochDay,
				totalDistance = DistanceM(394f),
				totalSteps = StepCount(812),
				totalDuration = DurationMs(600_000L),
				tripCount = 1,
				activeTrackingDuration = DurationMs(0L),
			),
		)
		runCurrent()

		viewModel.weeklyBars.value.last().distanceM shouldBe 394f
		viewModel.weeklyStepsSummary.value shouldBe StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.NOT_CAPTURED,
		)
		viewModel.weeklyBars.value.last().sessionCount shouldBe 1
		viewModel.weeklyBars.value.last().durationMs shouldBe 600_000L
		viewModel.heatmapData.value[java.time.LocalDate.ofEpochDay(todayEpochDay)] shouldBe 1f
		summaryCollector.cancelAndJoin()
	}

	@Test
	fun `weekly Steps observes one exact seven day qualified window`() = runTest {
		val today = LocalDate.of(2026, 9, 3)
		val expected = StepsNumericSummary.Ready(
			days = (today.toEpochDay() - 6L..today.toEpochDay()).map { epochDay ->
				StepsNumericDay(epochDay = epochDay, steps = epochDay - today.toEpochDay() + 6L)
			},
		)
		stepsNumericSummaryRepository.result = expected
		val clock = FixedClock(
			today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
		)

		val viewModel = createViewModel(clock = clock)
		val summaryCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
			viewModel.weeklyStepsSummary.collect()
		}
		runCurrent()

		viewModel.weeklyStepsSummary.value shouldBe expected
		val expectedRequest = StepsNumericSummaryRequest(
			firstEpochDay = today.toEpochDay() - 6L,
			lastEpochDayInclusive = today.toEpochDay(),
			fallbackCalendarZoneId = ZoneId.systemDefault().id,
		)
		stepsNumericSummaryRepository.requests shouldBe listOf(expectedRequest)
		stepsNumericSummaryRepository.readRequests shouldBe emptyList()

		summaryCollector.cancelAndJoin()
		runCurrent()
		stepsNumericSummaryRepository.cancelledRequests shouldBe listOf(expectedRequest)
		viewModel.weeklyStepsSummary.value shouldBe StepsNumericSummary.Materializing
		viewModel.cancelDayRolloverObservation()
	}

	@Test
	fun `weekly Steps settles without a daily summary emission`() = runTest {
		val today = LocalDate.of(2026, 9, 3)
		val clock = FixedClock(
			today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
		)
		stepsNumericSummaryRepository.result = StepsNumericSummary.Materializing
		val viewModel = createViewModel(clock = clock)
		val summaryCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
			viewModel.weeklyStepsSummary.collect()
		}
		runCurrent()

		viewModel.weeklyStepsSummary.value shouldBe StepsNumericSummary.Materializing
		dailySummariesFlow.value shouldBe emptyList()

		val settled = StepsNumericSummary.Ready(
			days = (today.toEpochDay() - 6L..today.toEpochDay()).map { epochDay ->
				StepsNumericDay(epochDay = epochDay, steps = 1L)
			},
		)
		stepsNumericSummaryRepository.result = settled
		runCurrent()

		viewModel.weeklyStepsSummary.value shouldBe settled
		dailySummariesFlow.value shouldBe emptyList()
		stepsNumericSummaryRepository.requests.size shouldBe 1
		stepsNumericSummaryRepository.readRequests shouldBe emptyList()
		summaryCollector.cancelAndJoin()
		viewModel.cancelDayRolloverObservation()
	}

	@Test
	fun `distance-only today summary still produces visible daily activity state`() = runTest {
		val todayEpochDay = java.time.LocalDate.now().toEpochDay()
		val viewModel = createViewModel()
		runCurrent()

		dailySummariesFlow.value = listOf(
			DailySummary(
				dayEpoch = todayEpochDay,
				totalDistance = DistanceM(304.06f),
				totalSteps = StepCount(0),
				totalDuration = DurationMs(57_000L),
				tripCount = 1,
				activeTrackingDuration = DurationMs(57_000L),
			),
		)
		runCurrent()

		val todayBar = viewModel.weeklyBars.value.last()
		todayBar.distanceM shouldBe 304.06f
		todayBar.sessionCount shouldBe 1
		todayBar.durationMs shouldBe 57_000L
		viewModel.heatmapData.value[java.time.LocalDate.ofEpochDay(todayEpochDay)] shouldBe 1f
	}

	@Test
	fun `date filter survives ViewModel recreation`() = runTest {
		val savedStateHandle = SavedStateHandle()
		val firstViewModel = createViewModel(savedStateHandle = savedStateHandle)
		runCurrent()

		firstViewModel.setDateRange(startMs = 100L, endMs = 200L)
		val recreatedViewModel = createViewModel(savedStateHandle = savedStateHandle)
		runCurrent()

		recreatedViewModel.activeDateFilter.value shouldBe StatsPresenterViewModel.DateFilter(
			startMs = 100L,
			endMs = 200L,
		)
	}

	@Test
	fun `today window advances across midnight without recreating ViewModel`() = runTest {
		val initialDate = LocalDate.of(2026, 7, 21)
		val initialTimeMs = initialDate.atTime(23, 59, 59)
			.atZone(ZoneId.systemDefault())
			.toInstant()
			.toEpochMilli()
		val clock = FixedClock(initialTimeMs)
		val viewModel = createViewModel(
			clock = clock,
			dispatchers = TestDispatchersProvider(testDispatcher),
		)
		val summaryCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
			viewModel.weeklyStepsSummary.collect()
		}
		runCurrent()

		viewModel.weeklyBars.value.last().epochDay shouldBe initialDate.toEpochDay()
		val initialRequest = StepsNumericSummaryRequest(
			firstEpochDay = initialDate.toEpochDay() - 6L,
			lastEpochDayInclusive = initialDate.toEpochDay(),
			fallbackCalendarZoneId = ZoneId.systemDefault().id,
		)
		stepsNumericSummaryRepository.requests shouldBe listOf(initialRequest)

		clock.advance(2_000L)
		advanceTimeBy(1_000L)
		runCurrent()

		viewModel.weeklyBars.value.last().epochDay shouldBe initialDate.plusDays(1).toEpochDay()
		stepsNumericSummaryRepository.requests shouldBe listOf(
			initialRequest,
			StepsNumericSummaryRequest(
				firstEpochDay = initialDate.toEpochDay() - 5L,
				lastEpochDayInclusive = initialDate.plusDays(1).toEpochDay(),
				fallbackCalendarZoneId = ZoneId.systemDefault().id,
			),
		)
		stepsNumericSummaryRepository.cancelledRequests shouldBe listOf(initialRequest)
		summaryCollector.cancelAndJoin()
		runCurrent()
		stepsNumericSummaryRepository.cancelledRequests shouldBe stepsNumericSummaryRepository.requests
		viewModel.weeklyStepsSummary.value shouldBe StepsNumericSummary.Materializing
		viewModel.cancelDayRolloverObservation()
		runCurrent()
	}

	private fun createViewModel(
		sessionStatsRepository: SessionStatsRepository = FakeSessionStatsRepository(
			allTimeResult = sampleSnapshot().right(),
			betweenResult = sampleSnapshot().right(),
		),
		savedStateHandle: SavedStateHandle = SavedStateHandle(),
		clock: Clock = FixedClock(System.currentTimeMillis()),
		dispatchers: DispatchersProvider = DefaultDispatchersProvider,
	): StatsPresenterViewModel {
		return StatsPresenterViewModel(
			tripPresentationRepository = tripPresentationRepository,
			sessionStatsRepository = sessionStatsRepository,
			dailySummaryRepository = dailySummaryRepository,
			stepsNumericSummaryRepository = stepsNumericSummaryRepository,
			wifiObservationRepository = wifiObservationRepository,
			cellSignalRepository = io.mockk.mockk(relaxed = true),
			gpxShareHelper = gpxShareHelper,
			sessionStatsUiFormatter = sessionStatsUiFormatter,
			savedStateHandle = savedStateHandle,
			clock = clock,
			dispatchers = dispatchers,
		)
	}

	private fun sampleSnapshot(): SessionStatsSnapshot {
		return SessionStatsSnapshot(
			duration = DurationMs(12_000L),
			collections = 42L,
			totalDistance = DistanceM(1234.5f),
			onFootDistance = DistanceM(456.7f),
			inVehicleDistance = DistanceM(890.1f),
			steps = StepCount(678),
			tripCount = 9L,
			locationCount = 77L,
			wifiCount = 4L,
			cellCount = 5L,
		)
	}

	private class FakeSessionStatsRepository(
		private val allTimeResult: arrow.core.Either<StatsError, SessionStatsSnapshot>,
		private val betweenResult: arrow.core.Either<StatsError, SessionStatsSnapshot>,
	) : SessionStatsRepository {
		var allTimeCalls: Int = 0
		var betweenCalls: Int = 0
		var capturedFrom: EpochMs? = null
		var capturedTo: EpochMs? = null

		override suspend fun getAllTime(): arrow.core.Either<StatsError, SessionStatsSnapshot> {
			allTimeCalls += 1
			return allTimeResult
		}

		override suspend fun getBetween(
			fromMs: EpochMs,
			toMs: EpochMs,
		): arrow.core.Either<StatsError, SessionStatsSnapshot> {
			betweenCalls += 1
			capturedFrom = fromMs
			capturedTo = toMs
			return betweenResult
		}
	}

	private class FakeStepsNumericSummaryRepository : StepsNumericSummaryRepository {
		private val summaries = MutableStateFlow<StepsNumericSummary>(
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.NOT_CAPTURED),
		)
		var result: StepsNumericSummary
			get() = summaries.value
			set(value) {
				summaries.value = value
			}
		val requests = mutableListOf<StepsNumericSummaryRequest>()
		val readRequests = mutableListOf<StepsNumericSummaryRequest>()
		val cancelledRequests = mutableListOf<StepsNumericSummaryRequest>()

		override suspend fun read(request: StepsNumericSummaryRequest): StepsNumericSummary {
			readRequests += request
			return result
		}

		override fun observe(request: StepsNumericSummaryRequest): Flow<StepsNumericSummary> = flow {
			requests += request
			try {
				emitAll(summaries)
			} finally {
				cancelledRequests += request
			}
		}
	}
}
