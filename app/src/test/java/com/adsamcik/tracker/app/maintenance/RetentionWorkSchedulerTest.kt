package com.adsamcik.tracker.app.maintenance

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.RetentionFloorDestructivePlan
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionStartResult
import com.adsamcik.tracker.shared.base.database.beginOrResumeRetentionWorkExecution
import io.kotest.matchers.shouldBe
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionWorkSchedulerTest {
	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private lateinit var workManager: WorkManager
	private lateinit var scheduler: RetentionWorkScheduler

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		WorkManagerTestInitHelper.initializeTestWorkManager(
			context,
			Configuration.Builder()
				.setExecutor(SynchronousExecutor())
				.setTaskExecutor(SynchronousExecutor())
				.build(),
		)
		database = AppDatabase.testDatabase(context)
		workManager = WorkManager.getInstance(context)
		scheduler = RetentionWorkScheduler(Provider { database }, workManager)
	}

	@After
	fun tearDown() {
		workManager.cancelAllWork().result.get()
		database.close()
	}

	@Test
	fun `pipeline migration abandons legacy receipt before replacing its schedule`() = runTest {
		val legacyRequest = OneTimeWorkRequestBuilder<NoOpWorker>()
			.setInitialDelay(1, TimeUnit.DAYS)
			.build()
		workManager.enqueueUniqueWork(
			RetentionPipelineWorker.LEGACY_WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			legacyRequest,
		).result.get()
		val legacyExecution = (
			database.beginOrResumeRetentionWorkExecution(
				workRequestId = legacyRequest.id.toString(),
				workerKind = RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
				runAttemptCount = 0,
				startedAtMs = 1_000L,
			) as RetentionWorkExecutionStartResult.Open
		).receipt

		scheduler.ensureScheduled()

		database.retentionWorkExecutionReceiptDao().get(legacyExecution.executionId)?.state shouldBe
			"ABANDONED"
		workManager.getWorkInfoById(legacyRequest.id).get()?.state shouldBe WorkInfo.State.CANCELLED
		workManager.getWorkInfosForUniqueWork(RetentionPipelineWorker.WORK_NAME).get()
			.any { it.state == WorkInfo.State.ENQUEUED } shouldBe true
	}

	class NoOpWorker(
		context: Context,
		params: WorkerParameters,
	) : Worker(context, params) {
		override fun doWork(): Result = Result.success()
	}
}
