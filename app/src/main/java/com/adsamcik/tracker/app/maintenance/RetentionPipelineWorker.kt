package com.adsamcik.tracker.app.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.analysis.PresenceCompactor
import com.adsamcik.tracker.shared.base.database.analysis.LegacyPresencePersistence
import com.adsamcik.tracker.logging.api.ReporterFacade
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
        val storedConfig = retentionConfigStore.config.first()
        val config = storedConfig.forWorker()

        if (!storedConfig.autoPurgeEnabled && !storedConfig.autoCleanupEnabled) return Result.success()

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
            purgeOperationalData(appDatabase, config, now)

            Result.success()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ReporterFacade.report(e)
            Result.retry()
        }
    }

    private suspend fun purgeRawData(db: AppDatabase, config: RetentionConfigState, now: Long) {
        if (config.rawDataRetentionDays == 0) return
        if (db.pendingSignalDao().hasAny()) return
        val cutoff = now - config.rawDataRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
        val progress = if (LegacyPresencePersistence.PROACTIVE_COMPACTION_ENABLED) {
            PresenceCompactor(db).compactThroughExclusive(cutoff)
        } else {
            null
        }
        val safeCutoff = progress?.let { minOf(cutoff, it.safeThroughMs) } ?: cutoff
        db.withTransaction {
            if (db.pendingSignalDao().hasAny()) return@withTransaction
            val observationDao = db.locationObservationDao()
            // A source committed after compaction can carry an old provider fix time. Keep the
            // whole raw evidence set until that id has been reconciled into the checkpoint.
            if (progress != null && observationDao.maxId() > progress.safeObservationId) return@withTransaction
            if (progress == null) {
                observationDao.deleteOlderThan(safeCutoff)
            } else {
                observationDao.deleteOlderThanThroughId(safeCutoff, progress.safeObservationId)
            }
            db.locationSampleDao().deleteOlderThan(safeCutoff)
            db.stepIntervalDao().deleteOlderThan(safeCutoff)
            db.activitySnapshotDao().deleteOlderThan(safeCutoff)
            db.trackerRunDao().deleteOlderThan(safeCutoff)
            db.pressureSampleDao().deleteOlderThan(safeCutoff)
        }
    }

    private suspend fun purgeWifiCellData(db: AppDatabase, config: RetentionConfigState, now: Long) {
        if (config.wifiCellRetentionDays == 0) return
        if (db.pendingSignalDao().hasAny()) return
        val cutoff = now - config.wifiCellRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
        db.withTransaction {
            if (db.pendingSignalDao().hasAny()) return@withTransaction
            db.cellSampleDao().deleteOlderThan(cutoff)
            db.wifiObservationDao().deleteOlderThan(cutoff)
        }
    }

    private suspend fun purgeTripData(db: AppDatabase, config: RetentionConfigState, now: Long) {
        if (config.tripRetentionDays == 0) return
        val cutoff = now - config.tripRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
        db.sessionSegmentDao().deleteOlderThan(cutoff)
        db.inferredTripDao().deleteOlderThan(cutoff)
        db.tripLegDao().deleteOlderThan(cutoff)
        db.frequentPlaceDao().deleteOlderThan(cutoff)
        db.routeCacheDao().deleteOlderThan(cutoff)
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

	private suspend fun purgeOperationalData(db: AppDatabase, config: RetentionConfigState, now: Long) {
		if (config.rawDataRetentionDays == 0) return
		val rawCutoff = now - config.rawDataRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
		val domainEventDao = db.domainEventDao()
		val cursorCutoff = domainEventDao.getMinimumCursorTimestampMs()
		// With no consumer cursors yet, retention falls back to the configured raw cutoff.
		val domainEventCutoff = cursorCutoff?.let { minOf(rawCutoff, it) } ?: rawCutoff
		domainEventDao.deleteOlderThan(domainEventCutoff)
		db.exportLogDao().deleteOlderThan(rawCutoff)
	}

    private fun RetentionConfigState.forWorker(): RetentionConfigState {
        if (!autoCleanupEnabled) return this
        val days = if (dataRetentionYears == 0) 0 else dataRetentionYears.coerceAtLeast(1) * DAYS_PER_YEAR
        return copy(
            rawDataRetentionDays = days,
            wifiCellRetentionDays = days,
            tripRetentionDays = days,
            dailySummaryRetentionDays = days,
            explorationRetentionDays = days,
            legacySessionRetentionDays = days,
            autoPurgeEnabled = true,
        )
    }

    companion object {
        private const val WORK_NAME = "APP.DATA_RETENTION_WEEKLY"
        private const val DAYS_PER_YEAR = 365

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionPipelineWorker>(
                7, TimeUnit.DAYS,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun schedule(context: Context) = ensureScheduled(context)
    }
}
