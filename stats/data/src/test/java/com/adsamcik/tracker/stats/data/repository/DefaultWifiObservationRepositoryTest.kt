package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.WifiObservationBrowseRow
import com.adsamcik.tracker.shared.base.database.data.WifiObservationScanSummary
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.repository.WifiObservationBrowseFilter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultWifiObservationRepositoryTest {

	private val wifiObservationDao: WifiObservationDao = mockk()
	private val testDispatcher = StandardTestDispatcher()
	private val dispatchers = object : DispatchersProvider {
		override val io: CoroutineDispatcher = testDispatcher
		override val default: CoroutineDispatcher = testDispatcher
		override val main: CoroutineDispatcher = testDispatcher
		override val unconfined: CoroutineDispatcher = testDispatcher
	}
	private val repository = DefaultWifiObservationRepository(
		wifiObservationDao = wifiObservationDao,
		dispatchers = dispatchers,
	)

	@Test
	fun `getBrowseItems normalizes filter queries and maps rows`() = runTest(testDispatcher) {
		coEvery {
			wifiObservationDao.getBrowseItems(
				bssid = "AA\\%BB",
				ssid = "Cafe",
				capabilities = "WPA\\_PSK",
				frequencyPrefix = "24",
				limit = 50,
			)
		} returns listOf(
			WifiObservationBrowseRow(
				bssid = "AA%BB",
				ssid = "Cafe",
				capabilities = "WPA_PSK",
				frequency = 2412,
				firstSeenMs = 1_000L,
				lastSeenMs = 1_234L,
			),
		)

		val items = repository.getBrowseItems(
			WifiObservationBrowseFilter(
				bssid = "  AA%BB  ",
				ssid = " Cafe ",
				capabilities = "WPA_PSK",
				frequencyPrefix = "24",
				limit = 50,
			),
		).fold(
			ifLeft = { error("Expected browse items but got $it") },
			ifRight = { it },
		)

		items.single().bssid shouldBe "AA%BB"
		items.single().ssid shouldBe "Cafe"
		items.single().capabilities shouldBe "WPA_PSK"
		items.single().frequency shouldBe 2412
		items.single().firstSeenAt.raw shouldBe 1_000L
		items.single().lastSeenAt.raw shouldBe 1_234L
	}

	@Test
	fun `getStatsSummary maps scan aggregates`() = runTest(testDispatcher) {
		coEvery { wifiObservationDao.getScanSummary() } returns WifiObservationScanSummary(
			totalObservations = 20L,
			distinctScanTimes = 5L,
		)
		every { wifiObservationDao.countDistinctBssid() } returns 4L

		val summary = repository.getStatsSummary().fold(
			ifLeft = { error("Expected Wi-Fi stats summary but got $it") },
			ifRight = { it },
		)

		summary.uniqueNetworks shouldBe 4L
		summary.totalScans shouldBe 5L
		summary.averageNetworksPerScan shouldBe 4.0
	}

	@Test
	fun `getBrowseItems wraps dao failures as database errors`() = runTest(testDispatcher) {
		coEvery {
			wifiObservationDao.getBrowseItems(
				bssid = null,
				ssid = null,
				capabilities = null,
				frequencyPrefix = null,
				limit = 1_000,
			)
		} throws IllegalStateException("boom")

		val error = repository.getBrowseItems(WifiObservationBrowseFilter()).fold(
			ifLeft = { it },
			ifRight = { error("Expected failure but got successful browse items") },
		)

		(error as StatsError.DatabaseError).message shouldBe "Failed to load Wi-Fi observations: boom"
		error.cause?.message shouldBe "boom"
	}
}
