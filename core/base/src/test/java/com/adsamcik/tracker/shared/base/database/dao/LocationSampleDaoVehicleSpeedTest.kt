package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import android.database.Cursor
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the vehicle-only chunk projection used by the
 * "Vehicle speed compliance" map layer:
 *
 *  - returns only samples whose timestamp falls inside a `session_segment`
 *    whose `primary_activity` is in the supplied driving list,
 *  - drops samples with NULL coordinates or NULL speed,
 *  - drops samples with an untrustworthy speed reading (LOW/COARSE quality,
 *    or speed_accuracy_mps above the trusted threshold),
 *  - paginates using the (time_ms, id) stable cursor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationSampleDaoVehicleSpeedTest {

	private lateinit var database: AppDatabase
	private lateinit var sampleDao: LocationSampleDao
	private lateinit var segmentDao: SessionSegmentDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		sampleDao = database.locationSampleDao()
		segmentDao = database.sessionSegmentDao()
	}

	@After
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
	fun `driving sample projection retains nullable horizontal accuracy`() = runTest {
		insertSegment(0L, 10_000L, DetectedActivity.IN_VEHICLE.value)
		sampleDao.insert(createSample(timeMs = 1_000L, hAccM = 12.5f))
		sampleDao.insert(createSample(timeMs = 2_000L, hAccM = null))

		val rows = sampleDao.getDrivingChunkBetweenOrdered(
			fromMs = 0L,
			toMs = Long.MAX_VALUE,
			drivingActivities = DRIVING_ACTIVITIES,
			afterTimeMs = null,
			afterId = null,
			limit = 100,
		)

		rows.map { it.hAccM } shouldBe listOf(12.5f, null)
	}

	@Test
	fun `samples with an untrustworthy speed reading are dropped`() = runTest {
		insertSegment(0L, 10_000L, DetectedActivity.IN_VEHICLE.value)

		sampleDao.insert(createSample(timeMs = 1_000L, quality = SampleQuality.LOW))
		sampleDao.insert(createSample(timeMs = 2_000L, quality = SampleQuality.COARSE))
		sampleDao.insert(createSample(timeMs = 3_000L, speedAccuracyMps = 5f)) // above trusted threshold
		sampleDao.insert(createSample(timeMs = 4_000L)) // fully valid (HIGH quality, 0.5 accuracy)
		sampleDao.insert(createSample(timeMs = 5_000L, speedAccuracyMps = null)) // unknown accuracy is trusted
		sampleDao.insert(createSample(timeMs = 6_000L, quality = SampleQuality.MEDIUM)) // MEDIUM is trusted

		val rows = sampleDao.getDrivingChunkBetweenOrdered(
			fromMs = 0L,
			toMs = Long.MAX_VALUE,
			drivingActivities = DRIVING_ACTIVITIES,
			afterTimeMs = null,
			afterId = null,
			limit = 100,
		)

		rows.map { it.timeMs } shouldBe listOf(4_000L, 5_000L, 6_000L)
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

	// region (time_ms, id) composite index planner regression
	// Guards against the SQLite planner regressing the driving-chunk seek query
	// off the composite `idx_location_sample_time_id` index (added in v30
	// in response to R2 round-6 perf review). With a single-column time index
	// alone, the (time_ms = ? AND id > ?) cursor clause cannot be a full
	// index seek and SQLite may fall back to a SCAN+sort plan over location_sample.

	@Test
	fun `driving chunk seek plans as SEARCH not SCAN on location_sample`() = runTest {
		// Populate with enough rows that the planner actually considers indexes.
		// SQLite will prefer SCAN over very small tables.
		insertSegment(0L, Long.MAX_VALUE, DetectedActivity.IN_VEHICLE.value)
		val rows = buildList(PLAN_PROBE_ROW_COUNT) {
			repeat(PLAN_PROBE_ROW_COUNT) { idx ->
				add(createSample(timeMs = (idx + 1) * 100L))
			}
		}
		rows.forEach { sampleDao.insert(it) }

		// Replicate the exact SQL Room generates for getDrivingChunkBetweenOrdered.
		// If that @Query is reshaped into a form SQLite cannot index-seek (e.g. a
		// more complex OR, or an unindexed expression on ls.time_ms), this fires.
		val explainSql = """
			EXPLAIN QUERY PLAN
			SELECT ls.time_ms AS time_ms,
			       ls.id AS id,
			       ls.lat_e7 AS lat_e7,
			       ls.lon_e7 AS lon_e7,
			       ls.speed_mps AS speed_mps,
			       ls.h_acc_m AS h_acc_m
			FROM location_sample ls
			INNER JOIN session_segment ss
				ON ls.time_ms BETWEEN ss.start_time_ms AND ss.end_time_ms
			WHERE ss.primary_activity IN (?)
				AND ls.lat_e7 IS NOT NULL
				AND ls.lon_e7 IS NOT NULL
				AND ls.speed_mps IS NOT NULL
				AND ls.quality NOT IN ('LOW', 'COARSE')
				AND (ls.speed_accuracy_mps IS NULL OR ls.speed_accuracy_mps <= 3.0)
				AND ls.time_ms >= ?
				AND ls.time_ms <= ?
				AND (
					? IS NULL
					OR ls.time_ms > ?
					OR (ls.time_ms = ? AND ls.id > COALESCE(?, 0))
				)
			ORDER BY ls.time_ms ASC, ls.id ASC
			LIMIT ?
		""".trimIndent()

		val raw = database.openHelper.readableDatabase
		val args = arrayOf<Any?>(
			DetectedActivity.IN_VEHICLE.value,
			0L,
			Long.MAX_VALUE,
			null,
			0L,
			0L,
			null,
			PLAN_PROBE_LIMIT,
		)
		val planLines = mutableListOf<String>()
		raw.query(explainSql, args).use { cursor: Cursor ->
			val detailColumn = cursor.getColumnIndexOrThrow("detail")
			while (cursor.moveToNext()) {
				planLines += cursor.getString(detailColumn)
			}
		}

		check(planLines.isNotEmpty()) { "EXPLAIN QUERY PLAN returned no rows" }

		// Hard regression guard: any SCAN of the location_sample table means the
		// planner regressed off all indexes. (A SCAN of session_segment may be OK
		// at small sizes — only the location_sample side carries the perf risk
		// because location_sample dominates row count in production.)
		val scanOfTable = planLines.filter { line ->
			line.contains("SCAN") && line.contains("location_sample") &&
				!line.contains("USING INDEX") && !line.contains("USING COVERING INDEX")
		}
		check(scanOfTable.isEmpty()) {
			"Query plan regressed to SCAN of location_sample:\n${planLines.joinToString("\n")}"
		}
	}

	// endregion (time_ms, id) composite index planner regression

	private fun createSample(
		timeMs: Long,
		latE7: Int? = 500_000_000,
		lonE7: Int? = 140_000_000,
		speedMps: Float? = 1.5f,
		quality: SampleQuality = SampleQuality.HIGH,
		speedAccuracyMps: Float? = 0.5f,
		hAccM: Float? = 5f,
	) = LocationSample(
		timeMs = timeMs,
		elapsedRealtimeNanos = timeMs * 1_000_000L,
		latE7 = latE7,
		lonE7 = lonE7,
		altitudeM = 100f,
		rawGpsAltitudeM = 100f,
		hAccM = hAccM,
		vAccM = 10f,
		speedMps = speedMps,
		speedAccuracyMps = speedAccuracyMps,
		provider = "fused",
		quality = quality,
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
		const val PLAN_PROBE_ROW_COUNT = 64
		const val PLAN_PROBE_LIMIT = 32
	}
}
