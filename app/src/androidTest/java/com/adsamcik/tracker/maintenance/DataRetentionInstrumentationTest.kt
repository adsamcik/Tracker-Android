package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataRetentionInstrumentationTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Enable auto-clean and set 1-year retention via Proto DataStore
        runBlocking {
            RetentionConfigStore(context, Dispatchers.IO).update {
                copy(autoCleanupEnabled = true, dataRetentionYears = 1)
            }
        }

        // Seed two location rows: one older than 2 years, one now
        val db = AppDatabase.database(context)
        val now = System.currentTimeMillis()
        val old = now - 2L * 365L * 24L * 60L * 60L * 1000L
        val sampleOld = LocationSample(
            timeMs = old,
            elapsedRealtimeNanos = 0L,
            latE7 = 0,
            lonE7 = 0,
            altitudeM = null,
            rawGpsAltitudeM = null,
            hAccM = null,
            vAccM = null,
            speedMps = null,
            speedAccuracyMps = null,
            provider = "gps",
            quality = SampleQuality.HIGH,
            motionState = null,
            policy = null,
            bucketId = null,
            createdAt = old,
        )
        val sampleNew = LocationSample(
            timeMs = now,
            elapsedRealtimeNanos = 0L,
            latE7 = (1.0 * 1e7).toInt(),
            lonE7 = (1.0 * 1e7).toInt(),
            altitudeM = null,
            rawGpsAltitudeM = null,
            hAccM = null,
            vAccM = null,
            speedMps = null,
            speedAccuracyMps = null,
            provider = "gps",
            quality = SampleQuality.HIGH,
            motionState = null,
            policy = null,
            bucketId = null,
            createdAt = now,
        )
        runBlocking { db.locationSampleDao().insert(listOf(sampleOld, sampleNew)) }

        // Simulate process death by recreating WorkManager
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun retention_applied_on_next_worker_tick_after_restart() {
        val wm = WorkManager.getInstance(context)

		// Enqueue a one-time work for deterministic execution with SynchronousExecutor
        val req = OneTimeWorkRequestBuilder<DataRetentionWorker>()
            .build()
        wm.enqueue(req).result.get()

        // Run worker synchronously by polling until DB reflects pruning
        // Since we used SynchronousExecutor, the work should have run
        val count = runBlocking {
            AppDatabase.database(context).locationSampleDao().countBetween(0L, Long.MAX_VALUE)
        }
        // Old row should be pruned, new should remain
        assertEquals(1, count)
    }
}
