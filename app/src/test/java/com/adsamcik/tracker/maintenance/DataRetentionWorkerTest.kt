package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.work.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.SynchronousExecutor
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupRepository
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DataRetentionWorkerTest {
    private companion object {
        const val UNIQUE_WORK_NAME = "APP.DATA_RETENTION_PIPELINE_WEEKLY"
		val READY_STARTUP_GATE = object : TrackingStartupGate {
			override val isReady: Boolean = true
			override suspend fun reconcile(retryFailedStorage: Boolean) =
				TrackingStartupResult.Ready(legacyRecoveryPartial = false, liveCompletedThroughOrdinal = 0L)
		}
    }

    private lateinit var context: Context
    private val retentionStore: RetentionConfigStore = mockk {
        every { config } returns flowOf(RetentionConfigState(autoCleanupEnabled = false))
    }
    private val testDispatcher = StandardTestDispatcher()
    private val mockDatabase: AppDatabase = mockk(relaxed = true)
    private val exportPlanStore: ExportPlanStore = mockk(relaxed = true)
    private val migrationBackupRepository: DatabaseMigrationBackupRepository = mockk(relaxed = true)
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore = mockk(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `doWork returns success and does nothing when disabled`() = runTest(testDispatcher) {
        val worker = TestListenableWorkerBuilder<DataRetentionWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker {
                    return DataRetentionWorker(
                        appContext,
                        workerParameters,
                        retentionStore,
                        Provider { mockDatabase },
                        exportPlanStore,
                        migrationBackupRepository,
						collectedDataLifecycleStore,
						READY_STARTUP_GATE,
                    )
                }
            })
            .build() as DataRetentionWorker

        // Call doWork() directly to avoid blocking thread with startWork().get()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `ensureScheduled enqueues work and cancel removes active work`() {
        val workManager = WorkManager.getInstance(context)

        DataRetentionWorker.cancel(context)
        var works = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
        assertTrue("expected no active work, found ${works.map { it.state }}", works.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })

        DataRetentionWorker.ensureScheduled(context)
        works = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
        assertTrue("expected scheduled work, found ${works.map { it.state }}", works.isNotEmpty())

        DataRetentionWorker.cancel(context)
        works = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
        assertTrue("expected cancellation, found ${works.map { it.state }}", works.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })
    }

}
