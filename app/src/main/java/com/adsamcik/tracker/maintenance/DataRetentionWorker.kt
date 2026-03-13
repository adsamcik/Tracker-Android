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
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
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
    private val locationSampleDao: LocationSampleDao,
    private val wifiObservationDao: WifiObservationDao,
    private val cellSampleDao: CellSampleDao,
    private val sessionSegmentDao: SessionSegmentDao,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val config = retentionConfigStore.config.first()
        if (!config.autoCleanupEnabled) {
            return Result.success()
        }

        val years = config.dataRetentionYears
        val cutoff = System.currentTimeMillis() - yearsToMillis(years)
        tryWithReport {
            pruneOlderThan(cutoff)
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
        private fun yearsToMillis(years: Int): Long = years * ONE_YEAR_MILLIS

        /** Visible for tests to validate cutoff calculation for different retention values. */
        @JvmStatic
        @VisibleForTesting
        fun computeCutoffMillis(years: Int, nowMillis: Long = System.currentTimeMillis()): Long {
            return nowMillis - yearsToMillis(years)
        }
    }

    @WorkerThread
    private suspend fun pruneOlderThan(cutoffMillis: Long) {
        appDatabase.withTransaction {
            locationSampleDao.deleteOlderThan(cutoffMillis)
            wifiObservationDao.deleteOlderThan(cutoffMillis)
            cellSampleDao.deleteOlderThan(cutoffMillis)
            sessionSegmentDao.deleteOlderThan(cutoffMillis)
        }
    }
}
