package com.adsamcik.tracker.shared.base.database.analysis

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationObservation
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.PresenceInterval
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresenceCompactorRobolectricTest {
	private lateinit var database: AppDatabase
	private lateinit var compactor: PresenceCompactor

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		compactor = PresenceCompactor(database, nowMillis = { NOW_MS })
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `late old observation rebuilds its day before observation watermark advances`() = runTest {
		val day = 10L * Time.DAY_IN_MILLISECONDS
		val firstId = database.locationObservationDao().insert(
			observation(day + 10 * Time.MINUTE_IN_MILLISECONDS),
		)

		val initial = compactor.compactThroughExclusive(day + Time.DAY_IN_MILLISECONDS)

		assertEquals(firstId, initial.safeObservationId)
		assertEquals(
			1,
			database.presenceAnalysisDao().maxGeneration(
				PresenceCompactor.METRIC_KIND,
				PresenceCompactor.MODEL_KEY,
				MetricAnalysisGrid.GRID_VERSION,
				day,
			),
		)

		val lateId = database.locationObservationDao().insert(
			observation(day + 20 * Time.MINUTE_IN_MILLISECONDS),
		)
		val reconciled = compactor.compactThroughExclusive(day + Time.DAY_IN_MILLISECONDS)

		assertEquals(lateId, reconciled.safeObservationId)
		assertEquals(1, reconciled.partitionsProcessed)
		assertEquals(
			2,
			database.presenceAnalysisDao().maxGeneration(
				PresenceCompactor.METRIC_KIND,
				PresenceCompactor.MODEL_KEY,
				MetricAnalysisGrid.GRID_VERSION,
				day,
			),
		)
		assertTrue(
			database.presenceIntervalDao()
				.getOverlapping(PresenceCompactor.MODEL_KEY, day, day + Time.DAY_IN_MILLISECONDS)
				.any { it.sourceFirstObservationId == lateId },
		)
	}

	@Test
	fun `migrated observation without tracker run receives bounded standalone support`() = runTest {
		val fix = 20L * Time.DAY_IN_MILLISECONDS + 12 * 60 * Time.MINUTE_IN_MILLISECONDS
		val observationId = database.locationObservationDao().insert(observation(fix))

		val rows = compactor.materializePresence(
			fix - 2 * Time.MINUTE_IN_MILLISECONDS,
			fix + 2 * Time.MINUTE_IN_MILLISECONDS,
		)

		assertEquals(1, rows.size)
		val row = rows.single()
		assertEquals(-observationId, row.sessionId)
		assertEquals(fix - Time.MINUTE_IN_MILLISECONDS, row.startTimeMs)
		assertEquals(fix + Time.MINUTE_IN_MILLISECONDS, row.endTimeMs)
		assertEquals("OBSERVED", row.resolutionState)
		assertEquals("UNKNOWN", row.motionState)
		assertEquals("BEHAVIOUR_NOT_CLASSIFIED", row.unresolvedReason)
		assertEquals(observationId, row.sourceFirstObservationId)
	}

	@Test
	fun `overlapping orphan tracker runs materialize one globally non-overlapping timeline`() = runTest {
		val start = 30L * Time.DAY_IN_MILLISECONDS
		val tenMinutes = 10 * Time.MINUTE_IN_MILLISECONDS
		database.trackerRunDao().insert(run(start, start + tenMinutes))
		database.trackerRunDao().insert(
			run(start + 5 * Time.MINUTE_IN_MILLISECONDS, start + 15 * Time.MINUTE_IN_MILLISECONDS),
		)

		val rows = compactor.materializePresence(start, start + 15 * Time.MINUTE_IN_MILLISECONDS)

		assertEquals(2, rows.size)
		assertEquals(15 * Time.MINUTE_IN_MILLISECONDS, rows.sumOf { it.endTimeMs - it.startTimeMs })
		assertEquals(rows[0].endTimeMs, rows[1].startTimeMs)
		assertEquals(
			0,
			database.presenceIntervalDao().countOverlappingPairs(
				PresenceCompactor.MODEL_KEY,
				start,
				start + 15 * Time.MINUTE_IN_MILLISECONDS,
			),
		)
	}

	@Test
	fun `partition boundaries use observations on both sides of UTC midnight`() = runTest {
		val midnight = 40L * Time.DAY_IN_MILLISECONDS
		database.trackerRunDao().insert(
			run(midnight - 10 * Time.MINUTE_IN_MILLISECONDS, midnight + 10 * Time.MINUTE_IN_MILLISECONDS),
		)
		database.locationObservationDao().insert(
			observation(midnight - 2 * Time.MINUTE_IN_MILLISECONDS),
		)
		database.locationObservationDao().insert(
			observation(midnight + 2 * Time.MINUTE_IN_MILLISECONDS),
		)

		val before = compactor.materializePresence(midnight - Time.DAY_IN_MILLISECONDS, midnight)
		val after = compactor.materializePresence(midnight, midnight + Time.DAY_IN_MILLISECONDS)
		val observedBefore = before.filter { it.resolutionState == "OBSERVED" }
		val observedAfter = after.filter { it.resolutionState == "OBSERVED" }

		assertEquals(midnight, observedBefore.maxOf { it.endTimeMs })
		assertEquals(midnight, observedAfter.minOf { it.startTimeMs })
	}

	@Test
	fun `fragment rebuild deletes crossing row and preserves only its outside pieces`() = runTest {
		val start = 50L * Time.DAY_IN_MILLISECONDS
		database.presenceIntervalDao().insert(
			presence(startMs = start, endMs = start + 1_000L),
		)

		compactor.materializePresence(start + 300L, start + 700L)

		val rows = database.presenceIntervalDao().getOverlapping(
			PresenceCompactor.MODEL_KEY,
			start,
			start + 1_000L,
		)
		assertEquals(2, rows.size)
		assertEquals(
			listOf(start to start + 300L, start + 700L to start + 1_000L),
			rows.map { it.startTimeMs to it.endTimeMs },
		)
		assertEquals(
			0,
			database.presenceIntervalDao().countOverlappingPairs(
				PresenceCompactor.MODEL_KEY,
				start,
				start + 1_000L,
			),
		)
	}

	@Test
	fun `live observation needs a matching accepted sample before it can seed presence`() = runTest {
		val start = 60L * Time.DAY_IN_MILLISECONDS
		val fix = start + 5 * Time.MINUTE_IN_MILLISECONDS
		database.trackerRunDao().insert(run(start, start + 10 * Time.MINUTE_IN_MILLISECONDS))
		database.locationObservationDao().insert(
			observation(fix, ingressDisposition = "REJECTED_INVALID_COORDINATE"),
		)
		database.locationObservationDao().insert(
			observation(fix, ingressDisposition = "DELIVERED_VALID"),
		)

		val withoutAcceptedSample = compactor.materializePresence(
			start,
			start + 10 * Time.MINUTE_IN_MILLISECONDS,
		)
		assertTrue(withoutAcceptedSample.all { it.resolutionState == "UNRESOLVED" })

		database.locationSampleDao().insert(sample(fix))
		val withAcceptedSample = compactor.materializePresence(
			start,
			start + 10 * Time.MINUTE_IN_MILLISECONDS,
		)
		assertTrue(withAcceptedSample.any { it.resolutionState == "OBSERVED" })
		assertTrue(
			withAcceptedSample.filter { it.resolutionState == "OBSERVED" }
				.all { it.sourceFirstObservationId != null },
		)
	}

	@Test
	fun `committed presence generation conserves exact milliseconds at every supported level`() = runTest {
		val day = 70L * Time.DAY_IN_MILLISECONDS
		val runEnd = day + 10 * Time.MINUTE_IN_MILLISECONDS
		database.trackerRunDao().insert(run(day, runEnd))
		database.locationObservationDao().insert(
			observation(day + 5 * Time.MINUTE_IN_MILLISECONDS),
		)

		compactor.compactPartition(day)

		val dao = database.presenceAnalysisDao()
		val block = requireNotNull(
			dao.getCommittedBlock(
				PresenceCompactor.METRIC_KIND,
				PresenceCompactor.MODEL_KEY,
				MetricAnalysisGrid.GRID_VERSION,
				day,
			),
		)
		assertEquals(PresenceCompactor.METRIC_KIND, block.metricKind)
		assertEquals(runEnd - day, block.trackedMs)
		assertEquals(2 * Time.MINUTE_IN_MILLISECONDS, block.spatiallyObservedMs)
		assertEquals(0L, block.spatiallyInferredMs)
		assertEquals(8 * Time.MINUTE_IN_MILLISECONDS, block.spatiallyUnresolvedMs)

		MetricAnalysisGrid.RESOLUTIONS_M.forEach { resolution ->
			val expectedAtLevel = if (resolution >= 100) 2 * Time.MINUTE_IN_MILLISECONDS else 0L
			val actualAtLevel = dao.getViewportCells(
				metricKind = PresenceCompactor.METRIC_KIND,
				modelKey = PresenceCompactor.MODEL_KEY,
				gridVersion = MetricAnalysisGrid.GRID_VERSION,
				resolutionM = resolution,
				fromMs = day,
				toMs = day + Time.DAY_IN_MILLISECONDS,
				southE7 = -900_000_000,
				northE7 = 900_000_000,
				westE7 = -1_800_000_000,
				eastE7 = 1_800_000_000,
			).sumOf { it.expectedMs }
			assertEquals("mass at ${resolution}m", expectedAtLevel, actualAtLevel)
		}
	}

	@Test
	fun `partition replacement leaves exactly one committed generation`() = runTest {
		val day = 80L * Time.DAY_IN_MILLISECONDS
		database.trackerRunDao().insert(run(day, day + 10 * Time.MINUTE_IN_MILLISECONDS))
		database.locationObservationDao().insert(observation(day + 5 * Time.MINUTE_IN_MILLISECONDS))

		compactor.compactPartition(day)
		compactor.compactPartition(day)

		val dao = database.presenceAnalysisDao()
		assertEquals(
			2,
			dao.maxGeneration(
				PresenceCompactor.METRIC_KIND,
				PresenceCompactor.MODEL_KEY,
				MetricAnalysisGrid.GRID_VERSION,
				day,
			),
		)
		assertEquals(
			1,
			dao.countCommittedBlocks(
				PresenceCompactor.METRIC_KIND,
				PresenceCompactor.MODEL_KEY,
				MetricAnalysisGrid.GRID_VERSION,
				day,
			),
		)
	}

	private fun observation(
		fixTimeMs: Long,
		ingressDisposition: String = "MIGRATED_ACCEPTED",
	): LocationObservation = LocationObservation(
		fixTimeMs = fixTimeMs,
		fixElapsedRealtimeNanos = fixTimeMs * Time.MILLISECONDS_IN_NANOSECONDS,
		receivedAtMs = fixTimeMs,
		receivedElapsedRealtimeNanos = fixTimeMs * Time.MILLISECONDS_IN_NANOSECONDS,
		deliveryAgeMs = 0L,
		latE7 = LAT_E7,
		lonE7 = LON_E7,
		rawAltitudeM = null,
		hAccM = 5f,
		vAccM = null,
		speedMps = null,
		speedAccuracyMps = null,
		provider = "fused",
		acquisitionMode = "PERIODIC",
		requestPriority = "HIGH_ACCURACY",
		permissionPrecision = "PRECISE",
		batchIndex = 0,
		batchSize = 1,
		isMock = false,
		ingressDisposition = ingressDisposition,
		estimatorVersion = 1,
		calibrationVersion = 0,
		createdAt = fixTimeMs,
	)

	private fun sample(fixTimeMs: Long): LocationSample = LocationSample(
		timeMs = fixTimeMs,
		elapsedRealtimeNanos = fixTimeMs * Time.MILLISECONDS_IN_NANOSECONDS,
		latE7 = LAT_E7,
		lonE7 = LON_E7,
		altitudeM = null,
		rawGpsAltitudeM = null,
		hAccM = 5f,
		vAccM = null,
		speedMps = null,
		speedAccuracyMps = null,
		provider = "fused",
		quality = SampleQuality.HIGH,
		motionState = null,
		policy = "ACTIVE_ELEVATED",
		bucketId = null,
		createdAt = fixTimeMs,
		batchIndex = 0,
		batchSize = 1,
	)

	private fun run(startTimeMs: Long, endTimeMs: Long): TrackerRun = TrackerRun(
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		policy = "ACTIVE_ELEVATED",
		policyParams = null,
		userInitiated = false,
		createdAt = startTimeMs,
	)

	private fun presence(startMs: Long, endMs: Long): PresenceInterval = PresenceInterval(
		sessionId = 42L,
		modelKey = PresenceCompactor.MODEL_KEY,
		estimatorVersion = PresenceCompactor.ESTIMATOR_VERSION,
		calibrationVersion = PresenceCompactor.CALIBRATION_VERSION,
		configHash = PresenceCompactor.CONFIG_HASH,
		startTimeMs = startMs,
		endTimeMs = endMs,
		startElapsedRealtimeNanos = startMs * Time.MILLISECONDS_IN_NANOSECONDS,
		endElapsedRealtimeNanos = endMs * Time.MILLISECONDS_IN_NANOSECONDS,
		resolutionState = "UNRESOLVED",
		motionState = null,
		provenance = "TRACKER_RUN",
		unresolvedReason = "LOCATION_GAP",
		centerLatE7 = null,
		centerLonE7 = null,
		covarianceXxM2 = null,
		covarianceXyM2 = null,
		covarianceYyM2 = null,
		effectiveR90M = null,
		posteriorFormat = null,
		posteriorPayload = null,
		sourceFirstObservationId = null,
		sourceLastObservationId = null,
		createdAt = NOW_MS,
	)

	companion object {
		private const val LAT_E7 = 500_000_000
		private const val LON_E7 = 140_000_000
		private const val NOW_MS = 100L * Time.DAY_IN_MILLISECONDS
	}
}
