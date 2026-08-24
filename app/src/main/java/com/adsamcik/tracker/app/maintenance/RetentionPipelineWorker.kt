package com.adsamcik.tracker.app.maintenance

import dev.tracebox.Tracebox
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
import com.adsamcik.tracker.shared.base.database.dao.synchronizeLifecycle
import com.adsamcik.tracker.shared.base.database.pruneSourceEventStorageBefore
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupRepository
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigState
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Provider

@HiltWorker
class RetentionPipelineWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val retentionConfigStore: RetentionConfigStore,
    private val collectedDataLifecycleStore: CollectedDataLifecycleStore,
    private val appDatabaseProvider: Provider<AppDatabase>,
    private val migrationBackupRepository: DatabaseMigrationBackupRepository,
	private val trackingStartupGate: TrackingStartupGate,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val storedConfig = retentionConfigStore.config.first()
        val config = storedConfig.forWorker()

        if (!storedConfig.autoPurgeEnabled && !storedConfig.autoCleanupEnabled) return Result.success()
		val startupGeneration = trackingStartupGate.currentGeneration
		when (trackingStartupGate.reconcile()) {
			is TrackingStartupResult.Ready -> Unit
			is TrackingStartupResult.RetryableFailure -> return Result.retry()
			is TrackingStartupResult.Blocked -> return Result.success()
		}

        return try {
			requireReadyGeneration(startupGeneration)
			val appDatabase = appDatabaseProvider.get()
            val now = System.currentTimeMillis()

            val rawRetentionResult = if (config.rawDataRetentionDays == 0) {
                RawRetentionResult.NOT_APPLICABLE
            } else {
                val cutoff = now - config.rawDataRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
                // Establish the durable policy before deleting either live source rows
                // or a migration backup. A delayed WAL entry must be rejected even if
                // it postpones the physical delete.
				requireReadyGeneration(startupGeneration)
                val lifecycle = collectedDataLifecycleStore.advanceRetainedFrom(cutoff)
				requireReadyGeneration(startupGeneration)
                migrationBackupRepository.deleteAll()
				val result = purgeRawData(
					appDatabase,
					cutoff,
					lifecycle,
					now,
					startupGeneration,
				)
				requireReadyGeneration(startupGeneration)
				appDatabase.pruneSourceEventStorageBefore(
					createdBeforeMs = cutoff,
					verifyCollectedDataAccess = { requireReadyGeneration(startupGeneration) },
				)
                result
            }
			purgeWifiCellData(appDatabase, config, now, startupGeneration)
			purgeTripData(appDatabase, config, now, startupGeneration)
			purgeDailySummaries(appDatabase, config, now, startupGeneration)
			purgeExplorationData(appDatabase, config, now, startupGeneration)
			purgeOperationalData(appDatabase, config, now, startupGeneration)

            if (rawRetentionResult == RawRetentionResult.DEFERRED_FOR_PENDING_SIGNALS) {
                Result.retry()
            } else {
                Result.success()
            }
		} catch (_: StartupGenerationChangedException) {
			Result.success()
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Tracebox.log.error(error, "Data retention failed")
            Result.retry()
        }
    }

    private suspend fun purgeRawData(
        db: AppDatabase,
        cutoff: Long,
        lifecycle: CollectedDataLifecycleSnapshot,
        updatedAtMs: Long,
		startupGeneration: Long,
    ): RawRetentionResult {
        return db.withTransaction {
			requireReadyGeneration(startupGeneration)
			try {
				val sourceEvidenceStateDao = db.sourceEvidenceStateDao()
				val lifecycleChanged = sourceEvidenceStateDao.synchronizeLifecycle(
					epoch = lifecycle.epoch,
					retainedFromMs = lifecycle.retainedFromMs,
					updatedAtMs = updatedAtMs,
				)
				if (db.pendingSignalDao().hasAny()) {
					return@withTransaction RawRetentionResult.DEFERRED_FOR_PENDING_SIGNALS
				}
				if (!lifecycleChanged) {
					check(sourceEvidenceStateDao.incrementRevision(updatedAtMs) == 1) {
						"Unable to advance source-evidence revision for raw-data retention"
					}
				}
				// A derived run is only auditable while its complete raw source range remains.
				// Cascades remove states, visits, hypotheses, and lineage links atomically.
				db.trajectoryReconstructionDao().deleteWithSourceBefore(cutoff)
				val observationDao = db.locationObservationDao()
				observationDao.deleteOlderThan(cutoff)
				db.locationProjectionDao().deleteObservationsOlderThan(cutoff)
				db.locationObservationDecisionDao().apply {
					deleteOlderThan(cutoff)
					deleteWithoutObservation()
				}
				db.trackerStateEventDao().deleteOlderThan(cutoff)
				db.locationSampleDao().deleteOlderThan(cutoff)
				db.stepIntervalDao().deleteOlderThan(cutoff)
				db.activitySnapshotDao().deleteOlderThan(cutoff)
				db.trackerRunDao().deleteOlderThan(cutoff)
				db.pressureSampleDao().deleteOlderThan(cutoff)
				db.skiRunSegmentDao().deleteOlderThan(cutoff)
				RawRetentionResult.PURGED
			} finally {
				requireReadyGeneration(startupGeneration)
			}
        }
    }

    private enum class RawRetentionResult {
        NOT_APPLICABLE,
        PURGED,
        DEFERRED_FOR_PENDING_SIGNALS,
    }

    private suspend fun purgeWifiCellData(
		db: AppDatabase,
		config: RetentionConfigState,
		now: Long,
		startupGeneration: Long,
	) {
        if (config.wifiCellRetentionDays == 0) return
		requireReadyGeneration(startupGeneration)
        if (db.pendingSignalDao().hasAny()) return
        val cutoff = now - config.wifiCellRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
        db.withTransaction {
			requireReadyGeneration(startupGeneration)
			try {
				if (db.pendingSignalDao().hasAny()) return@withTransaction
				db.cellSampleDao().deleteOlderThan(cutoff)
				db.wifiObservationDao().deleteOlderThan(cutoff)
			} finally {
				requireReadyGeneration(startupGeneration)
			}
        }
    }

    private suspend fun purgeTripData(
		db: AppDatabase,
		config: RetentionConfigState,
		now: Long,
		startupGeneration: Long,
	) {
        if (config.tripRetentionDays == 0) return
        val cutoff = now - config.tripRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
		db.withTransaction {
			requireReadyGeneration(startupGeneration)
			try {
				db.sessionSegmentDao().deleteOlderThan(cutoff)
			} finally {
				requireReadyGeneration(startupGeneration)
			}
		}
    }

    private suspend fun purgeDailySummaries(
		db: AppDatabase,
		config: RetentionConfigState,
		now: Long,
		startupGeneration: Long,
	) {
        if (config.dailySummaryRetentionDays == 0) return
        val cutoffMs = now - config.dailySummaryRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
        val cutoffDay = cutoffMs / Time.DAY_IN_MILLISECONDS
		db.withTransaction {
			requireReadyGeneration(startupGeneration)
			try {
				db.dailySummaryDao().deleteOlderThan(cutoffDay)
			} finally {
				requireReadyGeneration(startupGeneration)
			}
		}
    }

    private suspend fun purgeExplorationData(
		db: AppDatabase,
		config: RetentionConfigState,
		now: Long,
		startupGeneration: Long,
	) {
        if (config.explorationRetentionDays == 0) return
        val cutoff = now - config.explorationRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
		db.withTransaction {
			requireReadyGeneration(startupGeneration)
			try {
				db.explorationCellDao().deleteOlderThan(cutoff)
				db.explorationStreakDao().deleteOlderThan(cutoff)
				db.achievementProgressDao().deleteOlderThan(cutoff)
			} finally {
				requireReadyGeneration(startupGeneration)
			}
		}
    }

	private suspend fun purgeOperationalData(
		db: AppDatabase,
		config: RetentionConfigState,
		now: Long,
		startupGeneration: Long,
	) {
		if (config.rawDataRetentionDays == 0) return
		val rawCutoff = now - config.rawDataRetentionDays.toLong() * Time.DAY_IN_MILLISECONDS
		db.withTransaction {
			requireReadyGeneration(startupGeneration)
			try {
				val domainEventDao = db.domainEventDao()
				val cursorCutoff = domainEventDao.getMinimumCursorTimestampMs()
				// With no consumer cursors yet, retention falls back to the configured raw cutoff.
				val domainEventCutoff = cursorCutoff?.let { minOf(rawCutoff, it) } ?: rawCutoff
				domainEventDao.deleteOlderThan(domainEventCutoff)
				db.exportLogDao().deleteOlderThan(rawCutoff)
			} finally {
				requireReadyGeneration(startupGeneration)
			}
		}
	}

	private fun requireReadyGeneration(startupGeneration: Long) {
		if (!trackingStartupGate.isReady ||
			trackingStartupGate.currentGeneration != startupGeneration
		) {
			throw StartupGenerationChangedException
		}
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
            autoPurgeEnabled = true,
        )
    }

    companion object {
        internal const val WORK_NAME = "APP.DATA_RETENTION_PIPELINE_WEEKLY"
        internal const val LEGACY_WORK_NAME = "APP.DATA_RETENTION_WEEKLY"
        private const val DAYS_PER_YEAR = 365

        fun ensureScheduled(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(LEGACY_WORK_NAME)
            val request = PeriodicWorkRequestBuilder<RetentionPipelineWorker>(
                7, TimeUnit.DAYS,
            ).build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).run {
                cancelUniqueWork(WORK_NAME)
                cancelUniqueWork(LEGACY_WORK_NAME)
            }
        }

        fun schedule(context: Context) = ensureScheduled(context)
    }

	private object StartupGenerationChangedException : RuntimeException()
}
