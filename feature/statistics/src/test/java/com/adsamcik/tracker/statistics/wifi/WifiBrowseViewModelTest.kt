package com.adsamcik.tracker.statistics.wifi

import arrow.core.left
import arrow.core.right
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.WifiObservationBrowseFilter
import com.adsamcik.tracker.stats.api.repository.WifiObservationBrowseItem
import com.adsamcik.tracker.stats.api.repository.WifiObservationRepository
import com.adsamcik.tracker.stats.api.repository.WifiObservationStatsSummary
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
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
class WifiBrowseViewModelTest {

	private val testDispatcher = StandardTestDispatcher()

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `init loads default filter and preserves repository browse order`() = runTest {
		val repository = FakeWifiObservationRepository(
			browseResult = listOf(
				WifiObservationBrowseItem(
					bssid = "b-network",
					ssid = "B",
					capabilities = "WPA2",
					frequency = 5200,
					firstSeenAt = EpochMs(2_000L),
					lastSeenAt = EpochMs(2_500L),
				),
				WifiObservationBrowseItem(
					bssid = "a-network",
					ssid = "A",
					capabilities = "WPA2",
					frequency = 2412,
					firstSeenAt = EpochMs(1_000L),
					lastSeenAt = EpochMs(1_500L),
				),
			).right(),
		)

		val viewModel = WifiBrowseViewModel(repository)
		advanceUntilIdle()

		viewModel.filter.value shouldBe WifiObservationBrowseFilter()
		viewModel.uiState.value shouldBe WifiBrowseUiState.Success(
			listOf(
				WifiObservationBrowseItem(
					bssid = "b-network",
					ssid = "B",
					capabilities = "WPA2",
					frequency = 5200,
					firstSeenAt = EpochMs(2_000L),
					lastSeenAt = EpochMs(2_500L),
				),
				WifiObservationBrowseItem(
					bssid = "a-network",
					ssid = "A",
					capabilities = "WPA2",
					frequency = 2412,
					firstSeenAt = EpochMs(1_000L),
					lastSeenAt = EpochMs(1_500L),
				),
			),
		)
		repository.capturedBrowseFilters shouldBe listOf(WifiObservationBrowseFilter())
	}

	@Test
	fun `applyFilter triggers a new repository load and maps errors`() = runTest {
		val repository = FakeWifiObservationRepository(
			browseResult = StatsError.DatabaseError("browse failed").left(),
		)
		val viewModel = WifiBrowseViewModel(repository)
		advanceUntilIdle()
		repository.capturedBrowseFilters.clear()

		val filter = WifiObservationBrowseFilter(
			bssid = "Cafe",
			frequencyPrefix = "24",
			limit = 25,
		)
		viewModel.applyFilter(filter)
		advanceUntilIdle()

		viewModel.filter.value shouldBe filter
		viewModel.uiState.value shouldBe WifiBrowseUiState.Error("browse failed")
		repository.capturedBrowseFilters shouldBe listOf(filter)
	}

	private class FakeWifiObservationRepository(
		private val browseResult: arrow.core.Either<StatsError, List<WifiObservationBrowseItem>>,
	) : WifiObservationRepository {
		val capturedBrowseFilters = mutableListOf<WifiObservationBrowseFilter>()

		override suspend fun getBrowseItems(
			filter: WifiObservationBrowseFilter,
		): arrow.core.Either<StatsError, List<WifiObservationBrowseItem>> {
			capturedBrowseFilters += filter
			return browseResult
		}

		override suspend fun getStatsSummary(): arrow.core.Either<StatsError, WifiObservationStatsSummary> {
			error("Not used in this test")
		}
	}
}
