package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseMaintenanceWorkerTest {

	private lateinit var context: Context
	private lateinit var database: AppDatabase
	private val workerParams = mockk<WorkerParameters>(relaxed = true)

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
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
		database.close()
		unmockkAll()
	}

	@Test
	fun `returns success`() {
		executeDoWork() shouldBe ListenableWorker.Result.success()
	}

	@Test
	fun `legacy worker preserves attributed zero sample placeholder`() = runBlocking {
		val id = database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = 1_000L,
				endTimeMs = 1_000L,
				distanceM = 0f,
				steps = 0,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 0,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = "active-placeholder",
				createdAt = 1_000L,
				logicalTrackingId = "logical-1",
				serviceRunId = "run-1",
			),
		)

		executeDoWork() shouldBe ListenableWorker.Result.success()
		database.sessionSegmentDao().getById(id)?.sampleCount shouldBe 0
		Unit
	}

	@Test
	fun `cancel retires a persisted legacy periodic request idempotently`() {
		val workManager = WorkManager.getInstance(context)
		val request = PeriodicWorkRequestBuilder<DatabaseMaintenanceWorker>(6L, TimeUnit.HOURS)
			.build()
		workManager.enqueueUniquePeriodicWork(
			DatabaseMaintenanceWorker.MAINTENANCE_UNIQUE_ID,
			ExistingPeriodicWorkPolicy.KEEP,
			request,
		).result.get()
		activeMaintenanceWork().single().state shouldBe WorkInfo.State.ENQUEUED

		DatabaseMaintenanceWorker.cancel(context).result.get()
		workManager.getWorkInfoById(request.id).get()?.state shouldBe WorkInfo.State.CANCELLED
		activeMaintenanceWork() shouldBe emptyList()

		DatabaseMaintenanceWorker.cancel(context).result.get()
		activeMaintenanceWork() shouldBe emptyList()
	}

	private fun executeDoWork(): ListenableWorker.Result = runBlocking {
		DatabaseMaintenanceWorker(
			context,
			workerParams,
		).doWork()
	}

	private fun activeMaintenanceWork(): List<WorkInfo> {
		val work = WorkManager.getInstance(context)
			.getWorkInfosForUniqueWork(DatabaseMaintenanceWorker.MAINTENANCE_UNIQUE_ID)
			.get()
		return work.filterNot { it.state.isFinished }
	}
}
