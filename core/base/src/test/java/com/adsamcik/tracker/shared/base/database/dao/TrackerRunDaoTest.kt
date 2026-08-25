package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
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

	@Test
	fun `deleteOlderThan retains active and newer closed runs`() = runTest {
		dao.insert(run(startTimeMs = 100L, endTimeMs = null))
		dao.insert(run(startTimeMs = 200L, endTimeMs = 300L))
		dao.insert(run(startTimeMs = 400L, endTimeMs = 1_400L))

		dao.deleteOlderThan(beforeMs = 1_000L) shouldBe 1

		dao.getActiveRun()?.startTimeMs shouldBe 100L
		dao.getOverlapping(fromMs = 0L, toMs = 2_000L).map { it.startTimeMs } shouldBe listOf(100L, 400L)
	}

	@Test
	fun `legacy fenced run keeps unknown end without appearing active or being closed later`() = runTest {
		dao.insert(run(startTimeMs = 100L, endTimeMs = null, legacyRuntimeFenced = true))

		dao.getActiveRun() shouldBe null
		dao.closeOpenRuns(endTimeMs = 3_000L) shouldBe 0

		val retained = dao.getOverlapping(fromMs = 0L, toMs = 2_000L).single()
		retained.endTimeMs shouldBe null
		retained.legacyRuntimeFenced shouldBe true
		dao.getOverlapping(fromMs = 101L, toMs = 2_000L) shouldBe emptyList()
		dao.getAllBetween(fromMs = 0L, toMs = 100L) shouldBe emptyList()
		dao.getAllBetweenFlow(fromMs = 0L, toMs = 100L).first() shouldBe emptyList()
		dao.getAllBetween(fromMs = 0L, toMs = 101L).single().legacyRuntimeFenced shouldBe true
		dao.getAllBetweenFlow(fromMs = 0L, toMs = 101L).first()
			.single().legacyRuntimeFenced shouldBe true

		val throughId = dao.maxId()
		dao.getOverlappingChunk(
			fromMs = 0L,
			toMsExclusive = 2_000L,
			afterId = 0L,
			throughId = throughId,
			limit = 10,
		).single().legacyRuntimeFenced shouldBe true
		dao.getOverlappingChunk(
			fromMs = 101L,
			toMsExclusive = 2_000L,
			afterId = 0L,
			throughId = throughId,
			limit = 10,
		) shouldBe emptyList()
	}

	@Test
	fun `live null end remains open for a window after its known start`() = runTest {
		dao.insert(run(startTimeMs = 100L, endTimeMs = null))

		dao.getOverlapping(fromMs = 101L, toMs = 2_000L).single().legacyRuntimeFenced shouldBe false
		dao.getOverlappingChunk(
			fromMs = 101L,
			toMsExclusive = 2_000L,
			afterId = 0L,
			throughId = dao.maxId(),
			limit = 10,
		).single().legacyRuntimeFenced shouldBe false
	}

	private fun run(
		startTimeMs: Long,
		endTimeMs: Long?,
		legacyRuntimeFenced: Boolean = false,
	): TrackerRun = TrackerRun(
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		policy = "PASSIVE_LOW",
		policyParams = null,
		userInitiated = false,
		createdAt = startTimeMs,
		legacyRuntimeFenced = legacyRuntimeFenced,
	)
}
