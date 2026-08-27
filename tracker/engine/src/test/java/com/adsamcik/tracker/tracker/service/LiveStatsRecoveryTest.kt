package com.adsamcik.tracker.tracker.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveStatsRecoveryTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `seed excludes resumed row and prorates cross-midnight contributions`() = runTest {
		val dayStart = 86_400_000L
		database.sessionSegmentDao().insert(segment(dayStart + HOUR, dayStart + 2 * HOUR, 100f, 100))
		database.sessionSegmentDao().insert(segment(dayStart - HOUR / 2, dayStart + HOUR / 2, 60f, 60))
		val currentId = database.sessionSegmentDao().insert(
			segment(dayStart - HOUR, dayStart + HOUR, 200f, 200),
		)
		val session = TrackerSession(
			id = currentId,
			start = dayStart - HOUR,
			end = dayStart + HOUR,
			isUserInitiated = true,
			collections = 20,
			distanceInM = 200f,
			steps = 200,
		)

		val seed = database.liveStatsRecoverySeed(session, isResuming = true, zoneId = ZoneId.of("UTC"))

		seed.priorDayDistanceM shouldBe (130f plusOrMinus 0.001f)
		seed.priorDaySteps shouldBe 130
		seed.priorDayDurationMs shouldBe HOUR + HOUR / 2
		seed.priorDayTrips shouldBe 1
		seed.restoredDayDistanceM shouldBe (100f plusOrMinus 0.001f)
		seed.restoredDaySteps shouldBe 100
		seed.restoredDayDurationMs shouldBe HOUR
	}

	private fun segment(start: Long, end: Long, distance: Float, steps: Int) = SessionSegment(
		startTimeMs = start,
		endTimeMs = end,
		distanceM = distance,
		steps = steps,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 1,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = "test",
		createdAt = start,
	)

	private companion object {
		const val HOUR = 3_600_000L
	}
}
