package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.SynchronousExecutor
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DataRetentionWorkerTest {
    private lateinit var context: Context
    private lateinit var retentionStore: RetentionConfigStore

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

        val worker = TestListenableWorkerBuilder<DataRetentionWorker>(context).build()
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
    var works = wm.getWorkInfosForUniqueWork("APP.DATA_RETENTION_WEEKLY").get()
        assertTrue(works.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })

        // Enable: expect work enqueued
        runBlocking {
            retentionStore.update { copy(autoCleanupEnabled = true) }
        }
    DataRetentionWorker.initialize(context)
        val start = System.currentTimeMillis()
        do {
            works = wm.getWorkInfosForUniqueWork("APP.DATA_RETENTION_WEEKLY").get()
            if (works.isNotEmpty()) break
            Thread.sleep(50)
        } while (System.currentTimeMillis() - start < 2000)
        assertTrue(works.isNotEmpty())

        // Disable again: expect cancellation
        runBlocking {
            retentionStore.update { copy(autoCleanupEnabled = false) }
        }
        DataRetentionWorker.initialize(context)
        val startCancel = System.currentTimeMillis()
        do {
            works = wm.getWorkInfosForUniqueWork("APP.DATA_RETENTION_WEEKLY").get()
            if (works.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) break
            Thread.sleep(50)
        } while (System.currentTimeMillis() - startCancel < 5000)
        assertTrue(works.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })
    }
}
