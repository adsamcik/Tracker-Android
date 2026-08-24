package com.adsamcik.tracker.maintenance

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
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Provider

/**
 * Periodic worker that deletes data older than N years to honor auto-cleanup setting.
 */
@HiltWorker
class DataRetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val retentionConfigStore: RetentionConfigStore,
    private val appDatabaseProvider: Provider<AppDatabase>,
    private val exportPlanStore: ExportPlanStore,
    private val migrationBackupRepository: DatabaseMigrationBackupRepository,
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore,
	private val trackingStartupGate: TrackingStartupGate,
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
		val startupGeneration = trackingStartupGate.currentGeneration
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> return Result.success()
		}
		val now = System.currentTimeMillis()
		val cutoff = computeCutoffMillis(years, now)
        return try {
			requireReadyGeneration(startupGeneration)
			val appDatabase = appDatabaseProvider.get()
			// The policy and backup cleanup precede the physical delete, so a WAL
			// entry can defer that delete without preserving expired history in a
			// migration snapshot.
			requireReadyGeneration(startupGeneration)
			val lifecycle = collectedDataLifecycleStore.advanceRetainedFrom(cutoff)
			requireReadyGeneration(startupGeneration)
			migrationBackupRepository.deleteAll()
			when (pruneRawData(appDatabase, cutoff, lifecycle, now, startupGeneration)) {
				RawRetentionPruneResult.PRUNED -> {
					requireReadyGeneration(startupGeneration)
					appDatabase.pruneSourceEventStorageBefore(
						createdBeforeMs = cutoff,
						verifyCollectedDataAccess = { requireReadyGeneration(startupGeneration) },
					)
					Result.success()
				}
				RawRetentionPruneResult.DEFERRED_FOR_PENDING_SIGNALS -> {
					Result.retry()
				}
			}
        } catch (e: CancellationException) {
            throw e
		} catch (_: StartupGenerationChangedException) {
			Result.success()
        } catch (error: Exception) {
            Tracebox.log.error(error, "Data retention failed")
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
		appDatabase: AppDatabase,
        cutoffMillis: Long,
        lifecycle: CollectedDataLifecycleSnapshot,
        updatedAtMs: Long,
		startupGeneration: Long,
    ): RawRetentionPruneResult {
        val pruned = appDatabase.withTransaction {
			requireReadyGeneration(startupGeneration)
			try {
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
				appDatabase.locationSampleDao().deleteOlderThan(cutoffMillis)
				appDatabase.stepIntervalDao().deleteOlderThan(cutoffMillis)
				appDatabase.activitySnapshotDao().deleteOlderThan(cutoffMillis)
				appDatabase.trackerRunDao().deleteOlderThan(cutoffMillis)
				appDatabase.pressureSampleDao().deleteOlderThan(cutoffMillis)
				appDatabase.skiRunSegmentDao().deleteOlderThan(cutoffMillis)
				appDatabase.wifiObservationDao().deleteOlderThan(cutoffMillis)
				appDatabase.cellSampleDao().deleteOlderThan(cutoffMillis)
				appDatabase.sessionSegmentDao().deleteOlderThan(cutoffMillis)
				true
			} finally {
				requireReadyGeneration(startupGeneration)
			}
        }
        return if (pruned) {
			requireReadyGeneration(startupGeneration)
            exportPlanStore.resetAllWatermarks()
            RawRetentionPruneResult.PRUNED
        } else {
            RawRetentionPruneResult.DEFERRED_FOR_PENDING_SIGNALS
        }
    }

	private fun requireReadyGeneration(startupGeneration: Long) {
		if (!trackingStartupGate.isReady ||
			trackingStartupGate.currentGeneration != startupGeneration
		) {
			throw StartupGenerationChangedException
		}
	}

    private enum class RawRetentionPruneResult {
        PRUNED,
        DEFERRED_FOR_PENDING_SIGNALS,
    }

	private object StartupGenerationChangedException : RuntimeException()
}
