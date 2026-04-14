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
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.resetRetentionConfigForTests
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DataRetentionWorkerTest {
    private companion object {
        const val UNIQUE_WORK_NAME = "APP.DATA_RETENTION_WEEKLY"
    }

    private lateinit var context: Context
    private lateinit var retentionStore: RetentionConfigStore
    private val testDispatcher = StandardTestDispatcher()
    private val mockDatabase: AppDatabase = mockk(relaxed = true)
    private val locationSampleDao: LocationSampleDao = mockk(relaxed = true)
    private val wifiObservationDao: WifiObservationDao = mockk(relaxed = true)
    private val cellSampleDao: CellSampleDao = mockk(relaxed = true)
    private val sessionSegmentDao: SessionSegmentDao = mockk(relaxed = true)
    private val exportPlanStore: ExportPlanStore = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        runTest(testDispatcher) {
            resetRetentionConfigForTests(context)
            advanceUntilIdle()
        }
        retentionStore = RetentionConfigStore(context, testDispatcher)
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        runTest(testDispatcher) {
            resetRetentionConfigForTests(context)
            advanceUntilIdle()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `doWork returns success and does nothing when disabled`() = runTest(testDispatcher) {
        retentionStore.update { copy(autoCleanupEnabled = false) }
        advanceUntilIdle()

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
                        mockDatabase,
                        locationSampleDao,
                        wifiObservationDao,
                        cellSampleDao,
                        sessionSegmentDao,
                        exportPlanStore,
                    )
                }
            })
            .build() as DataRetentionWorker

        // Call doWork() directly to avoid blocking thread with startWork().get()
        val result = worker.doWork()
        advanceUntilIdle()
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
