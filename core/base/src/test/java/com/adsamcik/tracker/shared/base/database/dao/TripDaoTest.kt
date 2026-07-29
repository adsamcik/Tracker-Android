package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var tripDao: TripDao
	private lateinit var segmentDao: SessionSegmentDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		tripDao = database.tripDao()
		segmentDao = database.sessionSegmentDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	private fun createSegment(
		startTimeMs: Long,
		endTimeMs: Long,
		distanceM: Float = 1000f,
		steps: Int? = 500,
		source: SegmentSource = SegmentSource.USER_CREATED,
		hasDistanceAnomaly: Boolean = false,
	): SessionSegment = SessionSegment(
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		distanceM = distanceM,
		steps = steps,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 10,
		source = source,
		inferenceVersion = null,
		createdAt = System.currentTimeMillis(),
		hasDistanceAnomaly = hasDistanceAnomaly,
	)

	@Test
	fun getByIdReturnsNullForMissingId() = runBlocking {
		val trip = tripDao.getById(999L)
		assertNull(trip)
	}

	@Test
	fun getByIdReturnsTripForExistingSegment() = runBlocking {
		val segment = createSegment(1000L, 2000L)
		val id = segmentDao.insert(segment)

		val trip = tripDao.getById(id)
		assertNotNull(trip)
		assertEquals(id, trip!!.id)
		assertEquals(1000L, trip.startTimeMs)
		assertEquals(2000L, trip.endTimeMs)
		assertEquals(1000f, trip.distanceM)
		assertEquals(500, trip.steps)
		assertEquals(SegmentSource.USER_CREATED, trip.source)
	}

	@Test
	fun getBetweenReturnsMatchingTrips() = runBlocking {
		segmentDao.insert(createSegment(1000L, 2000L))
		segmentDao.insert(createSegment(3000L, 4000L))
		segmentDao.insert(createSegment(5000L, 6000L))

		val trips = tripDao.getBetween(2500L, 4500L)
		assertEquals(1, trips.size)
		assertEquals(3000L, trips[0].startTimeMs)
	}

	@Test
	fun getBetweenReturnsEmptyListForNoMatches() = runBlocking {
		segmentDao.insert(createSegment(1000L, 2000L))

		val trips = tripDao.getBetween(5000L, 6000L)
		assertEquals(0, trips.size)
	}

	@Test
	fun getRecentTripsRespectsLimit() = runBlocking {
		repeat(5) { i ->
			segmentDao.insert(createSegment(
				startTimeMs = (i * 1000 + 1000).toLong(),
				endTimeMs = (i * 1000 + 2000).toLong()
			))
		}

		val trips = tripDao.getRecentTrips(3)
		assertEquals(3, trips.size)
		// Should be newest first
		assertEquals(5000L, trips[0].startTimeMs)
		assertEquals(4000L, trips[1].startTimeMs)
	}

	@Test
	fun getTodaySummaryAggregatesCorrectly() = runBlocking {
		segmentDao.insert(createSegment(1000L, 2000L, distanceM = 500f, steps = 100))
		segmentDao.insert(createSegment(3000L, 4000L, distanceM = 300f, steps = 200))
		// This one outside range
		segmentDao.insert(createSegment(10000L, 11000L, distanceM = 999f, steps = 999))

		val summary = tripDao.getTodaySummary(0L, 5000L)
		assertNotNull(summary)
		assertEquals(2, summary!!.tripCount)
		assertEquals(800f, summary.totalDistanceM)
		assertEquals(300, summary.totalSteps)
		assertEquals(2000L, summary.totalDurationMs)
	}

	@Test
	fun getTodaySummaryReturnsZerosForEmptyRange() = runBlocking {
		val summary = tripDao.getTodaySummary(0L, 1000L)
		assertNotNull(summary)
		assertEquals(0, summary!!.tripCount)
		assertEquals(0f, summary.totalDistanceM)
		assertEquals(0, summary.totalSteps)
	}

	@Test
	fun tripDurationMsIsCalculatedCorrectly() = runBlocking {
		val id = segmentDao.insert(createSegment(1000L, 3500L))
		val trip = tripDao.getById(id)!!

		assertEquals(2500L, trip.durationMs)
	}

	@Test
	fun getBetweenRespectsLimit() = runBlocking {
		// Insert more than 500 segments within the same time range
		val count = 510
		repeat(count) { i ->
			segmentDao.insert(createSegment(
				startTimeMs = (i * 10 + 1000).toLong(),
				endTimeMs = (i * 10 + 1005).toLong()
			))
		}

		val trips = tripDao.getBetween(0L, Long.MAX_VALUE)
		// Safety LIMIT caps results at 500
		assertEquals(500, trips.size)
	}

	@Test
	fun tripIsUserInitiatedMatchesSource() = runBlocking {
		val userId = segmentDao.insert(createSegment(1000L, 2000L, source = SegmentSource.USER_CREATED))
		val inferredId = segmentDao.insert(createSegment(3000L, 4000L, source = SegmentSource.INFERRED_HIGH_CONFIDENCE))

		val userTrip = tripDao.getById(userId)!!
		val inferredTrip = tripDao.getById(inferredId)!!

		assertEquals(true, userTrip.isUserInitiated)
		assertEquals(false, inferredTrip.isUserInitiated)
	}

	@Test
	fun anomalyFlagDefaultsToFalse() = runBlocking {
		val id = segmentDao.insert(createSegment(1000L, 2000L, distanceM = 500f))
		val trip = tripDao.getById(id)!!
		assertEquals(false, trip.hasDistanceAnomaly)
	}

	@Test
	fun anomalyFlagTrueWhenPersisted() = runBlocking {
		val id = segmentDao.insert(
			createSegment(1000L, 2000L, distanceM = 9_393_800f, hasDistanceAnomaly = true)
		)
		val trip = tripDao.getById(id)!!
		assertEquals(true, trip.hasDistanceAnomaly)
	}

	@Test
	fun anomalyFlagProjectedInGetBetween() = runBlocking {
		segmentDao.insert(createSegment(1000L, 2000L, distanceM = 500f, hasDistanceAnomaly = false))
		segmentDao.insert(createSegment(3000L, 4000L, distanceM = 9_393_800f, hasDistanceAnomaly = true))

		val trips = tripDao.getBetween(0L, 5000L)
		assertEquals(2, trips.size)
		val anomalous = trips.first { it.hasDistanceAnomaly }
		val plausible = trips.first { !it.hasDistanceAnomaly }
		assertEquals(9_393_800f, anomalous.distanceM)
		assertEquals(500f, plausible.distanceM)
	}

	@Test
	fun anomalyFlagProjectedInGetRecentTrips() = runBlocking {
		segmentDao.insert(createSegment(1000L, 2000L, distanceM = 500f, hasDistanceAnomaly = false))
		segmentDao.insert(createSegment(3000L, 4000L, distanceM = 9_393_800f, hasDistanceAnomaly = true))

		val trips = tripDao.getRecentTrips(10)
		assertEquals(2, trips.size)
		val anomalous = trips.first { it.hasDistanceAnomaly }
		assertEquals(true, anomalous.hasDistanceAnomaly)
	}
}
