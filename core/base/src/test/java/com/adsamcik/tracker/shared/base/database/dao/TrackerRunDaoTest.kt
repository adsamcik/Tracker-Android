package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
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
class TrackerRunDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: TrackerRunDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.trackerRunDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `closeOpenRuns closes every orphan and leaves completed run unchanged`() = runTest {
		dao.insert(run(startTimeMs = 1_000L, endTimeMs = null))
		dao.insert(run(startTimeMs = 2_000L, endTimeMs = null))
		dao.insert(run(startTimeMs = 500L, endTimeMs = 750L))

		dao.closeOpenRuns(endTimeMs = 3_000L) shouldBe 2

		dao.getActiveRun() shouldBe null
		val runs = dao.getOverlapping(fromMs = 0L, toMs = 10_000L).associateBy { it.startTimeMs }
		runs.getValue(500L).endTimeMs shouldBe 750L
		runs.getValue(1_000L).endTimeMs shouldBe 3_000L
		runs.getValue(2_000L).endTimeMs shouldBe 3_000L
	}

	@Test
	fun `closeOpenRuns clamps end to start after backwards wall clock change`() = runTest {
		dao.insert(run(startTimeMs = 5_000L, endTimeMs = null))

		dao.closeOpenRuns(endTimeMs = 3_000L) shouldBe 1

		val closed = dao.getOverlapping(fromMs = 0L, toMs = 10_000L).single()
		closed.endTimeMs shouldBe 5_000L
	}

	private fun run(
		startTimeMs: Long,
		endTimeMs: Long?,
	): TrackerRun = TrackerRun(
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		policy = "PASSIVE_LOW",
		policyParams = null,
		userInitiated = false,
		createdAt = startTimeMs,
	)
}
