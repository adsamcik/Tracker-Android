package com.adsamcik.tracker.maintenance

import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import dev.tracebox.Tracebox
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.annotation.WorkerThread
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.app.maintenance.RetentionPipelineWorker
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.pruneSourceEventStorageBefore
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupRepository
import com.adsamcik.tracker.shared.base.database.dao.CellSampleDao
import com.adsamcik.tracker.shared.base.database.dao.LocationSampleDao
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import com.adsamcik.tracker.shared.base.database.dao.WifiObservationDao
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

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
    private val exportPlanStore: ExportPlanStore,
    private val migrationBackupRepository: DatabaseMigrationBackupRepository,
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val config = retentionConfigStore.config.first()
        if (!config.autoCleanupEnabled) {
            return Result.success()
        }

		val years = config.dataRetentionYears
		if (years == 0) {
			return Result.success()
		}
		val now = System.currentTimeMillis()
		val cutoff = computeCutoffMillis(years, now)
        return try {
			// The policy and backup cleanup precede the physical delete, so a WAL
			// entry can defer that delete without preserving expired history in a
			// migration snapshot.
			val lifecycle = collectedDataLifecycleStore.advanceRetainedFrom(cutoff)
			migrationBackupRepository.deleteAll()
			when (pruneRawData(cutoff, lifecycle, now)) {
				RawRetentionPruneResult.PRUNED -> {
					appDatabase.pruneSourceEventStorageBefore(cutoff)
					Result.success()
				}
				RawRetentionPruneResult.DEFERRED_FOR_PENDING_SIGNALS -> {
					Result.retry()
				}
			}
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            Tracebox.log.error(error, TrackerTraceboxTemplates.DATA_RETENTION_FAILED)
            Result.retry()
        }
    }

    companion object {
        private const val ONE_YEAR_MILLIS: Long = 365L * 24L * 60L * 60L * 1000L

        /** Schedule weekly cleanup with unique work policy. */
        fun ensureScheduled(context: Context) {
            RetentionPipelineWorker.ensureScheduled(context)
        }

        /** Cancel scheduled cleanup. */
        fun cancel(context: Context) {
            RetentionPipelineWorker.cancel(context)
        }

        internal fun syncScheduling(context: Context, enabled: Boolean) {
            try {
                if (enabled) ensureScheduled(context) else cancel(context)
            } catch (_: IllegalStateException) {
                // WorkManager may not be initialized in tests; ignore the exception as before.
                return
            }
        }

        @WorkerThread
        private fun yearsToMillis(years: Int): Long = years.coerceAtLeast(0) * ONE_YEAR_MILLIS

        /** Visible for tests to validate cutoff calculation for different retention values. */
        @JvmStatic
        @VisibleForTesting
        fun computeCutoffMillis(years: Int, nowMillis: Long = System.currentTimeMillis()): Long {
            if (years <= 0) return Long.MIN_VALUE
            return nowMillis - yearsToMillis(years)
        }
    }

    @WorkerThread
    private suspend fun pruneRawData(
        cutoffMillis: Long,
        lifecycle: CollectedDataLifecycleSnapshot,
        updatedAtMs: Long,
    ): RawRetentionPruneResult {
        val pruned = appDatabase.withTransaction {
            val sourceEvidenceStateDao = appDatabase.sourceEvidenceStateDao()
            val lifecycleChanged = sourceEvidenceStateDao.synchronizeLifecycle(
                epoch = lifecycle.epoch,
                retainedFromMs = lifecycle.retainedFromMs,
                updatedAtMs = updatedAtMs,
            )
            if (appDatabase.pendingSignalDao().hasAny()) return@withTransaction false
            if (!lifecycleChanged) {
                check(sourceEvidenceStateDao.incrementRevision(updatedAtMs) == 1) {
                    "Unable to advance source-evidence revision for raw-data retention"
                }
            }
            appDatabase.trajectoryReconstructionDao().deleteWithSourceBefore(cutoffMillis)
            val observationDao = appDatabase.locationObservationDao()
            observationDao.deleteOlderThan(cutoffMillis)
			appDatabase.locationProjectionDao().deleteObservationsOlderThan(cutoffMillis)
            appDatabase.locationObservationDecisionDao().apply {
                deleteOlderThan(cutoffMillis)
                deleteWithoutObservation()
            }
            appDatabase.trackerStateEventDao().deleteOlderThan(cutoffMillis)
            locationSampleDao.deleteOlderThan(cutoffMillis)
			appDatabase.stepIntervalDao().deleteOlderThan(cutoffMillis)
			appDatabase.activitySnapshotDao().deleteOlderThan(cutoffMillis)
			appDatabase.trackerRunDao().deleteOlderThan(cutoffMillis)
			appDatabase.pressureSampleDao().deleteOlderThan(cutoffMillis)
			appDatabase.skiRunSegmentDao().deleteOlderThan(cutoffMillis)
            wifiObservationDao.deleteOlderThan(cutoffMillis)
            cellSampleDao.deleteOlderThan(cutoffMillis)
            sessionSegmentDao.deleteOlderThan(cutoffMillis)
            true
        }
        return if (pruned) {
            exportPlanStore.resetAllWatermarks()
            RawRetentionPruneResult.PRUNED
        } else {
            RawRetentionPruneResult.DEFERRED_FOR_PENDING_SIGNALS
        }
    }

    private enum class RawRetentionPruneResult {
        PRUNED,
        DEFERRED_FOR_PENDING_SIGNALS,
    }
}
