package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.base.database.data.SessionSegmentStats
import com.adsamcik.tracker.stats.api.error.StatsError
import com.adsamcik.tracker.stats.api.value.EpochMs
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
	private val wifiObservationDao: WifiObservationDao = mockk()
	private val cellSampleDao: CellSampleDao = mockk()
	private val testDispatcher = StandardTestDispatcher()
	private val dispatchers = object : DispatchersProvider {
		override val io: CoroutineDispatcher = testDispatcher
		override val default: CoroutineDispatcher = testDispatcher
		override val main: CoroutineDispatcher = testDispatcher
		override val unconfined: CoroutineDispatcher = testDispatcher
	}
	private val repository = DefaultSessionStatsRepository(
		sessionSegmentDao = sessionSegmentDao,
		tripDao = tripDao,
		locationSampleDao = locationSampleDao,
		wifiObservationDao = wifiObservationDao,
		cellSampleDao = cellSampleDao,
		dispatchers = dispatchers,
	)

	@Test
	fun `getAllTime maps aggregate counts into snapshot`() = runTest(testDispatcher) {
		coEvery {
			sessionSegmentDao.getSummary(any<List<Int>>(), any<List<Int>>())
		} returns SessionSegmentStats(
			durationMs = 12_000L,
			collectionCount = 42L,
			distanceM = 1234.5f,
			onFootDistanceM = 400f,
			inVehicleDistanceM = 800f,
			stepCount = 678L,
		)
		every { tripDao.countAllTrips() } returns 9L
		coEvery { locationSampleDao.countAll() } returns 77L
		every { wifiObservationDao.countDistinctBssid() } returns 4L
		every { cellSampleDao.uniqueCount() } returns 5L

		val snapshot = repository.getAllTime().fold(
			ifLeft = { error("Expected Right but was $it") },
			ifRight = { it },
		)

		snapshot.duration.raw shouldBe 12_000L
		snapshot.collections shouldBe 42L
		snapshot.totalDistance.raw shouldBe 1234.5f
		snapshot.onFootDistance.raw shouldBe 400f
		snapshot.inVehicleDistance.raw shouldBe 800f
		snapshot.steps.raw shouldBe 678
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
			stepCount = 100L,
		)
		every { tripDao.countTripsBetween(from.raw, to.raw) } returns 2L
		coEvery { locationSampleDao.countBetween(from.raw, to.raw) } returns 11
		every { wifiObservationDao.countDistinctBssid(from.raw, to.raw) } returns 3L
		every { cellSampleDao.uniqueCount(from.raw, to.raw) } returns 6L

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
