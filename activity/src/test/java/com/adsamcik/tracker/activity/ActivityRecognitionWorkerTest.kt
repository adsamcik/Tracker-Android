package com.adsamcik.tracker.activity

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.Trip
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@DisplayName("ActivityRecognitionWorker")
class ActivityRecognitionWorkerTest {

	private val context: Context
		get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()
	private val database: AppDatabase = mockk(relaxed = true)
	private val skiInfrastructureManager: com.adsamcik.tracker.activity.ski.SkiInfrastructureManager = mockk(relaxed = true)
	private val tripDao: TripDao = mockk(relaxed = true)
	private val locationSampleDao: LocationSampleDao = mockk(relaxed = true)
	private val segmentDao: SessionSegmentDao = mockk(relaxed = true)

	@BeforeEach
	fun setUp() {
		mockkObject(AppDatabase.Companion)
		mockkObject(Reporter)
		mockkObject(Logger)

		every { AppDatabase.database(any()) } returns database
		every { database.tripDao() } returns tripDao
		every { database.locationSampleDao() } returns locationSampleDao
		every { database.sessionSegmentDao() } returns segmentDao
		every { Reporter.report(any<Throwable>()) } just runs
		every { Reporter.log(any<String>()) } just runs
		every { Logger.logWithStringPreference(any(), any(), any()) } just runs
	}

	@AfterEach
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

		return ActivityRecognitionWorker(context, params, database, skiInfrastructureManager)
	}

	private fun buildWorkerWithNoSessionId(): ActivityRecognitionWorker {
		val inputData = Data.Builder().build()

		val params: WorkerParameters = mockk(relaxed = true) {
			every { getInputData() } returns inputData
		}

		return ActivityRecognitionWorker(context, params, database, skiInfrastructureManager)
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

	@Nested
	@DisplayName("doWork")
	inner class DoWork {

		@Test
		fun `returns failure when session id is not set`()  { runTest {
			val worker = buildWorkerWithNoSessionId()

			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.failure()
			verify { Reporter.report(any<Throwable>()) }
		} }

		@Test
		fun `returns failure when session not found in database`()  { runTest {
			coEvery { tripDao.getById(42L) } returns null
			val worker = buildWorker(42L)

			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.failure()
		} }

		@Test
		fun `returns success when no recognizer produces a result`()  { runTest {
			val trip = createTrip()
			coEvery { tripDao.getById(1L) } returns trip
			// Empty locations and no segments needing recognition → success
			coEvery { segmentDao.getUnrecognizedWithin(any(), any()) } returns emptyList()

			val worker = buildWorker(1L)
			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.success()
		} }

		@Test
		fun `returns success and updates segment for walking activity`()  { runTest {
			val trip = createTrip(id = 5L)
			coEvery { tripDao.getById(5L) } returns trip
			val samples = createLocationSamples(count = 20)
			coEvery { locationSampleDao.getChunkBetweenOrdered(any(), any(), any(), any(), any()) } returnsMany listOf(samples, emptyList())
			val segment = createSegment(startTimeMs = trip.startTimeMs, endTimeMs = trip.endTimeMs)
			coEvery { segmentDao.getUnrecognizedWithin(any(), any()) } returns listOf(segment)
			coEvery { segmentDao.update(any<SessionSegment>()) } just runs

			val worker = buildWorker(5L)
			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.success()
		} }

		@Test
		fun `returns success and updates segment for vehicle activity`()  { runTest {
			val trip = createTrip(id = 10L)
			coEvery { tripDao.getById(10L) } returns trip
			val samples = createLocationSamples(count = 20)
			coEvery { locationSampleDao.getChunkBetweenOrdered(any(), any(), any(), any(), any()) } returnsMany listOf(samples, emptyList())
			val segment = createSegment(startTimeMs = trip.startTimeMs, endTimeMs = trip.endTimeMs)
			coEvery { segmentDao.getUnrecognizedWithin(any(), any()) } returns listOf(segment)
			coEvery { segmentDao.update(any<SessionSegment>()) } just runs

			val worker = buildWorker(10L)
			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.success()
		} }

		@Test
		fun `returns failure for negative session id`()  { runTest {
			val inputData = Data.Builder()
				.putLong(ActivityRecognitionWorker.ARG_SESSION_ID, -5L)
				.build()
			val params: WorkerParameters = mockk(relaxed = true) {
				every { getInputData() } returns inputData
			}
			val worker = ActivityRecognitionWorker(context, params, database, skiInfrastructureManager)

			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.failure()
		} }
	}

	@Nested
	@DisplayName("companion constants")
	inner class CompanionConstants {

		@Test
		fun `ARG_SESSION_ID has expected value`() {
			ActivityRecognitionWorker.ARG_SESSION_ID shouldBe "sessionId"
		}

		@Test
		fun `ARG_BATCH_MODE has expected value`() {
			ActivityRecognitionWorker.ARG_BATCH_MODE shouldBe "batchMode"
		}

		@Test
		fun `WORK_TAG has expected value`() {
			ActivityRecognitionWorker.WORK_TAG shouldBe "ActivityRecognition"
		}
	}
}
