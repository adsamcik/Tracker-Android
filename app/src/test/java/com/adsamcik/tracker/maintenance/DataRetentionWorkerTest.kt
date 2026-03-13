package com.adsamcik.tracker.maintenance

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
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
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DataRetentionWorkerTest {
    private lateinit var context: Context
    private lateinit var retentionStore: RetentionConfigStore
    private val mockDatabase: AppDatabase = mockk(relaxed = true)
    private val locationSampleDao: LocationSampleDao = mockk(relaxed = true)
    private val wifiObservationDao: WifiObservationDao = mockk(relaxed = true)
    private val cellSampleDao: CellSampleDao = mockk(relaxed = true)
    private val sessionSegmentDao: SessionSegmentDao = mockk(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        retentionStore = RetentionConfigStore(context, Dispatchers.IO)
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `doWork returns success and does nothing when disabled`() {
        runBlocking {
            retentionStore.update { copy(autoCleanupEnabled = false) }
        }

        val workerFactory = object : WorkerFactory() {
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
                )
            }
        }

        val worker = TestListenableWorkerBuilder<DataRetentionWorker>(context)
            .setWorkerFactory(workerFactory)
            .build()
        val result = worker.startWork().get()
        assertEquals(ListenableWorker.Result.success()::class, result::class)
    }

    @Test
    fun `initialize schedules when enabled and cancels when disabled`() {
    val wm = WorkManager.getInstance(context)

        // Disable first: expect no work enqueued after initialize
        runBlocking {
            retentionStore.update { copy(autoCleanupEnabled = false) }
        }
    DataRetentionWorker.initialize(context)
    Thread.sleep(200)
    shadowOf(Looper.getMainLooper()).idle()
    var works = wm.getWorkInfosForUniqueWork("APP.DATA_RETENTION_WEEKLY").get()
        assertTrue(works.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })

        // Enable: expect work enqueued
        runBlocking {
            retentionStore.update { copy(autoCleanupEnabled = true) }
        }
    DataRetentionWorker.initialize(context)
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        works = wm.getWorkInfosForUniqueWork("APP.DATA_RETENTION_WEEKLY").get()
        assertTrue(works.isNotEmpty())

        // Disable again: expect cancellation
        runBlocking {
            retentionStore.update { copy(autoCleanupEnabled = false) }
        }
        DataRetentionWorker.initialize(context)
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        works = wm.getWorkInfosForUniqueWork("APP.DATA_RETENTION_WEEKLY").get()
        assertTrue(works.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })
    }
}
