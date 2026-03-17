package com.adsamcik.tracker.statistics.presenter

import arrow.core.left
import arrow.core.right
import androidx.paging.PagingSource
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.DailySummary
import com.adsamcik.tracker.stats.api.repository.DailySummaryRepository
import com.adsamcik.tracker.stats.api.repository.SessionStatsRepository
import com.adsamcik.tracker.stats.api.repository.TripPresentationRepository
import com.adsamcik.tracker.stats.api.repository.WifiObservationBrowseFilter
import com.adsamcik.tracker.stats.api.repository.WifiObservationRepository
import com.adsamcik.tracker.stats.api.repository.WifiObservationStatsSummary
import com.adsamcik.tracker.statistics.export.GpxShareHelper
import com.adsamcik.tracker.statistics.viewmodel.WifiStatsLoadState
import io.kotest.matchers.shouldBe
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
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatsPresenterViewModelWifiStatsTest {

	private val testDispatcher = StandardTestDispatcher()
	private val sessionStatsRepository: SessionStatsRepository = mockk()
	private val sessionStatsUiFormatter: SessionStatsUiFormatter = mockk(relaxed = true)
	private val dailySummaryRepository: DailySummaryRepository = mockk()
	private val tripPresentationRepository: TripPresentationRepository = mockk()
	private val gpxShareHelper: GpxShareHelper = mockk(relaxed = true)

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		coEvery { dailySummaryRepository.getBetween(any(), any()) } returns emptyList<DailySummary>().right()
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
	fun `loadWifiStats maps repository summary into success state`() = runTest {
		val wifiObservationRepository = FakeWifiObservationRepository(
			statsSummaryResult = WifiObservationStatsSummary(
				uniqueNetworks = 4L,
				totalScans = 2L,
				averageNetworksPerScan = 3.5,
			).right(),
		)
		val viewModel = createViewModel(wifiObservationRepository)
		advanceUntilIdle()

		viewModel.loadWifiStats()
		advanceUntilIdle()

		viewModel.wifiStatsState.value shouldBe WifiStatsLoadState.Success(
			WifiObservationStatsSummary(
				uniqueNetworks = 4L,
				totalScans = 2L,
				averageNetworksPerScan = 3.5,
			),
		)
		wifiObservationRepository.statsCalls shouldBe 1
	}

	@Test
	fun `loadWifiStats maps empty and error states`() = runTest {
		val emptyRepository = FakeWifiObservationRepository(
			statsSummaryResult = WifiObservationStatsSummary(
				uniqueNetworks = 0L,
				totalScans = 0L,
				averageNetworksPerScan = 0.0,
			).right(),
		)
		val emptyViewModel = createViewModel(emptyRepository)
		advanceUntilIdle()

		emptyViewModel.loadWifiStats()
		advanceUntilIdle()

		emptyViewModel.wifiStatsState.value shouldBe WifiStatsLoadState.Empty

		val errorViewModel = createViewModel(
			FakeWifiObservationRepository(
				statsSummaryResult = StatsError.DatabaseError("wifi failed").left(),
			),
		)
		advanceUntilIdle()

		errorViewModel.loadWifiStats()
		advanceUntilIdle()

		errorViewModel.wifiStatsState.value shouldBe WifiStatsLoadState.Error("wifi failed")
	}

	private fun createViewModel(
		wifiObservationRepository: WifiObservationRepository,
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

	private class FakeWifiObservationRepository(
		private val statsSummaryResult: arrow.core.Either<StatsError, WifiObservationStatsSummary>,
	) : WifiObservationRepository {
		var statsCalls: Int = 0

		override suspend fun getBrowseItems(
			filter: WifiObservationBrowseFilter,
		): arrow.core.Either<StatsError, List<com.adsamcik.tracker.stats.api.repository.WifiObservationBrowseItem>> {
			return emptyList<com.adsamcik.tracker.stats.api.repository.WifiObservationBrowseItem>().right()
		}

		override suspend fun getStatsSummary(): arrow.core.Either<StatsError, WifiObservationStatsSummary> {
			statsCalls += 1
			return statsSummaryResult
		}
	}
}
