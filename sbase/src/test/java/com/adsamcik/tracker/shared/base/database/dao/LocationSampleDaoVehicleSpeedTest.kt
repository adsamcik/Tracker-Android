package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Verifies the vehicle-only chunk projection used by the
 * "Vehicle speed compliance" map layer:
 *
 *  - returns only samples whose timestamp falls inside a `session_segment`
 *    whose `primary_activity` is in the supplied driving list,
 *  - drops samples with NULL coordinates or NULL speed,
 *  - paginates using the (time_ms, id) stable cursor.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class LocationSampleDaoVehicleSpeedTest {

	private lateinit var database: AppDatabase
	private lateinit var sampleDao: LocationSampleDao
	private lateinit var segmentDao: SessionSegmentDao

	@BeforeEach
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		sampleDao = database.locationSampleDao()
		segmentDao = database.sessionSegmentDao()
	}

	@AfterEach
	fun tearDown() {
		database.close()
	}

	@Test
	fun `only samples inside a driving segment are returned`() = runTest {
		// Driving window: 1000..3000.
		insertSegment(1_000L, 3_000L, DetectedActivity.IN_VEHICLE.value)
		// Walking window: 3000..5000 (should be excluded entirely).
		insertSegment(3_000L, 5_000L, DetectedActivity.WALKING.value)

		sampleDao.insert(createSample(timeMs = 500L)) // before any segment
		sampleDao.insert(createSample(timeMs = 1_500L)) // inside driving
		sampleDao.insert(createSample(timeMs = 2_500L)) // inside driving
		sampleDao.insert(createSample(timeMs = 4_000L)) // inside walking
		sampleDao.insert(createSample(timeMs = 6_000L)) // after all segments

		val rows = sampleDao.getDrivingChunkBetweenOrdered(
			fromMs = 0L,
			toMs = Long.MAX_VALUE,
			drivingActivities = DRIVING_ACTIVITIES,
			afterTimeMs = null,
			afterId = null,
			limit = 100,
		)

		rows shouldHaveSize 2
		rows.map { it.timeMs } shouldBe listOf(1_500L, 2_500L)
	}

	@Test
	fun `samples without coordinates or speed are dropped`() = runTest {
		insertSegment(0L, 10_000L, DetectedActivity.IN_VEHICLE.value)

		sampleDao.insert(createSample(timeMs = 1_000L, latE7 = null, lonE7 = null))
		sampleDao.insert(createSample(timeMs = 2_000L, speedMps = null))
		sampleDao.insert(createSample(timeMs = 3_000L)) // fully valid
		sampleDao.insert(createSample(timeMs = 4_000L, latE7 = null))
		sampleDao.insert(createSample(timeMs = 5_000L, lonE7 = null))

		val rows = sampleDao.getDrivingChunkBetweenOrdered(
			fromMs = 0L,
			toMs = Long.MAX_VALUE,
			drivingActivities = DRIVING_ACTIVITIES,
			afterTimeMs = null,
			afterId = null,
			limit = 100,
		)

		rows.map { it.timeMs } shouldBe listOf(3_000L)
	}

	@Test
	fun `pagination uses time_ms then id as a stable cursor`() = runTest {
		insertSegment(0L, 10_000L, DetectedActivity.IN_VEHICLE.value)

		// Two samples with identical time_ms — disambiguated only by id (auto-increment).
		sampleDao.insert(createSample(timeMs = 1_000L, latE7 = 500_000_000))
		sampleDao.insert(createSample(timeMs = 1_000L, latE7 = 500_000_001))
		sampleDao.insert(createSample(timeMs = 2_000L, latE7 = 500_000_002))

		val firstChunk = sampleDao.getDrivingChunkBetweenOrdered(
			fromMs = 0L,
			toMs = Long.MAX_VALUE,
			drivingActivities = DRIVING_ACTIVITIES,
			afterTimeMs = null,
			afterId = null,
			limit = 2,
		)
		firstChunk shouldHaveSize 2
		firstChunk.map { it.timeMs } shouldBe listOf(1_000L, 1_000L)

		val last = firstChunk.last()
		val secondChunk = sampleDao.getDrivingChunkBetweenOrdered(
			fromMs = 0L,
			toMs = Long.MAX_VALUE,
			drivingActivities = DRIVING_ACTIVITIES,
			afterTimeMs = last.timeMs,
			afterId = last.id,
			limit = 100,
		)
		secondChunk shouldHaveSize 1
		secondChunk.single().timeMs shouldBe 2_000L
	}

	@Test
	fun `time bounds restrict returned chunk`() = runTest {
		insertSegment(0L, 100_000L, DetectedActivity.IN_VEHICLE.value)
		sampleDao.insert(createSample(timeMs = 1_000L))
		sampleDao.insert(createSample(timeMs = 5_000L))
		sampleDao.insert(createSample(timeMs = 9_000L))

		val rows = sampleDao.getDrivingChunkBetweenOrdered(
			fromMs = 2_000L,
			toMs = 6_000L,
			drivingActivities = DRIVING_ACTIVITIES,
			afterTimeMs = null,
			afterId = null,
			limit = 100,
		)
		rows.map { it.timeMs } shouldBe listOf(5_000L)
	}

	@Test
	fun `empty driving activity list yields no rows`() = runTest {
		insertSegment(0L, 10_000L, DetectedActivity.IN_VEHICLE.value)
		sampleDao.insert(createSample(timeMs = 1_000L))

		val rows = sampleDao.getDrivingChunkBetweenOrdered(
			fromMs = 0L,
			toMs = Long.MAX_VALUE,
			drivingActivities = emptyList(),
			afterTimeMs = null,
			afterId = null,
			limit = 100,
		)
		rows.shouldBeEmpty()
	}

	private fun createSample(
		timeMs: Long,
		latE7: Int? = 500_000_000,
		lonE7: Int? = 140_000_000,
		speedMps: Float? = 1.5f,
	) = LocationSample(
		timeMs = timeMs,
		elapsedRealtimeNanos = timeMs * 1_000_000L,
		latE7 = latE7,
		lonE7 = lonE7,
		altitudeM = 100f,
		rawGpsAltitudeM = 100f,
		hAccM = 5f,
		vAccM = 10f,
		speedMps = speedMps,
		speedAccuracyMps = 0.5f,
		provider = "fused",
		quality = SampleQuality.HIGH,
		motionState = MotionState.MOVING,
		policy = null,
		bucketId = null,
		createdAt = System.currentTimeMillis(),
	)

	private suspend fun insertSegment(
		startMs: Long,
		endMs: Long,
		primaryActivity: Int?,
	) {
		segmentDao.insert(
			SessionSegment(
				startTimeMs = startMs,
				endTimeMs = endMs,
				distanceM = 0f,
				steps = 0,
				primaryActivity = primaryActivity,
				activityConfidence = 90,
				sampleCount = 10,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = "test",
				createdAt = startMs,
			)
		)
	}

	private companion object {
		val DRIVING_ACTIVITIES = listOf(
			DetectedActivity.IN_VEHICLE.value,
		)
	}
}
