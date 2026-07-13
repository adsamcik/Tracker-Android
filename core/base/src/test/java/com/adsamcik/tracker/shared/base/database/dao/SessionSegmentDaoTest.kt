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
import java.time.LocalDate
import java.time.ZoneId

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
		assertEquals(300f, summary.inVehicleDistanceM, 0.001f)
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
		assertEquals(300f, summary.inVehicleDistanceM, 0.001f)
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

	@Test
	fun `sumDistanceByActivitiesBetween returns full distance for segment fully inside window`() = runBlocking {
		// Segment [100, 300] fully inside window [0, 400]: fraction = 200/200 = 1.0
		dao.insert(createSegment(100L, 300L, 200f, DetectedActivity.WALKING.value))

		val result = dao.sumDistanceByActivitiesBetween(0L, 400L, ON_FOOT_ACTIVITY_TYPES)

		assertEquals(200L, result)
	}

	@Test
	fun `sumDistanceByActivitiesBetween prorates segment straddling from boundary`() = runBlocking {
		// Segment [0, 200], window [100, 300]: overlap = 100, duration = 200, fraction = 0.5
		dao.insert(createSegment(0L, 200L, 200f, DetectedActivity.WALKING.value))

		val result = dao.sumDistanceByActivitiesBetween(100L, 300L, ON_FOOT_ACTIVITY_TYPES)

		assertEquals(100L, result)
	}

	@Test
	fun `sumDistanceByActivitiesBetween prorates segment straddling to boundary`() = runBlocking {
		// Segment [200, 400], window [100, 300]: overlap = 100, duration = 200, fraction = 0.5
		dao.insert(createSegment(200L, 400L, 200f, DetectedActivity.WALKING.value))

		val result = dao.sumDistanceByActivitiesBetween(100L, 300L, ON_FOOT_ACTIVITY_TYPES)

		assertEquals(100L, result)
	}

	@Test
	fun `sumDistanceByActivitiesBetween prorates segment that fully encloses window`() = runBlocking {
		// Segment [0, 400], window [100, 300]: overlap = 200, duration = 400, fraction = 0.5
		dao.insert(createSegment(0L, 400L, 400f, DetectedActivity.WALKING.value))

		val result = dao.sumDistanceByActivitiesBetween(100L, 300L, ON_FOOT_ACTIVITY_TYPES)

		assertEquals(200L, result)
	}

	@Test
	fun `sumDistanceByActivitiesBetween returns zero for segment fully outside window`() = runBlocking {
		// Segment [400, 600] is entirely after window [0, 300]: WHERE clause excludes it
		dao.insert(createSegment(400L, 600L, 100f, DetectedActivity.WALKING.value))

		val result = dao.sumDistanceByActivitiesBetween(0L, 300L, ON_FOOT_ACTIVITY_TYPES)

		assertEquals(0L, result)
	}

	@Test
	fun `countByActivitiesBetween counts each overlapping segment as one regardless of overlap fraction`() = runBlocking {
		// Fully inside, straddles from, straddles to — all counted as 1 each
		dao.insert(createSegment(150L, 250L, 50f, DetectedActivity.WALKING.value))   // fully inside [100, 300]
		dao.insert(createSegment(0L, 150L, 50f, DetectedActivity.WALKING.value))     // straddles from
		dao.insert(createSegment(250L, 400L, 50f, DetectedActivity.WALKING.value))   // straddles to
		dao.insert(createSegment(500L, 600L, 50f, DetectedActivity.WALKING.value))   // fully outside

		val result = dao.countByActivitiesBetween(100L, 300L, ON_FOOT_ACTIVITY_TYPES)

		assertEquals(3L, result)
	}

	@Test
	fun `countDistinctDaysByActivities separates motorized and cycling days`() = runBlocking {
		val dayOne = LocalDate.of(2026, 1, 10).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
		val dayTwo = LocalDate.of(2026, 1, 11).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
		dao.insert(createSegment(dayOne + 1_000L, dayOne + 2_000L, 100f, DetectedActivity.IN_VEHICLE.value))
		dao.insert(createSegment(dayOne + 3_000L, dayOne + 4_000L, 100f, DetectedActivity.IN_VEHICLE.value))
		dao.insert(createSegment(dayTwo + 1_000L, dayTwo + 2_000L, 100f, DetectedActivity.IN_VEHICLE.value))
		dao.insert(createSegment(dayTwo + 3_000L, dayTwo + 4_000L, 100f, DetectedActivity.ON_BICYCLE.value))

		assertEquals(
			2L,
			dao.countDistinctDaysByActivities(listOf(DetectedActivity.IN_VEHICLE.value)),
		)
		assertEquals(
			1L,
			dao.countDistinctDaysByActivities(listOf(DetectedActivity.ON_BICYCLE.value)),
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
		)
	}
}
