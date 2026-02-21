package com.adsamcik.tracker.activity

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.MutableTrackerSession
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.dao.SessionDataDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
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

	private val context: Context = mockk(relaxed = true)
	private val database: AppDatabase = mockk(relaxed = true)
	private val sessionDao: SessionDataDao = mockk(relaxed = true)
	private val locationDao: LocationDataDao = mockk(relaxed = true)

	@BeforeEach
	fun setUp() {
		mockkObject(AppDatabase.Companion)
		mockkObject(Reporter)
		mockkObject(Logger)

		every { AppDatabase.database(any()) } returns database
		every { database.sessionDao() } returns sessionDao
		every { database.locationDao() } returns locationDao
		every { Reporter.report(any<Throwable>()) } just runs
		every { Reporter.log(any<String>()) } just runs
		every { Logger.logWithPreference(any(), any(), any()) } just runs
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

		return ActivityRecognitionWorker(context, params)
	}

	private fun buildWorkerWithNoSessionId(): ActivityRecognitionWorker {
		val inputData = Data.Builder().build()

		val params: WorkerParameters = mockk(relaxed = true) {
			every { getInputData() } returns inputData
		}

		return ActivityRecognitionWorker(context, params)
	}

	private fun createSession(
		id: Long = 1L,
		start: Long = 1_700_000_000_000L,
		end: Long = 1_700_000_060_000L
	): TrackerSession = mockk(relaxed = true) {
		every { this@mockk.id } returns id
		every { this@mockk.start } returns start
		every { this@mockk.end } returns end
	}

	private fun createLocations(
		activity: DetectedActivity,
		count: Int,
		confidence: Int = 80,
		startTime: Long = 1_700_000_000_000L
	): List<DatabaseLocation> = (0 until count).map { i ->
		DatabaseLocation(
			Location(
				time = startTime + i * 1000L,
				latitude = 50.0,
				longitude = 14.0,
				altitude = null,
				horizontalAccuracy = 10f,
				verticalAccuracy = null,
				speed = null,
				speedAccuracy = null
			),
			ActivityInfo(activity, confidence)
		)
	}

	@Nested
	@DisplayName("doWork")
	inner class DoWork {

		@Test
		fun `returns failure when session id is not set`() = runTest {
			val worker = buildWorkerWithNoSessionId()

			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.failure()
			verify { Reporter.report(any<Throwable>()) }
		}

		@Test
		fun `returns failure when session not found in database`() = runTest {
			every { sessionDao.get(42L) } returns null
			val worker = buildWorker(42L)

			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.failure()
		}

		@Test
		fun `returns success when no recognizer produces a result`() = runTest {
			val session = createSession()
			every { sessionDao.get(1L) } returns session
			// Empty locations: recognizers will produce null results
			every { locationDao.getAllBetween(any(), any()) } returns emptyList()

			val worker = buildWorker(1L)
			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.success()
		}

		@Test
		fun `returns success and updates session for walking activity`() = runTest {
			val session = createSession(id = 5L)
			every { sessionDao.get(5L) } returns session
			val locations = createLocations(DetectedActivity.WALKING, count = 20)
			every { locationDao.getAllBetween(any(), any()) } returns locations
			every { sessionDao.update(any<MutableTrackerSession>()) } just runs

			val worker = buildWorker(5L)
			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.success()
			val sessionSlot = slot<MutableTrackerSession>()
			verify { sessionDao.update(capture(sessionSlot)) }
			sessionSlot.captured.sessionActivityId shouldBe NativeSessionActivity.WALKING.id
		}

		@Test
		fun `returns success and updates session for vehicle activity`() = runTest {
			val session = createSession(id = 10L)
			every { sessionDao.get(10L) } returns session
			val locations = createLocations(DetectedActivity.IN_VEHICLE, count = 20)
			every { locationDao.getAllBetween(any(), any()) } returns locations
			every { sessionDao.update(any<MutableTrackerSession>()) } just runs

			val worker = buildWorker(10L)
			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.success()
			val sessionSlot = slot<MutableTrackerSession>()
			verify { sessionDao.update(capture(sessionSlot)) }
			sessionSlot.captured.sessionActivityId shouldBe NativeSessionActivity.LAND_VEHICLE.id
		}

		@Test
		fun `returns failure for negative session id`() = runTest {
			val inputData = Data.Builder()
				.putLong(ActivityRecognitionWorker.ARG_SESSION_ID, -5L)
				.build()
			val params: WorkerParameters = mockk(relaxed = true) {
				every { getInputData() } returns inputData
			}
			val worker = ActivityRecognitionWorker(context, params)

			val result = worker.doWork()

			result shouldBe ListenableWorker.Result.failure()
		}
	}

	@Nested
	@DisplayName("companion constants")
	inner class CompanionConstants {

		@Test
		fun `ARG_SESSION_ID has expected value`() {
			ActivityRecognitionWorker.ARG_SESSION_ID shouldBe "sessionId"
		}

		@Test
		fun `WORK_TAG has expected value`() {
			ActivityRecognitionWorker.WORK_TAG shouldBe "ActivityRecognition"
		}
	}
}
