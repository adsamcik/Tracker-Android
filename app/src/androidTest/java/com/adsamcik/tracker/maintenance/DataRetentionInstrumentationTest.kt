package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.preferences.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class DataRetentionInstrumentationTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Enable auto-clean and set 1-year retention
        Preferences.getPref(context).edit {
            setBoolean(R.string.settings_auto_cleanup_old_data_key, true)
            setString(R.string.settings_data_retention_years_key, "1")
        }

        // Seed two location rows: one older than 2 years, one now
        val db = AppDatabase.database(context)
        val now = System.currentTimeMillis()
        val old = now - 2L * 365L * 24L * 60L * 60L * 1000L
        val locOld = DatabaseLocation(
            location = Location(
                time = old,
                latitude = 0.0,
                longitude = 0.0,
                altitude = null,
                horizontalAccuracy = null,
                verticalAccuracy = null,
                speed = null,
                speedAccuracy = null
            ),
            activityInfo = ActivityInfo(DetectedActivity.UNKNOWN, 0)
        )
        val locNew = DatabaseLocation(
            location = Location(
                time = now,
                latitude = 1.0,
                longitude = 1.0,
                altitude = null,
                horizontalAccuracy = null,
                verticalAccuracy = null,
                speed = null,
                speedAccuracy = null
            ),
            activityInfo = ActivityInfo(DetectedActivity.UNKNOWN, 0)
        )
        db.locationDao().insert(listOf(locOld, locNew))

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

        // Ensure initialize runs on main thread due to PreferenceObserver @MainThread
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            DataRetentionWorker.initialize(context)
        }

        // Enqueue a one-time work for deterministic execution with SynchronousExecutor
        val req = OneTimeWorkRequestBuilder<DataRetentionWorker>()
            .build()
        wm.enqueue(req).result.get()

        // Run worker synchronously by polling until DB reflects pruning
        // Since we used SynchronousExecutor, the work should have run
        val count = AppDatabase.database(context).locationDao().count()
        // Old row should be pruned, new should remain
        assertEquals(1L, count)
    }
}
