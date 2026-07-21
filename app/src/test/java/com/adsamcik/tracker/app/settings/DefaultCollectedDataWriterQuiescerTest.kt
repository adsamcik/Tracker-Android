package com.adsamcik.tracker.app.settings

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
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
import com.adsamcik.tracker.impexp.exporter.automation.ExportAutomationController
import com.adsamcik.tracker.impexp.importer.DataImporter
import com.adsamcik.tracker.app.maintenance.RetentionPipelineWorker
import com.adsamcik.tracker.maintenance.DatabaseMaintenanceWorker
import com.adsamcik.tracker.points.event.PointsDomainEventConsumer
import com.adsamcik.tracker.stats.data.worker.AchievementWorker
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.resilience.PendingSignalDrainWork
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationWorker
import com.adsamcik.tracker.osm.imp.OsmImportWorker
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultCollectedDataWriterQuiescerTest {
	@get:Rule
	val instantTaskExecutorRule = InstantTaskExecutorRule()

	private lateinit var context: Application
	private lateinit var workManager: WorkManager
	private val trackerRunning = MutableStateFlow(false)
	private val trackerStateReader: TrackerStateReader = mockk()
	private val activityWatcherController: ActivityWatcherController = mockk()
	private val exportAutomationController: ExportAutomationController = mockk()

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
		workManager = WorkManager.getInstance(context)
		every { trackerStateReader.isServiceRunning } answers { trackerRunning.value }
		every { trackerStateReader.isServiceRunningFlow } returns trackerRunning
		every { activityWatcherController.pauseForDataDeletion() } just Runs
		every { activityWatcherController.resumeAfterDataDeletion() } just Runs
		coEvery { exportAutomationController.pauseForDataDeletion() } just Runs
		every { exportAutomationController.resumeAfterDataDeletion() } just Runs
	}

	@After
	fun tearDown() {
		workManager.cancelAllWork().result.get()
	}

	@Test
	fun `quiesce cancels database writers and resume restores schedulers`() = runTest {
		val requests = listOf(
			delayedWork(tag = "ActivityRecognition"),
			delayedWork(tag = PointsDomainEventConsumer.POINTS_WORK_TAG),
			delayedWork(tag = AchievementWorker.TAG),
		)
		requests.forEach { workManager.enqueue(it).result.get() }
		val dailySummary = delayedWork()
		workManager.enqueueUniqueWork(
			DailySummaryMaterializationWorker.UNIQUE_WORK_ID,
			ExistingWorkPolicy.REPLACE,
			dailySummary,
		).result.get()
		val osmImport = delayedWork()
		workManager.enqueueUniqueWork(
			OsmImportWorker.UNIQUE_WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			osmImport,
		).result.get()
		val import = delayedWork()
		workManager.enqueueUniqueWork(
			DataImporter.UNIQUE_WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			import,
		).result.get()
		val pendingSignalDrain = delayedWork()
		workManager.enqueueUniqueWork(
			PendingSignalDrainWork.UNIQUE_WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			pendingSignalDrain,
		).result.get()
		val retention = delayedWork()
		workManager.enqueueUniqueWork(
			RetentionPipelineWorker.WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			retention,
		).result.get()
		val legacyRetention = delayedWork()
		workManager.enqueueUniqueWork(
			RetentionPipelineWorker.LEGACY_WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			legacyRetention,
		).result.get()
		val databaseMaintenance = delayedWork()
		workManager.enqueueUniqueWork(
			DatabaseMaintenanceWorker.MAINTENANCE_UNIQUE_ID,
			ExistingWorkPolicy.REPLACE,
			databaseMaintenance,
		).result.get()
		val quiescer = DefaultCollectedDataWriterQuiescer(
			context = context,
			trackerStateReader = trackerStateReader,
			activityWatcherController = activityWatcherController,
			exportAutomationController = exportAutomationController,
		)

		quiescer.quiesce()

		(requests + dailySummary + osmImport + import + pendingSignalDrain + retention +
			legacyRetention + databaseMaintenance).forEach { request ->
			workManager.getWorkInfoById(request.id).get()?.state shouldBe WorkInfo.State.CANCELLED
		}
		verify(exactly = 1) { activityWatcherController.pauseForDataDeletion() }
		coVerify(exactly = 1) { exportAutomationController.pauseForDataDeletion() }

		quiescer.resume()

		verify(exactly = 1) { exportAutomationController.resumeAfterDataDeletion() }
		verify(exactly = 1) { activityWatcherController.resumeAfterDataDeletion() }
		workManager.getWorkInfosForUniqueWork(
			DailySummaryMaterializationWorker.UNIQUE_WORK_ID,
		).get().any { it.state == WorkInfo.State.ENQUEUED } shouldBe true
		workManager.getWorkInfosForUniqueWork(
			RetentionPipelineWorker.WORK_NAME,
		).get().any { it.state == WorkInfo.State.ENQUEUED } shouldBe true
		workManager.getWorkInfosForUniqueWork(
			DatabaseMaintenanceWorker.MAINTENANCE_UNIQUE_ID,
		).get().any { it.state == WorkInfo.State.ENQUEUED } shouldBe true
	}

	private fun delayedWork(tag: String? = null) =
		OneTimeWorkRequestBuilder<NoOpWorker>()
			.setInitialDelay(1, TimeUnit.DAYS)
			.apply { if (tag != null) addTag(tag) }
			.build()

	class NoOpWorker(
		context: android.content.Context,
		params: WorkerParameters,
	) : Worker(context, params) {
		override fun doWork(): Result = Result.success()
	}
}
