package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionSegmentDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: SessionSegmentDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.sessionSegmentDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `getSummary includes grouped on-foot and vehicle distances`() = runBlocking {
		dao.insert(createSegment(1_000L, 2_000L, 100f, DetectedActivity.WALKING.value))
		dao.insert(createSegment(2_000L, 3_000L, 200f, DetectedActivity.RUNNING.value))
		dao.insert(createSegment(3_000L, 4_000L, 300f, DetectedActivity.IN_VEHICLE.value))
		dao.insert(createSegment(4_000L, 5_000L, 400f, DetectedActivity.ON_BICYCLE.value))
		dao.insert(createSegment(5_000L, 6_000L, 500f, DetectedActivity.STILL.value))
		dao.insert(createSegment(6_000L, 7_000L, 600f, null))

		val summary = dao.getSummary(
			onFootActivities = ON_FOOT_ACTIVITY_TYPES,
			inVehicleActivities = IN_VEHICLE_ACTIVITY_TYPES,
		)

		assertEquals(6_000L, summary.durationMs)
		assertEquals(60L, summary.collectionCount)
		assertEquals(2_100f, summary.distanceM, 0.001f)
		assertEquals(300f, summary.onFootDistanceM, 0.001f)
		assertEquals(700f, summary.inVehicleDistanceM, 0.001f)
		assertEquals(120L, summary.stepCount)
	}

	@Test
	fun `getSummaryBetween applies time bounds to grouped distances`() = runBlocking {
		dao.insert(createSegment(1_000L, 2_000L, 100f, DetectedActivity.WALKING.value))
		dao.insert(createSegment(2_000L, 3_000L, 200f, DetectedActivity.RUNNING.value))
		dao.insert(createSegment(3_000L, 4_000L, 300f, DetectedActivity.IN_VEHICLE.value))
		dao.insert(createSegment(4_000L, 5_000L, 400f, DetectedActivity.ON_BICYCLE.value))
		dao.insert(createSegment(5_000L, 6_000L, 500f, DetectedActivity.STILL.value))

		val summary = dao.getSummaryBetween(
			fromMs = 2_000L,
			toMs = 5_000L,
			onFootActivities = ON_FOOT_ACTIVITY_TYPES,
			inVehicleActivities = IN_VEHICLE_ACTIVITY_TYPES,
		)

		assertEquals(3_000L, summary.durationMs)
		assertEquals(30L, summary.collectionCount)
		assertEquals(900f, summary.distanceM, 0.001f)
		assertEquals(200f, summary.onFootDistanceM, 0.001f)
		assertEquals(700f, summary.inVehicleDistanceM, 0.001f)
		assertEquals(60L, summary.stepCount)
	}

	private fun createSegment(
		startTimeMs: Long,
		endTimeMs: Long,
		distanceM: Float,
		primaryActivity: Int?,
	): SessionSegment {
		return SessionSegment(
			startTimeMs = startTimeMs,
			endTimeMs = endTimeMs,
			distanceM = distanceM,
			steps = 20,
			primaryActivity = primaryActivity,
			activityConfidence = 90,
			sampleCount = 10,
			source = SegmentSource.USER_CREATED,
			inferenceVersion = "test",
			createdAt = startTimeMs,
		)
	}

	private companion object {
		val ON_FOOT_ACTIVITY_TYPES = listOf(
			DetectedActivity.WALKING.value,
			DetectedActivity.RUNNING.value,
			DetectedActivity.ON_FOOT.value,
		)
		val IN_VEHICLE_ACTIVITY_TYPES = listOf(
			DetectedActivity.IN_VEHICLE.value,
			DetectedActivity.ON_BICYCLE.value,
		)
	}
}
