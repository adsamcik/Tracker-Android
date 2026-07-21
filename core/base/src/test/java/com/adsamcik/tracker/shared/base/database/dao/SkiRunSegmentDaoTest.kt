package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SkiRunSegment
import com.adsamcik.tracker.shared.base.database.data.SkiSegmentType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkiRunSegmentDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: SkiRunSegmentDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.skiRunSegmentDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `deleteOlderThan removes only segments ending before cutoff`() = runTest {
		dao.insert(segment(startTimeMs = 100L, endTimeMs = 200L))
		dao.insert(segment(startTimeMs = 900L, endTimeMs = 1_000L))
		dao.insert(segment(startTimeMs = 1_100L, endTimeMs = 1_200L))

		dao.deleteOlderThan(beforeMs = 1_000L) shouldBe 1

		dao.getByTimeRange(startMs = 0L, endMs = 2_000L).map { it.endTimeMs } shouldBe listOf(1_000L, 1_200L)
	}

	private fun segment(startTimeMs: Long, endTimeMs: Long): SkiRunSegment = SkiRunSegment(
		sessionId = 1L,
		runIndex = startTimeMs.toInt(),
		segmentType = SkiSegmentType.DOWNHILL_RUN,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		verticalM = -100f,
		distanceM = 500f,
		maxSpeedMps = 10f,
		avgSpeedMps = 8f,
		createdAt = endTimeMs,
	)
}
