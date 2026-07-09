package com.adsamcik.tracker.activity

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ActivitySnapshotDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.testing.fake.FakeTrackingParamsRepository
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActivityRecognitionWorkerTest {

	private val context: Context
		get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()
	private val database: AppDatabase = mockk(relaxed = true)
	private val skiInfrastructureManager: com.adsamcik.tracker.activity.ski.SkiInfrastructureManager = mockk(relaxed = true)
	private val tripDao: TripDao = mockk(relaxed = true)
	private val locationSampleDao: LocationSampleDao = mockk(relaxed = true)
	private val activitySnapshotDao: ActivitySnapshotDao = mockk(relaxed = true)
	private val pressureSampleDao: PressureSampleDao = mockk(relaxed = true)
	private val segmentDao: SessionSegmentDao = mockk(relaxed = true)
	private lateinit var trackingParamsRepository: FakeTrackingParamsRepository

	@Before
	fun setUp() {
		mockkObject(AppDatabase.Companion)
		mockkObject(Reporter)
		mockkObject(Logger)

		every { AppDatabase.database(any()) } returns database
		every { database.tripDao() } returns tripDao
		every { database.locationSampleDao() } returns locationSampleDao
		every { database.activitySnapshotDao() } returns activitySnapshotDao
		every { database.pressureSampleDao() } returns pressureSampleDao
		every { database.sessionSegmentDao() } returns segmentDao
		every { Reporter.report(any<Throwable>()) } just runs
		every { Reporter.log(any<String>()) } just runs
		every { Logger.logWithStringPreference(any(), any(), any()) } just runs
		coEvery { activitySnapshotDao.getLatestBefore(any()) } returns null
		coEvery { activitySnapshotDao.getAllBetween(any(), any()) } returns emptyList()
		coEvery { pressureSampleDao.getAllBetween(any(), any()) } returns emptyList()
		trackingParamsRepository = FakeTrackingParamsRepository(
			TrackingParamsState(skiDetectionEnabled = true),
		)
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	private fun buildWorker(sessionId: Long): ActivityRecognitionWorker {
		val inputData = Data.Builder()
			.putLong(ActivityRecognitionWorker.ARG_SESSION_ID, sessionId)
			.build()

		val params: WorkerParameters = mockk(relaxed = true) {
			every { getInputData() } returns inputData
		}

		return ActivityRecognitionWorker(
			context,
			params,
			database,
			skiInfrastructureManager,
			trackingParamsRepository,
		)
	}

	private fun buildWorkerWithNoSessionId(): ActivityRecognitionWorker {
		val inputData = Data.Builder().build()

		val params: WorkerParameters = mockk(relaxed = true) {
			every { getInputData() } returns inputData
		}

		return ActivityRecognitionWorker(
			context,
			params,
			database,
			skiInfrastructureManager,
			trackingParamsRepository,
		)
	}

	private fun createTrip(
		id: Long = 1L,
		start: Long = 1_700_000_000_000L,
		end: Long = 1_700_000_060_000L
	): Trip = Trip(
		id = id,
		startTimeMs = start,
		endTimeMs = end,
		distanceM = 0f,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		createdAt = System.currentTimeMillis(),
	)

	private fun createLocationSamples(
		count: Int,
		startTime: Long = 1_700_000_000_000L
	): List<LocationSample> = (0 until count).map { i ->
		LocationSample(
			timeMs = startTime + i * 1000L,
			elapsedRealtimeNanos = 0L,
			latE7 = (50.0 * 1e7).toInt(),
			lonE7 = (14.0 * 1e7).toInt(),
			altitudeM = null,
			rawGpsAltitudeM = null,
			hAccM = 10f,
			vAccM = null,
			speedMps = null,
			speedAccuracyMps = null,
			provider = "gps",
			quality = SampleQuality.HIGH,
			motionState = null,
			policy = null,
			bucketId = null,
			createdAt = System.currentTimeMillis(),
		)
	}

	private fun createSegment(
		id: Long = 1L,
		startTimeMs: Long = 1_700_000_000_000L,
		endTimeMs: Long = 1_700_000_060_000L,
	): SessionSegment = SessionSegment(
		id = id,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		distanceM = 0f,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = null,
		createdAt = System.currentTimeMillis(),
	)

	private fun createActivitySnapshot(
		activity: DetectedActivity,
		confidence: Int = 80,
		timeMs: Long = 1_700_000_000_000L,
	): ActivitySnapshot = ActivitySnapshot(
		id = timeMs,
		timeMs = timeMs,
		activityType = activity.value,
		confidence = confidence,
		isTransition = false,
		createdAt = timeMs,
	)

	@Test
	fun `doWork returns failure when session id is not set`()  { runTest {
			val worker = buildWorkerWithNoSessionId()

			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.failure()
			verify { Reporter.report(any<Throwable>()) }
		} }

	@Test
	fun `doWork returns failure when session not found in database`()  { runTest {
			coEvery { tripDao.getById(42L) } returns null
			val worker = buildWorker(42L)

			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.failure()
		} }

	@Test
	fun `doWork returns success when no recognizer produces a result`()  { runTest {
			val trip = createTrip()
			coEvery { tripDao.getById(1L) } returns trip
			// Empty locations and no segments needing recognition → success
			coEvery { segmentDao.getUnrecognizedWithin(any(), any()) } returns emptyList()

			val worker = buildWorker(1L)
			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.success()
		} }

	@Test
	fun `doWork returns success and updates segment for walking activity snapshot`()  { runTest {
			val trip = createTrip(id = 5L)
			coEvery { tripDao.getById(5L) } returns trip
			val samples = createLocationSamples(count = 20)
			coEvery { locationSampleDao.getChunkBetweenOrdered(any(), any(), any(), any(), any()) } returnsMany listOf(samples, emptyList())
			coEvery { activitySnapshotDao.getAllBetween(any(), any()) } returns listOf(
				createActivitySnapshot(DetectedActivity.WALKING, confidence = 82),
			)
			val segment = createSegment(startTimeMs = trip.startTimeMs, endTimeMs = trip.endTimeMs)
			coEvery { segmentDao.getUnrecognizedWithin(any(), any()) } returns listOf(segment)
			var updatedSegment: SessionSegment? = null
			coEvery { segmentDao.update(any<SessionSegment>()) } answers {
				updatedSegment = firstArg()
				Unit
			}

			val worker = buildWorker(5L)
			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.success()
			updatedSegment?.primaryActivity shouldBe DetectedActivity.WALKING.value
			updatedSegment?.activityConfidence shouldBe 82
		} }

	@Test
	fun `doWork returns success and updates segment for vehicle activity snapshot`()  { runTest {
			val trip = createTrip(id = 10L)
			coEvery { tripDao.getById(10L) } returns trip
			val samples = createLocationSamples(count = 20)
			coEvery { locationSampleDao.getChunkBetweenOrdered(any(), any(), any(), any(), any()) } returnsMany listOf(samples, emptyList())
			coEvery { activitySnapshotDao.getAllBetween(any(), any()) } returns listOf(
				createActivitySnapshot(DetectedActivity.IN_VEHICLE, confidence = 91),
			)
			val segment = createSegment(startTimeMs = trip.startTimeMs, endTimeMs = trip.endTimeMs)
			coEvery { segmentDao.getUnrecognizedWithin(any(), any()) } returns listOf(segment)
			var updatedSegment: SessionSegment? = null
			coEvery { segmentDao.update(any<SessionSegment>()) } answers {
				updatedSegment = firstArg()
				Unit
			}

			val worker = buildWorker(10L)
			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.success()
			updatedSegment?.primaryActivity shouldBe DetectedActivity.IN_VEHICLE.value
			updatedSegment?.activityConfidence shouldBe 91
		} }

	@Test
	fun `doWork leaves segment unrecognized when no persisted activity signal exists`()  { runTest {
			val trip = createTrip(id = 12L)
			coEvery { tripDao.getById(12L) } returns trip
			val samples = createLocationSamples(count = 20)
			coEvery { locationSampleDao.getChunkBetweenOrdered(any(), any(), any(), any(), any()) } returnsMany listOf(samples, emptyList())
			val segment = createSegment(startTimeMs = trip.startTimeMs, endTimeMs = trip.endTimeMs)
			coEvery { segmentDao.getUnrecognizedWithin(any(), any()) } returns listOf(segment)

			val worker = buildWorker(12L)
			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.success()
			coVerify(exactly = 0) { segmentDao.update(any<SessionSegment>()) }
		} }

	@Test
	fun `doWork skips pressure loading when ski detection is disabled`()  { runTest {
			trackingParamsRepository.setSkiDetectionEnabled(false)
			val trip = createTrip(id = 13L)
			coEvery { tripDao.getById(13L) } returns trip
			val samples = createLocationSamples(count = 20)
			coEvery { locationSampleDao.getChunkBetweenOrdered(any(), any(), any(), any(), any()) } returnsMany listOf(samples, emptyList())
			val segment = createSegment(startTimeMs = trip.startTimeMs, endTimeMs = trip.endTimeMs)
			coEvery { segmentDao.getUnrecognizedWithin(any(), any()) } returns listOf(segment)

			val worker = buildWorker(13L)
			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.success()
			coVerify(exactly = 0) { pressureSampleDao.getAllBetween(any(), any()) }
		} }

	@Test
	fun `doWork returns failure for negative session id`()  { runTest {
			val inputData = Data.Builder()
				.putLong(ActivityRecognitionWorker.ARG_SESSION_ID, -5L)
				.build()
			val params: WorkerParameters = mockk(relaxed = true) {
				every { getInputData() } returns inputData
			}
			val worker = ActivityRecognitionWorker(
				context,
				params,
				database,
				skiInfrastructureManager,
				trackingParamsRepository,
			)

			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.failure()
		} }

	@Test
	fun `companion constants ARG_SESSION_ID has expected value`() {
		ActivityRecognitionWorker.ARG_SESSION_ID shouldBe "sessionId"
	}

	@Test
	fun `companion constants ARG_BATCH_MODE has expected value`() {
		ActivityRecognitionWorker.ARG_BATCH_MODE shouldBe "batchMode"
	}

	@Test
	fun `companion constants WORK_TAG has expected value`() {
		ActivityRecognitionWorker.WORK_TAG shouldBe "ActivityRecognition"
	}
}
