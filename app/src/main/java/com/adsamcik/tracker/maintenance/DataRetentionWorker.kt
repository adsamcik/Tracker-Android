package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.annotation.WorkerThread
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.time.Duration

/**
 * Periodic worker that deletes data older than N years to honor auto-cleanup setting.
 */
@HiltWorker
class DataRetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val retentionConfigStore: RetentionConfigStore,
    private val appDatabase: AppDatabase,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val config = retentionConfigStore.config.first()
        if (!config.autoCleanupEnabled) {
            return Result.success()
        }

        val years = config.dataRetentionYears
        val cutoff = System.currentTimeMillis() - yearsToMillis(years)
        tryWithReport {
            pruneOlderThan(appDatabase, cutoff)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "APP.DATA_RETENTION_WEEKLY"
    private const val ONE_YEAR_MILLIS: Long = 365L * 24L * 60L * 60L * 1000L

        /**
         * Initialize observation of the auto-cleanup setting and sync schedule.
         * Legacy entry point for non-Hilt callers (OnboardingActivity, tests).
         * Production code should use [DataRetentionScheduler] instead.
         */
        @Deprecated("Inject DataRetentionScheduler and call initialize() instead")
        fun initialize(context: Context) {
            val appContext = context.applicationContext
            val store = RetentionConfigStore(appContext, Dispatchers.IO)
            store.config.map { it.autoCleanupEnabled }.onEach { enabled ->
                syncScheduling(appContext, enabled)
            }.launchIn(CoroutineScope(SupervisorJob()))
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

        internal fun syncScheduling(context: Context, enabled: Boolean) {
            try {
                if (enabled) ensureScheduled(context) else cancel(context)
            } catch (e: IllegalStateException) {
                Reporter.report(e)
                // WorkManager may not be initialized in tests; ignore the exception as before.
            }
        }

        @WorkerThread
        private suspend fun pruneOlderThan(db: AppDatabase, cutoffMillis: Long) {
            db.withTransaction {
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
