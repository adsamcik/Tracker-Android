package com.adsamcik.tracker.statistics.presenter

import arrow.core.left
import arrow.core.right
import androidx.paging.PagingSource
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.DailySummary
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.repository.SessionStatsRepository
import com.adsamcik.tracker.stats.api.repository.SessionStatsSnapshot
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatsPresenterViewModelSessionStatsTest {

	private val testDispatcher = StandardTestDispatcher()
	private val sessionStatsUiFormatter: SessionStatsUiFormatter = mockk()
	private val dailySummaryRepository: DailySummaryRepository = mockk()
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
		advanceUntilIdle()

		viewModel.loadSummaryStats()
		advanceUntilIdle()

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
		val viewModel = createViewModel(sessionStatsRepository = sessionStatsRepository)
		advanceUntilIdle()
		val before = System.currentTimeMillis()

		viewModel.loadWeeklyStats()
		advanceUntilIdle()
		val after = System.currentTimeMillis()

		viewModel.weeklyStatsState.value shouldBe StatsLoadState.Error("weekly failure")
		assertTrue(sessionStatsRepository.capturedFrom != null)
		assertTrue(sessionStatsRepository.capturedTo != null)
		assertTrue(sessionStatsRepository.capturedFrom!!.raw < sessionStatsRepository.capturedTo!!.raw)
		assertTrue(sessionStatsRepository.capturedTo!!.raw in before..after)
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
		advanceUntilIdle()

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
		advanceUntilIdle()

		viewModel.weeklyBars.value.last().distanceM shouldBe 394f
		viewModel.weeklyBars.value.last().steps shouldBe 812
		viewModel.weeklyBars.value.last().sessionCount shouldBe 1
		viewModel.weeklyBars.value.last().durationMs shouldBe 600_000L
		viewModel.heatmapData.value[java.time.LocalDate.ofEpochDay(todayEpochDay)] shouldBe 1f
	}

	@Test
	fun `distance-only today summary still produces visible daily activity state`() = runTest {
		val todayEpochDay = java.time.LocalDate.now().toEpochDay()
		val viewModel = createViewModel()
		advanceUntilIdle()

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
		advanceUntilIdle()

		val todayBar = viewModel.weeklyBars.value.last()
		todayBar.distanceM shouldBe 304.06f
		todayBar.steps shouldBe 0
		todayBar.sessionCount shouldBe 1
		todayBar.durationMs shouldBe 57_000L
		viewModel.heatmapData.value[java.time.LocalDate.ofEpochDay(todayEpochDay)] shouldBe 1f
	}

	private fun createViewModel(
		sessionStatsRepository: SessionStatsRepository = FakeSessionStatsRepository(
			allTimeResult = sampleSnapshot().right(),
			betweenResult = sampleSnapshot().right(),
		),
	): StatsPresenterViewModel {
		return StatsPresenterViewModel(
			tripPresentationRepository = tripPresentationRepository,
			sessionStatsRepository = sessionStatsRepository,
			dailySummaryRepository = dailySummaryRepository,
			wifiObservationRepository = wifiObservationRepository,
			gpxShareHelper = gpxShareHelper,
			sessionStatsUiFormatter = sessionStatsUiFormatter,
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
}
