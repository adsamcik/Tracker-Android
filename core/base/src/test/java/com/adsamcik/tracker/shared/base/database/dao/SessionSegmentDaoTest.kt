package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.model.SegmentSource
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

	@Test
	fun `canonical activities satisfy triathlon day`() = runBlocking {
		val day = LocalDate.of(2026, 1, 10)
			.atStartOfDay(ZoneId.systemDefault())
			.toInstant()
			.toEpochMilli()
		dao.insert(
			createSegment(
				day + 1_000L,
				day + 2_000L,
				100f,
				DetectedActivity.WALKING.value,
			),
		)
		dao.insert(
			createSegment(
				day + 3_000L,
				day + 4_000L,
				100f,
				DetectedActivity.ON_BICYCLE.value,
			),
		)
		dao.insert(
			createSegment(
				day + 5_000L,
				day + 6_000L,
				100f,
				DetectedActivity.IN_VEHICLE.value,
			),
		)

		assertEquals(
			1L,
			dao.countTriathlonDays(
				walk = ON_FOOT_ACTIVITY_TYPES,
				cycle = listOf(DetectedActivity.ON_BICYCLE.value),
				drive = IN_VEHICLE_ACTIVITY_TYPES,
			),
		)
	}

	@Test
	fun `zero sample placeholders are excluded from product segment queries`() = runBlocking {
		val placeholderId = dao.insert(
			createSegment(1_000L, 2_000L, 100f, DetectedActivity.WALKING.value)
				.copy(sampleCount = 0),
		)
		val recordedId = dao.insert(
			createSegment(2_000L, 3_000L, 200f, DetectedActivity.WALKING.value),
		)

		assertEquals(1, dao.getOverlapping(0L, 4_000L).size)
		assertEquals(
			listOf(placeholderId, recordedId),
			dao.getAllOverlappingForSourceRepair(
				fromMs = 0L,
				toMs = 4_000L,
				excludedSegmentId = Long.MIN_VALUE,
				limit = 3,
			).map(SessionSegment::id),
		)
		assertEquals(1, dao.countBySource(SegmentSource.USER_CREATED))
		assertEquals(1L, dao.countDistinctActivities())
		assertEquals(1L, dao.countByActivity(DetectedActivity.WALKING.value))
		assertEquals(1L, dao.countByActivities(listOf(DetectedActivity.WALKING.value)))
	}

	@Test
	fun `source repair overlap query excludes selected row and applies deterministic SQL cap`() =
		runBlocking {
			dao.insert(
				createSegment(2_000L, 3_000L, 10f, DetectedActivity.WALKING.value),
			)
			val excludedId = dao.insert(
				createSegment(1_000L, 2_000L, 20f, DetectedActivity.WALKING.value),
			)
			val tieAId = dao.insert(
				createSegment(1_000L, 2_000L, 30f, DetectedActivity.WALKING.value),
			)
			val tieBId = dao.insert(
				createSegment(1_000L, 2_000L, 40f, DetectedActivity.WALKING.value),
			)

			assertEquals(
				listOf(tieAId, tieBId),
				dao.getAllOverlappingForSourceRepair(
					fromMs = 0L,
					toMs = 4_000L,
					excludedSegmentId = excludedId,
					limit = 2,
				).map(SessionSegment::id),
			)
		}

	@Test
	fun `source repair segment pages preserve half open overlap ties and exact once ordering`() =
		runBlocking {
			dao.insert(createSegment(0L, 1_000L, 1f, DetectedActivity.WALKING.value))
			val tieAId = dao.insert(
				createSegment(1_000L, 1_200L, 2f, DetectedActivity.WALKING.value),
			)
			val tieBId = dao.insert(
				createSegment(1_000L, 1_300L, 3f, DetectedActivity.WALKING.value),
			)
			val excludedId = dao.insert(
				createSegment(1_250L, 1_350L, 4f, DetectedActivity.WALKING.value),
			)
			val middleId = dao.insert(
				createSegment(1_500L, 1_700L, 5f, DetectedActivity.WALKING.value),
			)
			val lateId = dao.insert(
				createSegment(2_500L, 2_900L, 6f, DetectedActivity.WALKING.value),
			)
			dao.insert(createSegment(3_000L, 3_100L, 7f, DetectedActivity.WALKING.value))

			val seen = mutableListOf<SessionSegment>()
			var afterStartTimeMs: Long? = null
			var afterSegmentId: Long? = null
			while (true) {
				val page = dao.sourceRepairSegmentPage(
					fromMs = 1_000L,
					toMs = 3_000L,
					excludedSegmentId = excludedId,
					limit = 2,
					afterStartTimeMs = afterStartTimeMs,
					afterSegmentId = afterSegmentId,
				)
				seen += page
				val last = page.lastOrNull() ?: break
				afterStartTimeMs = last.startTimeMs
				afterSegmentId = last.id
				if (page.size < 2) {
					break
				}
			}

			val expectedIds = listOf(tieAId, tieBId, middleId, lateId)
			assertEquals(expectedIds, seen.map(SessionSegment::id))
			assertEquals(expectedIds.size, seen.map(SessionSegment::id).distinct().size)
		}

	@Test
	fun `crash placeholder is excluded from product time and activity inference queries`() = runBlocking {
		val day = LocalDate.of(2026, 1, 10).atStartOfDay(ZoneId.systemDefault())
		val dayStart = day.toInstant().toEpochMilli()
		val dayEnd = day.plusDays(1).toInstant().toEpochMilli()
		val placeholderStart = day.plusHours(2).toInstant().toEpochMilli()
		val recordedStart = day.plusHours(12).toInstant().toEpochMilli()
		dao.insert(
			createSegment(placeholderStart, placeholderStart, 0f, null).copy(
				steps = 0,
				activityConfidence = null,
				sampleCount = 0,
				inferenceVersion = "tracker_v2",
				logicalTrackingId = "logical-crash",
				serviceRunId = "run-crash",
			),
		)
		val unpersisted = createSegment(recordedStart, recordedStart + 1_000L, 0f, null)
		val recorded = unpersisted.copy(id = dao.insert(unpersisted))

		assertEquals(recordedStart, dao.minStartTime())
		assertEquals(1L, dao.countDistinctStartHours())
		assertEquals(0L, dao.countSessionsStartingBetweenHours(0, 5))
		assertEquals(
			SessionSegmentBounds(recordedStart, recordedStart + 1_000L),
			dao.getUnrecognizedBounds(),
		)
		assertEquals(
			listOf(recorded),
			dao.getUnrecognizedWithin(dayStart, dayEnd),
		)
		assertEquals(
			listOf(recorded),
			dao.getUnrecognizedStartingBetween(dayStart, dayEnd),
		)
	}

	@Test
	fun `deleteExact requires both durable logical and service run ownership`() = runBlocking {
		val selected = createSegment(1_000L, 2_000L, 10f, null).copy(
			logicalTrackingId = "logical-selected",
			serviceRunId = "run-selected",
		)
		val selectedId = dao.insert(selected)
		val unrelatedId = dao.insert(
			createSegment(3_000L, 4_000L, 20f, null).copy(
				logicalTrackingId = "logical-unrelated",
				serviceRunId = "run-unrelated",
			),
		)

		assertEquals(0, dao.deleteExact(selectedId, "logical-selected", "wrong-run"))
		assertEquals(1, dao.deleteExact(selectedId, "logical-selected", "run-selected"))
		assertEquals(null, dao.getById(selectedId))
		assertEquals("run-unrelated", dao.getById(unrelatedId)?.serviceRunId)
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
