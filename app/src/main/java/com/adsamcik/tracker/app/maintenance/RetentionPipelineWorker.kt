package com.adsamcik.tracker.app.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.logging.ReporterFacade
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@HiltWorker
class RetentionPipelineWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val retentionConfigStore: RetentionConfigStore,
    private val appDatabase: AppDatabase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val config = retentionConfigStore.config.first()

        if (!config.autoPurgeEnabled) return Result.success()

        if (config.exportBeforePurge) {
            ReporterFacade.report(
                IllegalStateException("exportBeforePurge is enabled but not yet implemented; skipping purge")
            )
            return Result.success()
        }

        return try {
            val now = System.currentTimeMillis()

            purgeRawData(appDatabase, config, now)
            purgeWifiCellData(appDatabase, config, now)
            purgeTripData(appDatabase, config, now)
            purgeDailySummaries(appDatabase, config, now)
            purgeExplorationData(appDatabase, config, now)
            purgeLegacySessions(appDatabase, config, now)

            Result.success()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ReporterFacade.report(e)
            Result.retry()
        }
    }

    private suspend fun purgeRawData(db: AppDatabase, config: RetentionConfigState, now: Long) {
        if (config.rawDataRetentionDays == 0) return
        val cutoff = now - config.rawDataRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
        db.locationSampleDao().deleteOlderThan(cutoff)
        db.stepIntervalDao().deleteOlderThan(cutoff)
        db.activitySnapshotDao().deleteOlderThan(cutoff)
        db.trackerRunDao().deleteOlderThan(cutoff)
    }

    private suspend fun purgeWifiCellData(db: AppDatabase, config: RetentionConfigState, now: Long) {
        if (config.wifiCellRetentionDays == 0) return
        val cutoff = now - config.wifiCellRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
        db.cellSampleDao().deleteOlderThan(cutoff)
        db.wifiObservationDao().deleteOlderThan(cutoff)
    }

    private suspend fun purgeTripData(db: AppDatabase, config: RetentionConfigState, now: Long) {
        if (config.tripRetentionDays == 0) return
        val cutoff = now - config.tripRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
        db.sessionSegmentDao().deleteOlderThan(cutoff)
        db.inferredTripDao().deleteOlderThan(cutoff)
        db.tripLegDao().deleteOlderThan(cutoff)
        db.frequentPlaceDao().deleteOlderThan(cutoff)
    }

    private suspend fun purgeDailySummaries(db: AppDatabase, config: RetentionConfigState, now: Long) {
        if (config.dailySummaryRetentionDays == 0) return
        val cutoffMs = now - config.dailySummaryRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
        val cutoffDay = cutoffMs / Time.DAY_IN_MILLISECONDS
        db.dailySummaryDao().deleteOlderThan(cutoffDay)
    }

    private suspend fun purgeExplorationData(db: AppDatabase, config: RetentionConfigState, now: Long) {
        if (config.explorationRetentionDays == 0) return
        val cutoff = now - config.explorationRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
        db.explorationCellDao().deleteOlderThan(cutoff)
        db.explorationStreakDao().deleteOlderThan(cutoff)
        db.achievementProgressDao().deleteOlderThan(cutoff)
        db.personalRecordDao().deleteOlderThan(cutoff)
    }

    private fun purgeLegacySessions(db: AppDatabase, config: RetentionConfigState, now: Long) {
        if (config.legacySessionRetentionDays == 0) return
        val cutoff = now - config.legacySessionRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
        val sqLiteDb = db.openHelper.writableDatabase
        for ((table, column) in LEGACY_TABLE_COLUMNS) {
            sqLiteDb.execSQL("DELETE FROM $table WHERE $column < ?", arrayOf(cutoff))
        }
    }

    companion object {
        private const val WORK_NAME = "retention_pipeline"

        private val LEGACY_TABLE_COLUMNS = listOf(
            "tracker_session" to "start",
            "location_data" to "time",
            "wifi_data" to "last_seen",
            "cell_location" to "time",
        )

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionPipelineWorker>(
                7, TimeUnit.DAYS,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
