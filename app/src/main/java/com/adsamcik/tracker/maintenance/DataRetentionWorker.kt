package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Observer
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.observer.PreferenceObserver
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import java.time.Duration

/**
 * Periodic worker that deletes data older than 1 year to honor auto-cleanup setting.
 */
class DataRetentionWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val prefs = Preferences.getPref(applicationContext)
        val enabled = prefs.getBooleanRes(
            R.string.settings_auto_cleanup_old_data_key,
            R.string.settings_auto_cleanup_old_data_default
        )
        if (!enabled) {
            // Safety: don’t run when user disabled it
            return Result.success()
        }

        val years = prefs.getStringRes(
            R.string.settings_data_retention_years_key,
            R.string.settings_data_retention_years_default
        ).toIntOrNull() ?: 1
        val cutoff = System.currentTimeMillis() - yearsToMillis(years)
        tryWithReport {
            pruneOlderThan(applicationContext, cutoff)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "APP.DATA_RETENTION_WEEKLY"
    private const val ONE_YEAR_MILLIS: Long = 365L * 24L * 60L * 60L * 1000L

        private val preferenceObserver = Observer<Boolean> { enabled ->
            val ctx = internalContext ?: return@Observer
            if (enabled == true) ensureScheduled(ctx) else cancel(ctx)
        }

        @Volatile
        private var internalContext: Context? = null

        /**
         * Initialize observation of the auto-cleanup preference and sync schedule once.
         */
        fun initialize(context: Context) {
            if (internalContext == null) internalContext = context.applicationContext
            // Ensure preferences and observer are initialized
            val prefs = Preferences.getPref(context)
            // Observe changes
            PreferenceObserver.observe(
                context,
                R.string.settings_auto_cleanup_old_data_key,
                R.string.settings_auto_cleanup_old_data_default,
                preferenceObserver,
                owner = null
            )
            // Sync current state
            val enabled = prefs.getBooleanRes(
                R.string.settings_auto_cleanup_old_data_key,
                R.string.settings_auto_cleanup_old_data_default
            )
            try {
                if (enabled) ensureScheduled(context) else cancel(context)
            } catch (e: IllegalStateException) {
                // When WorkManager isn't initialized (e.g., robolectric unit tests), skip scheduling
            }
        }

        /** Schedule weekly cleanup with unique work policy. */
        fun ensureScheduled(context: Context) {
            val workManager = WorkManager.getInstance(context)
            val request = PeriodicWorkRequestBuilder<DataRetentionWorker>(Duration.ofDays(7))
                .addTag(UNIQUE_WORK_NAME)
                .build()
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** Cancel scheduled cleanup. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        @WorkerThread
        private fun pruneOlderThan(context: Context, cutoffMillis: Long) {
            val db = AppDatabase.database(context)
            db.runInTransaction {
                // Locations
                db.compileStatement("DELETE FROM location_data WHERE time < ?")
                    .apply { bindLong(1, cutoffMillis); executeUpdateDelete() }

                // Wi-Fi networks by last seen
                db.compileStatement("DELETE FROM wifi_data WHERE last_seen < ?")
                    .apply { bindLong(1, cutoffMillis); executeUpdateDelete() }

                // Cell locations
                db.compileStatement("DELETE FROM cell_location WHERE time < ?")
                    .apply { bindLong(1, cutoffMillis); executeUpdateDelete() }

                // Aggregated Wi-Fi count locations
                db.compileStatement("DELETE FROM location_wifi_count WHERE time < ?")
                    .apply { bindLong(1, cutoffMillis); executeUpdateDelete() }

                // Sessions that fully ended before cutoff
                db.compileStatement("DELETE FROM tracker_session WHERE `end` < ?")
                    .apply { bindLong(1, cutoffMillis); executeUpdateDelete() }
            }
        }

    private fun yearsToMillis(years: Int): Long = years * ONE_YEAR_MILLIS

        /** Visible for tests to validate cutoff calculation for different retention values. */
        @JvmStatic
        @VisibleForTesting
        fun computeCutoffMillis(years: Int, nowMillis: Long = System.currentTimeMillis()): Long {
            return nowMillis - yearsToMillis(years)
        }
    }
}
