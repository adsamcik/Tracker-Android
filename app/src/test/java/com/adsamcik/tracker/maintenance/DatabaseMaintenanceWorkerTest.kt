package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseMaintenanceWorkerTest {

	private lateinit var context: Context
	private val workerParams = mockk<WorkerParameters>(relaxed = true)
	private val sessionSegmentDao = mockk<SessionSegmentDao>(relaxed = true)

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		WorkManagerTestInitHelper.initializeTestWorkManager(
			context,
			Configuration.Builder()
				.setMinimumLoggingLevel(android.util.Log.DEBUG)
				.setExecutor(SynchronousExecutor())
				.setTaskExecutor(SynchronousExecutor())
				.build(),
		)
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `returns success`() {
		executeDoWork() shouldBe ListenableWorker.Result.success()
	}

	@Test
	fun `deletes empty session segments through dao`() {
		executeDoWork()
		coVerify(exactly = 1) { sessionSegmentDao.deleteEmpty() }
	}

	@Test
	fun `schedules maintenance as unique periodic work`() {
		DatabaseMaintenanceWorker.schedule(context)

		val active = activeMaintenanceWork()
		assertEquals(1, active.size)
		assertEquals(WorkInfo.State.ENQUEUED, active.single().state)
	}

	@Test
	fun `scheduling again keeps the existing maintenance work`() {
		DatabaseMaintenanceWorker.schedule(context)
		val originalId = activeMaintenanceWork().single().id

		DatabaseMaintenanceWorker.schedule(context)

		val active = activeMaintenanceWork()
		assertEquals(1, active.size)
		assertEquals(originalId, active.single().id)
	}

	private fun executeDoWork(): ListenableWorker.Result = runBlocking {
		DatabaseMaintenanceWorker(context, workerParams, sessionSegmentDao).doWork()
	}

	private fun activeMaintenanceWork(): List<WorkInfo> {
		val work = WorkManager.getInstance(context)
			.getWorkInfosForUniqueWork(DatabaseMaintenanceWorker.MAINTENANCE_UNIQUE_ID)
			.get()
		val active = work.filterNot { it.state.isFinished }
		assertTrue("expected maintenance work, found ${work.map { it.state }}", active.isNotEmpty())
		return active
	}
}
