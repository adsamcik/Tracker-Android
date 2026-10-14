package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.SessionSegmentStats
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.repository.HistorySource
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
class DefaultSessionStatsRepositoryTest {

	private val sessionSegmentDao: SessionSegmentDao = mockk()
	private val tripDao: TripDao = mockk()
	private val locationSampleDao: LocationSampleDao = mockk()
	private val savedRadioCounts: SavedRadioCountsReader = mockk()
	private val testDispatcher = StandardTestDispatcher()
	private val dispatchers = object : DispatchersProvider {
		override val io: CoroutineDispatcher = testDispatcher
		override val default: CoroutineDispatcher = testDispatcher
		override val main: CoroutineDispatcher = testDispatcher
		override val unconfined: CoroutineDispatcher = testDispatcher
	}
	private val savedLocationMetrics = mockk<SavedLocationMetricsReader> {
        coEvery { read(0L, Long.MAX_VALUE) } returns SavedLocationMetrics(1234.5, 77, 400.0, 0.0, 800.0, null, null, null, null)
        coEvery { read(1_000L, 5_000L) } returns SavedLocationMetrics(900.0, 19, 250.0, 0.0, 500.0, null, null, null, null)
    }
    private val repository = DefaultSessionStatsRepository(
		sessionSegmentDao = sessionSegmentDao,
		tripDao = tripDao,
		locationSampleDao = locationSampleDao,
		dispatchers = dispatchers,
        savedLocationMetrics = savedLocationMetrics,
		savedRadioCounts = savedRadioCounts,
	)

	@Test
	fun `getAllTime maps non-Steps aggregate counts into snapshot`() = runTest(testDispatcher) {
		coEvery {
			sessionSegmentDao.getSummary(any<List<Int>>(), any<List<Int>>())
		} returns SessionSegmentStats(
			durationMs = 12_000L,
			collectionCount = 42L,
			distanceM = 1234.5f,
			onFootDistanceM = 400f,
			inVehicleDistanceM = 800f,
		)
		every { tripDao.countAllTrips() } returns 9L
		coEvery { locationSampleDao.countAll() } returns 77L
		coEvery { savedRadioCounts.read(HistorySource.WIFI, 0L, Long.MAX_VALUE) } returns SavedRadioCount(4L, false)
		coEvery { savedRadioCounts.read(HistorySource.CELL, 0L, Long.MAX_VALUE) } returns SavedRadioCount(5L, false)

		val snapshot = repository.getAllTime().fold(
			ifLeft = { error("Expected Right but was $it") },
			ifRight = { it },
		)

		snapshot.duration.raw shouldBe 12_000L
		snapshot.collections shouldBe 42L
		snapshot.totalDistance.raw shouldBe 1234.5f
		snapshot.onFootDistance.raw shouldBe 400f
		snapshot.inVehicleDistance.raw shouldBe 800f
		snapshot.tripCount shouldBe 9L
		snapshot.locationCount shouldBe 77L
		snapshot.wifiCount shouldBe 4L
		snapshot.cellCount shouldBe 5L
	}

	@Test
	fun `getBetween uses bounded dao queries`() = runTest(testDispatcher) {
		val from = EpochMs(1_000L)
		val to = EpochMs(5_000L)
		coEvery {
			sessionSegmentDao.getSummaryBetween(
				from.raw,
				to.raw,
				any<List<Int>>(),
				any<List<Int>>(),
			)
		} returns SessionSegmentStats(
			durationMs = 4_000L,
			collectionCount = 8L,
			distanceM = 900f,
			onFootDistanceM = 250f,
			inVehicleDistanceM = 500f,
		)
		every { tripDao.countTripsBetween(from.raw, to.raw) } returns 2L
		coEvery { locationSampleDao.countBetween(from.raw, to.raw) } returns 11
		coEvery { savedRadioCounts.read(HistorySource.WIFI, from.raw, to.raw) } returns SavedRadioCount(3L, false)
		coEvery { savedRadioCounts.read(HistorySource.CELL, from.raw, to.raw) } returns SavedRadioCount(6L, false)

		val snapshot = repository.getBetween(from, to).fold(
			ifLeft = { error("Expected Right but was $it") },
			ifRight = { it },
		)

		snapshot.duration.raw shouldBe 4_000L
		snapshot.collections shouldBe 8L
		snapshot.onFootDistance.raw shouldBe 250f
		snapshot.inVehicleDistance.raw shouldBe 500f
		snapshot.tripCount shouldBe 2L
		snapshot.locationCount shouldBe 11L
		snapshot.wifiCount shouldBe 3L
		snapshot.cellCount shouldBe 6L
	}

	@Test
	fun `one missing radio count keeps the sibling and measured Location summary`() = runTest(testDispatcher) {
		coEvery { sessionSegmentDao.getSummary(any<List<Int>>(), any<List<Int>>()) } returns
			SessionSegmentStats(12_000L, 42L, 999_999f, 0f, 0f)
		every { tripDao.countAllTrips() } returns 1L
		coEvery { locationSampleDao.countAll() } returns 77L
		coEvery { savedRadioCounts.read(HistorySource.WIFI, 0L, Long.MAX_VALUE) } returns SavedRadioCount(null, true)
		coEvery { savedRadioCounts.read(HistorySource.CELL, 0L, Long.MAX_VALUE) } returns SavedRadioCount(5L, false)
		val value = repository.getAllTime().getOrNull()!!
		value.wifiCount shouldBe null
		value.cellCount shouldBe 5L
		value.totalDistance.raw shouldBe 1234.5f
		value.hasMeasuredDistance shouldBe true
	}

	@Test
	fun `getAllTime wraps dao failures as database errors`() = runTest(testDispatcher) {
		val failure = IllegalStateException("boom")
		coEvery {
			sessionSegmentDao.getSummary(any<List<Int>>(), any<List<Int>>())
		} throws failure

		val error = repository.getAllTime().fold(
			ifLeft = { it },
			ifRight = { error("Expected Left but was Right") },
		)

		error shouldBe StatsError.DatabaseError("Failed to load summary stats: boom", failure)
	}

	@Test
	fun `getBetween rethrows cancellation`() = runTest(testDispatcher) {
		val failure = kotlinx.coroutines.CancellationException("cancel")
		coEvery {
			sessionSegmentDao.getSummaryBetween(
				any(),
				any(),
				any<List<Int>>(),
				any<List<Int>>(),
			)
		} throws failure

		try {
			repository.getBetween(EpochMs(1_000L), EpochMs(2_000L))
			error("Expected CancellationException")
		} catch (actual: kotlinx.coroutines.CancellationException) {
			actual shouldBe failure
		}
	}
}
