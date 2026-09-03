package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingSource
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.CellSignalReport
import com.adsamcik.tracker.stats.api.repository.CellSignalRepository
import com.adsamcik.tracker.stats.api.repository.CellTowerStat
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.repository.NetworkTypeSignalStat
import com.adsamcik.tracker.stats.api.repository.SessionStatsRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRepository
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.stats.api.repository.WifiObservationRepository
import com.adsamcik.tracker.statistics.export.GpxShareHelper
import com.adsamcik.tracker.statistics.viewmodel.CellSignalReportLoadState
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatsPresenterViewModelCellSignalTest {

	private val testDispatcher = StandardTestDispatcher()
	private val sessionStatsRepository: SessionStatsRepository = mockk()
	private val sessionStatsUiFormatter: SessionStatsUiFormatter = mockk(relaxed = true)
	private val dailySummaryRepository: DailySummaryRepository = mockk()
	private val stepsNumericSummaryRepository: StepsNumericSummaryRepository = mockk()
	private val tripPresentationRepository: TripPresentationRepository = mockk()
	private val wifiObservationRepository: WifiObservationRepository = mockk(relaxed = true)
	private val gpxShareHelper: GpxShareHelper = mockk(relaxed = true)

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		every { dailySummaryRepository.observeBetween(any(), any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
		every { stepsNumericSummaryRepository.observe(any()) } returns kotlinx.coroutines.flow.flowOf(
			StepsNumericSummary.Unverifiable(
				StepsNumericUnverifiableReason.NOT_CAPTURED,
			),
		)
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
	fun `loadCellSignalReport maps repository report into success state`() = runTest {
		val report = CellSignalReport(
			totalSamples = 100L,
			distinctTowers = 3L,
			networkTypes = listOf(NetworkTypeSignalStat(4, 100L, 100.0, 50.0, 3L)),
			topTowers = listOf(CellTowerStat(1L, 230, 1, 4, 40L, 50.0)),
		)
		val viewModel = createViewModel(FakeCellSignalRepository(report.right()))
		runCurrent()

		viewModel.loadCellSignalReport()
		runCurrent()

		viewModel.cellSignalReportState.value shouldBe CellSignalReportLoadState.Success(report)
	}

	@Test
	fun `loadCellSignalReport maps zero-sample report to empty`() = runTest {
		val emptyReport = CellSignalReport(0L, 0L, emptyList(), emptyList())
		val viewModel = createViewModel(FakeCellSignalRepository(emptyReport.right()))
		runCurrent()

		viewModel.loadCellSignalReport()
		runCurrent()

		viewModel.cellSignalReportState.value shouldBe CellSignalReportLoadState.Empty
	}

	@Test
	fun `loadCellSignalReport maps repository failure to error`() = runTest {
		val viewModel = createViewModel(
			FakeCellSignalRepository(StatsError.DatabaseError("cell failed").left()),
		)
		runCurrent()

		viewModel.loadCellSignalReport()
		runCurrent()

		viewModel.cellSignalReportState.value shouldBe CellSignalReportLoadState.Error("cell failed")
	}

	private fun createViewModel(
		cellSignalRepository: CellSignalRepository,
	): StatsPresenterViewModel {
		return StatsPresenterViewModel(
			tripPresentationRepository = tripPresentationRepository,
			sessionStatsRepository = sessionStatsRepository,
			dailySummaryRepository = dailySummaryRepository,
			stepsNumericSummaryRepository = stepsNumericSummaryRepository,
			wifiObservationRepository = wifiObservationRepository,
			cellSignalRepository = cellSignalRepository,
			gpxShareHelper = gpxShareHelper,
			sessionStatsUiFormatter = sessionStatsUiFormatter,
			savedStateHandle = SavedStateHandle(),
			clock = FixedClock(System.currentTimeMillis()),
			dispatchers = DefaultDispatchersProvider,
		)
	}

	private class FakeCellSignalRepository(
		private val result: Either<StatsError, CellSignalReport>,
	) : CellSignalRepository {
		override suspend fun getReport(topTowerLimit: Int): Either<StatsError, CellSignalReport> = result
	}
}
