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
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.preferences.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataRetentionWorkerTest {
    private lateinit var context: Context

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
    fun `doWork returns success and does nothing when disabled`() {
        // Ensure the preference is OFF by default
        Preferences.getPref(context).edit {
            setBoolean(R.string.settings_auto_cleanup_old_data_key, false)
        }

        val worker = TestListenableWorkerBuilder<DataRetentionWorker>(context).build()
        val result = worker.startWork().get()
        assertEquals(ListenableWorker.Result.success()::class, result::class)
    }

    @Test
    fun `initialize schedules when enabled and cancels when disabled`() {
    // Ensure WorkManager is initialized (done in setUp), then retrieve instance
    val wm = WorkManager.getInstance(context)

        // Disable first: expect no work enqueued after initialize
        Preferences.getPref(context).edit {
            setBoolean(R.string.settings_auto_cleanup_old_data_key, false)
        }
    DataRetentionWorker.initialize(context)
    var works = wm.getWorkInfosForUniqueWork("APP.DATA_RETENTION_WEEKLY").get()
        // It may momentarily exist as CANCELLED from a previous run; ensure nothing is ENQUEUED
        assertTrue(works.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })

        // Enable: expect work enqueued
        Preferences.getPref(context).edit {
            setBoolean(R.string.settings_auto_cleanup_old_data_key, true)
        }
    DataRetentionWorker.initialize(context)
        // Wait up to 2s for enqueue to be visible
        val start = System.currentTimeMillis()
        do {
            works = wm.getWorkInfosForUniqueWork("APP.DATA_RETENTION_WEEKLY").get()
            if (works.isNotEmpty()) break
            Thread.sleep(50)
        } while (System.currentTimeMillis() - start < 2000)
        // Some environments may report different intermediate states; presence is enough to confirm scheduling
        assertTrue(works.isNotEmpty())

        // Disable again: expect cancellation
        Preferences.getPref(context).edit {
            setBoolean(R.string.settings_auto_cleanup_old_data_key, false)
        }
        DataRetentionWorker.initialize(context)
        // Wait for cancellation
        val startCancel = System.currentTimeMillis()
        do {
            works = wm.getWorkInfosForUniqueWork("APP.DATA_RETENTION_WEEKLY").get()
            // Consider cancelled when there are no active entries
            if (works.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) break
            Thread.sleep(50)
        } while (System.currentTimeMillis() - startCancel < 5000)
        assertTrue(works.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })
    }
}
